package neth.iecal.curbox.ui.fragments.main.usage

import neth.iecal.curbox.data.db.ReelUsageStatsEntity
import neth.iecal.curbox.data.db.WebsiteStatsEntity

object AppUsageAggregator {

    fun appStats(days: List<List<AppUsageStat>>): List<AppUsageStat> {
        val totals = LinkedHashMap<String, MutableAppUsage>()
        days.flatten().forEach { stat ->
            val total = totals.getOrPut(stat.packageName) { MutableAppUsage() }
            total.totalTime += stat.totalTime
            total.sessions += stat.sessions
            stat.hourlyUsage.forEachIndexed { hour, duration ->
                total.hourlyUsage[hour] += duration
            }
        }
        return totals.map { (packageName, total) ->
            AppUsageStat(packageName, total.totalTime, total.sessions, total.hourlyUsage)
        }.sortedByDescending { it.totalTime }
    }

    fun websiteStats(days: List<List<WebsiteStatsEntity>>): List<WebsiteStatsEntity> = days
        .flatten()
        .groupBy { WebsiteKey(it.packageName, it.urlIdentifier, it.domain) }
        .map { (key, stats) ->
            WebsiteStatsEntity(
                date = stats.first().date,
                packageName = key.packageName,
                urlIdentifier = key.urlIdentifier,
                domain = key.domain,
                totalTime = stats.sumOf { it.totalTime },
                lastVisited = stats.maxOf { it.lastVisited }
            )
        }

    fun reelStats(days: List<List<ReelUsageStatsEntity>>): List<ReelUsageStatsEntity> = days
        .flatten()
        .groupBy { it.packageName }
        .map { (packageName, stats) ->
            ReelUsageStatsEntity(
                date = stats.first().date,
                packageName = packageName,
                totalTime = stats.sumOf { it.totalTime },
                reelCount = stats.sumOf { it.reelCount },
                lastUpdated = stats.maxOf { it.lastUpdated }
            )
        }

    private data class WebsiteKey(
        val packageName: String,
        val urlIdentifier: String,
        val domain: String
    )

    private class MutableAppUsage(
        var totalTime: Long = 0L,
        var sessions: Int = 0,
        val hourlyUsage: LongArray = LongArray(24)
    )
}
