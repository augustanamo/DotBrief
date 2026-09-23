package com.briefwidget.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.briefwidget.data.local.entity.CapturedNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface CapturedNotificationDao {

    /** 冲突忽略：靠 fingerprint 唯一索引做幂等，重复推送不会产生重复行。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: CapturedNotification): Long

    @Query(
        "SELECT * FROM captured_notification " +
            "WHERE expireAtEpochSeconds > :now " +
            "ORDER BY capturedAtEpochSeconds DESC",
    )
    suspend fun active(now: Long): List<CapturedNotification>

    @Query(
        "SELECT * FROM captured_notification " +
            "WHERE kind = :kind AND expireAtEpochSeconds > :now " +
            "ORDER BY capturedAtEpochSeconds DESC",
    )
    suspend fun activeByKind(kind: String, now: Long): List<CapturedNotification>

    @Query("SELECT COUNT(*) FROM captured_notification WHERE expireAtEpochSeconds > :now")
    suspend fun activeCount(now: Long): Int

    /**
     * 最新一条已捕获通知的入库时间；一条都没有时返回 null。
     *
     * 有意**不带** `expireAtEpochSeconds > :now` 过滤：这个值只回答一个问题 ——
     * 「有没有比"上次听完"更晚到达的通知」，而一条**已经过期但从没被听过**的通知，
     * 答案依然是"有"。过期只影响空间清理，不该影响这个判定。
     */
    @Query("SELECT MAX(capturedAtEpochSeconds) FROM captured_notification")
    suspend fun latestCapturedAt(): Long?

    /** 同 [latestCapturedAt]，但以 Flow 形式给设置页做实时回显（表一变 Room 自动重发）。 */
    @Query("SELECT MAX(capturedAtEpochSeconds) FROM captured_notification")
    fun latestCapturedAtFlow(): Flow<Long?>

    /** 过期清理，返回删掉的行数。每次生成简报前顺手跑一次即可。 */
    @Query("DELETE FROM captured_notification WHERE expireAtEpochSeconds <= :now")
    suspend fun purgeExpired(now: Long): Int

    @Query("DELETE FROM captured_notification")
    suspend fun clear()
}
