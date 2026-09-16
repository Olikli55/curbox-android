package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ReelUsageStatsDao {

    @Query("SELECT * FROM reel_usage_stats WHERE date = :date")
    suspend fun getForDate(date: String): List<ReelUsageStatsEntity>

    @Query("SELECT * FROM reel_usage_stats WHERE date IN (:dates)")
    suspend fun getForDates(dates: List<String>): List<ReelUsageStatsEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: ReelUsageStatsEntity)

    @Query("UPDATE reel_usage_stats SET totalTime = totalTime + :deltaMs, lastUpdated = :updatedAt WHERE date = :date AND packageName = :packageName")
    suspend fun addTimeInternal(
        date: String,
        packageName: String,
        deltaMs: Long,
        updatedAt: Long
    )

    @Transaction
    suspend fun addTime(date: String, packageName: String, deltaMs: Long, updatedAt: Long) {
        insertIfAbsent(ReelUsageStatsEntity(date, packageName, lastUpdated = updatedAt))
        addTimeInternal(date, packageName, deltaMs, updatedAt)
    }

    @Query("UPDATE reel_usage_stats SET reelCount = reelCount + 1, lastUpdated = :updatedAt WHERE date = :date AND packageName = :packageName")
    suspend fun incrementCountInternal(date: String, packageName: String, updatedAt: Long)

    @Transaction
    suspend fun incrementCount(date: String, packageName: String, updatedAt: Long) {
        insertIfAbsent(ReelUsageStatsEntity(date, packageName, lastUpdated = updatedAt))
        incrementCountInternal(date, packageName, updatedAt)
    }

    @Query("SELECT DISTINCT date FROM reel_usage_stats")
    suspend fun getDistinctDates(): List<String>

    @Query("DELETE FROM reel_usage_stats WHERE date IN (:dates)")
    suspend fun deleteByDates(dates: List<String>)
}
