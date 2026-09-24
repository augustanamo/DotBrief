package com.augustana.dotbrief.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.augustana.dotbrief.data.ingest.CalendarSource
import com.augustana.dotbrief.data.ingest.RssSource
import com.augustana.dotbrief.data.ingest.WeatherSource
import com.augustana.dotbrief.data.llm.LlmClient
import com.augustana.dotbrief.data.llm.LlmCallLogStore
import com.augustana.dotbrief.data.llm.buildLlmCallLogStore
import com.augustana.dotbrief.data.local.BriefDatabase
import com.augustana.dotbrief.data.local.dao.CapturedNotificationDao
import com.augustana.dotbrief.data.settings.RuntimeStateStore
import com.augustana.dotbrief.data.settings.SettingsRepository
import com.augustana.dotbrief.data.settings.runtimeDataStore
import com.augustana.dotbrief.data.settings.settingsDataStore
import com.augustana.dotbrief.data.tts.DoubaoTtsClient
import com.augustana.dotbrief.data.update.BriefUpdateScheduler
import com.augustana.dotbrief.data.update.BriefUpdater
import com.augustana.dotbrief.data.update.BriefAlarmScheduler
import com.augustana.dotbrief.domain.GenerateBriefUseCase
import com.augustana.dotbrief.tts.SpeechCache

/**
 * 手写依赖容器（Service Locator 风格）。
 *
 * 生命周期：与 Application 同寿命；所有依赖用 by lazy 延迟创建，
 * 因此"冷启动只点一次小组件"这种场景不会白建数据库连接。
 *
 * 为什么不用 Hilt：需要注入的位置只有 SettingsActivity、前台播报服务、
 * 小组件广播、帧工厂、通知监听服务五处，且后四者的实例化时机完全由系统控制。
 * Hilt 在这里带来的注解处理开销（KSP 全量重编）大于收益；
 * 日后模块变多要换 Hilt，把下面的 by lazy 改成 @Provides 即可，调用方不用动。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 用户配置（含 API Key）。 */
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext.settingsDataStore)
    }

    /** 小组件 / 播报 / 服务连接状态的运行期状态。 */
    val runtimeStateStore: RuntimeStateStore by lazy {
        RuntimeStateStore(appContext.runtimeDataStore)
    }

    /** 本地库（行程 / 快递抓取结果）。 */
    val briefDatabase: BriefDatabase by lazy {
        BriefDatabase.build(appContext)
    }

    val capturedNotificationDao: CapturedNotificationDao by lazy {
        briefDatabase.capturedNotificationDao()
    }

    /** 日程取数（读系统日历 CalendarContract.Instances）。 */
    val calendarSource: CalendarSource by lazy { CalendarSource(appContext) }

    /** 新闻取数（并行拉取所有启用的 RSS / Atom 源）。 */
    val rssSource: RssSource by lazy { RssSource() }

    /**
     * 天气取数（上次已知位置 + 免密钥的 Open-Meteo）。
     * 它是唯一一个"不依赖用户配置"的取数源 —— 装完就能用，也照常参与本地简报。
     */
    val weatherSource: WeatherSource by lazy { WeatherSource(appContext) }

    /** OpenAI 兼容协议的对话补全客户端。 */
    val llmClient: LlmClient by lazy { LlmClient() }

    /** LLM 调用日志（谁成功谁失败），独立 DataStore，见 [LlmCallLogStore]。 */
    val llmCallLog: LlmCallLogStore by lazy {
        buildLlmCallLogStore(appContext)
    }

    /** 豆包（火山引擎）语音合成客户端。只在 TTS 引擎选了豆包时才会被真正调用。 */
    val doubaoTtsClient: DoubaoTtsClient by lazy { DoubaoTtsClient() }

    /** 云端合成语音的缓存（以正文为 key），命中则跳过合成、直接播缓存。 */
    val speechCache: SpeechCache by lazy { SpeechCache(appContext) }

    /**
     * 生成一份口语简报：并行取数 -> 拼 prompt -> 调模型 -> 清洗 Markdown。
     * 播报服务和设置页的「立即播报」共用这一个入口，保证两条路径行为完全一致。
     */
    val generateBrief: GenerateBriefUseCase by lazy {
        GenerateBriefUseCase(
            context = appContext,
            settingsRepository = settingsRepository,
            capturedNotificationDao = capturedNotificationDao,
            runtimeStateStore = runtimeStateStore,
            calendarSource = calendarSource,
            rssSource = rssSource,
            weatherSource = weatherSource,
            llmClient = llmClient,
            llmCallLog = llmCallLog,
        )
    }

    /**
     * 到点自动刷新内容（只生成、不出声）的排期器。
     *
     * 它只是"排下一个时刻"，真正干活的是 [com.augustana.dotbrief.data.update.BriefUpdateWorker]。
     * 放在容器里是为了让三个调用方（Application 启动、设置页保存、上一轮任务结束）
     * 拿到的是同一份配置来源，不必各自去拼路径。
     */
    val briefUpdateScheduler: BriefUpdateScheduler by lazy {
        BriefUpdateScheduler(appContext, settingsRepository)
    }

    /**
     * 定时**出声**播报的排期器，与上面的自动刷新排期器完全独立（时刻表、开关、任务名都各走各的）。
     */
    val briefAlarmScheduler: BriefAlarmScheduler by lazy {
        BriefAlarmScheduler(appContext, settingsRepository)
    }

    /**
     * 「生成一份新的并落盘」这个动作本身，不带任何触发方式。
     *
     * 两个调用方共用它：定时任务（[com.augustana.dotbrief.data.update.BriefUpdateWorker]）
     * 和设置页的「刷新内容」（`SettingsViewModel.updateBriefNow`）。
     * 抽取的理由写在类注释里 —— 核心是"失败不覆盖缓存"这类规矩不能有两份实现。
     */
    val briefUpdater: BriefUpdater by lazy {
        BriefUpdater(
            context = appContext,
            settingsRepository = settingsRepository,
            runtimeStateStore = runtimeStateStore,
            generateBrief = generateBrief,
        )
    }

    /** 暴露给需要直接操作 DataStore 的调用方（例如清空全部本地数据）。 */
    val settingsDataStore: DataStore<Preferences>
        get() = appContext.settingsDataStore
}
