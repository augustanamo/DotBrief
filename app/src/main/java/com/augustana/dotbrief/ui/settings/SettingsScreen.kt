package com.augustana.dotbrief.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.settings.AccentColor
import com.augustana.dotbrief.data.settings.BriefSource
import com.augustana.dotbrief.data.settings.CarouselPace
import com.augustana.dotbrief.data.settings.LlmConfig
import com.augustana.dotbrief.data.llm.LlmCallLogEntry
import com.augustana.dotbrief.data.settings.RSS_CATALOG_GROUPS
import com.augustana.dotbrief.data.settings.RssConfig
import com.augustana.dotbrief.data.settings.RssCatalogGroup
import com.augustana.dotbrief.data.settings.RssFeed
import com.augustana.dotbrief.data.settings.TtsConfig
import com.augustana.dotbrief.data.settings.TtsProvider
import com.augustana.dotbrief.data.settings.UserSettings
import com.augustana.dotbrief.data.settings.WidgetRuntimeState
import com.augustana.dotbrief.data.settings.WidgetState
import com.augustana.dotbrief.domain.BriefSchedule
import com.augustana.dotbrief.tts.TtsPreviewPlayer
import com.augustana.dotbrief.widget.DotMatrixArt
import com.augustana.dotbrief.ui.theme.ContentMaxWidth
import com.augustana.dotbrief.ui.theme.DotGridBackdrop
import com.augustana.dotbrief.ui.theme.DotMatrixText
import com.augustana.dotbrief.ui.theme.Hairline
import com.augustana.dotbrief.ui.theme.NothingButton
import com.augustana.dotbrief.ui.theme.NothingCheckbox
import com.augustana.dotbrief.ui.theme.NothingField
import com.augustana.dotbrief.ui.theme.NothingRadio
import com.augustana.dotbrief.ui.theme.NothingSegmented
import com.augustana.dotbrief.ui.theme.NothingSection
import com.augustana.dotbrief.ui.theme.NothingSliderRow
import com.augustana.dotbrief.ui.theme.NothingSwitch
import com.augustana.dotbrief.ui.theme.NothingTag
import com.augustana.dotbrief.ui.theme.PureBlack
import com.augustana.dotbrief.ui.theme.PureWhite
import com.augustana.dotbrief.ui.theme.SignalRed
import com.augustana.dotbrief.ui.theme.SignalRedLift
import com.augustana.dotbrief.ui.theme.StatusDot
import com.augustana.dotbrief.ui.theme.WindowGrey
import java.time.LocalTime
import kotlin.math.abs

/**
 * 设置页 —— 整个应用的唯一界面（"主界面"就是桌面小组件本身）。
 *
 * ## 版式：为什么不用卡片
 *
 * 改造前的版本是"单列 LazyColumn + Material 卡片分区"。那套写法能跑，
 * 但它讲的是另一个故事：**卡片 = 内容浮在背景之上**，靠圆角、描边和留白
 * 把每一块包成一个可以单独拿走的盒子。
 *
 * Nothing 的语言恰好相反：一切都在同一个平面上，分区只由"通栏的 1px 发丝线"切分，
 * 层级靠**负空间**（大留白）和点阵材质表达，而不是靠阴影。所以这一版：
 *
 * - 通栏发丝线切分区，内容缩进 20dp；
 * - 分区标题是"编号 + 中文 + 英文眉标"的铭牌式结构，等信息密度而不是靠字号抢注意力；
 * - 全部直角（圆角令牌已在 Theme 里归零）；
 * - 一屏只有一处红：状态灯 / 选中态 / 授权标签 —— 颜色是稀缺资源。
 *
 * ## 结构：一级页 + 二级页
 *
 * 一级页 = `HeroHeader`（纯黑点阵 logotype 区块）→ 01 动作 → 02 简报内容 →
 * 五个带摘要的二级页入口 → 底部固定操作条，大约两屏。
 * 二级页 = 顶部返回条 + 该分区的完整内容。
 *
 * 为什么分两级、哪些进二级，见 [Destination] 的注释 —— 一句话版本：
 * 一级页只留"动作 + 天天要碰的"，其余收进二级页，但每一项都在一级
 * 显示当前值的摘要，所以一级页同时还是一张"我现在配了什么"的总览表。
 *
 * Hero 永远是纯黑，因为品牌规范里 logotype 只能是黑白；亮色主题下
 * 它就成了页面顶部那条"黑带"，黑带下面是纸白 —— 经典的黑白对照。
 */

/**
 * 设置页的层级：**一级页 + 二级页**。
 *
 * ## 为什么不是全平铺，也不是全菜单
 *
 * 全平铺（原来那样）的问题不是"长"，是**三类东西混在一页里**：
 * 动作（立即播报 / 刷新 / 停止）、高频调节（前瞻时长、数据来源、自动刷新开关）、
 * 一次配置（API Key、音色、几十条 RSS 源的勾选）。第三类配完再也不动，
 * 却把天天要碰的前两类挤到了好几屏之外。
 *
 * 全菜单（进来只有 7 个入口块）能治"长"，但它把信息**藏**起来了，
 * 代价是丢掉"总览"——打开设置页最常见的动机之一就是"我现在配的是什么"，
 * 全收进二级之后得进 7 个页面才知道。7 个分区、约 30 个控件是"一页装得下"的量级，
 * 不值得为省滚动付 7 次跳转。
 *
 * ## 所以：混合式
 *
 * 一级页留**动作 + 天天要碰的**，其余收进二级页。收进二级的每一项
 * 都必须在一级显示**当前值的摘要**（见 [SettingsEntry]）—— 摘要是这套方案
 * 成立的前提：没有它，这就是全菜单的退化版；有了它，一级页反而比全平铺时更有用，
 * 因为它变成了一张"我现在配了什么"的总览表。
 *
 * ## 分流规则
 *
 * 进二级：要连续填 ≥2 个输入框（03 豆包三格、05 模型）/ 一长串勾选超过 6 项（06 RSS）/
 * 配一次就再不动（07 权限）/ 需要大画面才调得准（04 外观，二级页给它整屏预览）。
 * 留一级：是动作不是设置 / 天天碰 / 状态需要被看见。
 */
private enum class Destination {
    /** 一级页：动作 + 播报开关 + 简报内容 + 五个带摘要的入口。 */
    ROOT,

    /** 01 的两张时刻表（自动刷新 / 定时播报）—— 配一次就再不动，但改动频繁到值得单独一页。 */
    SCHEDULE,

    TTS,
    APPEARANCE,
    LLM,
    RSS,
    PERMISSION,
}

@Composable
fun SettingsScreen(
    state: SettingsViewModel.UiState,
    runtimeState: WidgetRuntimeState,
    logEntries: List<LlmCallLogEntry>,
    onChange: (UserSettings) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onAddFeed: (String, String) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onToggleFeed: (String, Boolean) -> Unit,
    onTogglePresetFeed: (RssFeed, Boolean) -> Unit,
    onPreviewSpeech: () -> Unit,
    onPlayBriefNow: () -> Unit,
    onUpdateBriefNow: () -> Unit,
    onStopBrief: () -> Unit,
    onProbeLlm: () -> Unit,
    onAddLlmProfile: () -> Unit,
    onRemoveLlmProfile: (String) -> Unit,
    onMoveLlmProfileToFront: (String) -> Unit,
    onSelectLlmProfile: (String) -> Unit,
    onClearLog: () -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val context = LocalContext.current

    // ---- 当前在哪一层。分流理由见 [Destination] ----
    var destination by rememberSaveable { mutableStateOf(Destination.ROOT) }

    // 系统返回键：在二级页里先回一级。不接的话按返回会直接退出整个设置页，
    // 而用户按返回时的心理模型是"退回上一级"。
    BackHandler(enabled = destination != Destination.ROOT) {
        destination = Destination.ROOT
    }

    // 一级页的滚动位置必须挂在**这一层**：Crossfade 切走时一级页会离开组合，
    // 状态写在它内部就跟着销毁，从二级页返回会莫名其妙跳回顶部。
    val rootListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // 弹窗开关统一放在这一层：AlertDialog 是**独立于列表的浮层**，挂在 LazyColumn 的
    // item 里会随滚动被回收，滚一下就自己关了。
    var showAddFeedDialog by remember { mutableStateOf(false) }
    var showAddTimeDialog by remember { mutableStateOf(false) }
    var showAddAlarmTimeDialog by remember { mutableStateOf(false) }

    // 「恢复默认」必须先弹一次确认，理由见 ResetConfirmDialog 的注释 —— 这个按钮
    // 和「保存配置」并排在同一行，误触的代价是连 API Key 一起清空。
    var showResetConfirm by remember { mutableStateOf(false) }

    var showLogDialog by remember { mutableStateOf(false) }

    // 权限状态：进入页面与从系统设置返回时都重新读一次
    var hasCalendar by remember { mutableStateOf(context.hasCalendarPermission()) }
    var hasLocation by remember { mutableStateOf(context.hasLocationPermission()) }
    var hasNotificationAccess by remember { mutableStateOf(context.hasNotificationListenerAccess()) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCalendar = granted }

    // 天气只用"大致位置"，所以申请时也只申请粗定位：让系统弹窗里显示的是最低的那一档，
    // 用户点"允许"的心理成本最低，而我们拿到的信息完全够用。
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocation = granted }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCalendar = context.hasCalendarPermission()
                hasLocation = context.hasLocationPermission()
                hasNotificationAccess = context.hasNotificationListenerAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 中上方的短 Toast：一条消息只占一小段时间，到时自动收起。
    // 以前用 Snackbar（底部、长条、会挡住悬浮胶囊），改成顶部短提示更轻、不抢操作。
    var toastText by remember { mutableStateOf<String?>(null) }

    // ⚠️ 不能用 `LaunchedEffect(state.message)` 做 key：onConsumeMessage() 会把 message
    // 置 null，key 一变协程就被取消重启，`delay(...)` 之后的 `toastText = null` 永远执行不到，
    // 于是 toast 显示出来就再也不消失（踩过）。改成用 state.message 值本身做 key：
    // 只有真的来了一条新消息才重启协程；置 null 不影响这条已启动协程跑完 delay。
    LaunchedEffect(state.message?.let { it }) {
        val message = state.message ?: return@LaunchedEffect
        toastText = message
        onConsumeMessage()
        delay(3000)
        toastText = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Crossfade(
                targetState = destination,
                label = "settingsDestination",
            ) { dest ->
                when (dest) {
                    Destination.ROOT -> RootPage(
                        state = state,
                        runtimeState = runtimeState,
                        listState = rootListState,
                        hasCalendar = hasCalendar,
                        hasLocation = hasLocation,
                        hasNotificationAccess = hasNotificationAccess,
                        onChange = onChange,
                        onPlayBriefNow = onPlayBriefNow,
                        onUpdateBriefNow = onUpdateBriefNow,
                        onStopBrief = onStopBrief,
                        onNavigate = { destination = it },
                    )

                    Destination.SCHEDULE -> SchedulePage(
                        draft = state.draft,
                        onChange = onChange,
                        onBack = { destination = Destination.ROOT },
                        onAddTimeClick = { showAddTimeDialog = true },
                        onAddAlarmTimeClick = { showAddAlarmTimeDialog = true },
                    )

                    Destination.TTS -> DetailPage(
                        onBack = { destination = Destination.ROOT },
                    ) {
                        TtsSection(
                            index = "03",
                            tts = state.draft.tts,
                            draft = state.draft,
                            onChange = onChange,
                            onPreviewSpeech = onPreviewSpeech,
                        )
                    }

                    Destination.APPEARANCE -> DetailPage(
                        onBack = { destination = Destination.ROOT },
                    ) {
                        AppearanceSection(
                            index = "04",
                            accent = state.draft.accent,
                            draft = state.draft,
                            onChange = onChange,
                        )
                    }

                    Destination.LLM -> DetailPage(
                        onBack = { destination = Destination.ROOT },
                    ) {
                        LlmSection(
                            index = "05",
                            draft = state.draft,
                            onChange = onChange,
                            probing = state.probing,
                            probeResult = state.probeResult,
                            probeOk = state.probeOk,
                            onProbe = onProbeLlm,
                            onAddProfile = onAddLlmProfile,
                            onRemoveProfile = onRemoveLlmProfile,
                            onMoveToFront = onMoveLlmProfileToFront,
                            onSelectProfile = onSelectLlmProfile,
                            onShowLog = { showLogDialog = true },
                        )
                    }

                    Destination.RSS -> DetailPage(
                        onBack = { destination = Destination.ROOT },
                    ) {
                        RssSection(
                            index = "06",
                            rss = state.draft.rss,
                            catalog = RSS_CATALOG_GROUPS,
                            onToggleFeed = onToggleFeed,
                            onRemoveFeed = onRemoveFeed,
                            onTogglePresetFeed = onTogglePresetFeed,
                            onAddFeedClick = { showAddFeedDialog = true },
                        )
                    }

                    Destination.PERMISSION -> DetailPage(
                        onBack = { destination = Destination.ROOT },
                    ) {
                        SystemSection(
                            index = "07",
                            hasCalendar = hasCalendar,
                            hasLocation = hasLocation,
                            hasNotificationAccess = hasNotificationAccess,
                            onRequestCalendar = {
                                calendarPermissionLauncher.launch(
                                    android.Manifest.permission.READ_CALENDAR,
                                )
                            },
                            onRequestLocation = {
                                locationPermissionLauncher.launch(
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            },
                            onOpenNotificationSettings = {
                                context.openNotificationListenerSettings()
                            },
                            onOpenAppSettings = { context.openAppDetailsSettings() },
                        )
                    }
                }
            }

            FloatingActions(
                saving = state.saving,
                saveEnabled = state.loaded,
                onSave = onSave,
                onReset = { showResetConfirm = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 18.dp),
            )

            // 中上方短 Toast：悬浮在内容之上、不占版面，1.8 秒自动收起。
            // 顶部从状态栏下面 12dp 起、水平居中。用深色底白字，浅色设置页上也醒目。
            AnimatedVisibility(
                visible = toastText != null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(PureBlack.copy(alpha = 0.82f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = toastText.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = PureWhite,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    if (showAddFeedDialog) {
        AddFeedDialog(
            onDismiss = { showAddFeedDialog = false },
            onConfirm = { name, url ->
                onAddFeed(name, url)
                showAddFeedDialog = false
            },
        )
    }

    if (showAddTimeDialog) {
        // 解析与去重都交给弹窗自己做：它才知道用户刚输了什么，
        // 出错时能就地提示并保持打开，而不是关掉弹窗再飘一条 Snackbar。
        AddTimeDialog(
            existing = state.draft.brief.updateTimes,
            onDismiss = { showAddTimeDialog = false },
            onConfirm = { time ->
                val brief = state.draft.brief
                onChange(
                    state.draft.copy(
                        brief = brief.copy(updateTimes = (brief.updateTimes + time).sorted()),
                    ),
                )
                showAddTimeDialog = false
            },
        )
    }

    if (showAddAlarmTimeDialog) {
        AddTimeDialog(
            existing = state.draft.brief.alarmTimes,
            onDismiss = { showAddAlarmTimeDialog = false },
            onConfirm = { time ->
                val brief = state.draft.brief
                onChange(
                    state.draft.copy(
                        brief = brief.copy(alarmTimes = (brief.alarmTimes + time).sorted()),
                    ),
                )
                showAddAlarmTimeDialog = false
            },
        )
    }

    if (showResetConfirm) {
        ResetConfirmDialog(
            onDismiss = { showResetConfirm = false },
            onConfirm = {
                onReset()
                showResetConfirm = false
            },
        )
    }

    if (showLogDialog) {
        LlmLogDialog(
            entries = logEntries,
            onDismiss = { showLogDialog = false },
            onClear = onClearLog,
        )
    }
}

// ---------------------------------------------------------------------------
// 一级页 / 二级页骨架
// ---------------------------------------------------------------------------

/**
 * 一级页。
 *
 * 顺序 = 用户动手的频率：动作 → 高频调节 → 五个带摘要的二级页入口。
 * 大约两屏，滚到底也就两下。
 */
@Composable
private fun RootPage(
    state: SettingsViewModel.UiState,
    runtimeState: WidgetRuntimeState,
    listState: LazyListState,
    hasCalendar: Boolean,
    hasLocation: Boolean,
    hasNotificationAccess: Boolean,
    onChange: (UserSettings) -> Unit,
    onPlayBriefNow: () -> Unit,
    onUpdateBriefNow: () -> Unit,
    onStopBrief: () -> Unit,
    onNavigate: (Destination) -> Unit,
) {
    val draft = state.draft

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        // 底部预留悬浮按钮的高度，否则最后一个分区会被压住。
        contentPadding = PaddingValues(bottom = 104.dp),
    ) {
        item { HeroHeader(runtimeState = runtimeState) }

        // 01 动作 + 两个开关（时刻表在二级页）
        item {
            UpdateSection(
                index = "01",
                draft = draft,
                updating = state.updating,
                playing = runtimeState.state == WidgetState.PLAYING,
                onChange = onChange,
                onEditSchedule = { onNavigate(Destination.SCHEDULE) },
                onPlayBriefNow = onPlayBriefNow,
                onUpdateBriefNow = onUpdateBriefNow,
                onStopBrief = onStopBrief,
            )
        }

        // 02 简报内容：前瞻时长 / 数据来源都会来回改，留在一级
        item { BriefSection(index = "02", draft = draft, onChange = onChange) }

        item {
            SettingsEntry(
                index = "03",
                title = stringResource(R.string.section_tts),
                eyebrow = stringResource(R.string.eyebrow_tts),
                summary = ttsSummary(draft.tts),
                onClick = { onNavigate(Destination.TTS) },
            )
        }

        item {
            SettingsEntry(
                index = "04",
                title = stringResource(R.string.section_appearance),
                eyebrow = stringResource(R.string.eyebrow_appearance),
                summary = appearanceSummary(draft.accent),
                onClick = { onNavigate(Destination.APPEARANCE) },
            )
        }

        item {
            SettingsEntry(
                index = "05",
                title = stringResource(R.string.section_llm),
                eyebrow = stringResource(R.string.eyebrow_llm),
                summary = llmSummary(draft),
                onClick = { onNavigate(Destination.LLM) },
            )
        }

        item {
            SettingsEntry(
                index = "06",
                title = stringResource(R.string.section_rss),
                eyebrow = stringResource(R.string.eyebrow_rss),
                summary = rssSummary(draft.rss),
                onClick = { onNavigate(Destination.RSS) },
            )
        }

        item {
            SettingsEntry(
                index = "07",
                title = stringResource(R.string.section_system),
                eyebrow = stringResource(R.string.eyebrow_system),
                summary = permissionSummary(
                    hasCalendar = hasCalendar,
                    hasLocation = hasLocation,
                    hasNotificationAccess = hasNotificationAccess,
                ),
                onClick = { onNavigate(Destination.PERMISSION) },
            )
        }
    }
}

/**
 * 二级页入口：**标题 + 当前值摘要 + 箭头**。
 *
 * 摘要这一行是整个混合式方案的支点。抽屉式菜单之所以让人不踏实，是因为
 * 把设置藏起来之后你就看不见自己配了什么 —— 有了摘要，一级页反而比全平铺时
 * 更像一张"我现在是什么配置"的总览表，进二级只是"要改的时候才去"。
 */
@Composable
private fun SettingsEntry(
    index: String,
    title: String,
    eyebrow: String,
    summary: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Hairline()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = index,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = eyebrow.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 二级页外壳：顶部返回条 + 可滚动内容。返回键由外层 [BackHandler] 统一接。 */
@Composable
private fun DetailPage(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BackBar(onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 104.dp),
        ) {
            item { content() }
        }
    }
}

@Composable
private fun BackBar(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.action_back_to_settings),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Hairline()
    }
}

// ---------------------------------------------------------------------------
// 摘要：二级页入口下面那一行"当前是什么"
// ---------------------------------------------------------------------------

@Composable
private fun ttsSummary(tts: TtsConfig): String {
    val provider = stringResource(
        when (tts.provider) {
            TtsProvider.SYSTEM -> R.string.tts_system
            TtsProvider.DOUBAO -> R.string.tts_doubao
        },
    )
    val bgm = stringResource(
        if (tts.bgmEnabled) R.string.summary_bgm_on else R.string.summary_bgm_off,
    )
    return "$provider · $bgm"
}

@Composable
private fun appearanceSummary(accent: AccentColor): String {
    val mode = stringResource(
        if (accent.carousel) R.string.accent_carousel else R.string.accent_fixed,
    )
    return stringResource(R.string.summary_hue, mode, accent.hue.toInt())
}

/** 没填 Key 时说"未配置"，比说"已配 1 份"有用 —— 那一份是空的。 */
@Composable
private fun llmSummary(draft: UserSettings): String {
    val count = draft.llm.profiles.size
    return if (draft.llm.primary.apiKey.isBlank()) {
        stringResource(R.string.summary_llm_unset)
    } else {
        stringResource(R.string.summary_profiles, count)
    }
}

@Composable
private fun rssSummary(rss: RssConfig): String =
    stringResource(R.string.summary_feeds, rss.feeds.count { it.enabled })

@Composable
private fun permissionSummary(
    hasCalendar: Boolean,
    hasLocation: Boolean,
    hasNotificationAccess: Boolean,
): String {
    val granted = listOf(hasCalendar, hasLocation, hasNotificationAccess).count { it }
    return stringResource(R.string.summary_permission, granted, 3)
}

/** 时刻表摘要：直接把时刻列出来，省掉一次进二级页。 */
@Composable
private fun timesSummary(times: List<LocalTime>): String {
    if (times.isEmpty()) return stringResource(R.string.summary_times_none)
    return stringResource(
        R.string.summary_times,
        times.size,
        times.joinToString(" ") { BriefSchedule.format(it) },
    )
}

// ---------------------------------------------------------------------------
// 顶部 logotype 区块
// ---------------------------------------------------------------------------

/**
 * 顶部 logotype 条。
 *
 * 刻意做得**矮**：这是一页配置界面，头部只需要"我是谁 + 现在什么状态"两条信息。
 * 两行、约 74dp，比之前那块带大标题和渐层的横幅省下近 150dp —— 打开页面
 * 第一屏就能直接看到真正要调的东西。
 *
 * 版式沿用品牌里 `NOTHING phone (2)` 的锁版关系：
 * 点阵 logotype（品牌）+ 低一级的 "widget"（产品名）+ 右侧状态灯。
 *
 * 配色写死纯黑/纯白，不跟随主题：亮色主题下它就是页首那条黑带，
 * 也符合规范里"logotype 只能黑白"的硬约束。
 */
@Composable
private fun HeroHeader(runtimeState: WidgetRuntimeState) {
    val live = runtimeState.state == WidgetState.PLAYING ||
        runtimeState.state == WidgetState.GENERATING
    // 「有待处理的新内容」和「正在出声」是两种不同的"要看这里"，
    // 但对红灯来说它们是同一件事：这枚点阵现在有事在说。
    val attention = live || runtimeState.unheard
    val statusText = if (!live && runtimeState.unheard) {
        stringResource(R.string.state_new_content)
    } else {
        runtimeState.state.label()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(PureBlack),
    ) {
        DotGridBackdrop(
            modifier = Modifier.matchParentSize(),
            color = PureWhite.copy(alpha = 0.07f),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    DotMatrixText(
                        text = "DOTBRIEF",
                        dotSize = 3.dp,
                        dotGap = 1.05.dp,
                        charGap = 2.4.dp,
                        color = PureWhite,
                    )
                    Spacer(Modifier.weight(1f))
                    StatusDot(active = attention)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusText.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (attention) SignalRedLift else WindowGrey,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }

                // 只有一行眉标。这里曾经还挂着 "BUILD 0.1.0" —— 版本号是给
                // 提 bug 的人看的，不是给用的人看的，所以从页首撤掉。
                Text(
                    text = stringResource(R.string.brand_eyebrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = WindowGrey,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 高级设置（可折叠）
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 04 小组件外观（预览 + 配色 + 轮播）
// ---------------------------------------------------------------------------

/**
 * 点阵配色与动效。
 *
 * ## 为什么把"预览"和"配色"合成一块
 *
 * 它们回答的是同一个问题 ——"我的小组件长什么样"。拆成两块时，
 * 用户调完颜色要往回滚一屏去看效果；合成一块之后，预览就在色条正上方，
 * 拖到哪儿、上面立刻变到哪儿。
 *
 * ## 为什么是"一条彩虹拖条"而不是取色盘
 *
 * 这套点阵只有**色相**是真正有效的自由度（饱和/明度被三段色阶锁死，
 * 见 [AccentColor] 的说明），所以最好的方式就是让用户沿着色环直接拖 ——
 * 所见即所得，不用理解 HSV 是什么。
 *
 * ## 轮播
 *
 * 打开后色相不再固定，而是逐帧沿色环走。色相周期与呼吸周期**互相独立**：
 * 默认 42 帧 × 260ms ≈ 11 秒走完一圈（快 / 中 / 慢三档可选），
 * 桌面侧的 `AdapterViewFlipper` 本来就在逐帧切图，所以这份"霓虹感"是白拿的；
 * 代价只有待机时也要保持动画，因此在提示里明确说了会略微费电。
 *
 * ## 灰阶
 *
 * 上面两档都只描述"亮起来"长什么样。真正决定桌面是灰是彩的是**有没有新内容**
 * （见 [WidgetRuntimeState.muted]）：灰阶时连轮播都会停，整块退成静止的灰点阵。
 * 预览区末尾那排对照色块就是这两档的样子。
 */
@Composable
private fun AppearanceSection(
    index: String,
    accent: AccentColor,
    draft: UserSettings,
    onChange: (UserSettings) -> Unit,
) {
    NothingSection(
        index = index,
        title = stringResource(R.string.section_appearance),
        eyebrow = stringResource(R.string.eyebrow_appearance),
        desc = stringResource(R.string.section_appearance_desc),
    ) {
        WidgetPreviewContent(accent = accent)

        Hairline(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
            text = stringResource(R.string.label_accent_mode),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AccentModeRow(
                selected = !accent.carousel,
                title = stringResource(R.string.accent_fixed),
                desc = stringResource(R.string.accent_fixed_desc),
                onClick = { onChange(draft.copy(accent = accent.copy(carousel = false))) },
            )
            AccentModeRow(
                selected = accent.carousel,
                title = stringResource(R.string.accent_carousel),
                desc = stringResource(R.string.accent_carousel_desc),
                onClick = { onChange(draft.copy(accent = accent.copy(carousel = true))) },
            )
        }

        // 节奏只在开了轮播之后才有意义 —— 没开就不占版面
        if (accent.carousel) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = stringResource(R.string.label_carousel_pace),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.carousel_pace_seconds,
                            DotMatrixArt.carouselCycleSeconds(accent.carouselPace.stepsPerCycle),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                NothingSegmented(
                    options = listOf(
                        stringResource(R.string.carousel_pace_fast),
                        stringResource(R.string.carousel_pace_normal),
                        stringResource(R.string.carousel_pace_slow),
                    ),
                    selectedIndex = CarouselPace.values().indexOf(accent.carouselPace),
                    onSelect = { index ->
                        onChange(
                            draft.copy(
                                accent = accent.copy(
                                    carouselPace = CarouselPace.values()[index],
                                ),
                            ),
                        )
                    },
                )
            }
        }

        HueSliderRow(
            hue = accent.hue,
            onHueChange = { onChange(draft.copy(accent = accent.copy(hue = it))) },
        )

        NothingSliderRow(
            label = stringResource(R.string.label_accent_saturation),
            valueText = "${(accent.saturation * 100).toInt()}%",
            value = accent.saturation,
            valueRange = 0f..1f,
            steps = 9,
            // 滑杆的填充色跟着当前色相走，调鲜艳度时眼睛不用来回对比预览图
            accent = hsvColor(accent.hue, 1f, 1f),
            onValueChange = { onChange(draft.copy(accent = accent.copy(saturation = it))) },
        )

        // 一整区只留这一段说明。之前这里是两段：一段讲轮播怎么走色环、呼吸怎么算亮度，
        // 一段讲灰阶规则 —— 前者是实现的原理，用户改不动也不用知道；
        // 后者是这套外观唯一的"规则"，必须说，但一句话就够。
        Text(
            text = stringResource(R.string.accent_muted_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 配色模式单选项（固定单色 / 霓虹轮播）。 */
@Composable
private fun AccentModeRow(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NothingRadio(selected = selected, onClick = onClick)
        ChoiceLabel(title = title, desc = desc)
    }
}

/**
 * 彩色（彩虹）色相拖条。
 *
 * 没有复用 Material 的 `Slider`：它只画单色轨道，想拼彩虹得走 `track` 插槽，
 * 还得手工对齐 thumb 半径（各版本默认值不同），很容易错位。
 * 自己实现反而更短也更准 —— 整条就是一张 0°→360° 的线性渐变，
 * 按下点的横向比例直接换算成色相，点哪儿就是哪儿。
 *
 * 形状上做了取舍：品牌规范反对在图形上用渐变，但这里**渐变本身就是控件语义**
 * （它就是色环的展开），所以保留渐变、把外形压成方角，与整页的直角版式对齐。
 */
@Composable
private fun HueSliderRow(hue: Float, onHueChange: (Float) -> Unit) {
    // 与 NothingSlider 同一个坑：`pointerInput(Unit)` 的手势块不随重组合重启，
    // 里面捕获的 onHueChange 会一直用**第一次组合**时的 draft —— 拖动色相时
    // 顺手把鲜艳度打回原值（用户的原话是"动了谁另一个就会重置"）。
    val currentOnHueChange by rememberUpdatedState(onHueChange)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stringResource(R.string.label_accent_hue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(text = "${hue.toInt()}°", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(8.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(rainbowStops))
                // 手势判定与 NothingSlider 一致：横向越过 touchSlop 才接管，
                // 纵向越过 touchSlop 就放手让页面滚 —— 否则在列表里往上翻页时，
                // 手指落在色条上会顺手把颜色改掉。
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop

                    fun hueAt(x: Float): Float =
                        (x / size.width.toFloat().coerceAtLeast(1f) * 360f).coerceIn(0f, 359f)

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        var owned = false
                        var abandoned = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val x = change.position.x
                            val dx = abs(x - startX)
                            val dy = abs(change.position.y - startY)

                            if (!owned && !abandoned) {
                                when {
                                    dx > slop && dx >= dy -> owned = true
                                    dy > slop -> abandoned = true
                                }
                            }

                            if (owned) {
                                currentOnHueChange(hueAt(x))
                                if (change.pressed) change.consume()
                            }

                            if (!change.pressed) {
                                if (owned) {
                                    change.consume()
                                } else if (!abandoned) {
                                    // 轻点：点哪儿选哪儿
                                    currentOnHueChange(hueAt(x))
                                    change.consume()
                                }
                                break
                            }
                        }
                    }
                },
        ) {
            // 游标：白框 + 当前色相内芯，一眼看出选中的是哪一档。
            // 用 offset 而不是 Arrangement：需要让它跟着色相停在任意位置。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth - HUE_THUMB) * (hue / 360f))
                    .size(HUE_THUMB)
                    .background(PureWhite)
                    .padding(2.dp)
                    .background(hsvColor(hue, 1f, 1f)),
            )
        }
    }
}

private val HUE_THUMB = 26.dp

/** 13 个采样点足够让线性渐变看起来连续（再密肉眼也分不出来）；首尾都是红，正好无缝衔接。 */
private val rainbowStops: List<Color> = List(13) { index ->
    hsvColor(index / 12f * 360f, 1f, 1f)
}

private fun hsvColor(hue: Float, saturation: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))

// ---------------------------------------------------------------------------
// 07 权限
// ---------------------------------------------------------------------------

/**
 * 「权限」。
 *
 * 这里原来是「权限与诊断」，下面还挂着半屏"运行状态"：状态灯、上次生成时间、
 * 上次出错的原因原文。那半屏整块撤了，理由两条：
 *
 * 1. **那些读数是给我们排错用的，不是给用户办事的**。用户来这一页只有一件事：
 *    日历 / 位置 / 通知使用权给没给。状态灯和桌面点阵的颜色说的是同一件事，
 *    看桌面更直接；"上次生成"是个时间戳，用户看了也没有下一步动作。
 * 2. **出问题不该等用户翻设置页才发现**。那条链路几乎没有可视反馈，失败时
 *    用户看到的只是点阵换了个颜色，而唯一的线索被压在这一页最底下 ——
 *    真机上真发生过（模型回 429 额度用尽，反复点了半天也没找到那句话）。
 *    现在失败当场 Toast（见 `toastIssue`），原因原文仍旧落盘在
 *    `RuntimeKeys.LAST_ERROR` 里，要查随时能查。
 *
 * 于是这一区剩下的全是"能不能干活"的三个开关。位置仍在最后：
 * 装好之后的日常使用中几乎不会翻到这里。
 */
@Composable
private fun SystemSection(
    index: String,
    hasCalendar: Boolean,
    hasLocation: Boolean,
    hasNotificationAccess: Boolean,
    onRequestCalendar: () -> Unit,
    onRequestLocation: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    NothingSection(
        index = index,
        title = stringResource(R.string.section_system),
        eyebrow = stringResource(R.string.eyebrow_system),
    ) {

        PermissionRow(
            title = stringResource(R.string.permission_calendar),
            desc = stringResource(R.string.permission_calendar_desc),
            granted = hasCalendar,
            onGrant = onRequestCalendar,
        )
        PermissionRow(
            title = stringResource(R.string.permission_location),
            desc = stringResource(R.string.permission_location_desc),
            granted = hasLocation,
            onGrant = onRequestLocation,
        )
        PermissionRow(
            title = stringResource(R.string.permission_notification),
            desc = stringResource(R.string.permission_notification_desc),
            granted = hasNotificationAccess,
            onGrant = onOpenNotificationSettings,
        )

        Text(
            text = stringResource(R.string.permission_manual_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onOpenAppSettings),
        )
    }
}

@Composable
private fun WidgetState.label(): String = stringResource(
    when (this) {
        WidgetState.IDLE -> R.string.state_idle
        WidgetState.GENERATING -> R.string.state_generating
        WidgetState.PLAYING -> R.string.state_playing
        WidgetState.ERROR -> R.string.state_error
    },
)

// ---------------------------------------------------------------------------
// 05 大模型接口
// ---------------------------------------------------------------------------

@Composable
private fun LlmSection(
    index: String,
    draft: UserSettings,
    onChange: (UserSettings) -> Unit,
    probing: Boolean,
    probeResult: String?,
    probeOk: Boolean,
    onProbe: () -> Unit,
    onAddProfile: () -> Unit,
    onRemoveProfile: (String) -> Unit,
    onMoveToFront: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onShowLog: () -> Unit,
) {
    val profiles = draft.llm

    NothingSection(
        index = index,
        title = stringResource(R.string.section_llm),
        eyebrow = stringResource(R.string.eyebrow_llm),
        desc = stringResource(R.string.section_llm_desc),
    ) {
        // 先说清"不填会怎样"：这一块是唯一会让用户以为"必须配"的地方，
        // 而实际上没它也能出声（只是不含新闻）。
        Text(
            text = stringResource(R.string.llm_local_fallback_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 多份配置的说明：列表顺序 = 失败自动切换的顺序，第一份是首选。
        Text(
            text = stringResource(R.string.llm_failover_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 每一份：一个可展开的条目。展开显示该份的字段，收起只留一行标题 + 操作。
        profiles.profiles.forEachIndexed { position, config ->
            LlmProfileItem(
                config = config,
                isPrimary = position == 0,
                isActive = config.id == profiles.activeId,
                isExpanded = config.id == profiles.activeId,
                canRemove = profiles.profiles.size > 1,
                onToggle = { onSelectProfile(config.id) },
                onMoveToFront = { onMoveToFront(config.id) },
                onRemove = { onRemoveProfile(config.id) },
                onEdit = { updated ->
                    val list = profiles.profiles.map { if (it.id == config.id) updated else it }
                    onChange(draft.copy(llm = profiles.copy(profiles = list)))
                },
            )
        }

        // 新增一份
        NothingButton(
            text = stringResource(R.string.action_add_profile),
            onClick = onAddProfile,
            modifier = Modifier.fillMaxWidth(),
        )

        // 大模型接口最常见的失败是「网络到不了」，它的表现是"点了半天没反应然后超时"，
        // 极难自己定位。所以这里给一个一键自检，把地址错 / 被墙 / Key 无效 / 模型名错分开说清楚。
        // 自检针对「首选」那一份（列表第一份）。
        NothingButton(
            text = stringResource(if (probing) R.string.action_probing else R.string.action_probe),
            onClick = onProbe,
            enabled = !probing,
            modifier = Modifier.fillMaxWidth(),
        )

        if (probeResult != null) {
            Text(
                text = probeResult,
                style = MaterialTheme.typography.bodySmall,
                color = if (probeOk) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        // 接口日志：点开看"最近每次到底谁成功了、谁失败了、为什么"。排查额度 / 断网时最有用。
        NothingButton(
            text = stringResource(R.string.action_view_log),
            onClick = onShowLog,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 一份 AI 配置的条目。
 *
 * 展开态 = 这份的 Base URL / Key / 模型名三个输入框 + 置顶/删除；收起态 = 一行标题。
 * 用 `isExpanded = (id == activeId)` 驱动：点标题即切换「当前编辑」的那一份，
 * 展开另一份时这份自动收起（同一时刻只展开一份，避免列表被多份输入框撑爆）。
 */
@Composable
private fun LlmProfileItem(
    config: LlmConfig,
    isPrimary: Boolean,
    isActive: Boolean,
    isExpanded: Boolean,
    canRemove: Boolean,
    onToggle: () -> Unit,
    onMoveToFront: () -> Unit,
    onRemove: () -> Unit,
    onEdit: (LlmConfig) -> Unit,
) {
    Column {
        // 标题行：显示名 + 「主用」标签。点它切换展开/收起（即切换当前编辑份）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = config.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isPrimary) {
                    Text(
                        text = stringResource(R.string.llm_primary_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (isExpanded) {
                Text(
                    text = stringResource(R.string.action_collapse),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 展开态：字段 + 操作
        if (isExpanded) {
            NothingField(
                value = config.name,
                onValueChange = { onEdit(config.copy(name = it)) },
                label = stringResource(R.string.label_profile_name),
            )
            NothingField(
                value = config.baseUrl,
                onValueChange = { onEdit(config.copy(baseUrl = it)) },
                label = stringResource(R.string.label_base_url),
                keyboardType = KeyboardType.Uri,
            )
            SecretField(
                value = config.apiKey,
                onValueChange = { onEdit(config.copy(apiKey = it)) },
                label = stringResource(R.string.label_api_key),
                hint = stringResource(R.string.hint_api_key),
            )
            NothingField(
                value = config.model,
                onValueChange = { onEdit(config.copy(model = it)) },
                label = stringResource(R.string.label_model),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isPrimary) {
                    NothingButton(
                        text = stringResource(R.string.action_make_primary),
                        onClick = onMoveToFront,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (canRemove) {
                    NothingButton(
                        text = stringResource(R.string.action_delete),
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 与旧版同一段说明：发散度/超时/系统提示词三个调优参数退回 Defaults，
            // 这里只留三件"只有用户知道"的事。
            Text(
                text = stringResource(R.string.llm_tuning_hidden_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 接口调用日志弹窗。
 *
 * 回答「最近每次到底谁成功了」：每条 = 一次完整生成，里面按尝试顺序列出各份配置的
 * 成败（成功打绿、失败打红并附原因）。绿色的最后一条就是那次"谁接住了"；一条绿都没有
 * 就是全失败、退了本地简报。来源标签（点组件 / 立即播报 / 刷新 / 定时）帮用户定位
 * "是哪个入口出的问题"。
 */
@Composable
private fun LlmLogDialog(
    entries: List<LlmCallLogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.log_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.log_clear),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable(onClick = onClear)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries.size) { index ->
                        val entry = entries[index]
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = sourceLabel(entry.source),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = timeLabel(entry.atEpochSeconds),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            entry.records.forEach { record ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = if (record.ok) "✓" else "✕",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (record.ok) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = record.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (!record.ok && record.reason.isNotBlank()) {
                                            Text(
                                                text = record.reason,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (index != entries.lastIndex) {
                            Hairline(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            NothingButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun sourceLabel(source: String): String = stringResource(
    when (source) {
        "widget" -> R.string.log_source_widget
        "manual" -> R.string.log_source_manual
        "refresh" -> R.string.log_source_refresh
        "auto" -> R.string.log_source_auto
        "alarm" -> R.string.log_source_alarm
        else -> R.string.log_source_other
    },
)

private fun timeLabel(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "--:--"
    val local = java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
    return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").format(local)
}

/** 密钥输入框：默认打码，可切换明文，方便用户核对粘贴结果。 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String? = null,
) {
    var visible by remember { mutableStateOf(false) }

    NothingField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        hint = hint,
        keyboardType = KeyboardType.Password,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailing = {
            Text(
                text = stringResource(if (visible) R.string.action_hide else R.string.action_show),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clickable { visible = !visible }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        },
    )
}

// ---------------------------------------------------------------------------
// 06 新闻订阅源
// ---------------------------------------------------------------------------

@Composable
private fun RssSection(
    index: String,
    rss: RssConfig,
    catalog: List<RssCatalogGroup>,
    onToggleFeed: (String, Boolean) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onTogglePresetFeed: (RssFeed, Boolean) -> Unit,
    onAddFeedClick: () -> Unit,
) {
    NothingSection(
        index = index,
        title = stringResource(R.string.section_rss),
        eyebrow = stringResource(R.string.eyebrow_rss),
        desc = stringResource(R.string.section_rss_desc),
    ) {
        // 目录近百条，靠滑不容易定位 —— 搜索框是这个页面的主要入口，
        // 分组只是没有关键词时的浏览方式（分组与收录标准见 `RssCatalog.kt`）。
        var query by rememberSaveable { mutableStateOf("") }
        NothingField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.label_rss_search),
        )

        // 推荐源目录：按分类分组，勾选即添加、取消即移除，不用手输地址。
        // 勾选状态 = 用户 feeds 里有没有这个源（按 id 或 url 任一匹配，兼容老用户的随机 id）。
        val filtered = remember(query, catalog) {
            val keyword = query.trim()
            if (keyword.isEmpty()) {
                catalog
            } else {
                catalog.mapNotNull { group ->
                    val hits = group.feeds.filter {
                        it.name.contains(keyword, ignoreCase = true)
                    }
                    if (hits.isEmpty()) null else RssCatalogGroup(group.title, hits)
                }
            }
        }

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.msg_rss_no_match),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BlockLabel(text = stringResource(R.string.label_rss_catalog))
        filtered.forEach { group ->
            Text(
                text = group.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            group.feeds.forEach { preset ->
                val checked = rss.feeds.any {
                    it.id == preset.id || it.url.equals(preset.url, ignoreCase = true)
                }
                PresetFeedRow(
                    name = preset.name,
                    url = preset.url,
                    checked = checked,
                    onToggle = { onTogglePresetFeed(preset, it) },
                )
            }
        }

        Hairline(color = MaterialTheme.colorScheme.outlineVariant)

        // 用户自己添加的源（不在目录里的那些）：可启用 / 删除。
        val allCatalogFeeds = catalog.flatMap { it.feeds }
        val customFeeds = rss.feeds.filter { feed ->
            allCatalogFeeds.none { it.id == feed.id || it.url.equals(feed.url, ignoreCase = true) }
        }
        if (customFeeds.isNotEmpty()) {
            BlockLabel(text = stringResource(R.string.label_rss_custom))
            customFeeds.forEach { feed ->
                FeedRow(
                    feed = feed,
                    onToggle = { enabled -> onToggleFeed(feed.id, enabled) },
                    onRemove = { onRemoveFeed(feed.id) },
                )
            }
        } else if (rss.feeds.isEmpty()) {
            Text(
                text = stringResource(R.string.msg_no_enabled_feed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        NothingButton(
            text = stringResource(R.string.action_add_feed),
            onClick = onAddFeedClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 推荐源目录里的一行：名字 + 地址 + 勾选开关（没有删除键，取消勾选即移除）。 */
@Composable
private fun PresetFeedRow(
    name: String,
    url: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NothingSwitch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun FeedRow(
    feed: RssFeed,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.name,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = feed.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NothingSwitch(checked = feed.enabled, onCheckedChange = onToggle)
        SquareIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.action_delete),
            onClick = onRemove,
        )
    }
}

@Composable
private fun AddFeedDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.action_add_feed),
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NothingField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.hint_feed_name),
                )
                NothingField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.hint_feed_url),
                    keyboardType = KeyboardType.Uri,
                )
            }
        },
        confirmButton = {
            NothingButton(
                text = stringResource(R.string.action_add),
                onClick = { onConfirm(name, url) },
                filled = true,
            )
        },
        dismissButton = {
            NothingButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * 「恢复默认」的二次确认。
 *
 * 为什么非要有这一下：它和「保存配置」是同一行里并排的两个按钮，落错一下
 * 就足以把整份配置清空 —— 这件事真的发生过一次，连同 API Key 一起没了，
 * 而 Key 是清理之后最难补回来的那类东西（得回控制台重新复制）。
 * 破坏性操作的门槛应该是"多按一下"，不是"别按错"。
 *
 * 文案里点名 API Key，而不写笼统的"所有设置"：用户看到"设置没了"只会想
 * "重新点一遍就好"，看到"API Key 也会清空"才知道这一下有多贵。
 */
@Composable
private fun ResetConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.msg_reset_confirm_title),
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.msg_reset_confirm_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            NothingButton(
                text = stringResource(R.string.action_reset),
                onClick = onConfirm,
                filled = true,
            )
        },
        dismissButton = {
            NothingButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

// ---------------------------------------------------------------------------
// 03 语音播报
// ---------------------------------------------------------------------------

@Composable
private fun TtsSection(
    index: String,
    tts: TtsConfig,
    draft: UserSettings,
    onChange: (UserSettings) -> Unit,
    onPreviewSpeech: () -> Unit,
) {
    NothingSection(
        index = index,
        title = stringResource(R.string.section_tts),
        eyebrow = stringResource(R.string.eyebrow_tts),
        desc = stringResource(R.string.section_tts_desc),
    ) {
        Text(
            text = stringResource(R.string.label_tts_provider),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TtsProvider.values().forEach { provider ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NothingRadio(
                        selected = tts.provider == provider,
                        onClick = { onChange(draft.copy(tts = tts.copy(provider = provider))) },
                    )
                    ChoiceLabel(
                        title = stringResource(
                            when (provider) {
                                TtsProvider.SYSTEM -> R.string.tts_system
                                TtsProvider.DOUBAO -> R.string.tts_doubao
                            },
                        ),
                        desc = stringResource(
                            when (provider) {
                                TtsProvider.SYSTEM -> R.string.tts_system_desc
                                TtsProvider.DOUBAO -> R.string.tts_doubao_desc
                            },
                        ),
                    )
                }
            }
        }

        // ---- 豆包（火山引擎 · 语音技术）配置：只在选了豆包时才展开 ----
        if (tts.provider == TtsProvider.DOUBAO) {
            Hairline(color = MaterialTheme.colorScheme.outlineVariant)

            // 只有三格。曾经这里是「接口版本 + App ID + Access Token + 音色 + Resource ID/Cluster」
            // 七格，其中"该选 v3 还是 v1""Resource ID 该填哪一档"都是用户没法自己判断的问题。
            // 换成这套单 Key 接口之后，答案唯一了，那些选择题也就不存在了。
            SecretField(
                value = tts.doubaoApiKey,
                onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoApiKey = it))) },
                label = stringResource(R.string.label_doubao_api_key),
            )
            NothingField(
                value = tts.doubaoSpeaker,
                onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoSpeaker = it))) },
                label = stringResource(R.string.label_doubao_speaker),
            )
            NothingField(
                value = tts.doubaoResourceId,
                onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoResourceId = it))) },
                label = stringResource(R.string.label_doubao_resource_id),
            )

            Text(
                text = stringResource(R.string.doubao_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Hairline(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 背景音乐：等待那几秒垫一段，开口后压到垫底 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tts_bgm_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.tts_bgm_enabled_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NothingSwitch(
                checked = tts.bgmEnabled,
                onCheckedChange = { onChange(draft.copy(tts = tts.copy(bgmEnabled = it))) },
            )
        }

        // 语速 / 音调两个滑块已移除（见 TtsConfig 的注释）：“试听一句”留着 ——
        // 它的用处是当场验证 Key 与音色配得对不对，跟语速无关。
        NothingButton(
            text = stringResource(R.string.action_preview),
            onClick = onPreviewSpeech,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 02 简报内容
// ---------------------------------------------------------------------------

@Composable
private fun BriefSection(
    index: String,
    draft: UserSettings,
    onChange: (UserSettings) -> Unit,
) {
    val brief = draft.brief
    val ingest = draft.ingest

    NothingSection(
        index = index,
        title = stringResource(R.string.section_brief),
        eyebrow = stringResource(R.string.eyebrow_brief),
        desc = stringResource(R.string.section_brief_desc),
    ) {
        // 篇幅不再是一个设置项：念多长由**素材量**决定，不由预设档位决定。
        //
        // 这里曾经是三档「播报篇幅」（约 30 秒 / 1 分钟 / 2 分钟），两头都不讨好 ——
        // 素材多的日子被硬砍，素材少的日子被逼着注水。规则现在写在系统提示词里
        // （见 Defaults.SYSTEM_PROMPT 第 8 条），不必让用户去猜"我该选哪一档"。
        NothingSliderRow(
            label = stringResource(R.string.label_lookahead),
            valueText = "${ingest.lookaheadHours} H",
            value = ingest.lookaheadHours.toFloat(),
            valueRange = 4f..72f,
            steps = 16,
            onValueChange = {
                onChange(draft.copy(ingest = ingest.copy(lookaheadHours = it.toInt())))
            },
        )

        Text(
            text = stringResource(R.string.label_sources),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BriefSource.values().forEach { source ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NothingCheckbox(
                        checked = source in brief.sources,
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                brief.sources + source
                            } else {
                                brief.sources - source
                            }
                            onChange(draft.copy(brief = brief.copy(sources = updated)))
                        },
                    )
                    Text(
                        text = stringResource(source.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 说清"日期只说一次"这条规则：用户会疑惑"为什么早上报日期、下午不报了"，
        // 与其让他以为是坏了，不如直接讲明这是有意的。
        Text(
            text = stringResource(R.string.brief_first_of_day_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun BriefSource.labelRes(): Int = when (this) {
    BriefSource.CALENDAR -> R.string.source_calendar
    BriefSource.TODO -> R.string.source_todo
    BriefSource.TRIP -> R.string.source_trip
    BriefSource.PACKAGE -> R.string.source_package
    BriefSource.WEATHER -> R.string.source_weather
    BriefSource.NEWS -> R.string.source_news
}

// ---------------------------------------------------------------------------
// 01 播报与更新
// ---------------------------------------------------------------------------

/**
 * 「现在就听 / 现在就刷 / 什么时候自动刷」。
 *
 * ## 为什么手动动作在最前、自动排期反而垫后
 *
 * 这块的主体曾经是「自动更新」的时刻表（默认 10:00 / 14:00 / 19:00，可改），
 * 手动播报只是附在末尾的一个按钮。实际用起来正好相反：时刻表是**配一次就再不动**的，
 * 而"我现在就想听""我现在想要一份新的"是天天要碰的 —— 所以现在手动在上、自动在下，
 * 分区名也从「自动更新」改成了「播报与更新」。
 *
 * ## 两个按钮为什么不是一个（上一轮在这里删过头了）
 *
 * 见 `SettingsViewModel.updateBriefNow` 的注释：按**生产 / 消费**划开，两者永不重叠 ——
 * 「立即播报」消费现成的那份，「刷新内容」去要一份新的、但不出声。
 * 上一轮我的划分是"用不用缓存"，用户没法从字面上分辨，于是我把其中一个删了，
 * 顺手把"我现在就想要一份新的"这个需求也删掉了。
 *
 * 时刻表本身同时是"后台什么时候刷"和"一天最多请求几次模型"这两件事的参数，
 * 所以说明文字必须把第二件事也讲出来 —— 否则用户只会以为它是个定时器，
 * 遇到"为什么有时候点一下秒出声、有时候要等"就完全没法解释。
 */
@Composable
private fun UpdateSection(
    index: String,
    draft: UserSettings,
    updating: Boolean,
    playing: Boolean,
    onChange: (UserSettings) -> Unit,
    onEditSchedule: () -> Unit,
    onPlayBriefNow: () -> Unit,
    onUpdateBriefNow: () -> Unit,
    onStopBrief: () -> Unit,
) {
    val brief = draft.brief

    NothingSection(
        index = index,
        title = stringResource(R.string.section_update),
        eyebrow = stringResource(R.string.eyebrow_playback),
        desc = stringResource(R.string.section_update_desc),
    ) {
        // ---- 消费：把现成的那份念出来 ----
        //
        // 和点桌面小组件是**同一条路**（缓存优先），所以这两个入口的表现永远一致。
        NothingButton(
            text = stringResource(R.string.action_play_brief_now),
            onClick = onPlayBriefNow,
            filled = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.action_play_brief_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 播报中才出现的「停」，紧挨着「立即播报」。
        //
        // 它原来挂在 07 那半屏"运行状态"里（那块已撤），播报中想停得从上往下滚一整页 ——
        // 而"停不下来"比"听不到"更急。一进一出放在同一处，才不用满页找出口。
        if (playing) {
            NothingButton(
                text = stringResource(R.string.action_stop_brief),
                onClick = onStopBrief,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 生产：去问一次模型，只要内容、不要声音 ----
        //
        // 刻意**不顺手念出来**：那样它就和上面的「立即播报」只差一个"用不用缓存"，
        // 又回到那个分不清的状态了。刷新的产出是桌面点阵转彩 —— 那就是"刷好了"的信号。
        NothingButton(
            text = stringResource(
                if (updating) R.string.action_updating else R.string.action_update_now,
            ),
            onClick = onUpdateBriefNow,
            enabled = !updating,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.action_update_now_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Hairline(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 两个开关留在一级：它们天天要被看见（"开着吗"是最常见的疑问），
        // 而时刻表收进二级页。开关下面那行摘要负责回答"那它几点跑"。 ----
        ScheduleSwitchRow(
            title = stringResource(R.string.update_enabled),
            desc = stringResource(R.string.update_enabled_desc),
            checked = brief.updateEnabled,
            summary = timesSummary(brief.updateTimes),
            onCheckedChange = { onChange(draft.copy(brief = brief.copy(updateEnabled = it))) },
        )

        ScheduleSwitchRow(
            title = stringResource(R.string.alarm_enabled),
            desc = stringResource(R.string.alarm_enabled_desc),
            checked = brief.alarmEnabled,
            summary = timesSummary(brief.alarmTimes),
            onCheckedChange = { onChange(draft.copy(brief = brief.copy(alarmEnabled = it))) },
        )

        NothingButton(
            text = stringResource(R.string.action_edit_schedule),
            onClick = onEditSchedule,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「开关 + 当前时刻」的一行。
 *
 * 时刻**不在这行里编辑**，只在这里显示：改时刻是低频动作（配一次就再不动），
 * 但"现在设的是几点"是高频疑问，所以把答案摆在一级页、把编辑器收进二级页。
 */
@Composable
private fun ScheduleSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    summary: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NothingSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 01 的二级页：两张时刻表。
 *
 * 它们是"配一次就再不动"的典型，但两张表加起来占掉一级页一整屏 ——
 * 把天天要碰的「立即播报」挤到了下面。所以整块搬出来单独一页。
 */
@Composable
private fun SchedulePage(
    draft: UserSettings,
    onChange: (UserSettings) -> Unit,
    onBack: () -> Unit,
    onAddTimeClick: () -> Unit,
    onAddAlarmTimeClick: () -> Unit,
) {
    val brief = draft.brief

    DetailPage(onBack = onBack) {
        NothingSection(
            index = "01",
            title = stringResource(R.string.section_schedule),
            eyebrow = stringResource(R.string.eyebrow_schedule),
            desc = stringResource(R.string.section_schedule_desc),
        ) {
            // ---- 到点自动刷（只刷不出声）----
            BlockLabel(text = stringResource(R.string.label_update_times))
            TimeTable(
                times = brief.updateTimes,
                emptyText = stringResource(R.string.update_no_times),
                onRemove = { time ->
                    onChange(draft.copy(brief = brief.copy(updateTimes = brief.updateTimes - time)))
                },
            )
            NothingButton(
                text = stringResource(R.string.action_add_time),
                onClick = onAddTimeClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.update_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Hairline(color = MaterialTheme.colorScheme.outlineVariant)

            // ---- 到点自动播（出声，和上面是两回事）----
            BlockLabel(text = stringResource(R.string.label_alarm_times))
            TimeTable(
                times = brief.alarmTimes,
                emptyText = stringResource(R.string.alarm_no_times),
                onRemove = { time ->
                    onChange(draft.copy(brief = brief.copy(alarmTimes = brief.alarmTimes - time)))
                },
            )
            NothingButton(
                text = stringResource(R.string.action_add_time),
                onClick = onAddAlarmTimeClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.alarm_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeTable(
    times: List<LocalTime>,
    emptyText: String,
    onRemove: (LocalTime) -> Unit,
) {
    if (times.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            times.forEach { time ->
                TimeRow(time = time, onRemove = { onRemove(time) })
            }
        }
    }
}

/** 一个更新时刻。用等宽样式显示，因为它是**时间**，不是一段文字。 */
@Composable
private fun TimeRow(time: LocalTime, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = BriefSchedule.format(time),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        SquareIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.action_delete),
            onClick = onRemove,
        )
    }
}

/**
 * 添加一个更新时刻。
 *
 * 解析与查重都在这里做，直接改**草稿**（而不是立刻落盘）：
 * 这一整页都是"草稿 + 显式保存"的模型，时刻也不例外 —— 否则用户点了删除
 * 又不想保存时，就没有退路了。提示因此必须是弹窗内部的一行字
 * （不能走 Snackbar：Snackbar 属于 Activity 那一层，这里失败时要保持弹窗开着）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTimeDialog(
    existing: List<LocalTime>,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    // 默认指向"下一个整点"，比从 00:00 起手更贴近"加一个时刻"的本意。
    val initialHour = remember { (java.time.LocalTime.now().hour + 1) % 24 }
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = 0,
        is24Hour = true,
    )
    var duplicated by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.action_add_time),
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 滚轮选时刻，比手输"09:30"字符串友好——不用记格式、不会输错。
                // 24 小时制，与时刻表显示（BriefSchedule.format 输出 09:30）一致。
                TimePicker(state = state)
                if (duplicated) {
                    Text(
                        text = stringResource(R.string.msg_time_duplicated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            NothingButton(
                text = stringResource(R.string.action_add),
                filled = true,
                onClick = {
                    val time = LocalTime.of(state.hour, state.minute)
                    if (time in existing) {
                        duplicated = true
                    } else {
                        onConfirm(time)
                    }
                },
            )
        },
        dismissButton = {
            NothingButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

// ---------------------------------------------------------------------------
// 通用小部件
// ---------------------------------------------------------------------------

/**
 * 分区内部的次级小标题（"运行状态" / "权限"这种块名）。
 * 比分区标题再低一级：等宽、更小、更淡，只负责分段，不参与编号体系。
 */
@Composable
private fun BlockLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            NothingTag(text = stringResource(R.string.permission_granted), active = true)
        } else {
            NothingButton(text = stringResource(R.string.action_grant), onClick = onGrant)
        }
    }
}

// ---------------------------------------------------------------------------
// 悬浮操作胶囊（右下角）
// ---------------------------------------------------------------------------

/**
 * 右下角悬浮的"液态玻璃"操作胶囊：**保存配置** + 一个不带底色的**恢复默认**。
 *
 * ## 为什么从通栏底条改成悬浮
 *
 * 原来是一条通栏底条（发丝线 + 两个直角按钮），它和内容抢版面：底条常驻 70dp，
 * 列表尾部就得永久留白，而尾部恰是「权限」这种翻一次就够的地方 —— 用一整条
 * 常驻横条去换它不划算。改成悬浮之后内容能一直滚到底，操作照样随手可及，
 * 好处立刻体现在 `contentPadding` 上（少留 28dp）。
 *
 * ## 液态玻璃：第一版是模拟，这一版是真模糊
 *
 * Compose 1.6 自己**没有**背景模糊（`Modifier.blur` 只糊自己那份内容），
 * 第一版用"半透明底 + 高光渐变 + 描边 + 投影"模拟 —— 压在纯色上像，压在
 * 滚动的文字上就露馅（底下的字清晰透过来，一眼是块色片，不是玻璃）。
 * 现在用 haze 库（dev.chrisbanes.haze 0.7.3，最后一代支持 Compose 1.6 的稳定版）
 * 做真 backdrop blur：内容层 `Modifier.hazeChild(state)` 注册可被模糊的区域，
 * 这里 `Modifier.haze(state, style)` 把那块区域取样并模糊。
 * ⚠️ 0.7.x 命名反直觉：child = 被糊的内容、haze = 玻璃本身
 * （1.x 起改名 hazeSource/hazeEffect），升级版本时签名会变，别直接平移。
 * API 31+ 走 RenderEffect 真模糊；API 26–30
 * 自动降级为半透明 tint（无模糊）—— 老机上观感退回第一版那种色片，可接受。
 *
 * 模糊之外的"玻璃感"来自 [glassMaterial]（两个按钮共用同一套材质）：
 * 大半径模糊（28dp）+ 细噪点（0.05，防色带）+ **清透的 tint（0.55）** —— 浓了
 * 模糊就被盖死，底下透不出明暗，玻璃会退化成"纯色片"（踩过：第一版 0.72 被主人
 * 说"就一个纯色的按钮"）；顶部一条**贴着上缘**的白色高光带（果冻感的来源）；
 * 上亮下暗的描边（下缘那条暗边是"玻璃厚度"）；大而淡的主色投影。
 *
 * ## 结构：保存 = 主胶囊，重置 = 圆形 icon 玻璃钮
 *
 * 两颗**分开**：保存是带文字的主胶囊（设计图那种），重置是一颗等高的圆玻璃钮
 * （`Icons.Rounded.Refresh`），横向并排、间距 10dp —— 破坏性操作不该和主操作
 * 挤在一颗胶囊里分不出主次（踩过：第一版做成"一根竖线隔两段"，被要求分开）。
 * 重置仍走 [onReset] 的确认弹窗，icon 不直达。
 *
 * ## 颜色跟着主色走
 *
 * 底色直接取「小组件外观」那支色相 + 鲜艳度（同一个 `hsvColor`），所以按钮和
 * 桌面点阵永远同色：用户拖色相时这一颗跟着变，不需要第二处配色入口。
 * 文字/图标色**按玻璃的实际明度分流**：亮玻璃（黄/青/白）配暖黑字，深玻璃
 * （深蓝/紫）配同色系提亮的亮字。⚠️ 设计图里"荧光字压亮黄底"是因为它截图的
 * 环境底色深、玻璃透出来的东西暗；我们的设置页是浅色底，照抄只会两头都看不清
 * （踩过：同色提亮黄字压黄玻璃，主人说"看不见字"）。**可读性优先。**
 *
 * [saveEnabled] 传的是"配置读完了没有"：读盘没回来之前草稿还是全默认值，
 * 这时候点保存会把**整份默认配置**盖到已有的配置上（API Key 一起没）。
 * 所以没读完就整颗禁用，别给用户一个能把数据写坏的入口。
 */
@Composable
private fun FloatingActions(
    saving: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !saving && saveEnabled

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 重置：白色方角按钮 + 刷新 icon。破坏性操作独立成颗，不和主操作挤在一起；
        // 仍走 onReset 里的确认弹窗，icon 不是直达。白底黑字（图标），在纸白设置页上
        // 靠 1px 发丝线描边从背景里浮出来。
        Row(
            modifier = Modifier
                .height(46.dp)
                .background(PureWhite)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .clickable(onClick = onReset)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.action_reset),
                tint = PureBlack,
                modifier = Modifier.size(20.dp),
            )
        }

        // 保存：红色方角实底 + 白字，是全页唯一的主操作。红 = Nothing 的信号红（SignalRed），
        // 白字在红底上对比拉满，不会再出现"玻璃 + 荧光字"那种看不清的情况。
        Box(
            modifier = Modifier
                .height(46.dp)
                .background(SignalRed.copy(alpha = if (enabled) 1f else 0.35f))
                .clickable(enabled = enabled, onClick = onSave)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.action_save).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = PureWhite.copy(alpha = if (enabled) 1f else 0.35f),
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 通用小部件
// ---------------------------------------------------------------------------

/** 单选项右侧的文字块：一行标题 + 一行说明。 */
@Composable
private fun RowScope.ChoiceLabel(title: String, desc: String?) {
    Column(Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (desc != null) {
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 方角图标按钮（发丝线描边）—— 全页的"删除"一类的轻操作都用它。 */
@Composable
private fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}
