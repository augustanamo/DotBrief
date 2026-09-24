package com.augustana.dotbrief.data.update

import android.content.Context
import android.util.Log
import com.augustana.dotbrief.data.settings.RuntimeStateStore
import com.augustana.dotbrief.data.settings.SettingsRepository
import com.augustana.dotbrief.domain.BriefOutcome
import com.augustana.dotbrief.domain.GenerateBriefUseCase
import com.augustana.dotbrief.toastIssue
import com.augustana.dotbrief.widget.BriefWidgetProvider
import kotlinx.coroutines.CancellationException

/**
 * 生成一份简报并落盘（写缓存 + 刷新桌面点阵）。**绝不出声。**
 *
 * ## 为什么要有这么一个类
 *
 * "到点自动刷"和"用户在设置页手动刷"必须是**同一条路径**。这两处各自写一份的话，
 * 下面这几条规矩迟早只改一边 —— 而它们每一条都是踩出来的：
 * - 失败**不覆盖**缓存（否则用户点了刷新反而把昨天那份能念的内容清掉了）；
 * - 降级交付（模型没走通、退了本地简报）照常写缓存，但原因要 [recordIssue]；
 * - 落盘后要 `refreshAll()`，让桌面点阵当场变彩。
 *
 * 表现会是"自动刷的内容好好的，手动刷的却把缓存清了"这种极难复现的差异。
 *
 * 搬过来之前这段逻辑在 [BriefUpdateWorker] 里，只有定时任务一个调用方；
 * 加手动入口时没有复制一份，而是抽到这里 —— 二选一的话，多一个类比多一份实现便宜。
 *
 * ## 为什么"手动"要能穿过自动更新的开关
 *
 * 见 [run] 的 `force` 参数。
 */
class BriefUpdater(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val runtimeStateStore: RuntimeStateStore,
    private val generateBrief: GenerateBriefUseCase,
) {

    /** 一次刷新的结果。调用方据此给用户反馈，不用再去读一遍状态。 */
    sealed interface Outcome {

        /**
         * 生成成功并已落盘。
         *
         * [chars] 是正文长度，用于日志与提示；[degraded] 非空表示这次交付的是
         * **降级内容**（模型没走通，退了本地简报），值就是原因 —— 用户能听到东西，
         * 但"今天怎么没有快讯"只有它解释得了。
         */
        data class Updated(val chars: Int, val degraded: String? = null) : Outcome

        /** 生成失败，**缓存保持原样**。[message] 已经是给用户看的人话。 */
        data class Failed(val message: String) : Outcome

        /** 自动更新关着（或时刻表为空），且本次不是强制 —— 什么都没做。 */
        data object Skipped : Outcome
    }

    /**
     * 跑一次。
     *
     * @param force `true` = 用户主动点的「刷新内容」。
     *
     * 「到点自动刷新」那个开关管的是**自动**，不该顺带把用户主动要一份新的也禁掉 ——
     * 关掉自动更新的人恰恰最可能想手动刷（他选择"我不定时刷，我想刷的时候自己刷"）。
     * 所以闸门只在 `force = false` 时生效。
     */
    suspend fun run(force: Boolean): Outcome {
        val settings = try {
            settingsRepository.snapshot()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "读配置失败，本次跳过", error)
            return Outcome.Failed("读配置失败，稍后再试")
        }

        // 定时任务是在上一次排期时定下的，用户中途可能已经把自动更新关掉或清空了时刻。
        // 这里必须复查 —— 否则"关掉自动更新"之后，已经排在那儿的任务还会再跑一轮。
        if (!force && (!settings.brief.updateEnabled || settings.brief.updateTimes.isEmpty())) {
            return Outcome.Skipped
        }

        val outcome = try {
            generateBrief.execute(if (force) GenerateBriefUseCase.SOURCE_REFRESH else GenerateBriefUseCase.SOURCE_AUTO)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "生成时抛出未捕获异常", error)
            return Outcome.Failed("更新出错：${error.javaClass.simpleName}")
        }

        return when (outcome) {
            is BriefOutcome.Success -> {
                runtimeStateStore.saveBrief(outcome.text)
                BriefWidgetProvider.refreshAll(context)

                outcome.notice?.let { note ->
                    Log.w(TAG, "本次为降级内容：$note")
                    runtimeStateStore.recordIssue(note)
                    // 手动刷新时用户在设置页等着看 Snackbar，这里再弹一次就是重复打扰；
                    // 定时任务跑的时候没人在看设置页，只能靠 Toast 把话说出口。
                    if (!force) toastIssue(context, note)
                }

                Log.i(TAG, "更新完成（${if (force) "手动" else "定时"}），正文 ${outcome.text.length} 字")
                Outcome.Updated(outcome.text.length, outcome.notice)
            }

            is BriefOutcome.Failure -> {
                // 不覆盖缓存：留着上一份，至少用户点下去还有话说。
                // 写个空串进去才是真的全丢 —— 连昨天没听完的都没了。
                Log.w(TAG, "更新失败，保留上一份缓存：${outcome.message}")
                runtimeStateStore.recordIssue(outcome.message)
                if (!force) toastIssue(context, outcome.message)
                Outcome.Failed(outcome.message)
            }
        }
    }

    private companion object {
        const val TAG = "BriefUpdate"
    }
}
