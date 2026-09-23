package com.augustana.dotbrief.domain

import com.augustana.dotbrief.data.ingest.AgendaEvent
import com.augustana.dotbrief.data.ingest.BriefInput
import com.augustana.dotbrief.data.ingest.CapturedItem
import com.augustana.dotbrief.data.ingest.WeatherInfo

/**
 * 没接大模型时的「本地简报」。
 *
 * ## 为什么要有它
 *
 * 简报里的几类信息其实只有两类：
 * - **照抄即可**：天气、日程、行程、待取包裹 —— 它们在取数阶段就已经是结构化事实
 *   （温度、时间、车次、取件码），拼起来就是一句能直接念的话，不需要任何"理解"；
 * - **需要提炼**：新闻快讯 —— 头条原文动辄几百字，不压成一句就没法听。
 *
 * 所以没配置大模型时不该整条链路直接失败 —— 前一类照样念得出来。
 * 这也是"本地优先"的默认姿势：**有模型更好，没模型也不哑**。
 *
 * ## 有意不解释自己
 *
 * 这段文字是要**听**的，所以刻意不写"未配置大模型，以下是本地信息"这类系统状态。
 * 每听一次就被提醒一次很烦，缺什么该由设置页去说（见 `section_llm_desc` 的说明）。
 *
 * ## 只念"识别码 + 时间"
 *
 * 通知原文常带推广话术（"感谢您使用……"）。整段念出来没人听得下去，
 * 而车次 / 航班号 / 取件码这类"照着念就能用"的信息才是重点。
 *
 * 抠不出识别码的条目**整条不念**，而不是退回念原文摘要 —— 原文通常就是
 * 被推送截断的半句话，念出来既不中听也没法照着办事。
 */
object LocalBriefComposer {

    /** 降水概率到这个数才值得提醒带伞。低于它提一句反而显得啰嗦。 */
    private const val RAIN_LIKELY_PERCENT = 50

    fun compose(input: BriefInput): String {
        val parts = mutableListOf<String>()
        parts += "现在是${input.nowText}。"

        // 纪念日排在最前面：它是今天最有分量的一件事，不该被日程和天气挤到后面。
        // 每条单独成句 —— "今天是妈妈生日，结婚纪念日"这种念法是机器味。
        input.anniversaries.forEach { parts += "今天是${it.title}。" }

        // 天气紧随其后：它是"此刻外面是什么样"，是这几句里唯一一个
        // "出门之前用得上"的信息。
        input.weather?.let { parts += it.describe() }

        val hasAgenda = input.events.isNotEmpty() ||
            input.trips.isNotEmpty() ||
            input.packages.isNotEmpty()

        if (!hasAgenda) {
            // 只有天气或纪念日也算"说了点什么"，这时候再补一句"没有日程"是废话。
            // 真正什么都没取到时（连天气都没有）才需要交代一句，免得用户以为是空响。
            if (input.weather == null && input.anniversaries.isEmpty()) {
                parts += "暂时没有需要提醒的日程、行程和包裹。"
            }
            return parts.joinToString("")
        }

        // 空的类别**整句不提**，而不是念一句"接下来没有日程安排" ——
        // 没有的东西不值得占一次开口的时间。
        //
        // 每类都先算出"说出来是什么词"、再过滤掉算出来是空的那些：
        // 条数取的是**真的会念出来的条数**，而不是库里取到的条数。
        // 典型症状就是"有 2 个包裹待取：取件码 86214" —— 第二条没有取件码，
        // 一个字都念不出来，但计数把它算进去了。
        val agenda = input.events.map { it.describe() }.filter { it.isNotBlank() }
        if (agenda.isNotEmpty()) {
            parts += "接下来有 ${agenda.size} 项安排：${agenda.joinToString("；")}。"
        }
        val trips = input.trips.map { it.describeTrip() }.filter { it.isNotBlank() }
        if (trips.isNotEmpty()) {
            parts += "有 ${trips.size} 趟行程：${trips.joinToString("；")}。"
        }
        val packages = input.packages.map { it.describePackage() }.filter { it.isNotBlank() }
        if (packages.isNotEmpty()) {
            parts += "有 ${packages.size} 个包裹待取：${packages.joinToString("；")}。"
        }

        return parts.joinToString("")
    }

    /**
     * "外面26度，多云，今天22到31度。下午3点前后可能有雨，出门带把伞。"
     *
     * 三件事分开说，而且都是**行动建议**而不是数据播报：
     * - 下雨给的是"几点"而不是概率数字 —— TTS 念 "50%" 会变成"百分之五十"
     *   甚至"五零百分号"，而听的人只关心"带不带伞"；
     * - 紫外线按档位给不同的话，中等和强不是一回事；
     * - 空气质量只在差的时候提，好天气不该占一次开口。
     * 日出日落只在当天第一次播报时出现在素材里，所以这里不用再判断。
     */
    private fun WeatherInfo.describe(): String = buildString {
        append("外面${temperature}度，$condition")
        if (high != low) append("，今天${low}到${high}度")
        append("。")

        if (rainAtText != null) {
            append("${rainAtText}前后可能有雨，出门带把伞。")
        } else if ((precipitationProbability ?: 0) >= RAIN_LIKELY_PERCENT) {
            // 逐小时数据里找不到未来的雨点（比如雨已经在早上下完了），
            // 但全天概率仍然很高 —— 退回一句笼统的提醒，别把这条信息丢了。
            append("今天可能会有雨，出门记得带把伞。")
        }

        if (uvNeedsCare) {
            append(if (uvIsStrong) "紫外线挺强，注意防晒。" else "紫外线中等，出门涂点防晒。")
        }

        if (airIsBad) append("空气不太好，出门戴个口罩。")

        if (sunriseText != null || sunsetText != null) {
            append("今天")
            sunriseText?.let { append("${it}日出") }
            if (sunriseText != null && sunsetText != null) append("、")
            sunsetText?.let { append("${it}日落") }
            append("。")
        }
    }

    /** "下午2点30分 项目评审，在会议室A"。 */
    private fun AgendaEvent.describe(): String = buildString {
        if (atText.isNotBlank()) append(atText)
        if (title.isNotBlank()) {
            if (isNotEmpty()) append(' ')
            append(title)
        }
        if (location.isNotBlank()) append("，在$location")
    }

    /** 车次 / 航班号本身就是主角，直接念号 + 时间。两者都没有就整条不念。 */
    private fun CapturedItem.describeTrip(): String =
        code.ifBlank { null } ?: timeText.ifBlank { null } ?: ""

    /**
     * 取件码光念一串数字没人对得上，得先说清它是什么码。
     *
     * 抠不出码就返回空串（整条不念），**不退回通知原文**：原文往往是推送里
     * 就被截断的半句话（实测："菜鸟驿站，您的包裹已到丰巢快递柜，取件码。"），
     * 念出来像机器坏了，用户拿着它也没法取件 —— 不知道去哪个柜子、输什么数字。
     * 真正带码的那条通常在库里，信息不会丢。
     *
     * 这道过滤在取数阶段已经做过一次（见 `GenerateBriefUseCase`），这里是第二道防线：
     * 本地简报是"照抄即可"的链路，一旦素材脏了就会原样念出去，所以宁可多防一层。
     */
    private fun CapturedItem.describePackage(): String =
        code.ifBlank { null }?.let { "取件码 $it" }.orEmpty()
}
