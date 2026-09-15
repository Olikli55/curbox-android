package neth.iecal.curbox.data.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.PomodoroPhase
import neth.iecal.curbox.data.models.PomodoroState
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
import org.json.JSONObject

@OptIn(FlowPreview::class)
class PlaystoreSyncProvider(private val context: Context) : SyncProvider {

    private val gson = Gson()
    private val rest by lazy { SupabaseRest() }
    private val keys by lazy { SecureKeyStore(context) }

    // Flavor specific billing: Polar in full, Google Play in playstore.
    private val entitlement by lazy { SyncEntitlement(context, rest) { billingSession() } }
    private val entitled get() = entitlement.billing.value.entitled
    private val db by lazy { AppDatabase.getInstance(context) }
    private val dataStoreManager by lazy { DataStoreManager(context) }
    private val dataStore by lazy { DataStoreManager.getSettingsDataStore(context, gson) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val tokenMutex = Mutex()
    private val pullMutex = Mutex()
    private val pushMutex = Mutex()

    private val NS_ANDROID_CONFIG = "android_config"
    private val NS_USAGE_WEB = "usage_web"
    private val NS_USAGE_APP = "usage_app"
    private val NS_FOCUS = "focus_state"
    private val NS_FOCUS_GROUPS = "focus_groups"

    // Websites visited for less than this in a day aren't worth syncing.
    private val MIN_WEBSITE_SYNC_MS = 60_000L

    // Usage stats don't need instant sync. Sampling the Room flows caps how often a day's
    // usage row is rewritten on the server, which keeps row churn, network, and battery low.
    // Focus and config changes are not sampled and still sync instantly.
    private val USAGE_PUSH_SAMPLE_MS = 5 * 60_000L
    private val PULL_PAGE_SIZE = 500

    private var session: SupabaseRest.Session? = null
    private var signedInInitialised = false
    private var dek: ByteArray? = null
    private var vaultExists: Boolean = false
    private var realtime: RealtimeClient? = null
    @Volatile private var realtimeConnected = false
    private var pollStarted = false
    // Compare both ways. Structural equality handles HashSet order changes,
    // while JSON equality handles primitive arrays whose Kotlin equals uses identity.
    private var lastConfig: Settings? = null
    private var lastConfigJson: String? = null
    // Dedup key for focus state. The wire payload stamps startedAt with the
    // push time, so comparing full payloads never matches and every collector
    // wake re-pushed an unchanged session. The key holds only the fields that
    // define the state.
    private var lastFocusKey: String? = null
    private val injectedGroupIds = HashSet<String>()
    private val focusGroupShadow = HashMap<String, String>()
    private val knownFocusGroupIds = HashSet<String>()
    private val pushedDigests = HashMap<String, String>()
    private val wakeRunning = AtomicBoolean(false)
    private val wakePending = AtomicBoolean(false)
    private var observersStarted = false
    private var devices: List<SyncDevice> = emptyList()

    private fun preferences() = SyncPreferences(
        usageStats = keys.syncUsageStats,
        reducerConfigs = keys.syncReducerConfigs,
        usageDeviceIds = keys.usageDeviceIds,
    )

    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status
    override val isAvailable = true
    override val supportsGoogleSignIn = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    override val billing get() = entitlement.billing
    override fun launchBillingFlow(activity: android.app.Activity) = entitlement.launchPurchase(activity)
    override fun refreshBilling() = entitlement.refresh()

    override fun start() {
        scope.launch {
            try {
                ensureStarted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishStatus(error = e.message)
            }
        }
        watchEntitlement()
    }

    private var entitlementWatched = false

    /** Starts sync the moment a subscription activates and cuts it when one lapses. */
    @Synchronized
    private fun watchEntitlement() {
        if (entitlementWatched) return
        entitlementWatched = true
        scope.launch {
            entitlement.billing
                .map { it.entitled }
                .distinctUntilChanged()
                .drop(1)
                .collect { nowEntitled ->
                    if (nowEntitled) {
                        try {
                            ensureStarted()
                            if (session != null && dek != null) onSignedIn()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            publishStatus(error = e.message)
                        }
                    } else {
                        stopRealtime()
                        publishStatus()
                    }
                }
        }
    }

    private suspend fun ensureStarted() = startMutex.withLock {
        if (session != null) {
            entitlement.refreshNow()
            if (entitled) {
                runCatching { SyncWorker.schedule(context) }
                if (!signedInInitialised) onSignedIn()
            }
            publishStatus()
            return
        }
        val refresh = keys.refreshToken ?: return
        try {
            session = rest.refresh(refresh).also { persistSession(it) }
            signedInInitialised = false
            keys.dekB64?.let { dek = CryptoBox.fromBase64Url(it) }
            entitlement.refreshNow()
            if (entitled) {
                runCatching { SyncWorker.schedule(context) }
                onSignedIn()
            } else {
                publishStatus()
            }
        } catch (e: Exception) {
            if (isRejectedToken(e)) {
                clearLocalAccount()
                publishStatus(error = e.message)
                return
            }
            publishStatus(error = e.message)
            throw e
        }
    }

    fun wake() {
        wakePending.set(true)
        if (!wakeRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                do {
                    wakePending.set(false)
                    ensureStarted()
                    if (session != null && dek != null && entitled) {
                        ensureFreshToken()
                        retryAfterRefreshingAccess {
                            pullSinceCursor()
                            pushMutex.withLock { pushUsage() }
                        }
                    }
                } while (wakePending.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishStatus(error = e.message)
            } finally {
                wakeRunning.set(false)
                if (wakePending.get()) wake()
            }
        }
    }

    private var pendingEmail: String? = null

    override suspend fun signUp(email: String, password: String) = withContext(Dispatchers.IO) {
        val s = rest.signUp(email, password)
        if (s == null) {
            pendingEmail = email
            publishStatus()
        } else {
            finishSignIn(s)
        }
    }

    override suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val s = try {
            rest.signIn(email, password)
        } catch (e: Exception) {
            if (e.message?.contains("confirm", ignoreCase = true) == true) {
                pendingEmail = email
                publishStatus()
                return@withContext
            }
            throw e
        }
        finishSignIn(s)
    }

    override suspend fun signInWithGoogle(activity: Activity) {
        check(supportsGoogleSignIn) {
            context.getString(neth.iecal.curbox.R.string.account_err_google_not_configured)
        }
        val rawNonce = randomNonce()
        val hashedNonce = sha256Hex(rawNonce)
        val idToken = try {
            withContext(Dispatchers.Main) {
                val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    .setNonce(hashedNonce)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val credential = CredentialManager.create(activity)
                    .getCredential(activity, request)
                    .credential as? CustomCredential
                    ?: throw IllegalStateException("Google did not return an account")
                if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    throw IllegalStateException("Google returned an unsupported account")
                }
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            }
        } catch (_: GetCredentialCancellationException) {
            return
        }
        withContext(Dispatchers.IO) {
            finishSignIn(rest.signInWithIdToken(idToken, rawNonce))
        }
    }

    override suspend fun verifySignupCode(email: String, code: String) = withContext(Dispatchers.IO) {
        val s = rest.verifyOtp(email, code.trim(), "signup")
        finishSignIn(s)
    }

    override suspend fun resendSignupCode(email: String) = withContext(Dispatchers.IO) {
        rest.resend(email, "signup")
    }

    override suspend fun sendPasswordReset(email: String) = withContext(Dispatchers.IO) {
        rest.recover(email)
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String) = withContext(Dispatchers.IO) {
        val s = rest.verifyOtp(email, code.trim(), "recovery")
        rest.updatePassword(s, newPassword)
        finishSignIn(s)
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        startMutex.withLock {
            tokenMutex.withLock {
                val oldSession = session
                val oldDeviceId = if (oldSession != null) keys.deviceId else null
                stopRealtime()
                runCatching { if (oldSession != null && oldDeviceId != null) rest.clearDeviceToken(oldSession, oldDeviceId) }
                runCatching { if (oldSession != null) rest.signOut(oldSession) }
                pullMutex.withLock {
                    pushMutex.withLock { clearLocalAccount() }
                }
                try {
                    CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Supabase is already signed out. Credential Manager cleanup
                    // only affects which Google accounts are suggested next time.
                }
                publishStatus()
            }
        }
    }

    override suspend fun setPassphrase(passphrase: String) = withContext(Dispatchers.IO) {
        require(entitled) { "Sync Premium is required" }
        require(passphrase.length >= 8) { context.getString(neth.iecal.curbox.R.string.account_msg_phrase_too_short) }
        require(passphrase.length <= 1024) { context.getString(neth.iecal.curbox.R.string.account_msg_phrase_too_long) }
        val s = requireSession()
        if (rest.getVault(s) != null) throw IllegalStateException("a passphrase already exists, unlock instead")
        val salt = CryptoBox.randomSalt()
        val kek = CryptoBox.deriveKekBytes(passphrase, salt, CryptoBox.DEFAULT_KDF_PARAMS)
        val newDek = CryptoBox.randomDek()
        val wrapped = CryptoBox.wrapDek(kek, newDek, s.userId)
        rest.insertVault(s, CryptoBox.toBase64Url(salt), paramsJson(), CryptoBox.toBase64Url(wrapped))
        adoptDek(newDek)
        onSignedIn()
    }

    override suspend fun unlock(passphrase: String) = withContext(Dispatchers.IO) {
        require(entitled) { "Sync Premium is required" }
        require(passphrase.length <= 1024) { context.getString(neth.iecal.curbox.R.string.account_msg_phrase_too_long) }
        val s = requireSession()
        val vault = rest.getVault(s) ?: throw IllegalStateException("no passphrase set yet")
        val params = gson.fromJson(vault.paramsJson, JsonObject::class.java)
        val kek = CryptoBox.deriveKekBytes(
            passphrase,
            CryptoBox.fromBase64Url(vault.saltB64),
            CryptoBox.KdfParams(
                alg = params.get("alg")?.asString ?: "PBKDF2-HMAC-SHA256",
                iterations = params.get("iterations").asInt,
                hash = params.get("hash")?.asString ?: "SHA-256",
                dkLenBits = params.get("dkLenBits").asInt,
            ),
        )
        val unwrapped = try {
            CryptoBox.unwrapDek(kek, CryptoBox.fromBase64Url(vault.wrappedB64), s.userId)
        } catch (e: Exception) {
            throw IllegalStateException("that passphrase did not work")
        }
        adoptDek(unwrapped)
        onSignedIn()
    }

    override suspend fun makePairingCode(): String {
        require(entitled) { "Sync Premium is required" }
        val s = requireSession()
        val d = dek ?: throw IllegalStateException("unlock first")
        return CryptoBox.buildPairingPayload(s.userId, d)
    }

    override suspend fun pairWithCode(payload: String) = withContext(Dispatchers.IO) {
        require(entitled) { "Sync Premium is required" }
        val s = requireSession()
        val pairing = CryptoBox.parsePairingPayload(payload)
        if (pairing.userId != s.userId) throw IllegalStateException("this code is for a different account")
        val previousDek = dek
        try {
            adoptDek(pairing.dek)
            onSignedIn()
        } catch (e: Exception) {
            dek = previousDek
            keys.dekB64 = previousDek?.let { CryptoBox.toBase64Url(it) }
            publishStatus(error = e.message)
            throw e
        }
    }

    // Called from cold processes (SyncWorker) where start() may not have finished
    // signing in yet, so establish the session first instead of silently doing
    // nothing when it is still null.
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        ensureStarted()
        if (session == null || dek == null || !entitled) return@withContext
        ensureFreshToken()
        retryAfterRefreshingAccess {
            val current = requireSession()
            rest.upsertDevice(current, keys.deviceId, "android", keys.deviceName, keys.fcmToken)
            pullSinceCursor()
        }
        startLiveSync()
        session?.let { runCatching { refreshDevices(it) } }
        publishStatus()
    }

    override suspend fun remoteWebsiteUsage(dateIso: String): Map<String, Long> = withContext(Dispatchers.IO) {
        if (!entitled || !keys.syncUsageStats || session == null || dek == null) {
            emptyMap()
        } else {
            RemoteUsageStore(context).websiteTotals(dateIso, keys.usageDeviceIds)
        }
    }

    override suspend fun remoteAppUsage(dateIso: String): Map<String, Long> = withContext(Dispatchers.IO) {
        if (!entitled || !keys.syncUsageStats || session == null || dek == null) {
            emptyMap()
        } else {
            RemoteUsageStore(context).appTotals(dateIso, keys.usageDeviceIds)
        }
    }

    override suspend fun remoteUsageForDates(
        dateIsos: Set<String>
    ): Map<String, RemoteUsageTotals> = withContext(Dispatchers.IO) {
        if (!entitled || !keys.syncUsageStats || session == null || dek == null) {
            emptyMap()
        } else {
            val store = RemoteUsageStore(context)
            dateIsos.associateWith { date ->
                RemoteUsageTotals(
                    apps = store.appTotals(date, keys.usageDeviceIds),
                    websites = store.websiteTotals(date, keys.usageDeviceIds)
                )
            }
        }
    }

    override suspend fun pushNow() = withContext(Dispatchers.IO) {
        ensureStarted()
        if (session == null || dek == null || !entitled) return@withContext
        ensureFreshToken()
        retryAfterRefreshingAccess {
            pushMutex.withLock {
                pushConfig()
                pushUsage()
            }
        }
    }

    override suspend fun setDeviceName(name: String) = withContext(Dispatchers.IO) {
        require(entitled) { "Sync Premium is required" }
        val label = name.trim().take(60)
        require(label.isNotEmpty()) { "Enter a device name" }
        keys.deviceName = label
        retryAfterRefreshingAccess {
            val current = requireSession()
            rest.upsertDevice(current, keys.deviceId, "android", label, keys.fcmToken)
            refreshDevices(current)
        }
        publishStatus()
    }

    override suspend fun setPreferences(preferences: SyncPreferences) = withContext(Dispatchers.IO) {
        require(entitled) { "Sync Premium is required" }
        keys.syncUsageStats = preferences.usageStats
        keys.syncReducerConfigs = preferences.reducerConfigs
        keys.usageDeviceIds = preferences.usageDeviceIds - keys.deviceId
        keys.cursor = "1970-01-01T00:00:00Z"
        // Rebuild the cache so a device removed from the selection cannot keep
        // contributing stale totals.
        pullMutex.withLock {
            RemoteUsageStore(context).clear()
        }
        retryAfterRefreshingAccess {
            pullSinceCursor()
        }
        retryAfterRefreshingAccess {
            pushMutex.withLock {
                if (keys.syncReducerConfigs) {
                    val settings = dataStore.data.first()
                    pushConfig()
                    pushFocusGroups(settings)
                    pushFocusFrom(settings)
                }
                if (keys.syncUsageStats) pushUsage()
            }
        }
        publishStatus()
    }

    private fun requireSession(): SupabaseRest.Session = session ?: throw IllegalStateException("sign in first")

    private suspend fun billingSession(): SupabaseRest.Session? {
        if (session == null) return null
        ensureFreshToken()
        return session
    }

    /**
     * Only a definitive auth rejection may wipe the stored keys, because clearing them also
     * deletes the local DEK. Matching on error text here once wiped the vault on transient
     * server errors, so this now trusts nothing but the auth endpoint's own status code.
     */
    private fun isRejectedToken(e: Exception): Boolean =
        e is SupabaseRest.AuthHttpException && e.code in setOf(400, 401, 403)

    private fun persistSession(s: SupabaseRest.Session) {
        keys.setSession(s.accessToken, s.refreshToken)
    }

    private suspend fun finishSignIn(s: SupabaseRest.Session) {
        session = s
        signedInInitialised = false
        persistSession(s)
        pendingEmail = null
        entitlement.refreshNow()
        if (entitled) onSignedIn() else publishStatus()
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun adoptDek(bytes: ByteArray) {
        dek = bytes
        keys.dekB64 = CryptoBox.toBase64Url(bytes)
    }

    private fun paramsJson(): String = gson.toJson(CryptoBox.DEFAULT_KDF_PARAMS)

    private fun clearLocalAccount() {
        stopRealtime()
        keys.clear()
        session = null
        signedInInitialised = false
        dek = null
        vaultExists = false
        lastConfig = null
        lastConfigJson = null
        lastFocusKey = null
        pendingEmail = null
        devices = emptyList()
        pushedDigests.clear()
        injectedGroupIds.clear()
        focusGroupShadow.clear()
        knownFocusGroupIds.clear()
        runCatching { RemoteUsageStore(context).clear() }
    }

    private suspend fun onSignedIn() {
        val s = session ?: return
        if (!entitled) {
            publishStatus()
            return
        }
        rest.upsertDevice(s, keys.deviceId, "android", keys.deviceName, keys.fcmToken)
        runCatching { refreshDevices(s) }
        registerFcmToken()
        vaultExists = dek != null || rest.getVault(s) != null
        knownFocusGroupIds.clear()
        knownFocusGroupIds.addAll(keys.knownFocusGroupIds)
        publishStatus()
        signedInInitialised = true
        if (dek != null && entitled) {
            pullSinceCursor()
            startLiveSync()
            pushMutex.withLock {
                if (keys.syncReducerConfigs) {
                    val settings = dataStore.data.first()
                    pushConfig()
                    pushFocusGroups(settings)
                    pushFocusFrom(settings)
                }
                if (keys.syncUsageStats) pushUsage()
            }
        }
        publishStatus()
    }

    private fun startLiveSync() {
        startObservers()
        if (!neth.iecal.curbox.BuildConfig.SYNC_USE_FCM) startRealtime()
        startSafetyPoll()
    }

    private fun refreshDevices(s: SupabaseRest.Session) {
        devices = rest.devices(s).map {
            val platform = it.platform.ifBlank { "device" }.take(40)
            SyncDevice(it.id, platform, it.label.ifBlank { platform }.take(60), it.lastSeen, it.id == keys.deviceId)
        }
    }

    private fun startRealtime() {
        val s = session ?: return
        val existing = realtime
        if (existing != null) {
            existing.updateToken(s.accessToken)
            return
        }
        realtime = RealtimeClient(
            userId = s.userId,
            accessToken = s.accessToken,
            onChange = { wake() },
            onConnected = { realtimeConnected = it },
        ).also { runCatching { it.start() } }
    }

    private fun stopRealtime() {
        runCatching { realtime?.stop() }
        realtime = null
        realtimeConnected = false
    }

    fun onFcmToken(token: String) {
        keys.fcmToken = token
        scope.launch {
            val s = session ?: return@launch
            runCatching { rest.upsertDevice(s, keys.deviceId, "android", keys.deviceName, token) }
        }
    }

    private fun registerFcmToken() {
        scope.launch {
            val token = runCatching { FcmPush.token(context) }.getOrNull() ?: return@launch
            keys.fcmToken = token
            val s = session ?: return@launch
            runCatching { rest.upsertDevice(s, keys.deviceId, "android", keys.deviceName, token) }
        }
    }

    private fun startSafetyPoll() {
        if (pollStarted) return
        pollStarted = true
        scope.launch {
            while (true) {
                val interval = when {
                    neth.iecal.curbox.BuildConfig.SYNC_USE_FCM -> 300_000L
                    realtimeConnected -> 60_000L
                    else -> 10_000L
                }
                delay(interval)
                if (dek == null || session == null || !entitled) continue
                try {
                    ensureFreshToken()
                    retryAfterRefreshingAccess { pullSinceCursor() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    publishStatus(error = e.message)
                }
            }
        }
    }

    private suspend fun ensureFreshToken(force: Boolean = false) = tokenMutex.withLock {
        val s = session ?: return@withLock
        val refresh = keys.refreshToken ?: throw IllegalStateException("sign in again")
        if (!force && System.currentTimeMillis() < s.expiresAt - 60_000) return@withLock
        try {
            val next = rest.refresh(refresh)
            session = next
            persistSession(next)
            realtime?.updateToken(next.accessToken)
        } catch (e: Exception) {
            if (isRejectedToken(e)) clearLocalAccount()
            throw e
        }
    }

    private suspend fun <T> retryAfterRefreshingAccess(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: SupabaseRest.RestHttpException) {
            if (e.code != 401) throw e
            ensureFreshToken(force = true)
            block()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startObservers() {
        if (observersStarted) return
        observersStarted = true
        scope.launch {
            dataStore.data.debounce(1500).collect { settings ->
                if (dek == null || !entitled) return@collect
                val norm = normalize(settings)
                val normJson = gson.toJson(norm)
                if (keys.syncReducerConfigs && !sameAsLastConfig(norm, normJson)) {
                    try {
                        retryAfterRefreshingAccess {
                            pushMutex.withLock { pushConfigJson(norm, normJson) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        publishStatus(error = e.message)
                    }
                }
                if (keys.syncReducerConfigs) {
                    try {
                        retryAfterRefreshingAccess {
                            pushMutex.withLock { pushFocusGroups(settings) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        publishStatus(error = e.message)
                    }
                }
            }
        }
        scope.launch {
            dataStore.data
                .distinctUntilChangedBy {
                    Pair(it.activeManualFocusGroupId, it.activePomodoroState.phase)
                }
                .collect { settings ->
                    if (dek != null && entitled && keys.syncReducerConfigs) {
                        try {
                            retryAfterRefreshingAccess {
                                pushMutex.withLock { pushFocusFrom(settings) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            publishStatus(error = e.message)
                        }
                    }
                }
        }
        scope.launch {
            currentDayFlow().flatMapLatest { native ->
                db.websiteStatsDao().observeStatsForDate(native).map { native to it }
            }.sample(USAGE_PUSH_SAMPLE_MS).collect { (native, rows) ->
                val iso = isoFor(native) ?: return@collect
                if (dek != null && entitled && keys.syncUsageStats) {
                    try {
                        retryAfterRefreshingAccess {
                            pushMutex.withLock { pushWebRows(iso, rows) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        publishStatus(error = e.message)
                    }
                }
            }
        }
        scope.launch {
            currentDayFlow().flatMapLatest { native ->
                db.appUsageDao().observeForDate(native).map { native to it }
            }.sample(USAGE_PUSH_SAMPLE_MS).collect { (native, rows) ->
                val iso = isoFor(native) ?: return@collect
                if (dek != null && entitled && keys.syncUsageStats) {
                    try {
                        retryAfterRefreshingAccess {
                            pushMutex.withLock { pushAppRows(iso, rows) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        publishStatus(error = e.message)
                    }
                }
            }
        }
    }

    private fun currentDayFlow(): Flow<String> = flow {
        var last: String? = null
        while (true) {
            val today = todayNative()
            if (today != last) {
                last = today
                emit(today)
            }
            delay(60_000)
        }
    }

    private fun normalize(s: Settings): Settings =
        s.copy(
            blockedAppGroups = s.blockedAppGroups.map { it.copy(temporarilyDisabledUntilMs = 0L) },
            activeManualFocusGroupId = Pair(null, 0L),
            activePomodoroState = PomodoroState(),
            reelBlockerConfig = s.reelBlockerConfig.copy(temporarilyDisabledUntilMs = 0L),
            keywordBlockerConfig = s.keywordBlockerConfig.copy(
                keywordGroups = s.keywordBlockerConfig.keywordGroups.map {
                    it.copy(temporarilyDisabledUntilMs = 0L)
                }
            ),
            nextWebsiteRecheckTime = 0L,
            manualFocusGroups = emptyList(),
            antiUninstallConfig2 = s.antiUninstallConfig2.copy(unlockRequestedAtMs = 0L),
            serviceProtectionConfig = s.serviceProtectionConfig.copy(appBlockerLastAliveMs = 0L),
            settingsChangeDelayConfig2 = s.settingsChangeDelayConfig2.copy(pendingChanges = emptyList()),
        )

    private suspend fun pushConfig() {
        if (!keys.syncReducerConfigs) return
        val settings = dataStore.data.first()
        val norm = normalize(settings)
        val normJson = gson.toJson(norm)
        if (sameAsLastConfig(norm, normJson)) return
        pushConfigJson(norm, normJson)
    }

    private fun sameAsLastConfig(norm: Settings, normJson: String): Boolean =
        norm == lastConfig || normJson == lastConfigJson

    private fun pushConfigJson(norm: Settings, normJson: String) {
        if (!entitled) return
        if (sameAsLastConfig(norm, normJson)) return
        val s = session ?: return
        val d = dek ?: return
        val aad = CryptoBox.recordAad(s.userId, NS_ANDROID_CONFIG, "config")
        val blob = CryptoBox.encryptRecord(d, aad, normJson)
        rest.upsertRecord(s, NS_ANDROID_CONFIG, "config", keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
        lastConfig = norm
        lastConfigJson = normJson
    }

    private suspend fun pushUsage() {
        if (!keys.syncUsageStats) return
        for (native in db.websiteStatsDao().getDistinctDates().sorted()) {
            val iso = isoFor(native) ?: continue
            if (!isRetainedUsageDate(iso)) continue
            pushWebRows(iso, db.websiteStatsDao().getStatsForDate(native))
        }
        for (native in db.appUsageDao().getDistinctDates().sorted()) {
            val iso = isoFor(native) ?: continue
            if (!isRetainedUsageDate(iso)) continue
            pushAppRows(iso, db.appUsageDao().getForDate(native))
        }
    }

    private fun pushWebRows(date: String, rows: List<WebsiteStatsEntity>) {
        val s = session ?: return
        val d = dek ?: return
        val domains = JsonObject()
        for ((domain, group) in rows.groupBy { it.domain }.toSortedMap()) {
            // Skip brief visits. Anything under a minute is noise we don't sync.
            val total = group.fold(0L) { sum, row -> saturatedAdd(sum, row.totalTime.coerceAtLeast(0L)) }
            if (total < MIN_WEBSITE_SYNC_MS) continue
            val paths = JsonObject().apply {
                group.groupBy { it.urlIdentifier }.toSortedMap().forEach { (path, pathRows) ->
                    addProperty(
                        path,
                        pathRows.fold(0L) { sum, row ->
                            saturatedAdd(sum, row.totalTime.coerceAtLeast(0L))
                        },
                    )
                }
            }
            domains.add(domain, JsonObject().apply {
                addProperty("ms", total)
                add("paths", paths)
            })
        }
        val payload = JsonObject().apply {
            addProperty("date", date)
            addProperty("platform", "android")
            add("domains", domains)
        }
        pushUsageRecord(s, d, NS_USAGE_WEB, "${keys.deviceId}:$date", payload)
    }

    private fun pushAppRows(date: String, rows: List<AppUsageEntity>) {
        val s = session ?: return
        val d = dek ?: return
        val apps = JsonObject()
        for (row in rows.sortedBy { it.packageName }) {
            apps.add(row.packageName, JsonObject().apply {
                addProperty("ms", row.totalTime.coerceAtLeast(0L))
                addProperty("launchCount", row.launchCount.coerceAtLeast(0))
                addProperty("hourlyUsage", row.hourlyUsage)
            })
        }
        val payload = JsonObject().apply {
            addProperty("date", date)
            add("apps", apps)
        }
        pushUsageRecord(s, d, NS_USAGE_APP, "${keys.deviceId}:$date", payload)
    }

    private fun pushUsageRecord(s: SupabaseRest.Session, d: ByteArray, namespace: String, recordKey: String, payload: JsonObject) {
        if (!entitled) return
        val hashKey = "$namespace/$recordKey"
        val payloadJson = payload.toString()
        val digest = CryptoBox.toBase64Url(
            MessageDigest.getInstance("SHA-256").digest(payloadJson.toByteArray(Charsets.UTF_8))
        )
        if (pushedDigests[hashKey] == digest) return
        val aad = CryptoBox.recordAad(s.userId, namespace, recordKey)
        val blob = CryptoBox.encryptRecord(d, aad, payloadJson)
        rest.upsertRecord(s, namespace, recordKey, keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
        pushedDigests[hashKey] = digest
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun buildFocusJson(
        active: Boolean,
        groupId: String,
        name: String,
        endsAt: Long,
        startedAt: Long,
        domains: List<String>,
        packages: List<String>,
        mode: String,
        exitable: Boolean,
    ): String {
        val o = JsonObject()
        o.addProperty("active", active)
        o.addProperty("groupId", groupId)
        o.addProperty("name", name)
        o.addProperty("endsAt", endsAt)
        o.addProperty("startedAt", startedAt)
        o.addProperty("mode", mode)
        o.addProperty("exitable", exitable)
        o.addProperty("origin", keys.deviceId)
        o.add("domains", com.google.gson.JsonArray().apply { domains.sorted().forEach { add(it) } })
        o.add("packages", com.google.gson.JsonArray().apply { packages.sorted().forEach { add(it) } })
        return o.toString()
    }

    // Everything that defines the session, excluding startedAt (stamped with the
    // push time) and the cosmetic name, so echoes and re-invocations compare equal.
    private fun focusKey(
        active: Boolean,
        groupId: String,
        endsAt: Long,
        domains: List<String>,
        packages: List<String>,
        mode: String,
        exitable: Boolean,
    ): String = JsonObject().apply {
        addProperty("active", active)
        addProperty("groupId", groupId)
        addProperty("endsAt", endsAt)
        addProperty("mode", mode)
        addProperty("exitable", exitable)
        add("domains", com.google.gson.JsonArray().apply { domains.sorted().forEach { add(it) } })
        add("packages", com.google.gson.JsonArray().apply { packages.sorted().forEach { add(it) } })
    }.toString()

    private fun pushFocusFrom(settings: Settings) {
        if (!keys.syncReducerConfigs) return
        if (!entitled) return
        val s = session ?: return
        val d = dek ?: return
        val (groupId, endsAt) = settings.activeManualFocusGroupId
        val isPomodoroBreak = settings.activePomodoroState.isActive &&
            settings.activePomodoroState.phase == PomodoroPhase.BREAK
        val active = groupId != null && endsAt > System.currentTimeMillis() && !isPomodoroBreak
        val g = if (active) settings.manualFocusGroups.find { it.groupId == groupId } else null
        val domains = g?.keywords?.toList() ?: emptyList()
        val packages = g?.packages?.toList() ?: emptyList()
        val mode = if (g?.blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) "all-except" else "only"
        val exitable = g?.exitable ?: true
        val key = if (active) focusKey(true, groupId!!, endsAt, domains, packages, mode, exitable)
                  else focusKey(false, "", 0, emptyList(), emptyList(), "only", true)
        if (key == lastFocusKey) return
        val json = if (active) {
            buildFocusJson(true, groupId!!, g?.groupName ?: "Focus", endsAt, System.currentTimeMillis(), domains, packages, mode, exitable)
        } else {
            buildFocusJson(false, "", "", 0, 0, emptyList(), emptyList(), "only", true)
        }
        val aad = CryptoBox.recordAad(s.userId, NS_FOCUS, "active")
        val blob = CryptoBox.encryptRecord(d, aad, json)
        rest.upsertRecord(s, NS_FOCUS, "active", keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
        lastFocusKey = key
    }

    private suspend fun applyFocusRow(d: ByteArray, s: SupabaseRest.Session, row: SupabaseRest.SyncRow) {
        val aad = CryptoBox.recordAad(s.userId, NS_FOCUS, row.recordKey)
        val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
        require(json.length <= 1_000_000) { context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid) }
        val p = JSONObject(json)
        val endsAt = p.optLong("endsAt")
        val groupId = p.optString("groupId")
        require(groupId.length <= 200) { context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid) }
        val active = p.optBoolean("active") && groupId.isNotBlank() && endsAt > System.currentTimeMillis()
        val domains = jsonArrToList(p.optJSONArray("domains"))
        val packages = jsonArrToList(p.optJSONArray("packages"))
        val mode = p.optString("mode", "only")
        val name = p.optString("name", "Focus").take(100)
        val exitable = p.optBoolean("exitable", true)

        // Match the key the local push will compute after this apply, so the
        // resulting settings change is not echoed straight back up.
        lastFocusKey = if (active) {
            focusKey(true, groupId, endsAt, domains, packages, mode, exitable)
        } else {
            focusKey(false, "", 0, emptyList(), emptyList(), "only", true)
        }

        if (active) {
            dataStore.updateData { local ->
                val existing = local.manualFocusGroups.find { it.groupId == groupId }
                val groups = if (existing != null) {
                    local.manualFocusGroups
                } else {
                    injectedGroupIds.add(groupId)
                    local.manualFocusGroups + neth.iecal.curbox.data.models.ManualFocusGroup(
                        groupId = groupId,
                        groupName = name,
                        packages = HashSet(packages),
                        keywords = HashSet(domains),
                        blockMode = if (mode == "all-except") FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED else FocusBlockMode.BLOCK_SELECTED,
                        exitable = exitable,
                    )
                }
                local.copy(
                    manualFocusGroups = groups,
                    activeManualFocusGroupId = Pair(groupId, endsAt),
                    activePomodoroState = PomodoroState()
                )
            }
        } else {
            dataStore.updateData {
                it.copy(
                    activeManualFocusGroupId = Pair(null, 0L),
                    activePomodoroState = PomodoroState()
                )
            }
        }
        context.sendBroadcast(android.content.Intent(neth.iecal.curbox.blockers.FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE))
    }

    private fun jsonArrToList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        require(arr.length() <= 5000) { context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid) }
        return (0 until arr.length()).map {
            arr.getString(it).also { value ->
                require(value.length <= 1000) {
                    context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid)
                }
            }
        }
    }

    private fun canonicalFocusGroupJson(g: neth.iecal.curbox.data.models.ManualFocusGroup): String {
        val o = JsonObject()
        o.addProperty("id", g.groupId)
        o.addProperty("name", g.groupName)
        o.addProperty("mode", if (g.blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) "all-except" else "only")
        o.addProperty("exitable", g.exitable)
        o.addProperty("autoTurnOnDnd", g.autoTurnOnDnd)
        o.add("domains", com.google.gson.JsonArray().apply { g.keywords.sorted().forEach { add(it) } })
        o.add("packages", com.google.gson.JsonArray().apply { g.packages.sorted().forEach { add(it) } })
        return o.toString()
    }

    private fun pushFocusGroups(settings: Settings) {
        if (!keys.syncReducerConfigs) return
        if (!entitled) return
        val s = session ?: return
        val d = dek ?: return
        val present = HashSet<String>()
        val groupsById = LinkedHashMap<String, neth.iecal.curbox.data.models.ManualFocusGroup>()
        settings.manualFocusGroups.forEach { groupsById[it.groupId] = it }
        for (g in groupsById.values) {
            if (g.groupId.isBlank()) continue
            present.add(g.groupId)
            if (g.groupId in injectedGroupIds) continue
            val json = canonicalFocusGroupJson(g)
            if (focusGroupShadow[g.groupId] == json) continue
            val aad = CryptoBox.recordAad(s.userId, NS_FOCUS_GROUPS, g.groupId)
            val blob = CryptoBox.encryptRecord(d, aad, json)
            rest.upsertRecord(s, NS_FOCUS_GROUPS, g.groupId, keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
            focusGroupShadow[g.groupId] = json
            knownFocusGroupIds.add(g.groupId)
        }
        for (id in knownFocusGroupIds.toList()) {
            if (id in present) continue
            val aad = CryptoBox.recordAad(s.userId, NS_FOCUS_GROUPS, id)
            val blob = CryptoBox.encryptRecord(d, aad, JSONObject().put("id", id).toString())
            rest.upsertRecord(s, NS_FOCUS_GROUPS, id, keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis(), deleted = true)
            focusGroupShadow.remove(id)
            knownFocusGroupIds.remove(id)
        }
        keys.knownFocusGroupIds = knownFocusGroupIds
    }

    private suspend fun applyFocusGroupRows(d: ByteArray, s: SupabaseRest.Session, rows: List<SupabaseRest.SyncRow>) {
        if (rows.isEmpty()) return
        val removed = HashSet<String>()
        val upserts = LinkedHashMap<String, neth.iecal.curbox.data.models.ManualFocusGroup>()
        for (row in rows) {
            require(row.recordKey.isNotBlank() && row.recordKey.length <= 200) {
                context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid)
            }
            if (row.deleted) {
                removed.add(row.recordKey)
                upserts.remove(row.recordKey)
                focusGroupShadow.remove(row.recordKey)
                knownFocusGroupIds.remove(row.recordKey)
                continue
            }
            val aad = CryptoBox.recordAad(s.userId, NS_FOCUS_GROUPS, row.recordKey)
            val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
            require(json.length <= 1_000_000) {
                context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid)
            }
            val p = JSONObject(json)
            val g = neth.iecal.curbox.data.models.ManualFocusGroup(
                groupId = row.recordKey,
                groupName = p.optString("name", "Focus").take(100),
                packages = HashSet(jsonArrToList(p.optJSONArray("packages"))),
                keywords = HashSet(jsonArrToList(p.optJSONArray("domains"))),
                blockMode = if (p.optString("mode", "only") == "all-except") FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED else FocusBlockMode.BLOCK_SELECTED,
                exitable = p.optBoolean("exitable", true),
                autoTurnOnDnd = p.optBoolean("autoTurnOnDnd", false),
            )
            upserts[row.recordKey] = g
            removed.remove(row.recordKey)
            focusGroupShadow[row.recordKey] = canonicalFocusGroupJson(g)
            knownFocusGroupIds.add(row.recordKey)
        }
        dataStore.updateData { local ->
            val byId = LinkedHashMap<String, neth.iecal.curbox.data.models.ManualFocusGroup>()
            for (g in local.manualFocusGroups) byId[g.groupId] = g
            for (id in removed) byId.remove(id)
            for ((id, g) in upserts) byId[id] = g
            injectedGroupIds.removeAll(upserts.keys)
            local.copy(manualFocusGroups = byId.values.toList())
        }
        keys.knownFocusGroupIds = knownFocusGroupIds
    }

    private suspend fun pullSinceCursor() = pullMutex.withLock {
        pullSinceCursorLocked()
    }

    private suspend fun pullSinceCursorLocked() {
        if (!entitled) return
        val s = session ?: return
        val d = dek ?: return
        val cursor = keys.cursor
        var after: SupabaseRest.PullPosition? = null
        var configRow: SupabaseRest.SyncRow? = null
        var focusRow: SupabaseRest.SyncRow? = null
        val focusGroupRows = ArrayList<SupabaseRest.SyncRow>()
        var remoteUsage: RemoteUsageStore? = null
        var maxCursor = cursor
        var foundRows = false

        var pageSize: Int
        do {
            val rows = rest.pull(s, cursor, after, PULL_PAGE_SIZE)
            pageSize = rows.size
            if (rows.isEmpty()) break
            foundRows = true
            for (row in rows) {
                when {
                    keys.syncReducerConfigs && !row.deleted &&
                        row.namespace == NS_ANDROID_CONFIG && row.deviceId != keys.deviceId ->
                        configRow = row
                    keys.syncReducerConfigs && !row.deleted &&
                        row.namespace == NS_FOCUS && row.deviceId != keys.deviceId ->
                        focusRow = row
                    keys.syncReducerConfigs && row.namespace == NS_FOCUS_GROUPS &&
                        row.deviceId != keys.deviceId -> {
                        require(focusGroupRows.size < 5000) {
                            context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid)
                        }
                        focusGroupRows.add(row)
                    }
                    keys.syncUsageStats && row.namespace == NS_USAGE_WEB &&
                        selectedRemoteDevice(row.deviceId) ->
                        applyUsageRow(
                            d,
                            s,
                            row,
                            remoteUsage ?: RemoteUsageStore(context).also { remoteUsage = it },
                            web = true,
                        )
                    keys.syncUsageStats && row.namespace == NS_USAGE_APP &&
                        selectedRemoteDevice(row.deviceId) ->
                        applyUsageRow(
                            d,
                            s,
                            row,
                            remoteUsage ?: RemoteUsageStore(context).also { remoteUsage = it },
                            web = false,
                        )
                    else -> Unit
                }
                maxCursor = row.updatedAt
            }
            val last = rows.last()
            after = SupabaseRest.PullPosition(last.updatedAt, last.id)
        } while (pageSize == PULL_PAGE_SIZE)

        if (!foundRows) return
        remoteUsage?.flush()
        if (focusGroupRows.isNotEmpty()) applyFocusGroupRows(d, s, focusGroupRows)
        configRow?.let { applyConfigRow(d, s, it) }
        focusRow?.let { applyFocusRow(d, s, it) }
        keys.cursor = maxCursor
        publishStatus(lastSync = System.currentTimeMillis())
    }

    private fun selectedRemoteDevice(deviceId: String?): Boolean {
        if (deviceId == null || deviceId == keys.deviceId) return false
        val selected = keys.usageDeviceIds
        return selected.isEmpty() || deviceId in selected
    }

    private suspend fun applyConfigRow(d: ByteArray, s: SupabaseRest.Session, row: SupabaseRest.SyncRow) {
        val aad = CryptoBox.recordAad(s.userId, NS_ANDROID_CONFIG, row.recordKey)
        val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
        require(json.length <= 5_000_000) { context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid) }
        val root = gson.fromJson(json, JsonObject::class.java)
        require(root.entrySet().none { it.value.isJsonNull }) {
            context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid)
        }
        val remote = gson.fromJson(json, Settings::class.java)
        val norm = normalize(remote)
        val normJson = gson.toJson(norm)
        if (sameAsLastConfig(norm, normJson)) return
        dataStoreManager.updateFromSync(remote)
        val applied = normalize(dataStore.data.first())
        lastConfig = applied
        lastConfigJson = gson.toJson(applied)
    }

    private fun applyUsageRow(d: ByteArray, s: SupabaseRest.Session, row: SupabaseRest.SyncRow, store: RemoteUsageStore, web: Boolean) {
        val ns = if (web) NS_USAGE_WEB else NS_USAGE_APP
        if (row.deleted) {
            store.remove(ns, row.recordKey)
            return
        }
        val aad = CryptoBox.recordAad(s.userId, ns, row.recordKey)
        val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
        require(json.length <= 2_000_000) { context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid) }
        val payloadDate = JSONObject(json).getString("date")
        require(row.recordKey == "${row.deviceId}:$payloadDate") {
            context.getString(neth.iecal.curbox.R.string.account_err_sync_data_invalid)
        }
        store.put(ns, row.recordKey, json)
    }

    private fun publishStatus(lastSync: Long? = _status.value.lastSync, error: String? = null) {
        val s = session
        _status.value = SyncStatus(
            signedIn = s != null,
            email = s?.email,
            hasVault = vaultExists || dek != null,
            unlocked = dek != null && entitled,
            deviceId = if (s != null) keys.deviceId else null,
            lastSync = lastSync,
            error = error,
            pendingEmail = pendingEmail,
            devices = devices,
            preferences = preferences(),
        )
    }

    private fun todayNative(): String = neth.iecal.curbox.utils.TimeTools.getCurrentDate()
    private fun isoFor(native: String): String? = try {
        java.time.LocalDate.parse(
            native,
            java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()),
        ).toString()
    } catch (e: Exception) {
        null
    }

    private fun isRetainedUsageDate(iso: String): Boolean {
        val date = java.time.LocalDate.parse(iso)
        val today = java.time.LocalDate.now()
        return !date.isBefore(today.minusDays(28)) && !date.isAfter(today.plusDays(28))
    }
}
