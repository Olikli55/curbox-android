package neth.iecal.curbox.ui.fragments.main.usage

import neth.iecal.curbox.data.db.ReelUsageStatsEntity
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUsageAggregatorTest {

    @Test
    fun appStatsCombineTimeSessionsAndHourlyBucketsByPackage() {
        val firstHours = LongArray(24).apply { this[9] = 1_000L }
        val secondHours = LongArray(24).apply { this[9] = 2_000L }

        val result = AppUsageAggregator.appStats(
            listOf(
                listOf(AppUsageStat("app.one", 1_000L, 1, firstHours)),
                listOf(
                    AppUsageStat("app.one", 2_000L, 2, secondHours),
                    AppUsageStat("app.two", 4_000L)
                )
            )
        )

        assertEquals(listOf("app.two", "app.one"), result.map { it.packageName })
        assertEquals(3_000L, result[1].totalTime)
        assertEquals(3, result[1].sessions)
        assertArrayEquals(LongArray(24).apply { this[9] = 3_000L }, result[1].hourlyUsage)
    }

    @Test
    fun websiteStatsCombineMatchingDomainsAcrossDays() {
        val result = AppUsageAggregator.websiteStats(
            listOf(
                listOf(website("14 September 2026", "youtube.com", 1_000L)),
                listOf(website("15 September 2026", "youtube.com", 2_000L))
            )
        )

        assertEquals(1, result.size)
        assertEquals(3_000L, result.single().totalTime)
    }

    @Test
    fun reelStatsCombineDurationAndCountByPackage() {
        val result = AppUsageAggregator.reelStats(
            listOf(
                listOf(ReelUsageStatsEntity("14 September 2026", "app.one", 1_000L, 2, 10L)),
                listOf(ReelUsageStatsEntity("15 September 2026", "app.one", 3_000L, 4, 20L))
            )
        )

        assertEquals(4_000L, result.single().totalTime)
        assertEquals(6, result.single().reelCount)
        assertEquals(20L, result.single().lastUpdated)
    }

    private fun website(date: String, domain: String, totalTime: Long) = WebsiteStatsEntity(
        date = date,
        packageName = "browser",
        urlIdentifier = domain,
        domain = domain,
        totalTime = totalTime
    )
}
