package neth.iecal.curbox.ui.fragments.main.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.*

class WebsiteUsageViewModel(application: Application, private val packageName: String) : AndroidViewModel(application) {

    private val websiteStatsDao = AppDatabase.getInstance(application).websiteStatsDao()
    private val isSynced = packageName == neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE

    private suspend fun websitesForDate(date: LocalDate, dateString: String): List<WebsiteStatsEntity> {
        return if (isSynced) {
            neth.iecal.curbox.data.sync.SyncGateway.provider.remoteWebsiteUsage(date.toString())
                .map { (domain, ms) -> WebsiteStatsEntity(dateString, packageName, domain, domain, ms, 0L) }
                .sortedByDescending { it.totalTime }
        } else {
            websiteStatsDao.getStatsForDate(dateString).filter { it.packageName == packageName && it.isWebsite() }
        }
    }

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _weekOffset = MutableLiveData(0)
    val weekOffset: LiveData<Int> = _weekOffset

    private val _weekRangeLabel = MutableLiveData<String>()
    val weekRangeLabel: LiveData<String> = _weekRangeLabel

    private val _weeklyData = MutableLiveData<List<WeeklyBarGraphView.BarData>>()
    val weeklyData: LiveData<List<WeeklyBarGraphView.BarData>> = _weeklyData

    private val _selectedDayIndex = MutableLiveData(6)
    val selectedDayIndex: LiveData<Int> = _selectedDayIndex

    private val _selectedDayWebsiteStats = MutableLiveData<List<WebsiteStatsEntity>>()
    val selectedDayWebsiteStats: LiveData<List<WebsiteStatsEntity>> = _selectedDayWebsiteStats

    private val _totalTime = MutableLiveData<Long>(0L)
    val totalTime: LiveData<Long> = _totalTime

    private val _dateSublabel = MutableLiveData("TOTAL TODAY")
    val dateSublabel: LiveData<String> = _dateSublabel

    private val _canGoNext = MutableLiveData(false)
    val canGoNext: LiveData<Boolean> = _canGoNext

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("MMM d")

    // Search keywords typed in the URL bar get stored with the raw text as the domain.
    // A real website domain has no spaces and contains at least one dot (e.g. "youtube.com").
    private val domainRegex = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$", RegexOption.IGNORE_CASE)

    private fun WebsiteStatsEntity.isWebsite(): Boolean =
        domain.isNotBlank() && !domain.contains(' ') && domainRegex.matches(domain)

    fun initialize() {
        loadWeekData()
        if (isSynced) refreshSyncedUsage()
    }

    // Usage records never send a push to this device, so the freshest browsing
    // from other devices only arrives when we ask. Pull once when the screen
    // opens, then reload with whatever came in.
    private fun refreshSyncedUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val provider = neth.iecal.curbox.data.sync.SyncGateway.provider
            if (!provider.isAvailable) return@launch
            runCatching { provider.refresh() }
            loadWeekData()
        }
    }

    fun goToPreviousWeek() {
        _weekOffset.value = (_weekOffset.value ?: 0) - 1
        loadWeekData()
    }

    fun goToNextWeek() {
        val current = _weekOffset.value ?: 0
        if (current < 0) {
            _weekOffset.value = current + 1
            loadWeekData()
        }
    }

    fun selectDay(index: Int) {
        _selectedDayIndex.value = index
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _isLoading.value = true }
            val weekStart = getWeekStart(_weekOffset.value ?: 0)
            val selectedDate = weekStart.plusDays(index.toLong())
            loadDayStats(selectedDate)
            withContext(Dispatchers.Main) { _isLoading.value = false }
        }
    }

    private fun loadWeekData() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _isLoading.value = true }

            val offset = withContext(Dispatchers.Main) { _weekOffset.value ?: 0 }
            val weekStart = getWeekStart(offset)
            val weekEnd = weekStart.plusDays(6)

            val today = LocalDate.now()
            val isCurrentWeek = offset == 0

            withContext(Dispatchers.Main) {
                _canGoNext.value = offset < 0
                val startLabel = weekStart.format(dayLabelFormatter)
                val endLabel = weekEnd.format(dayLabelFormatter)
                _weekRangeLabel.value = "$startLabel – $endLabel"
            }

            val dayDataList = mutableListOf<WeeklyBarGraphView.BarData>()
            val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

            var todayIndex = -1

            for (i in 0..6) {
                val date = weekStart.plusDays(i.toLong())
                val isFuture = date.isAfter(today)

                val dateString = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()))
                val totalTimeMs = if (isFuture) 0L else websitesForDate(date, dateString).sumOf { it.totalTime }

                val hours = totalTimeMs / (1000f * 60f * 60f)
                val dateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

                dayDataList.add(WeeklyBarGraphView.BarData(dayLabels[i], hours, dateMillis))
                if (date == today) todayIndex = i
            }

            val defaultSelected = if (isCurrentWeek && todayIndex >= 0) todayIndex else 6

            withContext(Dispatchers.Main) {
                _weeklyData.value = dayDataList
                _selectedDayIndex.value = defaultSelected
            }

            val selectedDate = weekStart.plusDays(defaultSelected.toLong())
            loadDayStats(selectedDate)

            withContext(Dispatchers.Main) { _isLoading.value = false }
        }
    }

    private suspend fun loadDayStats(date: LocalDate) {
        val today = LocalDate.now()
        val isToday = date == today
        val sublabel = if (isToday) "TOTAL TODAY" else "TOTAL · ${date.format(dayLabelFormatter)}"

        val dateString = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()))
        val websiteStats = websitesForDate(date, dateString)
        val total = websiteStats.sumOf { it.totalTime }

        withContext(Dispatchers.Main) {
            _selectedDayWebsiteStats.value = websiteStats
            _totalTime.value = total
            _dateSublabel.value = sublabel
        }
    }

    private fun getWeekStart(offset: Int): LocalDate {
        return LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong())
    }
}
