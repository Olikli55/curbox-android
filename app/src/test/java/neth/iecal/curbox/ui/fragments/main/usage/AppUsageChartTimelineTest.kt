package neth.iecal.curbox.ui.fragments.main.usage

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUsageChartTimelineTest {

    private val today = LocalDate.of(2026, 9, 15)
    private val currentWeekStart = LocalDate.of(2026, 9, 14)

    @Test
    fun dailyScaleCreatesOnePeriodForEachDayOfTheWeek() {
        val periods = AppUsageChartTimeline.periods(AppUsageChartScale.DAYS, currentWeekStart)

        assertEquals(7, periods.size)
        assertEquals(LocalDate.of(2026, 9, 14), periods.first().start)
        assertEquals(LocalDate.of(2026, 9, 20), periods.last().endInclusive)
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), periods.map { it.label })
    }

    @Test
    fun weeklyScaleCreatesFourCompleteWeeksEndingAtTheAnchor() {
        val periods = AppUsageChartTimeline.periods(AppUsageChartScale.WEEKS, currentWeekStart)

        assertEquals(4, periods.size)
        assertEquals(LocalDate.of(2026, 8, 24), periods.first().start)
        assertEquals(LocalDate.of(2026, 9, 20), periods.last().endInclusive)
    }

    @Test
    fun currentPeriodIsSelectedAtEitherScale() {
        val daily = AppUsageChartTimeline.periods(AppUsageChartScale.DAYS, currentWeekStart)
        val weekly = AppUsageChartTimeline.periods(AppUsageChartScale.WEEKS, currentWeekStart)

        assertEquals(1, AppUsageChartTimeline.defaultSelectedIndex(daily, today))
        assertEquals(3, AppUsageChartTimeline.defaultSelectedIndex(weekly, today))
    }

    @Test
    fun selectedWeeklyPeriodMapsBackToItsDailyWeekOffset() {
        val periods = AppUsageChartTimeline.periods(AppUsageChartScale.WEEKS, currentWeekStart)

        assertEquals(-2, AppUsageChartTimeline.weekOffsetFor(periods[1], today))
    }
}
