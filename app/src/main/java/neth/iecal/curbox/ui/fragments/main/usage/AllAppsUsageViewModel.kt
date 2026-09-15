package neth.iecal.curbox.ui.fragments.main.usage

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.db.ReelUsageStatsEntity
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.utils.DataStoreManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AllAppsUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val usageStatsHelper = UsageStatsHelper(application)
    private val packageManager = application.packageManager
    private val websiteStatsDao = AppDatabase.getInstance(application).websiteStatsDao()
    private val reelUsageStatsDao = AppDatabase.getInstance(application).reelUsageStatsDao()

    // Search keywords typed in the URL bar get stored with the raw text as the domain.
    // A real website domain has no spaces and contains at least one dot (e.g. "youtube.com").
    private val domainRegex = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$", RegexOption.IGNORE_CASE)

    private fun WebsiteStatsEntity.isWebsite(): Boolean =
        domain.isNotBlank() && !domain.contains(' ') && domainRegex.matches(domain)

    val ignoredPackages: MutableSet<String> = mutableSetOf()

    private val dayUsageCache = ConcurrentHashMap<LocalDate, DayUsageSource>()
    private val appMetadataCache = ConcurrentHashMap<String, AppMetadata>()

    private data class DayUsageSource(
        val localApps: List<AppUsageStat>,
        val remoteApps: Map<String, Long>,
        val websites: List<WebsiteStatsEntity>,
        val remoteWebsites: Map<String, Long>,
        val reels: List<ReelUsageStatsEntity>
    )

    private data class DayUsage(
        val apps: List<AppUsageStat>,
        val websites: List<WebsiteStatsEntity>,
        val reels: List<ReelUsageStatsEntity>
    )

    data class AppMetadata(
        val label: CharSequence,
        val category: String,
        val isSystemApp: Boolean,
        val installDate: String,
        val lastUpdate: String,
        val icon: Drawable?
    )

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private var anchorWeekOffset = 0
    private var currentScale = AppUsageChartScale.DAYS
    private var displayedPeriods: List<AppUsagePeriod> = emptyList()
    private var chartLoadJob: Job? = null
    private var selectionLoadJob: Job? = null

    private val _chartScale = MutableLiveData(currentScale)
    val chartScale: LiveData<AppUsageChartScale> = _chartScale

    private val _isChartTransitioning = MutableLiveData(false)
    val isChartTransitioning: LiveData<Boolean> = _isChartTransitioning

    private val _chartRangeLabel = MutableLiveData<String>()
    val chartRangeLabel: LiveData<String> = _chartRangeLabel

    private val _chartData = MutableLiveData<List<WeeklyBarGraphView.BarData>>()
    val chartData: LiveData<List<WeeklyBarGraphView.BarData>> = _chartData

    private val _selectedPeriodIndex = MutableLiveData(6)
    val selectedPeriodIndex: LiveData<Int> = _selectedPeriodIndex

    private val _selectedPeriodAnalytics = MutableLiveData<AppUsageAnalytics>()
    val selectedPeriodAnalytics: LiveData<AppUsageAnalytics> = _selectedPeriodAnalytics

    // Can navigate forward?
    private val _canGoNext = MutableLiveData(false)
    val canGoNext: LiveData<Boolean> = _canGoNext

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("MMM d")

    // The fragment's view gets recreated (and initialize() re-invoked) whenever
    // it returns from a child screen like AppUsageBreakdown, even though this
    // same ViewModel instance already has data loaded. Guard against redoing
    // that first-time setup, which would otherwise re-flash the loading overlay.
    private var hasLoadedOnce = false

    fun initialize() {
        if (hasLoadedOnce) return
        hasLoadedOnce = true
        chartLoadJob = viewModelScope.launch(Dispatchers.IO) {
            getDefaultLauncherPackageName(getApplication<Application>().packageManager)?.let {
                ignoredPackages.add(it)
            }
            val datastore = DataStoreManager(getApplication())
            ignoredPackages.addAll(datastore.settings.first().usageTrackerIgnoredApps)
            loadChartData()
            refreshSyncedUsage()
        }
    }

    // Usage records never send a push to this device, so the freshest usage from
    // other devices only arrives when we ask. Pull once when the screen opens,
    // then reload with whatever came in.
    private suspend fun refreshSyncedUsage() {
        val provider = neth.iecal.curbox.data.sync.SyncGateway.provider
        if (!provider.isAvailable) return
        refreshProvider(provider)
        dayUsageCache.clear()
        loadChartData()
    }

    fun goToPreviousPeriod() {
        anchorWeekOffset -= AppUsageChartTimeline.navigationStep(currentScale)
        requestChartLoad()
    }

    fun goToNextPeriod() {
        if (anchorWeekOffset >= 0) return
        anchorWeekOffset = (anchorWeekOffset + AppUsageChartTimeline.navigationStep(currentScale))
            .coerceAtMost(0)
        requestChartLoad()
    }

    fun setChartScale(scale: AppUsageChartScale) {
        if (scale == currentScale) return
        if (scale == AppUsageChartScale.DAYS && currentScale == AppUsageChartScale.WEEKS) {
            displayedPeriods.getOrNull(_selectedPeriodIndex.value ?: -1)?.let { selectedPeriod ->
                anchorWeekOffset = AppUsageChartTimeline.weekOffsetFor(selectedPeriod, LocalDate.now())
            }
        }
        currentScale = scale
        _chartScale.value = scale
        _isChartTransitioning.value = true
        requestChartLoad()
    }

    fun selectPeriod(index: Int) {
        val period = displayedPeriods.getOrNull(index) ?: return
        _selectedPeriodIndex.value = index
        selectionLoadJob?.cancel()
        selectionLoadJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _isLoading.value = true }
            loadPeriodStats(period)
            withContext(Dispatchers.Main) { _isLoading.value = false }
        }
    }

    fun reload() {
        if (_chartData.value == null && chartLoadJob?.isActive == true) return
        dayUsageCache.remove(LocalDate.now())
        requestChartLoad()
    }

    // A user asked refresh: drop the cached day stats so the system's freshest
    // usage is read again, pull the latest usage from other devices, then reload.
    // Unlike reload() this always shows the loading overlay so the tap has visible feedback.
    fun refresh() {
        chartLoadJob?.cancel()
        selectionLoadJob?.cancel()
        chartLoadJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _isLoading.value = true }
            dayUsageCache.clear()
            val provider = neth.iecal.curbox.data.sync.SyncGateway.provider
            if (provider.isAvailable) refreshProvider(provider)
            loadChartData()
            withContext(Dispatchers.Main) { _isLoading.value = false }
        }
    }

    private fun requestChartLoad() {
        chartLoadJob?.cancel()
        selectionLoadJob?.cancel()
        if (_isLoading.value == true) _isLoading.value = false
        chartLoadJob = viewModelScope.launch(Dispatchers.IO) { loadChartData() }
    }

    private suspend fun loadChartData() {
        // Only show the full-screen loading overlay when there's nothing on
        // screen yet. Reloads triggered by revisiting this screen (returning
        // from AppUsageBreakdown, resuming the app) already have data to show
        // while they refresh in the background, so flashing the overlay for
        // those is just an annoying flicker rather than useful feedback.
        val silent = _selectedPeriodAnalytics.value != null
        if (!silent) withContext(Dispatchers.Main) { _isLoading.value = true }

        val today = LocalDate.now()
        val request = withContext(Dispatchers.Main) {
            currentScale to anchorWeekOffset
        }
        val periods = AppUsageChartTimeline.periods(
            scale = request.first,
            anchorWeekStart = AppUsageChartTimeline.weekStart(today, request.second)
        )
        val defaultSelected = AppUsageChartTimeline.defaultSelectedIndex(periods, today)
        primeDayUsageCache(periods.flatMap { datesIn(it, today) })

        withContext(Dispatchers.Main) {
            _canGoNext.value = request.second < 0
            _chartRangeLabel.value = formatRange(periods.first().start, periods.last().endInclusive)
        }

        val chartEntries = periods.map { period ->
            val totalTime = if (period.start.isAfter(today)) 0L else totalTimeForPeriod(period, today)
            WeeklyBarGraphView.BarData(
                label = period.label,
                value = totalTime / MILLIS_PER_HOUR,
                dateMillis = period.start.atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            )
        }

        withContext(Dispatchers.Main) {
            displayedPeriods = periods
            _chartData.value = chartEntries
            _selectedPeriodIndex.value = defaultSelected
        }

        loadPeriodStats(periods[defaultSelected])

        withContext(Dispatchers.Main) {
            if (_isLoading.value == true) _isLoading.value = false
            _isChartTransitioning.value = false
        }
    }

    private suspend fun loadPeriodStats(period: AppUsagePeriod) {
        val today = LocalDate.now()
        val days = datesIn(period, today).map { usageForDay(it) }
        val appStats = AppUsageAggregator.appStats(days.map { it.apps })
        val websiteStats = AppUsageAggregator.websiteStats(days.map { it.websites })
        val reelStats = AppUsageAggregator.reelStats(days.map { it.reels })
        preloadAppMetadata(appStats.map { it.packageName })

        val sublabel = when {
            period.start == today && period.endInclusive == today ->
                getApplication<Application>().getString(R.string.total_today)
            currentScale == AppUsageChartScale.WEEKS && today in period.start..period.endInclusive ->
                getApplication<Application>().getString(R.string.total_this_week)
            else -> getApplication<Application>().getString(
                R.string.total_for_period,
                formatRange(period.start, period.endInclusive)
            )
        }

        withContext(Dispatchers.Main) {
            _selectedPeriodAnalytics.value = AppUsageAnalytics(
                apps = appStats,
                websites = websiteStats,
                reels = reelStats,
                totalTime = appStats.sumOf { it.totalTime },
                totalLabel = sublabel
            )
        }
    }

    private suspend fun totalTimeForPeriod(period: AppUsagePeriod, today: LocalDate): Long {
        return datesIn(period, today).sumOf { date ->
            usageForDay(date).apps.sumOf { it.totalTime }
        }
    }

    private suspend fun usageForDay(date: LocalDate): DayUsage {
        val source = sourceForDay(date)
        val localApps = source.localApps.filter {
            it.totalTime >= 1_000 && it.packageName !in ignoredPackages
        }
        var apps = mergeRemoteApps(localApps, source.remoteApps)
        var websites = source.websites.filter { it.isWebsite() }

        if (source.remoteWebsites.isNotEmpty()) {
            val dateString = neth.iecal.curbox.utils.TimeTools.dayKey(date)
            websites = websites + source.remoteWebsites.map { (domain, duration) ->
                WebsiteStatsEntity(
                    date = dateString,
                    packageName = neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE,
                    urlIdentifier = domain,
                    domain = domain,
                    totalTime = duration
                )
            }
            apps = apps + AppUsageStat(
                neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE,
                source.remoteWebsites.values.sum()
            )
        }
        return DayUsage(apps, websites, source.reels)
    }

    private suspend fun sourceForDay(date: LocalDate): DayUsageSource {
        dayUsageCache[date]?.let { return it }
        primeDayUsageCache(listOf(date))
        return dayUsageCache.getValue(date)
    }

    private suspend fun primeDayUsageCache(dates: Collection<LocalDate>) = coroutineScope {
        val missingDates = dates.distinct().filterNot(dayUsageCache::containsKey)
        if (missingDates.isEmpty()) return@coroutineScope

        val dateKeys = missingDates.associateWith { date ->
            neth.iecal.curbox.utils.TimeTools.dayKey(date)
        }
        val datesByKey = dateKeys.entries.associate { (date, key) -> key to date }
        val isoDates = missingDates.mapTo(linkedSetOf()) { it.toString() }

        val appStats = async { usageStatsHelper.getForegroundStatsByDays(missingDates) }
        val websites = async {
            websiteStatsDao.getStatsForDates(dateKeys.values.toList())
                .mapNotNull { stat -> datesByKey[stat.date]?.let { date -> date to stat } }
                .groupBy({ it.first }, { it.second })
        }
        val reels = async {
            reelUsageStatsDao.getForDates(dateKeys.values.toList())
                .mapNotNull { stat -> datesByKey[stat.date]?.let { date -> date to stat } }
                .groupBy({ it.first }, { it.second })
        }
        val remoteUsage = async {
            remoteUsageForDates(neth.iecal.curbox.data.sync.SyncGateway.provider, isoDates)
        }

        val appStatsByDate = appStats.await()
        val websitesByDate = websites.await()
        val reelsByDate = reels.await()
        val remoteUsageByDate = remoteUsage.await()
        missingDates.forEach { date ->
            val remote = remoteUsageByDate[date.toString()]
            dayUsageCache.putIfAbsent(
                date,
                DayUsageSource(
                    localApps = appStatsByDate[date].orEmpty(),
                    remoteApps = remote?.apps.orEmpty(),
                    websites = websitesByDate[date].orEmpty(),
                    remoteWebsites = remote?.websites.orEmpty(),
                    reels = reelsByDate[date].orEmpty()
                )
            )
        }
    }

    private suspend fun refreshProvider(provider: neth.iecal.curbox.data.sync.SyncProvider) {
        try {
            provider.refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Local usage remains available when a sync refresh fails.
        }
    }

    private suspend fun remoteUsageForDates(
        provider: neth.iecal.curbox.data.sync.SyncProvider,
        dates: Set<String>
    ): Map<String, neth.iecal.curbox.data.sync.RemoteUsageTotals> = try {
        provider.remoteUsageForDates(dates)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyMap()
    }

    // Combines other devices' per app time into this device's list: matching apps
    // get their time added together, and apps that only ran on another device are
    // appended as their own rows.
    private fun mergeRemoteApps(
        local: List<AppUsageStat>,
        remote: Map<String, Long>,
    ): List<AppUsageStat> {
        val localByPkg = local.associateBy { it.packageName }
        val merged = ArrayList<AppUsageStat>(local.size + remote.size)
        for (st in local) {
            val extra = remote[st.packageName] ?: 0L
            merged.add(
                if (extra > 0L) {
                    AppUsageStat(st.packageName, st.totalTime + extra, st.sessions, st.hourlyUsage)
                } else {
                    st
                },
            )
        }
        for ((pkg, ms) in remote) {
            if (pkg !in localByPkg && ms >= 1_000 && pkg !in ignoredPackages) {
                merged.add(AppUsageStat(pkg, ms))
            }
        }
        return merged
    }

    private fun datesIn(period: AppUsagePeriod, today: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var date = period.start
        val end = minOf(period.endInclusive, today)
        while (!date.isAfter(end)) {
            dates += date
            date = date.plusDays(1)
        }
        return dates
    }

    private fun formatRange(start: LocalDate, endInclusive: LocalDate): String {
        val startLabel = start.format(dayLabelFormatter)
        if (start == endInclusive) return startLabel
        return getApplication<Application>().getString(
            R.string.usage_date_range,
            startLabel,
            endInclusive.format(dayLabelFormatter)
        )
    }

    private suspend fun preloadAppMetadata(packageNames: Collection<String>) {
        withContext(Dispatchers.IO) {
            packageNames.distinct().forEach { packageName ->
                getAppMetadata(packageName)
            }
        }
    }

    fun getAppMetadata(packageName: String): AppMetadata {
        if (packageName == neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE) {
            return AppMetadata(
                label = getApplication<android.app.Application>().getString(neth.iecal.curbox.R.string.synced_browsing),
                category = getApplication<android.app.Application>().getString(neth.iecal.curbox.R.string.synced_other_devices),
                isSystemApp = false,
                installDate = "",
                lastUpdate = "",
                icon = androidx.core.content.ContextCompat.getDrawable(getApplication(), neth.iecal.curbox.R.drawable.ic_synced_web),
            )
        }
        return appMetadataCache.computeIfAbsent(packageName) {
            try {
                val appInfo = packageManager.getApplicationInfo(it, 0)
                val packageInfo = packageManager.getPackageInfo(it, 0)
                val category = when (appInfo.category) {
                    ApplicationInfo.CATEGORY_GAME -> "GAME"
                    ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL NETWORKING"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
                    ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
                    ApplicationInfo.CATEGORY_AUDIO -> "AUDIO"
                    ApplicationInfo.CATEGORY_NEWS -> "NEWS"
                    ApplicationInfo.CATEGORY_IMAGE -> "IMAGE"
                    ApplicationInfo.CATEGORY_MAPS -> "MAPS"
                    else -> "APP"
                }

                AppMetadata(
                    label = appInfo.loadLabel(packageManager),
                    category = category,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    installDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(Date(packageInfo.firstInstallTime)),
                    lastUpdate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(Date(packageInfo.lastUpdateTime)),
                    icon = appInfo.loadIcon(packageManager)
                )
            } catch (e: Exception) {
                AppMetadata(
                    label = packageName,
                    category = "APP",
                    isSystemApp = false,
                    installDate = "N/A",
                    lastUpdate = "N/A",
                    icon = null
                )
            }
        }
    }

    fun getAppCategory(packageName: String): String {
        return getAppMetadata(packageName).category
    }

    private companion object {
        const val MILLIS_PER_HOUR = 1000f * 60f * 60f
    }
}
