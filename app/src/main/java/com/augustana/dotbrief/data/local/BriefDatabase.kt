package com.augustana.dotbrief.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.augustana.dotbrief.data.local.dao.CapturedNotificationDao
import com.augustana.dotbrief.data.local.entity.CapturedNotification

/**
 * 本地库。
 *
 * 只放"从通知里抓到的行程/快递"这一张表——日程走 CalendarContract 实时读，
 * 新闻每次现拉，都没有必要落库。库越小，schema 迁移越省心。
 *
 * `exportSchema = true`（在 build.gradle.kts 里配了 schemaLocation），
 * 每次改表结构都会在 app/schemas 下留一份 JSON，这是日后写 Migration 的依据，别删。
 */
@Database(
    entities = [CapturedNotification::class],
    version = 1,
    exportSchema = true,
)
abstract class BriefDatabase : RoomDatabase() {

    abstract fun capturedNotificationDao(): CapturedNotificationDao

    companion object {
        private const val DATABASE_NAME = "brief.db"

        fun build(context: Context): BriefDatabase = Room
            .databaseBuilder(context.applicationContext, BriefDatabase::class.java, DATABASE_NAME)
            .build()
    }
}
