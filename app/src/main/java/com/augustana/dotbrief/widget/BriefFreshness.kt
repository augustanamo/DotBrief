package com.augustana.dotbrief.widget

import android.util.Log
import com.augustana.dotbrief.data.local.dao.CapturedNotificationDao

/**
 * 「还有没有没听过的新内容」的唯一判定处。
 *
 * ## 判据
 *
 * ```
 * 未听过  ==  通知库里最新一条通知的入库时间  >  上次听完简报的时刻
 *         或   简报正文的生成时刻              >  上次听完简报的时刻
 * ```
 *
 * 两半说的是同一件事的两个来源：**有东西进来了**（通知），
 * 或者**内容被重新刷过一遍了**（定时更新）。少了第二半，"到点自动刷新"
 * 这件事在桌面上就是隐形的 —— 内容在后台变新了，点阵却还是灰的，
 * 用户没有任何理由去点它，于是那条功能等于不存在。
 *
 * ## 为什么是时间戳比较，而不是一个布尔标志
 *
 * 布尔版要维护三个写点（捕获到通知置真、听完置假、清理过期时可能还要改），
 * 任何一个漏写都会让点阵永远停在错误的那一色上，而且**没有自查手段** ——
 * 桌面上只看到一个灰点阵，谁都说不清是哪一步漏了。
 * 时间戳版只写两个值（"我什么时候听完的""我什么时候刷过内容"），
 * "有没有新内容"每次现算，天然不可能不一致：
 *
 * | 情形 | 最新入库 | 上次听完 | 结果 |
 * |---|---|---|---|
 * | 从没有过通知 | 0 | 0 | 灰（0 > 0 为假） |
 * | 来了一条新通知 | t2 | t1 (< t2) | 彩 |
 * | 听完 | t2 | t2 | 灰 |
 * | 重启 / 覆盖安装 | t2 | t2 | 灰（不因为重启就重新变彩） |
 * | 老用户升级：库里有没听过的通知 | t2 | 0（没有这个键） | 彩（升级后自己亮起来，不需要种子数据） |
 *
 * 最后两行是这套设计真正的价值：既不误报，也不需要任何迁移代码。
 *
 * ## 已知边界
 *
 * 判据里的"通知"只覆盖**被本应用捕获并入库的**那几类（行程 / 快递）。
 * 日历与 RSS 是静默取数、没有推送事件，它们的变化只体现在第二半
 * （定时刷出来的新正文）上 —— 这也是第二半非有不可的原因。
 */
object BriefFreshness {

    /**
     * 查一次通知库，算出"有没有没听过的新内容"。
     *
     * 是挂起函数（要读 Room），因此只能用在已经有协程的地方：
     * [BriefWidgetProvider] 的异步推送分支、[BriefWidgetService] 的 `onDataSetChanged`。
     * 读库失败一律当"没有新内容" —— 点阵安静着总比卡在一个假的"有新消息"上强，
     * 但**必须留一条日志**：这个降级本身是看不见的（桌面上只是灰着），
     * 一旦是代码真出了问题，没有日志就只能靠猜。
     *
     * 先比"正文有没有刷新过"再查库：那一半是纯比较，
     * 省掉一次数据库查询（点阵刷新得比想象中频繁，尺寸一变就会走这里）。
     */
    suspend fun resolve(
        dao: CapturedNotificationDao,
        lastHeardAtEpochSeconds: Long,
        lastBriefAtEpochSeconds: Long,
    ): Boolean {
        if (lastBriefAtEpochSeconds > lastHeardAtEpochSeconds) return true

        val latest = runCatching { dao.latestCapturedAt() }
            .onFailure { Log.w(TAG, "读通知库失败，本次按「没有新内容」处理", it) }
            .getOrNull() ?: return false
        return latest > lastHeardAtEpochSeconds
    }

    private const val TAG = "BriefFreshness"
}
