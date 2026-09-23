package com.briefwidget.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.briefwidget.data.ingest.CalendarSource
import com.briefwidget.data.ingest.RssSource
import com.briefwidget.data.ingest.WeatherSource
import com.briefwidget.data.llm.LlmClient
import com.briefwidget.data.local.BriefDatabase
import com.briefwidget.data.local.dao.CapturedNotificationDao
import com.briefwidget.data.settings.RuntimeStateStore
import com.briefwidget.data.settings.SettingsRepository
import com.briefwidget.data.settings.runtimeDataStore
import com.briefwidget.data.settings.settingsDataStore
import com.briefwidget.data.tts.DoubaoTtsClient
import com.briefwidget.domain.GenerateBriefUseCase

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

    /** 豆包（火山引擎）语音合成客户端。只在 TTS 引擎选了豆包时才会被真正调用。 */
    val doubaoTtsClient: DoubaoTtsClient by lazy { DoubaoTtsClient() }

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
        )
    }

    /** 暴露给需要直接操作 DataStore 的调用方（例如清空全部本地数据）。 */
    val settingsDataStore: DataStore<Preferences>
        get() = appContext.settingsDataStore
}
