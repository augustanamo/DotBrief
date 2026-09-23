package com.augustana.dotbrief.data.notification

/** 通知被归类成哪一类。 */
enum class IngestKind {
    /** 飞机 / 高铁行程。 */
    TRIP,

    /** 快递包裹。 */
    PACKAGE,
}

data class ParsedNotification(
    val kind: IngestKind,
    /** 车次 / 航班号 / 取件码；没提取到时为空串。 */
    val code: String,
    /** 形如 "09:35" 的时间片段；没有则空串。 */
    val timeText: String,
)

/**
 * 通知正文的关键字 / 正则提取。
 *
 * 策略是**先关键字兜住噪声，再正则抠字段**：
 * 关键字负责判断"这条值不值得存"，正则负责抠出"具体要念什么"。
 *
 * 正则刻意做得保守（宁可漏，不要错）：把「京A12345」当成航班号念出来
 * 比漏掉一条通知更糟。等积累到真实通知样本后再逐条收紧。
 */
object NotificationParser {

    /** 高铁 / 动车 / 普速车次：G/D/C + 1~4 位数字，K/Z/T/Y 同理。 */
    private val TRAIN_PATTERN = Regex("""(?:^|[^A-Za-z0-9])([GDCKZTY]\d{1,4})(?![0-9])""")

    /** 航班号：两位航司代码 + 3~4 位数字，例如 CA1501、MU2331。 */
    private val FLIGHT_PATTERN = Regex("""(?:^|[^A-Za-z0-9])([A-Z]{2}\d{3,4})(?![0-9])""")

    /** 取件码：取件码 / 取货码 后面跟 4~12 位数字字母。 */
    private val PICKUP_PATTERN = Regex("""取[件货]?码[^0-9A-Za-z]{0,6}([0-9A-Za-z\-]{4,12})""")

    /** 时间：09:35 / 9：35。 */
    private val TIME_PATTERN = Regex("""(\d{1,2})[:：](\d{2})""")

    fun parse(
        title: String,
        body: String,
        tripKeywords: List<String>,
        packageKeywords: List<String>,
    ): ParsedNotification? {
        val haystack = "$title\n$body"
        if (haystack.isBlank()) return null

        val hitPackage = packageKeywords.any { it.isNotBlank() && haystack.contains(it) }
        val hitTrip = tripKeywords.any { it.isNotBlank() && haystack.contains(it) }

        val kind = when {
            hitPackage && !hitTrip -> IngestKind.PACKAGE
            hitTrip && !hitPackage -> IngestKind.TRIP

            // 两类关键字同时命中（"您的车票已发出，快递…"这种混排短信），
            // 看有没有取件码来裁决
            hitPackage && hitTrip ->
                if (PICKUP_PATTERN.containsMatchIn(haystack)) IngestKind.PACKAGE else IngestKind.TRIP

            else -> return null
        }

        val code = when (kind) {
            IngestKind.PACKAGE -> PICKUP_PATTERN.find(haystack)?.groupValues?.get(1)
                ?: TRAIN_PATTERN.find(haystack)?.groupValues?.get(1).orEmpty()

            IngestKind.TRIP -> TRAIN_PATTERN.find(haystack)?.groupValues?.get(1)
                ?: FLIGHT_PATTERN.find(haystack)?.groupValues?.get(1).orEmpty()
        }

        return ParsedNotification(
            kind = kind,
            code = code.orEmpty(),
            timeText = TIME_PATTERN.find(haystack)?.value.orEmpty(),
        )
    }
}
