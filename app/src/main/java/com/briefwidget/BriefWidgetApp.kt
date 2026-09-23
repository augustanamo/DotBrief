package com.briefwidget

import android.app.Application
import com.briefwidget.di.AppContainer
import com.briefwidget.widget.BriefWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 依赖注入选择**手写容器**而不是 Hilt，理由：
 * 本工程需要注入依赖的位置只有 Activity / 前台服务 / 小组件广播 / 帧工厂四处，
 * 且后三者的实例化时机完全由系统控制，Hilt 在这里带来的注解处理开销（KSP 全量重编）
 * 大于收益。日后模块变多再平滑换成 Hilt 也很容易——把 AppContainer 的 by lazy 改成 @Provides 即可。
 */
class BriefWidgetApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * 应用级协程作用域。
     *
     * 专门给「广播/服务里必须立刻返回、但后面还有活要干」的场景用：
     * 例如小组件 onUpdate 必须在 10 秒内返回，磁盘状态的读取只能异步补。
     * 挂在 Application 上比 GlobalScope 可控——进程结束就一起没了。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 存量配置迁移放在这里而不是设置页：进程可能是被小组件点出来的，
        // 压根不会经过 Activity，但一定经过 Application.onCreate。
        appScope.launch { runCatching { container.settingsRepository.ensureMigrations() } }

        // 主动回推一份 RemoteViews，盖掉 system_server 里可能残留的旧缓存。
        // 这一步是「应用更新后小组件显示无法添加微件」的自愈入口，见 BriefWidgetProvider.refreshAll。
        BriefWidgetProvider.refreshAll(this)
    }
}
