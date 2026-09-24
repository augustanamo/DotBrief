package com.augustana.dotbrief.data.ingest

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 把时间转成"人话说出来的样子"。
 *
 * ## 为什么要收在一处
 *
 * 这段逻辑原先在三个地方各写了一遍 —— 日历取数、天气取数、简报开场，
 * 结果就是**三份实现悄悄长得不一样**：开场把 6~10 点叫"早上"，日程和日出日落
 * 叫"上午"；6:02 三处都念成"六点二分"（中文里该说"六点零二分"）。
 * 时间口语化是同一件事，规则就该只有一处，改的时候不必去找有几个副本。
 *
 * ## 为什么不交给模型
 *
 * 这是要**听**的文字。模型换算日期经常错位一天、把"下午三点"写成"15:00"，
 * 而用代码做是零成本的 —— 与 [CalendarSource] 里"时间表达不交给模型"同一个理由。
 */
object SpokenTime {

    /**
     * 时段前缀（报时用）。
     *
     * ⚠️ 分档必须与 [greetingFor] **完全一致** —— 这不是巧合，是硬要求：
     * 报时说"下午5点"、问候却说"晚上好"，用户听出来的就是"说串了"
     * （真出过：见 [DayPart] 里那条 17 点的记录）。
     * 唯一有意保留的差异是 0–4 点：报时说"凌晨"（客观描述），问候说"晚上好"
     * （社交措辞，主人明确要求"0 点别叫早上好"）。
     *
     * 5 点起算"早上"而不是"上午"：那会儿问候语已经是"早上好"了；
     * 12 点整是"中午"，13 点起就是"下午"。
     */
    fun period(hour: Int): String = when {
        hour < 5 -> "凌晨"
        hour < 9 -> "早上"
        hour < 12 -> "上午"
        hour < 13 -> "中午"
        hour < 18 -> "下午"
        else -> "晚上"
    }

    /** 24 小时制 -> 12 小时制。0 点和 12 点都念"12 点"，不念"0 点"。 */
    fun hour12(hour: Int): Int = (hour % 12).let { if (it == 0) 12 else it }

    /**
     * 分钟部分。
     *
     * 整点不说分钟；不足 10 分要补一个"零"—— 6:02 念成"六点零二分"，
     * 少了那个"零"就成了"六点二分"，听着像漏了一个字。TTS 不会替我们补。
     */
    fun minutePart(minute: Int): String = when {
        minute == 0 -> ""
        minute < 10 -> "零${minute}分"
        else -> "${minute}分"
    }

    /** 15 -> "下午3点"；0 -> "凌晨12点"。 */
    fun hourOf(hour: Int): String = "${period(hour)}${hour12(hour)}点"

    /** (15, 5) -> "下午3点零5分"；(6, 0) -> "上午6点"。 */
    fun clock(hour: Int, minute: Int): String = "${hourOf(hour)}${minutePart(minute)}"

    /**
     * 开场那句「现在是……」里的"现在"。
     *
     * [includeDate] 为真（今天第一次开口）时带上日期与星期：「9月23日 星期三 上午10点」；
     * 之后只说时段与时刻：「上午10点」。
     *
     * 为什么第二次连星期也去掉：用户要的是"每天第一次说今天是几月几号，再听就不说了"，
     * 而"星期三"和日期是同一类信息（都是"今天是哪天"）。时段与时刻不同 —— 它们每次都变，
     * 是"现在这一刻"的坐标，听第二遍也有意义。
     *
     * 放在这里而不是留在 [com.augustana.dotbrief.domain.GenerateBriefUseCase] 里，
     * 是因为现在有**两个**地方要生成这句话：生成简报时写进素材，
     * 以及播放定时缓存时把正文开头那句过时的时间换成此刻（见 `BriefClock`）。
     * 同一件事有第二份实现就迟早会走样 —— 这是本文件开头的教训，别在它身上再犯一次。
     */
    fun nowText(includeDate: Boolean, at: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): String {
        // 局部变量有意不叫 clock：那会与上面的同名函数互相遮蔽，
        // `val clock = clock(...)` 读起来像自引用，很容易看成递归。
        val time = clock(at.hour, at.minute)
        return if (includeDate) {
            "${at.monthValue}月${at.dayOfMonth}日 ${weekday(at)} $time"
        } else {
            time
        }
    }

    /**
     * 时段问候语：早上好 / 上午好 / 中午好 / 下午好 / 晚上好。
     *
     * 分档与 [period] **完全对齐**（5–8 早上 / 9–11 上午 / 12 中午 / 13–17 下午 / 其余晚上）：
     * 报时和问候必须是同一条线，否则 17 点会说出"开场下午好、天气段现在是晚上"这种自相矛盾。
     * 5–8 点说"早上好"比"上午好"自然，那会儿也确实还是"早上"。
     *
     * 深夜（23 点–次日 4 点）一律"晚上好"：0 点说"早上好"是错的，那会儿天没亮，
     * 是夜晚不是清晨。所以 18–23 和 0–4 都归"晚上好"，5 点起才算"早上"。
     *
     * 收在这里而不是留在开场逻辑里，是因为"问候语 + 日期"的开场
     * （[greetingOpen]）和"换问候语"（`BriefClock.fixGreeting`）都要用它，
     * 留两份就会像当年的"时段前缀"那样悄悄走样。
     */
    fun greetingFor(hour: Int): String = when (hour) {
        in 5..8 -> "早上好"
        in 9..11 -> "上午好"
        12 -> "中午好"
        in 13..17 -> "下午好"
        else -> "晚上好"  // 0–4（凌晨）和 18–23（入夜至深夜）
    }

    /**
     * 非闹钟场景的开场白：只说问候语，不说具体几点几分。
     *
     * [includeDate] 为真（今天第一次开口）时带上日期与星期：「9月23日 星期三，早上好」；
     * 之后只说问候语：「早上好」。
     *
     * 为什么这里也管 [includeDate]：和 [nowText] 一样，"今天第一次说日期"是跨入口的规则，
     * 不该在每处各判一遍。问候语和日期不冲突——「9月23日 星期三，早上好」念起来自然。
     *
     * 与 [nowText] 的分工：闹钟场景要像报时一样说出具体时刻（[nowText]），
     * 普通点击只想知道"今天怎么样"，报"上午10点52分"是多余的精确。
     */
    fun greetingOpen(includeDate: Boolean, at: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): String {
        val greeting = greetingFor(at.hour)
        return if (includeDate) {
            "${at.monthValue}月${at.dayOfMonth}日 ${weekday(at)}，$greeting"
        } else {
            greeting
        }
    }

    /**
     * 深夜的结尾贴心话："夜深了，早点休息。"。
     *
     * 23 点到次日 5 点前为深夜 —— 这会儿还醒着的人该被提醒一句早点睡，
     * 而不是被当成"新的一天开始了"。5 点前（不含 5 点）返回这句话，
     * 其余时段返回 null（白天不打扰、不说教）。
     *
     * 用在两个地方：
     * - 现场生成时写进 [BriefInput.lateNightHint]，由 [LocalBriefComposer] 结尾拼上、
     *   [PromptBuilder] 给模型下指令让它在结尾带一句；
     * - 播放定时缓存时由 `BriefClock.refresh` 按此刻补一句（缓存可能是白天生成的，
     *   深夜播放时结尾没这句话，需要按当前时刻补上）。
     */
    fun lateNightCare(hour: Int): String? =
        if (hour >= 23 || hour < 5) LATE_NIGHT_CARE else null

    /**
     * 深夜结尾那句话的固定文案。
     *
     * 提成常量是为了**能把它摘掉**：缓存可能是凌晨生成的，白天被念出来时开场已换成
     * "早上好"，结尾却还挂着这句 —— 同一段话里早晚打架。补的时候用它拼、
     * 摘的时候认它，才谈得上对称（见 `BriefClock` 里的 `stripStaleCare`）。
     */
    const val LATE_NIGHT_CARE: String = "夜深了，早点休息。"

    /** 「星期三」。`DayOfWeek.value` 是 1..7 且以**周一**为 1，所以数组得按这个顺序排。 */
    fun weekday(at: LocalDateTime): String = WEEKDAYS[at.dayOfWeek.value - 1]

    private val WEEKDAYS = arrayOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
    )
}
