package com.augustana.dotbrief.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 从通知里抓到的行程 / 快递条目。
 *
 * 设计要点：
 * - **只存关键字命中的通知**，不落任何无关通知——这是隐私底线，也是降噪需求；
 * - [fingerprint] 建唯一索引：同一条通知系统可能重复推送（更新进度、重发），
 *   靠它做幂等，避免"同一个取件码念三遍"；
 * - [expireAtEpochSeconds] 让"过期自动清理"变成一条 SQL：起飞/签收之后就没必要再念了。
 */
@Entity(
    tableName = "captured_notification",
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class CapturedNotification(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** TRIP / PACKAGE，对应 [com.augustana.dotbrief.data.notification.IngestKind] 的枚举名。 */
    val kind: String,

    /** 来源应用包名，例如 com.taobao.taobao。 */
    val sourcePackage: String,

    /** 来源应用名，播报时用来交代"哪来的"，例如"菜鸟"。 */
    val appLabel: String,

    val title: String,

    val body: String,

    /** 结构化提取出来的关键值：车次 / 航班号 / 取件码。 */
    val code: String,

    /** 结构化提取出来的时间片段，例如 "09:35"。 */
    val timeText: String,

    val capturedAtEpochSeconds: Long,

    val expireAtEpochSeconds: Long,

    /** 去重指纹：来源包名 + 标题 + 正文的哈希。 */
    val fingerprint: String,
)
