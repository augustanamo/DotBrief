package com.augustana.dotbrief.data.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.augustana.dotbrief.BriefWidgetApp
import com.augustana.dotbrief.data.local.entity.CapturedNotification
import com.augustana.dotbrief.di.AppContainer
import com.augustana.dotbrief.widget.BriefWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant

/**
 * 通知监听：从航旅纵横 / 12306 / 菜鸟 / 京东等通知里抠出车次、航班与取件码。
 *
 * 隐私边界（重要）：
 * - **只有命中关键字的通知才会被写入数据库**，其余一律丢弃、不留痕；
 * - 正文截断到 [MAX_BODY] 字符，够提取和播报就行；
 * - 全流程无网络，数据不出设备。
 *
 * 这个服务必须由用户在「设置 -> 通知 -> 通知使用权」里手动勾选才能生效，
 * 无法通过运行时弹窗申请，所以设置页给了一个跳转入口。
 */
class BriefNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val container: AppContainer
        get() = (application as BriefWidgetApp).container

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch {
            container.runtimeStateStore.setNotificationListenerConnected(true)
            // 连上时顺手清一次过期条目，省得专门起个定时任务
            runCatching {
                container.capturedNotificationDao.purgeExpired(Instant.now().epochSecond)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        scope.launch {
            container.runtimeStateStore.setNotificationListenerConnected(false)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val sourcePackage = sbn.packageName ?: return

        // 自己的通知不抓（播报时那条第前台通知会自我循环）
        if (sourcePackage == packageName) return

        val flags = notification.flags
        // 正在进行的（音乐、下载）和分组摘要都不带有效信息
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            if (isNotEmpty()) append(' ')
            append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
        }.trim()

        if (title.isBlank() && body.isBlank()) return

        scope.launch { persistIfRelevant(sourcePackage, title, body) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun persistIfRelevant(sourcePackage: String, title: String, body: String) {
        val ingest = container.settingsRepository.snapshot().ingest
        if (!ingest.notificationListenerEnabled) return

        val parsed = NotificationParser.parse(
            title = title,
            body = body,
            tripKeywords = ingest.tripKeywords,
            packageKeywords = ingest.packageKeywords,
        ) ?: return

        val now = Instant.now().epochSecond
        val inserted = runCatching {
            container.capturedNotificationDao.insert(
                CapturedNotification(
                    kind = parsed.kind.name,
                    sourcePackage = sourcePackage,
                    appLabel = resolveAppLabel(sourcePackage),
                    title = title.take(MAX_TITLE),
                    body = body.take(MAX_BODY),
                    code = parsed.code,
                    timeText = parsed.timeText,
                    capturedAtEpochSeconds = now,
                    expireAtEpochSeconds = now + ingest.retentionDays.toLong() * SECONDS_PER_DAY,
                    fingerprint = fingerprint(sourcePackage, title, body),
                ),
            )
        }.getOrNull() ?: return

        // insert 走的是 OnConflictStrategy.IGNORE：同一条通知被重复推送时返回 -1。
        // 只有**真的新入库**的那一条才值得把桌面点亮 —— 否则一次进度更新就能让点阵
        // 在用户已经看完之后又闪回彩色，那等于把"颜色是信号"这件事作废。
        if (inserted <= 0L) return

        // 有新内容了：立刻把桌面点阵从灰翻成彩。
        // 这里不自己拼状态，走 refreshAll 让它按完整口径（含 unheard）重算一遍，
        // 避免"通知监听"和"小组件"两处各有一套判定逻辑。
        BriefWidgetProvider.refreshAll(this)
    }

    private fun resolveAppLabel(targetPackage: String): String {
        val info = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(targetPackage, 0)
        }.getOrNull() ?: return targetPackage
        return runCatching { packageManager.getApplicationLabel(info).toString() }
            .getOrDefault(targetPackage)
    }

    companion object {
        private const val MAX_TITLE = 120
        private const val MAX_BODY = 400
        private const val SECONDS_PER_DAY = 86_400L

        /** 同一通知常被重复推送（进度更新、重发），用内容指纹做幂等。 */
        private fun fingerprint(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(parts.joinToString("\u0001").toByteArray())
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
