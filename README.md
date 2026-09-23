# BriefWidget · 个人 AI 语音即时简报

> 点一下桌面小组件，它把你今天的日程、行程、快递和新闻读给你听。

当前进度：**Phase 1 完成**（工程脚手架 + 配置中心 + DataStore 存取）。
`./gradlew assembleDebug` 已实测通过（AGP 8.5.2 / Gradle 8.7 / Kotlin 1.9.24 / compileSdk 34）。

---

## 一、快速开始

```bash
export JAVA_HOME=~/jdk/jdk-17.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk

cd BriefWidget
./gradlew assembleDebug                 # 产出 app/build/outputs/apk/debug/app-debug.apk

# 装到手机（需要先 adb devices 能看到设备）
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.briefwidget/.ui.settings.SettingsActivity
```

**验证编译时请关掉构建缓存**，否则 Gradle 会用 `FROM-CACHE` 假装成功：

```bash
./gradlew --no-build-cache clean assembleDebug
```

## 二、版本锁定

| 组件 | 版本 | 为什么是它 |
| --- | --- | --- |
| AGP / Gradle | 8.5.2 / 8.7 | 与 Gradle 8.7 严格匹配，本机已缓存 |
| Kotlin | 1.9.24 | 配 `composeCompiler 1.5.14`；升 2.x 需改用 `kotlin.plugin.compose` 插件 |
| compileSdk / minSdk / targetSdk | 34 / 26 / 34 | minSdk 26 起 `java.time`、自适应图标、`NotificationListenerService` 都无需兼容分支 |
| Compose BOM | 2024.06.00 | 与 Kotlin 1.9.24 的编译器版本兼容 |
| Glance | 1.1.0 | 桌面小组件（Phase 4），其 Compose runtime 与 BOM 对齐 |
| Room / DataStore | 2.6.1 / 1.1.1 | Room 走 KSP；DataStore 存配置与 Key |

依赖统一在 `gradle/libs.versions.toml` 声明，`app/build.gradle.kts` 只引用别名。

## 三、工程目录结构

```
BriefWidget/
├── settings.gradle.kts              # 仓库配置：阿里云镜像优先，官方源兜底
├── build.gradle.kts                 # 根脚本：仅声明插件
├── gradle.properties
├── gradle/libs.versions.toml        # ✅ 版本目录（全部依赖集中在此）
├── gradlew / gradle/wrapper/        # wrapper 指向腾讯云镜像（networkTimeout=60s）
├── local.properties                 # sdk.dir（已 gitignore）
└── app/
    ├── build.gradle.kts             # ✅ 依赖清单 + KSP/Room schema 导出配置
    ├── proguard-rules.pro           # 序列化 / Retrofit / Room 的 keep 规则
    ├── schemas/                     # Room schema 导出目录（Phase 2 起自动生成）
    └── src/main/
        ├── AndroidManifest.xml      # ✅ 权限已全量声明；Phase 2-4 的组件在注释里待放开
        ├── res/
        │   ├── values/{strings,themes,colors}.xml
        │   ├── drawable/ic_launcher_foreground.xml   # 点阵风格图标
        │   └── mipmap-anydpi-v26/ic_launcher*.xml
        └── java/com/briefwidget/
            ├── BriefWidgetApp.kt                  # ✅ Application，持有容器
            ├── di/
            │   └── AppContainer.kt                # ✅ 手写依赖容器（不引 Hilt）
            ├── data/
            │   ├── settings/                      # ✅ Phase 1 核心：配置中心
            │   │   ├── UserSettings.kt            #   聚合根 + 各域配置 data class + 枚举
            │   │   ├── Defaults.kt                #   出厂默认值 + 默认 System Prompt
            │   │   ├── SettingsKeys.kt            #   DataStore key 清单（配置 / 运行期两套）
            │   │   ├── SettingsDataStore.kt       #   两个 DataStore 实例的注册处
            │   │   ├── SettingsMapper.kt          #   Preferences <-> 领域模型 编解码
            │   │   ├── SettingsRepository.kt      #   唯一读写入口（原子 edit + 订阅源增删）
            │   │   └── RuntimeStateStore.kt       #   小组件状态 / 最近简报 / 错误
            │   ├── calendar/                      # ⏳ Phase 2 CalendarRepository（CalendarContract）
            │   ├── notification/                  # ⏳ Phase 2 BriefNotificationListener
            │   ├── rss/                           # ⏳ Phase 2 RssFetcher
            │   ├── local/                         # ⏳ Phase 2 Room：实体 / DAO / Database
            │   └── llm/                           # ⏳ Phase 3 LlmService + PromptBuilder
            ├── domain/
            │   ├── model/                         # ⏳ Phase 2 CalendarEvent / TripItem / PackageItem / NewsItem
            │   └── usecase/                       # ⏳ Phase 3 GenerateBriefUseCase（拼装 -> LLM -> 落库）
            ├── tts/
            │   └── TtsPreviewPlayer.kt            # ✅ 设置页试听（短命引擎）
            │                                      # ⏳ Phase 3 BriefTtsService（前台服务 + 音频焦点）
            ├── widget/                            # ⏳ Phase 4 Glance 小组件 + Receiver
            └── ui/
                ├── settings/                      # ✅ Phase 1 配置界面
                │   ├── SettingsActivity.kt        #   唯一入口 Activity
                │   ├── SettingsViewModel.kt       #   草稿 + 显式保存
                │   ├── SettingsScreen.kt          #   Compose 设置页（六个分区）
                │   └── PermissionUtils.kt         #   日历权限 / 通知使用权 检测与跳转
                └── theme/{Color,Theme}.kt         # ✅ 点阵极简主题（墨黑 / 纸白 / 单一信号色）
```

## 四、Phase 1 交付内容

**设置页六个分区**：大模型接口（Base URL / Key / Model / 发散度 / System Prompt）、
新闻订阅源（增删改 + 启停）、语音播报（引擎选择 / 语速 / 音调 / 试听）、
简报内容（预读时长 / 字数区间 / 数据源开关）、权限授权（日历 + 通知使用权）、
顶部状态卡片（小组件状态 + 最近一次简报 + 上次错误）。

**关键设计决策**

| 决策 | 理由 |
| --- | --- |
| 编辑走「草稿 + 显式保存」，订阅源改走「立即落盘」 | 边打边存会让中途被触发的 Worker 读到半截 Base URL |
| 读取全部回落默认值，写入全量落盘 | 新增配置项不需要写 DataStore 迁移 |
| 配置与运行期状态拆成两个 DataStore | 运行期状态每次点击要写 3~4 次，不该和配置互相触发重组 |
| 并发改配置一律走 `edit {}` 事务 | 读改写同事务，小组件和设置页同时写也不会丢更新 |
| 手写 `AppContainer` 而非 Hilt | 只有 4 个注入点，且 Glance Receiver / 监听服务由系统实例化，Hilt 的 KSP 开销大于收益 |

## 五、后续阶段落地要点

### Phase 2 · 数据抓取与存储

- `CalendarRepository`：查 `CalendarContract.Instances` 而不是 `Events`，才能正确展开重复日程；
  按 `lookaheadHours` 取 `BEGIN..END` 窗口。日历权限被拒要降级为空列表，不要抛异常。
- `BriefNotificationListener`：必须在清单里声明 `BIND_NOTIFICATION_LISTENER_SERVICE` 权限，
  并在 `onListenerConnected()` 回写可用状态。用自己的关键字表过滤 `extras[EXTRA_TEXT]` +
  `extras[EXTRA_TITLE]`，命中后才落库，避免把全部通知写盘。
- `RssFetcher`：用 `com.prof18.rssparser` 一次性拿 `title` + `description`；
  多个源用 `coroutineScope { feeds.map { async { } } }` 并发拉取，单源失败只记日志。
- Room：Trip / Package 两张表加 `expireAt` 字段，Worker 启动时顺手清理超过 `retentionDays` 的记录。

### Phase 3 · LLM 与 TTS

- `PromptBuilder` 把 `Defaults.PLACEHOLDER_MIN_CHARS` / `PLACEHOLDER_MAX_CHARS` 替换成用户配置，
  再拼 JSON payload。**请求后要过滤 Markdown 符号**（`*` `#` `` ` `` `>`），否则 TTS 会念出"星号"。
- `LlmService`：Retrofit + `kotlinx-serialization` 转换器；超时取 `LlmConfig.timeoutSeconds`；
  重试只对 5xx 与网络异常做，4xx 直接失败（Key 错了重试没用）。
- `BriefTtsService`：前台服务 + `AudioManager.requestAudioFocus`，
  `AUDIOFOCUS_LOSS_TRANSIENT` 时暂停；再次点击小组件要能打断当前朗读。

### Phase 4 · Glance 小组件

- 小组件点击 → `WorkManager` 起 `BriefWorker`，链路是「收集 → 总结 → 播报」，状态写进 `RuntimeStateStore`。
- 小组件刷新用 `updateAppWidgetState` + `GlanceAppWidget.update`，不要用 `updatePeriodMillis`（最快 30 分钟）。
- `APPWIDGET_UPDATE` 广播必须在 10 秒内返回，否则 ANR。三条防线：空 id 直接 return、
  先用本地缓存出图再后台补拉、挂 5.5 秒 watchdog。
- 小尺寸胶囊和大尺寸卡片两种布局用 `SizeMode.Responsive`，对应任务书的点阵胶囊 / 卡片形态。

## 六、安全提示

API Key 目前以明文存于 DataStore（`/data/data/com.briefwidget/files/datastore/brief_settings.preferences_pb`），
非 root 设备上读不到，但**备份 / root / 调试工具可提取**。若要加固：

- 接入 Keystore 派生密钥，用 `AES/GCM` 只加密 `llm.api_key` 一个字段（改 `SettingsMapper` 的两处即可，
  其余代码不用动）；
- 或在应用层改成只在内存中持有 Key、持久化时存密文。

另外 `allowBackup="false"` 已设置，避免 Key 随系统备份外流。
