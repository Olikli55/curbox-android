package neth.iecal.curbox.utils

import com.google.gson.Gson
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelBlockingType
import neth.iecal.curbox.data.models.ReelUsageConfig
import neth.iecal.curbox.data.models.upgradeLegacyConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelBlockerRestrictionComparatorTest {

    @Test
    fun oldStoredConfigGetsEmptyExcludedPackages() {
        val config = Gson().fromJson("""{"isActive":true}""", ReelBlocker::class.java)

        assertTrue(config.excludedPackages.isEmpty())
        assertFalse(config.allowInstagramReelsFromDmInbox)
    }

    @Test
    fun addingExcludedPackageIsWeaker() {
        val old = ReelBlocker(isActive = true)
        val new = old.copy(excludedPackages = listOf("com.instagram.android"))

        assertFalse(RestrictionComparator.reelBlocker(old, new))
    }

    @Test
    fun legacyUsageConfigKeepsItsUsageLimitAndAllowsAllHours() {
        val legacy = ReelBlocker(
            blockingType = ReelBlockingType.USAGE,
            settings = Gson().toJson(ReelUsageConfig(uniformLimit = 30))
        )

        val config = legacy.upgradeLegacyConfig().config!!

        assertTrue(config.schedule.everydayIntervals.single().endHour == 24)
        assertTrue(config.usage.uniformLimit == 30L)
    }

    @Test
    fun removingExcludedPackageIsStricter() {
        val old = ReelBlocker(
            isActive = true,
            excludedPackages = listOf("com.instagram.android")
        )
        val new = old.copy(excludedPackages = emptyList())

        assertTrue(RestrictionComparator.reelBlocker(old, new))
    }

    @Test
    fun allowingInstagramReelsFromDmInboxIsWeaker() {
        val old = ReelBlocker(isActive = true)

        assertFalse(
            RestrictionComparator.reelBlocker(
                old,
                old.copy(allowInstagramReelsFromDmInbox = true)
            )
        )
    }

    @Test
    fun disallowingInstagramReelsFromDmInboxIsStricter() {
        val old = ReelBlocker(
            isActive = true,
            allowInstagramReelsFromDmInbox = true
        )

        assertTrue(
            RestrictionComparator.reelBlocker(
                old,
                old.copy(allowInstagramReelsFromDmInbox = false)
            )
        )
    }
}
