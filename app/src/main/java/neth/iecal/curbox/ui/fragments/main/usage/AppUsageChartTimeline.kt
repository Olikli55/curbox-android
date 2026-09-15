package neth.iecal.curbox.ui.fragments.main.usage

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit

object AppUsageChartTimeline {
    const val WEEKS_PER_PAGE = 4

    private val dayOfWeekLabels = listOf("M", "T", "W", "T", "F", "S", "S")
    private val weekLabelFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun weekStart(today: LocalDate, offset: Int): LocalDate = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .plusWeeks(offset.toLong())

    fun periods(
        scale: AppUsageChartScale,
        anchorWeekStart: LocalDate
    ): List<AppUsagePeriod> = when (scale) {
        AppUsageChartScale.DAYS -> dayOfWeekLabels.mapIndexed { index, label ->
            val date = anchorWeekStart.plusDays(index.toLong())
            AppUsagePeriod(date, date, label)
        }

        AppUsageChartScale.WEEKS -> (WEEKS_PER_PAGE - 1 downTo 0).map { weeksAgo ->
            val start = anchorWeekStart.minusWeeks(weeksAgo.toLong())
            AppUsagePeriod(start, start.plusDays(6), start.format(weekLabelFormatter))
        }
    }

    fun defaultSelectedIndex(
        periods: List<AppUsagePeriod>,
        today: LocalDate
    ): Int {
        val todayIndex = periods.indexOfFirst { today in it.start..it.endInclusive }
        return if (todayIndex >= 0) todayIndex else periods.lastIndex
    }

    fun weekOffsetFor(period: AppUsagePeriod, today: LocalDate): Int {
        val currentWeekStart = weekStart(today, 0)
        return ChronoUnit.WEEKS.between(currentWeekStart, period.start).toInt()
    }

    fun navigationStep(scale: AppUsageChartScale): Int = when (scale) {
        AppUsageChartScale.DAYS -> 1
        AppUsageChartScale.WEEKS -> WEEKS_PER_PAGE
    }
}
