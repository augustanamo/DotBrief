package com.augustana.dotbrief.data.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 天气取数。
 *
 * ## 为什么选 Open-Meteo
 *
 * 它是少数**不需要密钥**的天气接口：不用注册、不用填 API Key，
 * 也就意味着"天气"这件事不依赖用户的任何配置 —— 装完就能用。
 * 对简报这种"每天说一句今天多少度"的需求，用商业天气 API 是杀鸡用牛刀。
 *
 * ## 位置只取"上次已知"
 *
 * 有意**不主动发起定位请求**（`requestLocationUpdates` 那一套）：
 * 那会引入一次可能长达十几秒的等待、一套取消逻辑，
 * 还要处理"用户正在移动时定位抖动"。而天气只要城市粒度 ——
 * 系统里上一次已知位置（多半是某个 App 几分钟前顺手定位留下的）完全够用。
 *
 * 代价是它**可能取不到**：新手机、刚开机、从不定位的用户都可能没有已知位置。
 * 所以失败一律静默降级成"这次没有天气"，绝不因此让整条简报失败 ——
 * 少说一句天气，比点了不出声好得多。
 *
 * ## 一次取两天
 *
 * 白天只用今天的数字，但**明天那份也一起取回来**：入夜之后要改说明天的天气
 * （见 [DayPart]）。等 18 点再发一次请求，要么让"点一下立刻出声"白等一个网络往返，
 * 要么得回头改写已经生成好的简报。多取一天几乎不增加成本，却把这件事整个变简单了。
 */
class WeatherSource(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 粗定位就够天气用（精确到几公里，正好是"这个城市"的粒度）。 */
    fun hasPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    /**
     * 取一次天气。
     *
     * @param includeSunTimes 要不要带日出日落。
     *   它由调用方按"今天是不是第一次播报"决定：日出日落是**今天的框架**，
     *   一天说一次是有用的坐标，说第二遍就是复读。把它放在参数上而不是让下游
     *   各自判断，是为了让"数据本身就已经体现了规则"—— 与 `nowText` 里
     *   有没有日期完全同样的处理方式。
     * @return 第一个元素是天气（成功时非空），第二个是降级说明（失败时非空）；
     *   两者最多只有一个非空。
     */
    suspend fun fetch(includeSunTimes: Boolean): Pair<WeatherInfo?, String?> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext null to "位置权限没给，天气读不到"

            val location = lastKnownLocation()
                ?: return@withContext null to "拿不到定位，天气读不到"

            // 两个接口并行：空气质量是另一个 host（air-quality-api），
            // 串行发等于白白多等一个往返，而这条链路是"点一下就要出声"的。
            val (forecastBody, airBody) = coroutineScope {
                val forecast = async { getText(forecastEndpoint(location)) }
                val air = async { getText(airEndpoint(location)) }
                forecast.await() to air.await()
            }

            if (forecastBody.isNullOrBlank()) return@withContext null to "天气没拉到"

            val weather = parse(forecastBody, includeSunTimes)
                ?: return@withContext null to "天气数据没看懂"

            // 空气质量属于"顺手多问一句"：拿不到就整条不提，绝不能因为它
            // 把已经拿到的天气一起丢掉 —— 那是本末倒置。
            val air = airBody?.let(::parseAirQuality)
            weather.copy(airQuality = air) to null
        }

    /** 发一个 GET 并返回响应体；任何失败（网络、超时、非 2xx）都返回 null。 */
    private fun getText(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w(TAG, "请求失败：$url", error)
        null
    }

    private fun forecastEndpoint(location: Location): String = buildString {
        append(FORECAST_URL)
        // 四位小数（约 10 米）远超需求，但它是"这个点"的完整表达，坐标反正是本机算的
        append("?latitude=").append(String.format(Locale.US, "%.4f", location.latitude))
        append("&longitude=").append(String.format(Locale.US, "%.4f", location.longitude))
        // current 给"现在多少度"，hourly 给"几点会下雨"，daily 给高低温/紫外线/日出日落
        append("&current=temperature_2m,weather_code")
        append("&hourly=precipitation_probability")
        // daily 里的 weather_code 是给**明天**用的：今天的现象 current 已经给了，
        // 而夜里要念"明天什么天气"，只能从这里取。
        append(
            "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,uv_index_max,sunrise,sunset",
        )
        // 两天：今天 + 明天。白天的简报只用得上今天那份，但明天那份必须提前取回来 ——
        // 等 18 点之后才发现没取，就得再发一次请求（见 WeatherInfo.tomorrow 的说明）。
        append("&timezone=auto&forecast_days=2")
    }

    private fun airEndpoint(location: Location): String = buildString {
        append(AIR_URL)
        append("?latitude=").append(String.format(Locale.US, "%.4f", location.latitude))
        append("&longitude=").append(String.format(Locale.US, "%.4f", location.longitude))
        append("&current=us_aqi&timezone=auto")
    }

    /**
     * 上一次已知位置里最新的一条。
     *
     * 遍历所有可用 provider 而不是只看 GPS：室内、地铁上 GPS 常年没有新点，
     * 而网络定位往往刚刚更新过 —— 对天气来说两者一样好用。
     */
    private fun lastKnownLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { manager.getProviders(true) }.getOrNull().orEmpty()

        val newest = providers
            .mapNotNull { provider ->
                // 单个 provider 可能因为被关闭 / 无权限而抛异常，不能让它拖垮整次取数
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return null

        // 太旧的点宁可不用：它的作用只是告诉我们在哪个城市，
        // 而一个昨天在外地留下的点会报出另一个城市的天气 —— 那比不报更糟。
        val age = System.currentTimeMillis() - newest.time
        return if (age <= MAX_FIX_AGE_MS) newest else null
    }

    private fun parse(body: String, includeSunTimes: Boolean): WeatherInfo? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        val current = root["current"] as? JsonObject ?: return@runCatching null

        val temperature = current["temperature_2m"]?.let(::number) ?: return@runCatching null
        val code = current["weather_code"]?.let(::number)?.roundToInt() ?: return@runCatching null

        // 除"现在多少度"之外的东西都算锦上添花：缺了退回保守值就好，
        // 绝不该让整条天气作废 —— 少说一句，比一条天气都没有强。
        val daily = root["daily"] as? JsonObject
        val hourly = root["hourly"] as? JsonObject

        // 取数时刻既用来算"今天剩下来的雨点"，也随数据一起交出去 ——
        // 防晒提醒只在这个点还来得及的时候才说（见 WeatherInfo.uvNeedsCare）。
        val now = LocalDateTime.now()
        val high = numberAt(daily?.get("temperature_2m_max"), TODAY) ?: temperature
        val low = numberAt(daily?.get("temperature_2m_min"), TODAY) ?: temperature
        val rain = numberAt(daily?.get("precipitation_probability_max"), TODAY)

        WeatherInfo(
            condition = conditionOf(code),
            temperature = temperature.roundToInt(),
            high = high.roundToInt(),
            low = low.roundToInt(),
            tomorrow = tomorrowFrom(daily, hourly, includeSunTimes, now),
            rainAtText = firstRainAt(hourly, now.toLocalDate()),
            precipitationProbability = rain?.roundToInt()?.coerceIn(0, 100),
            uvIndex = numberAt(daily?.get("uv_index_max"), TODAY)?.roundToInt(),
            fetchedAtHour = now.hour,
            // 日出日落按需解析：不报的那几次，连字段都不去读
            sunriseText = if (includeSunTimes) sunTextAt(daily, TODAY, "sunrise") else null,
            sunsetText = if (includeSunTimes) sunTextAt(daily, TODAY, "sunset") else null,
        )
    }.onFailure { Log.w(TAG, "解析天气响应失败", it) }.getOrNull()

    private fun parseAirQuality(body: String): Int? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        val current = root["current"] as? JsonObject ?: return@runCatching null
        current["us_aqi"]?.let(::number)?.roundToInt()
    }.onFailure { Log.w(TAG, "解析空气质量失败", it) }.getOrNull()

    /**
     * 某一天里第一个"可能要下雨"的整点，已经口语化。
     *
     * [targetDate] 决定报哪一天的雨：白天报今天，入夜之后报明天。
     *
     * 必须按**日期**卡住：以前只取一天的数据，从下一个整点往后扫就行；
     * 现在一次取两天，不卡日期就会把"明天晚上有雨"当成今天报出来。
     *
     * 同一天里跳过已经过去的整点：正在下雨这件事 `weather_code` 已经说了
     * （"小雨""阵雨"），再补一句"现在前后有雨"是同一件事说两遍。
     *
     * 阈值 50%：低于它提醒带伞属于大惊小怪，而"带伞"这句话说多了就没人听了。
     */
    private fun firstRainAt(hourly: JsonObject?, targetDate: LocalDate): String? {
        val probabilities = hourly?.get("precipitation_probability") as? JsonArray ?: return null
        val times = hourly["time"] as? JsonArray ?: return null
        val nowHour = LocalTime.now().hour
        val today = LocalDate.now()

        probabilities.forEachIndexed { index, element ->
            val probability = (element as? JsonPrimitive)?.doubleOrNull ?: return@forEachIndexed
            if (probability < RAIN_LIKELY_PERCENT) return@forEachIndexed

            // time 的格式是 "2026-09-23T15:00"
            val stamp = (times.getOrNull(index) as? JsonPrimitive)?.content ?: return@forEachIndexed
            val date = stamp.substringBefore('T', "")
                .takeIf { it.length == DATE_LENGTH }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@forEachIndexed
            if (date != targetDate) return@forEachIndexed

            val hour = stamp.substringAfter('T', "").take(2).toIntOrNull() ?: return@forEachIndexed
            if (date == today && hour <= nowHour) return@forEachIndexed
            return speakableHour(hour)
        }
        return null
    }

    /** 15 -> "下午3点"。与 [CalendarSource] 的时间表达走同一套 [SpokenTime]。 */
    private fun speakableHour(hour: Int): String = SpokenTime.hourOf(hour)

    /** "2026-09-23T05:42" -> "凌晨5点42分"（整点只说到点）。 */
    private fun speakableTimeOfDay(raw: String): String? {
        val clock = raw.substringAfter('T', "")
        val hour = clock.substringBefore(':').toIntOrNull() ?: return null
        val minute = clock.substringAfter(':', "").take(2).toIntOrNull() ?: 0
        return SpokenTime.clock(hour, minute)
    }

    /** JsonNull 也是 JsonPrimitive，取不到数就是 null，所以这里转型是安全的。 */
    private fun number(element: JsonElement): Double? =
        (element as? JsonPrimitive)?.doubleOrNull

    /**
     * 取 `daily` 数组里第 [index] 天的一个数字（[TODAY] = 0，[TOMORROW] = 1）。
     *
     * 字段本身不是数组时按"这就是今天那一项"处理：只是多一层容错，
     * 别在接口偶尔换了形状时把整条天气都丢掉。
     */
    private fun numberAt(element: JsonElement?, index: Int): Double? {
        element ?: return null
        val array = element as? JsonArray ?: return if (index == TODAY) number(element) else null
        return array.getOrNull(index)?.let(::number)
    }

    /** 同上，但取字符串（日出日落是 ISO 时间字符串，不是数字）。 */
    private fun stringAt(element: JsonElement?, index: Int): String? {
        element ?: return null
        val array = element as? JsonArray
            ?: return if (index == TODAY) (element as? JsonPrimitive)?.content else null
        return (array.getOrNull(index) as? JsonPrimitive)?.content
    }

    /**
     * 明天的预报。取不到（接口没给第二天、或字段缺项）就返回 null。
     *
     * 温度和天气现象**缺一不可**：只说"明天多云"却报不出温度，那句话没有行动价值 ——
     * 与其念半句，不如让上游退回说今天那一组数字。
     */
    private fun tomorrowFrom(
        daily: JsonObject?,
        hourly: JsonObject?,
        includeSunTimes: Boolean,
        now: LocalDateTime,
    ): DayForecast? {
        daily ?: return null
        val code = numberAt(daily["weather_code"], TOMORROW)?.roundToInt() ?: return null
        val high = numberAt(daily["temperature_2m_max"], TOMORROW) ?: return null
        val low = numberAt(daily["temperature_2m_min"], TOMORROW) ?: return null

        return DayForecast(
            condition = conditionOf(code),
            high = high.roundToInt(),
            low = low.roundToInt(),
            rainAtText = firstRainAt(hourly, now.toLocalDate().plusDays(1)),
            precipitationProbability = numberAt(daily["precipitation_probability_max"], TOMORROW)
                ?.roundToInt()
                ?.coerceIn(0, 100),
            sunriseText = if (includeSunTimes) sunTextAt(daily, TOMORROW, "sunrise") else null,
            sunsetText = if (includeSunTimes) sunTextAt(daily, TOMORROW, "sunset") else null,
        )
    }

    /** `daily` 里的日出 / 日落，取出来就已经是口语（"凌晨5点42分"）。 */
    private fun sunTextAt(daily: JsonObject?, index: Int, key: String): String? =
        stringAt(daily?.get(key), index)?.let(::speakableTimeOfDay)

    private companion object {
        const val TAG = "WeatherSource"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val AIR_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"
        const val TIMEOUT_SECONDS = 8L

        /** 已知位置的最大可接受年龄：6 小时。 */
        const val MAX_FIX_AGE_MS = 6 * 60 * 60 * 1000L

        /** 小时级降水概率到这个数才值得提醒带伞。 */
        const val RAIN_LIKELY_PERCENT = 50

        /** `daily` 里的今天 / 明天：`forecast_days=2` 就是从今天开始的两项。 */
        const val TODAY = 0
        const val TOMORROW = 1

        /** "2026-09-23" 的长度，用来判断时间串里到底有没有日期部分。 */
        const val DATE_LENGTH = 10


        /**
         * WMO 天气代码 -> 中文。
         *
         * 代码表本身 28 档，其中大量是"同一现象的不同强度"。念出来只求听的人
         * 知道要不要带伞，所以刻意做了归并（例如 80/81 都叫阵雨）。
         */
        fun conditionOf(code: Int): String = when (code) {
            0 -> "晴"
            1 -> "晴间多云"
            2 -> "多云"
            3 -> "阴"
            45, 48 -> "有雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻雨"
            61 -> "小雨"
            63 -> "中雨"
            65 -> "大雨"
            66, 67 -> "冻雨"
            71 -> "小雪"
            73 -> "中雪"
            75 -> "大雪"
            77 -> "雪粒"
            80, 81 -> "阵雨"
            82 -> "暴雨"
            85, 86 -> "阵雪"
            95 -> "雷阵雨"
            96, 99 -> "雷阵雨夹冰雹"
            else -> "天气多变"
        }
    }
}
