package neth.iecal.curbox.trackers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.ReelStatsDao
import neth.iecal.curbox.data.db.ReelStatsEntity
import neth.iecal.curbox.data.models.ReelCounterOverlayConfig
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.reelData
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager
import neth.iecal.curbox.utils.TimeTools



class ReelsCountTracker {

    companion object {
        const val INTENT_ACTION_REFRESH_REEL_COUNTER = "neth.iecal.curbox.refresh.reel_counter"
    }

    private lateinit var service: BaseBlockingService
    private lateinit var overlayManager: ReelsOverlayManager
    private lateinit var reelStatsDao: ReelStatsDao
    private lateinit var crashLogger: CrashLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isOnDisplayCounter = true
    private var overlayConfig = ReelCounterOverlayConfig()
    private var todayCount = 0
    private var lastDateStr = TimeTools.getCurrentDate()
    private var reelCountedCallback: (String) -> Unit = {}


    private val lastDynamicText = mutableMapOf<String, String>()
    private val seenReelsCache = mutableMapOf<String, LruCache<String, Boolean>>()

    private var ignored = listOf<String>()
    fun setup(
        service: BaseBlockingService,
        overlayManager: ReelsOverlayManager,
        onReelCounted: (String) -> Unit
    ) {
        this.service = service
        this.overlayManager = overlayManager
        crashLogger = CrashLogger(service)
        reelCountedCallback = onReelCounted

        ignored = listOf("com.android.systemui",
            service.packageName,
            "com.google.android.apps.wellbeing")
        val db = AppDatabase.getInstance(service)
        this.reelStatsDao = db.reelStatsDao()

        scope.launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                isOnDisplayCounter = settings.isReelCounterOn
                overlayConfig = settings.reelCounterOverlayConfig
                postOverlayUpdate {
                    overlayManager.binding?.reelCounter?.text = todayCount.toString()
                    overlayManager.setCounterVisible(shouldShowCounter())
                }
            }
        }

        scope.launch {
            try {
                lastDateStr = TimeTools.getCurrentDate()
                todayCount = reelStatsDao.getCount(lastDateStr) ?: 0
            } catch (_: Exception) {
                todayCount = 0
            }
        }
    }

    fun onEvent(event: AccessibilityEvent?, dynamicComparator: String?) {
        try {
            val pkg = event?.packageName?.toString() ?: return
            if (ignored.contains(pkg)) return

            val data = reelData[pkg]

            if (data != null) {
                if (dynamicComparator == null) {
                    lastDynamicText.remove(pkg)
                    hideReelCounter()
                    return
                }
                if ((event.eventType and data.eventType) == 0) return
                if (Settings.canDrawOverlays(service) && !overlayManager.isOverlayVisible) {
                    postOverlayUpdate {
                        if (!overlayManager.isOverlayVisible) {
                            overlayManager.reelsScrolledThisSession = todayCount
                            overlayManager.startDisplaying(overlayConfig, shouldShowCounter())
                        }
                    }
                }

                checkForReelProgression(
                    pkg,
                    dynamicComparator,
                    data.deduplicateComparators,
                    data.initialComparator
                )
            } else if (overlayManager.isOverlayVisible) {
                postOverlayUpdate { overlayManager.removeOverlay() }
                return
            }


        } catch (error: Exception) {
            crashLogger.logNonFatalError(error)
        }
    }

    private fun checkForReelProgression(
        pkg: String,
        currentText: String,
        deduplicateComparators: Boolean,
        initialComparator: String?
    ) {
        if (currentText.trim().isBlank()) {
            if (!initialComparator.isNullOrBlank() && lastDynamicText[pkg].isNullOrEmpty()) {
                lastDynamicText[pkg] = initialComparator
            }
            return
        }

        val previousText = lastDynamicText[pkg] ?: ""
        if (currentText != previousText) {
            val isSubstantialChange = isSubstantialTextChange(currentText, previousText)

            if (previousText.isNotEmpty() && isSubstantialChange) {
                if (!deduplicateComparators) {
                    onReelCounted(pkg)
                } else {
                    val appCache = seenReelsCache.getOrPut(pkg) { LruCache(50) }
                    if (appCache.get(currentText) == null) {
                        onReelCounted(pkg)
                        appCache.put(currentText, true)
                    }
                }
            }
            
            if (isSubstantialChange || currentText.length > previousText.length) {
                lastDynamicText[pkg] = currentText
            }
        }
    }

    fun getTodayCount(): Int = todayCount

    private fun onReelCounted(packageName: String) {
        val date = TimeTools.getCurrentDate()
        if (date != lastDateStr) {
            todayCount = 0
            lastDateStr = date
        }
        todayCount++
        reelCountedCallback(packageName)
        overlayManager.reelsScrolledThisSession = todayCount

        postOverlayUpdate {
            val shouldShow = shouldShowCounter()
            if (shouldShow) {
                overlayManager.binding?.reelCounter?.apply {
                    text = todayCount.toString()
                }
            }
            overlayManager.setCounterVisible(shouldShow)
        }

        scope.launch {
            try {
                reelStatsDao.upsert(ReelStatsEntity(date = date, count = todayCount))
            } catch (_: Exception) { }
        }

        service.lastBackPressTimeStamp = SystemClock.uptimeMillis()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_ACTION_REFRESH_REEL_COUNTER -> setup(
                    service,
                    overlayManager,
                    reelCountedCallback
                )
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_REEL_COUNTER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun onDestroy() {
        postOverlayUpdate {
            overlayManager.removeOverlay()
            overlayManager.binding = null
        }
        try { service.unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun hideReelCounter() {
        postOverlayUpdate { overlayManager.setCounterVisible(false) }
    }

    private fun shouldShowCounter(): Boolean =
        isOnDisplayCounter && overlayConfig.shouldShowAtCount(todayCount)

    private fun postOverlayUpdate(block: () -> Unit) {
        mainHandler.post {
            try {
                block()
            } catch (error: Exception) {
                crashLogger.logNonFatalError(error)
            }
        }
    }

    private fun isSubstantialTextChange(currentText: String, previousText: String): Boolean {
        if (currentText.isEmpty() || previousText.isEmpty()) return true

        fun countWords(text: String, wordCounts: HashMap<String, Int>) {
            val len = text.length
            var start = -1
            for (i in 0 until len) {
                if (text[i].isWhitespace()) {
                    if (start != -1) {
                        val word = text.substring(start, i)
                        wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
                        start = -1
                    }
                } else {
                    if (start == -1) start = i
                }
            }
            if (start != -1) {
                val word = text.substring(start, len)
                wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
            }
        }

        val currentWords = HashMap<String, Int>()
        val previousWords = HashMap<String, Int>()
        
        countWords(currentText, currentWords)
        countWords(previousText, previousWords)

        if (currentWords.isEmpty() || previousWords.isEmpty()) return true

        var intersectionSize = 0
        var totalSmaller = 0
        
        val smallerMap = if (currentWords.size < previousWords.size) currentWords else previousWords
        val largerMap = if (currentWords.size < previousWords.size) previousWords else currentWords

        for ((word, count) in smallerMap) {
            totalSmaller += count
            val largerCount = largerMap[word] ?: 0
            intersectionSize += minOf(count, largerCount)
        }

        if (totalSmaller == 0) return true

        val overlapRatio = intersectionSize.toFloat() / totalSmaller
        return overlapRatio < 0.90f
    }
}
