package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.ReelCounterOverlayConfig
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import neth.iecal.curbox.utils.DataStoreManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

class ReelCounterViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)
    private val reelStatsDao = AppDatabase.getInstance(application).reelStatsDao()

    val settings = dataStoreManager.settings

    private val _overlayConfig = MutableStateFlow(ReelCounterOverlayConfig())
    val overlayConfig: StateFlow<ReelCounterOverlayConfig> = _overlayConfig.asStateFlow()

    private val _weekOffset = MutableLiveData(0)
    val weekOffset: LiveData<Int> = _weekOffset

    private val _weekRangeLabel = MutableLiveData<String>()
    val weekRangeLabel: LiveData<String> = _weekRangeLabel

    private val _weeklyData = MutableLiveData<List<WeeklyBarGraphView.BarData>>()
    val weeklyData: LiveData<List<WeeklyBarGraphView.BarData>> = _weeklyData

    private val _selectedDayIndex = MutableLiveData(6)
    val selectedDayIndex: LiveData<Int> = _selectedDayIndex

    private val _selectedDayTotal = MutableLiveData(0)
    val selectedDayTotal: LiveData<Int> = _selectedDayTotal

    private val _canGoNext = MutableLiveData(false)
    val canGoNext: LiveData<Boolean> = _canGoNext

    private val _dateSublabel = MutableLiveData("TOTAL TODAY")
    val dateSublabel: LiveData<String> = _dateSublabel

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("MMM d")

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _overlayConfig.value = settings.reelCounterOverlayConfig
            }
        }
    }

    fun initialize() {
        viewModelScope.launch {
            loadWeekData()
        }
    }

    fun setIsActive(isActive: Boolean) {
        viewModelScope.launch {
            dataStoreManager.updateReelCounterState(isActive)
            requestReelCounterRefresh()
        }
    }

    fun updateOverlayConfig(config: ReelCounterOverlayConfig) {
        viewModelScope.launch {
            dataStoreManager.updateReelCounterOverlayConfig(config)
        }
    }

    fun goToPreviousWeek() {
        _weekOffset.value = (_weekOffset.value ?: 0) - 1
        viewModelScope.launch { loadWeekData() }
    }

    fun goToNextWeek() {
        val current = _weekOffset.value ?: 0
        if (current < 0) {
            _weekOffset.value = current + 1
            viewModelScope.launch { loadWeekData() }
        }
    }

    fun selectDay(index: Int) {
        _selectedDayIndex.value = index
        viewModelScope.launch {
            val weekStart = getWeekStart(_weekOffset.value ?: 0)
            val selectedDate = weekStart.plusDays(index.toLong())
            loadDayStats(selectedDate)
        }
    }

    private fun requestReelCounterRefresh() {
        val intent = Intent(ReelsCountTracker.INTENT_ACTION_REFRESH_REEL_COUNTER)
        application.sendBroadcast(intent)
    }

    private suspend fun loadWeekData() = withContext(Dispatchers.IO) {
        val offset = _weekOffset.value ?: 0
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
        val dbRecords = reelStatsDao.getAll().associateBy { it.date }

        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            if (date == today) todayIndex = i

            val isFuture = date.isAfter(today)
            val count = if (isFuture) 0 else {
                val dbDateStr = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy"))
                dbRecords[dbDateStr]?.count ?: 0
            }

            val dateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            dayDataList.add(WeeklyBarGraphView.BarData(dayLabels[i], count.toFloat(), dateMillis))
        }

        val defaultSelected = if (isCurrentWeek && todayIndex >= 0) todayIndex else 6

        withContext(Dispatchers.Main) {
            _weeklyData.value = dayDataList
            _selectedDayIndex.value = defaultSelected
        }

        val selectedDate = weekStart.plusDays(defaultSelected.toLong())
        loadDayStats(selectedDate)
    }

    private suspend fun loadDayStats(date: LocalDate) = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val isFuture = date.isAfter(today)
        val isToday = date == today

        val dbDateStr = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy"))
        val count = if (isFuture) 0 else reelStatsDao.getCount(dbDateStr) ?: 0

        val sublabel = if (isToday) "TOTAL TODAY" else "TOTAL · ${date.format(dayLabelFormatter)}"

        withContext(Dispatchers.Main) {
            _selectedDayTotal.value = count
            _dateSublabel.value = sublabel
        }
    }

    private fun getWeekStart(offset: Int): LocalDate {
        return LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong())
    }
}
