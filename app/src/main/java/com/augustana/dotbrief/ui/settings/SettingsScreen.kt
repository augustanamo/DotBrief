package com.augustana.dotbrief.ui.settings

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.augustana.dotbrief.R
import com.augustana.dotbrief.data.settings.AccentColor
import com.augustana.dotbrief.data.settings.BriefSource
import com.augustana.dotbrief.data.settings.CarouselPace
import com.augustana.dotbrief.data.settings.Defaults
import com.augustana.dotbrief.data.settings.DoubaoApiVersion
import com.augustana.dotbrief.data.settings.RssConfig
import com.augustana.dotbrief.data.settings.RssFeed
import com.augustana.dotbrief.data.settings.TtsConfig
import com.augustana.dotbrief.data.settings.TtsProvider
import com.augustana.dotbrief.data.settings.UserSettings
import com.augustana.dotbrief.data.settings.WidgetRuntimeState
import com.augustana.dotbrief.data.settings.WidgetState
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
import com.augustana.dotbrief.ui.theme.SignalRedLift
import com.augustana.dotbrief.ui.theme.StatusDot
import com.augustana.dotbrief.ui.theme.TechRow
import com.augustana.dotbrief.ui.theme.WindowGrey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 * ## 结构
 *
 * `HeroHeader`（纯黑点阵 logotype 区块）→ 八个编号分区 → 底部固定操作条。
 * Hero 永远是纯黑，因为品牌规范里 logotype 只能是黑白；亮色主题下
 * 它就成了页面顶部那条"黑带"，黑带下面是纸白 —— 经典的黑白对照。
 */
@Composable
fun SettingsScreen(
    state: SettingsViewModel.UiState,
    runtimeState: WidgetRuntimeState,
    onChange: (UserSettings) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onAddFeed: (String, String) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onToggleFeed: (String, Boolean) -> Unit,
    onPreviewSpeech: () -> Unit,
    onPlayBriefNow: () -> Unit,
    onStopBrief: () -> Unit,
    onProbeLlm: () -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddFeedDialog by remember { mutableStateOf(false) }

    // 「高级设置」默认折叠 —— 但**没配好就自动展开**：
    // 一个刚装完的用户必须能找到 API Key 入口，而一个已经用了两周的用户
    // 没必要每次滚过一屏再也用不到的字段。默认值跟着"是否已配置"走，
    // 比给一个固定的 true/false 更贴近真实意图。
    var advancedExpanded by rememberSaveable {
        mutableStateOf(state.draft.llm.apiKey.isBlank() || state.draft.rss.feeds.isEmpty())
    }

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

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onConsumeMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // 底部预留操作条的高度，否则最后一个分区会被压住
                contentPadding = PaddingValues(bottom = 132.dp),
            ) {
                item { HeroHeader(runtimeState = runtimeState) }

                // ---- 每天都会碰的三块：外观 / 语音 / 内容 ----
                item {
                    AppearanceSection(
                        index = "01",
                        accent = state.draft.accent,
                        draft = state.draft,
                        onChange = onChange,
                    )
                }

                item {
                    TtsSection(
                        index = "02",
                        tts = state.draft.tts,
                        onChange = onChange,
                        draft = state.draft,
                        onPreviewSpeech = onPreviewSpeech,
                    )
                }

                item { BriefSection(index = "03", draft = state.draft, onChange = onChange) }

                // ---- 装完就不用再动的两块，收进可折叠的「高级设置」 ----
                item {
                    AdvancedHeader(
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = !advancedExpanded },
                    )
                }

                if (advancedExpanded) {
                    item {
                        LlmSection(
                            index = "04",
                            draft = state.draft,
                            onChange = onChange,
                            probing = state.probing,
                            probeResult = state.probeResult,
                            probeOk = state.probeOk,
                            onProbe = onProbeLlm,
                        )
                    }

                    item {
                        RssSection(
                            index = "05",
                            rss = state.draft.rss,
                            onToggleFeed = onToggleFeed,
                            onRemoveFeed = onRemoveFeed,
                            onAddFeedClick = { showAddFeedDialog = true },
                        )
                    }
                }

                // ---- 末尾：权限与诊断合并成一块"系统信息" ----
                item {
                    SystemSection(
                        index = "06",
                        runtimeState = runtimeState,
                        hasCalendar = hasCalendar,
                        hasLocation = hasLocation,
                        hasNotificationAccess = hasNotificationAccess,
                        onPlayBriefNow = onPlayBriefNow,
                        onStopBrief = onStopBrief,
                        onRequestCalendar = {
                            calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                        },
                        onRequestLocation = {
                            locationPermissionLauncher.launch(
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        },
                        onOpenNotificationSettings = { context.openNotificationListenerSettings() },
                        onOpenAppSettings = { context.openAppDetailsSettings() },
                    )
                }
            }

            ActionBar(
                saving = state.saving,
                onSave = onSave,
                onReset = onReset,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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

/**
 * 「高级设置」折叠条。
 *
 * 存在的意义纯粹是**信息分层**：大模型接口和订阅源属于"装完就不再动"的配置，
 * 但它们字段多、很长，平铺在中间会把每天要调的语音、内容参数挤到下面去。
 * 收起来之后，默认视野里只剩三块高频设置。
 *
 * 用一条和分区头长得一样的行来表达，保持"编号 + 标题 + 眉标"的读板节奏，
 * 但整体可点 —— 因为这里点哪里都是"展开"，不存在别的手势歧义。
 */
@Composable
private fun AdvancedHeader(expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
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
                    .clickable(onClick = onToggle)
                    .padding(vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.section_advanced_range),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.section_advanced),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            if (expanded) R.string.action_collapse else R.string.action_expand,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!expanded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.section_advanced_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 01 小组件外观（预览 + 配色 + 轮播）
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
                                onHueChange(hueAt(x))
                                if (change.pressed) change.consume()
                            }

                            if (!change.pressed) {
                                if (owned) {
                                    change.consume()
                                } else if (!abandoned) {
                                    // 轻点：点哪儿选哪儿
                                    onHueChange(hueAt(x))
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
// 06 权限与诊断（合并）
// ---------------------------------------------------------------------------

/**
 * 「权限与诊断」。
 *
 * 合并的理由：这两块单独看都不是"配置"，而是**同一类东西**——
 * "这个应用现在到底能不能干活"。用户来这里只有一个问题：
 * 点不动 / 不出声，是权限没给，还是接口挂了。
 * 把"现在什么状态"和两个授权入口放在一起，一眼就能把因果连起来；
 * 分在两处反而要来回对照。
 *
 * 但"能不能干活"只留**结论**，不留**过程**：状态灯 + 上次生成时间 + 三条权限，
 * 就这么几行。中间那些实现层读数（服务连接标志、点阵灰彩、原始异常）都已经撤掉，
 * 见下面 [TechRow] 处的注释。
 *
 * 位置放在最后：装好之后的日常使用中几乎不会翻到这里。
 */
@Composable
private fun SystemSection(
    index: String,
    runtimeState: WidgetRuntimeState,
    hasCalendar: Boolean,
    hasLocation: Boolean,
    hasNotificationAccess: Boolean,
    onPlayBriefNow: () -> Unit,
    onStopBrief: () -> Unit,
    onRequestCalendar: () -> Unit,
    onRequestLocation: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val live = runtimeState.state == WidgetState.PLAYING ||
        runtimeState.state == WidgetState.GENERATING

    val timeText = remember(runtimeState.lastBriefAtEpochSeconds) {
        if (runtimeState.lastBriefAtEpochSeconds <= 0L) {
            EMPTY_VALUE
        } else {
            DateTimeFormatter.ofPattern("M月d日 HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(runtimeState.lastBriefAtEpochSeconds))
        }
    }

    NothingSection(
        index = index,
        title = stringResource(R.string.section_system),
        eyebrow = stringResource(R.string.eyebrow_system),
    ) {
        BlockLabel(text = stringResource(R.string.block_status))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(active = live)
            Spacer(Modifier.width(11.dp))
            Text(
                text = runtimeState.state.label(),
                style = MaterialTheme.typography.titleSmall,
                color = if (live) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        // 这里只留一行"上次生成"。
        //
        // 之前这段还摊着三条读数：通知读取服务的连接状态、点阵当前是彩是灰、
        // 以及"上次出错：<原始异常>"。它们对开发很有用，但对用户全是噪音 ——
        // 服务连没连，看下面那条"通知使用权 已授权"就够了，同一件事说两遍
        // 只会让人怀疑哪条才算数；点阵灰彩是"有没有新内容"的结果，用户在桌面上
        // 一眼就能看见，不需要在设置页里再读一次；原始异常更糟，它不但看不懂，
        // 还会让人以为应用坏了。诊断信息一律走日志。
        TechRow(
            label = stringResource(R.string.tech_brief_time),
            value = timeText,
        )

        // 出错时也只说"怎么办"，不贴异常本身
        if (runtimeState.lastError.isNotBlank()) {
            Text(
                text = stringResource(R.string.status_error_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (runtimeState.lastBriefText.isBlank()) {
            Text(
                text = stringResource(R.string.status_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.status_last_brief),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = runtimeState.lastBriefText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // 不走桌面也能跑一遍完整链路，用来验证权限与语音是否真的通了
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NothingButton(
                text = stringResource(R.string.action_play_brief_now),
                onClick = onPlayBriefNow,
                enabled = runtimeState.state != WidgetState.GENERATING,
                modifier = Modifier.weight(1f),
            )
            if (runtimeState.state == WidgetState.PLAYING) {
                NothingButton(
                    text = stringResource(R.string.action_stop_brief),
                    onClick = onStopBrief,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Hairline(color = MaterialTheme.colorScheme.outlineVariant)

        BlockLabel(text = stringResource(R.string.block_permission))

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

private const val EMPTY_VALUE = "—"

// ---------------------------------------------------------------------------
// 04 大模型接口
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
) {
    val llm = draft.llm

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

        NothingField(
            value = llm.baseUrl,
            onValueChange = { onChange(draft.copy(llm = llm.copy(baseUrl = it))) },
            label = stringResource(R.string.label_base_url),
            keyboardType = KeyboardType.Uri,
        )

        SecretField(
            value = llm.apiKey,
            onValueChange = { onChange(draft.copy(llm = llm.copy(apiKey = it))) },
            label = stringResource(R.string.label_api_key),
            hint = stringResource(R.string.hint_api_key),
        )

        NothingField(
            value = llm.model,
            onValueChange = { onChange(draft.copy(llm = llm.copy(model = it))) },
            label = stringResource(R.string.label_model),
        )

        // 到这里就没有别的了。
        //
        // 之前这块下面还挂着三个滑杆/输入框：发散度、接口超时、系统提示词。
        // 它们是**调优参数**，不是**必填参数** —— 界面上多一个滑杆，用户就得先
        // 判断"这个我该不该动"，而这三个的正确答案对所有人都是"不用动"：
        // 发散度 0.6 是端到端试出来的；超时 90 秒是按带思维链的模型留的余量；
        // 系统提示词更是一段写给模型的规范（禁 Markdown、固定播报顺序、字数为
        // 运行时注入），改错一个字，播报就变成念符号或者不报行程。
        // 所以它们退回 [Defaults]，只由代码维护；这里只留三件"只有用户知道"的事：
        // 接口在哪（Base URL）、钥匙是什么（API Key）、用哪个型号（模型名称）。

        // 大模型接口最常见的失败是「网络到不了」，它的表现是"点了半天没反应然后超时"，
        // 极难自己定位。所以这里给一个一键自检，把地址错 / 被墙 / Key 无效 / 模型名错分开说清楚。
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
    }
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
// 05 新闻订阅源
// ---------------------------------------------------------------------------

@Composable
private fun RssSection(
    index: String,
    rss: RssConfig,
    onToggleFeed: (String, Boolean) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onAddFeedClick: () -> Unit,
) {
    NothingSection(
        index = index,
        title = stringResource(R.string.section_rss),
        eyebrow = stringResource(R.string.eyebrow_rss),
        desc = stringResource(R.string.section_rss_desc),
    ) {
        if (rss.feeds.isEmpty()) {
            Text(
                text = stringResource(R.string.msg_no_enabled_feed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        rss.feeds.forEach { feed ->
            FeedRow(
                feed = feed,
                onToggle = { enabled -> onToggleFeed(feed.id, enabled) },
                onRemove = { onRemoveFeed(feed.id) },
            )
        }

        NothingButton(
            text = stringResource(R.string.action_add_feed),
            onClick = onAddFeedClick,
            modifier = Modifier.fillMaxWidth(),
        )
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

// ---------------------------------------------------------------------------
// 06 语音播报
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

        // ---- 豆包（火山引擎）配置：只在选了豆包时才展开 ----
        if (tts.provider == TtsProvider.DOUBAO) {
            Hairline(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.label_doubao_api_version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DoubaoApiVersion.values().forEach { version ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NothingRadio(
                            selected = tts.doubaoApiVersion == version,
                            onClick = {
                                onChange(
                                    draft.copy(
                                        tts = tts.copy(
                                            doubaoApiVersion = version,
                                            // 资源 ID 必须和"代次"匹配，换接口时顺手给出对应的默认值，
                                            // 否则用户很容易留着 seed-tts-2.0 去调 v1 接口，然后拿到一个看不懂的报错
                                            doubaoResourceId = if (version == DoubaoApiVersion.V3) {
                                                Defaults.DOUBAO_RESOURCE_ID
                                            } else {
                                                tts.doubaoResourceId
                                            },
                                        ),
                                    ),
                                )
                            },
                        )
                        ChoiceLabel(
                            title = stringResource(
                                when (version) {
                                    DoubaoApiVersion.V3 -> R.string.doubao_v3
                                    DoubaoApiVersion.V1 -> R.string.doubao_v1
                                },
                            ),
                            desc = null,
                        )
                    }
                }
            }

            NothingField(
                value = tts.doubaoAppId,
                onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoAppId = it))) },
                label = stringResource(R.string.label_doubao_app_id),
            )
            SecretField(
                value = tts.doubaoAccessToken,
                onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoAccessToken = it))) },
                label = stringResource(R.string.label_doubao_token),
            )
            NothingField(
                value = tts.doubaoSpeaker,
                onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoSpeaker = it))) },
                label = stringResource(R.string.label_doubao_speaker),
            )
            if (tts.doubaoApiVersion == DoubaoApiVersion.V3) {
                NothingField(
                    value = tts.doubaoResourceId,
                    onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoResourceId = it))) },
                    label = stringResource(R.string.label_doubao_resource_id),
                )
            } else {
                NothingField(
                    value = tts.doubaoCluster,
                    onValueChange = { onChange(draft.copy(tts = tts.copy(doubaoCluster = it))) },
                    label = stringResource(R.string.label_doubao_cluster),
                )
            }

            Text(
                text = stringResource(R.string.doubao_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        NothingSliderRow(
            label = stringResource(R.string.label_speech_rate),
            valueText = String.format("%.1fX", tts.speechRate),
            value = tts.speechRate,
            valueRange = TtsPreviewPlayer.MIN_RATE..TtsPreviewPlayer.MAX_RATE,
            steps = 5,
            onValueChange = { onChange(draft.copy(tts = tts.copy(speechRate = it))) },
        )

        NothingSliderRow(
            label = stringResource(R.string.label_pitch),
            valueText = String.format("%.1f", tts.pitch),
            value = tts.pitch,
            valueRange = TtsPreviewPlayer.MIN_PITCH..TtsPreviewPlayer.MAX_PITCH,
            steps = 5,
            onValueChange = { onChange(draft.copy(tts = tts.copy(pitch = it))) },
        )

        NothingButton(
            text = stringResource(R.string.action_preview),
            onClick = onPreviewSpeech,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 07 简报内容
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

        NothingSliderRow(
            label = stringResource(R.string.label_length),
            valueText = "${brief.minChars} 字起",
            value = brief.minChars.toFloat(),
            valueRange = 60f..300f,
            steps = 11,
            onValueChange = {
                onChange(draft.copy(brief = brief.copy(minChars = it.toInt())))
            },
        )

        NothingSliderRow(
            label = stringResource(R.string.label_length_max),
            valueText = "${brief.maxChars} 字",
            value = brief.maxChars.toFloat(),
            valueRange = 120f..500f,
            steps = 18,
            onValueChange = {
                onChange(draft.copy(brief = brief.copy(maxChars = it.toInt())))
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
// 底部操作条
// ---------------------------------------------------------------------------

/**
 * 固定底条。
 *
 * 保存是这一页唯一的主操作，所以做成实底（白底上是纯黑块、黑底上是纯白块）；
 * "恢复默认"是破坏性操作，做成描边放在左边、宽度更窄 —— 不抢主操作的注意力，
 * 但位置固定、不会被滑走，改完配置不用再滚回去找。
 */
@Composable
private fun ActionBar(
    saving: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Hairline()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NothingButton(
                    text = stringResource(R.string.action_reset),
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                )
                NothingButton(
                    text = stringResource(R.string.action_save),
                    onClick = onSave,
                    filled = true,
                    enabled = !saving,
                    modifier = Modifier.weight(1.6f),
                )
            }
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
