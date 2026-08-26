package io.github.leosonus.runningpull.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WeatherException(message: String) : Exception(message)

/**
 * 특정 지점의 시간별 기온/습도. 원본이 정시 단위라, 그 사이 시각은 선형 보간해서 쓴다.
 * 기온은 원래 완만하게 변하므로 보간값이 실제와 크게 어긋나지 않는다.
 */
class HourlyWeather(
    val latitude: Double,
    val longitude: Double,
    /** 어느 데이터셋에서 받았는지. 폴백이 동작하면 값이 달라지므로 JSON에 남긴다. */
    val source: String,
    private val timesUtcMillis: List<Long>,
    private val temperaturesC: List<Double?>,
    private val humidityPct: List<Double?>,
    private val dewPointC: List<Double?>,
    private val feelsLikeC: List<Double?>,
    private val windSpeedMps: List<Double?>,
    private val windDirectionDeg: List<Double?>
) {
    /**
     * 기온 값이 하나라도 채워져 있는지. 응답이 200이어도 값이 전부 null로 오는 경우가 있어
     * (실측: forecast API를 몇 달 전 날짜로 부르면 24개 전부 null), 폴백 판단에 쓴다.
     */
    fun hasUsableData(): Boolean = temperaturesC.any { it != null }

    fun temperatureAt(time: Date): Double? = interpolate(time, temperaturesC)

    fun humidityAt(time: Date): Double? = interpolate(time, humidityPct)

    fun dewPointAt(time: Date): Double? = interpolate(time, dewPointC)

    fun feelsLikeAt(time: Date): Double? = interpolate(time, feelsLikeC)

    fun windSpeedAt(time: Date): Double? = interpolate(time, windSpeedMps)

    /** 풍향은 0~360도를 도는 값이라 선형보간하면 359°↔1° 사이에서 180°로 튀는 오류가 난다.
     * 그래서 가장 가까운 시각의 값을 그대로 쓴다(다른 값들처럼 보간하지 않음). */
    fun windDirectionAt(time: Date): Double? = nearest(time, windDirectionDeg)

    private fun nearest(time: Date, values: List<Double?>): Double? {
        if (timesUtcMillis.isEmpty()) return null
        val target = time.time
        var bestIndex = 0
        var bestDiff = Long.MAX_VALUE
        for (i in timesUtcMillis.indices) {
            val diff = Math.abs(timesUtcMillis[i] - target)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIndex = i
            }
        }
        return values.getOrNull(bestIndex)
    }

    private fun interpolate(time: Date, values: List<Double?>): Double? {
        if (timesUtcMillis.isEmpty()) return null
        val target = time.time

        // 조회 범위를 벗어나면 가장 가까운 끝값으로 대신한다(경계 한 시간을 버리지 않기 위해).
        if (target <= timesUtcMillis.first()) return values.firstOrNull { it != null }
        if (target >= timesUtcMillis.last()) return values.lastOrNull { it != null }

        for (i in 0 until timesUtcMillis.size - 1) {
            val start = timesUtcMillis[i]
            val end = timesUtcMillis[i + 1]
            if (target < start || target > end) continue

            val a = values.getOrNull(i)
            val b = values.getOrNull(i + 1)
            if (a == null) return b
            if (b == null) return a
            if (end == start) return a

            val ratio = (target - start).toDouble() / (end - start).toDouble()
            return a + (b - a) * ratio
        }
        return null
    }
}

/**
 * Open-Meteo에서 시간별 기상 데이터를 가져온다. API 키가 필요 없다.
 *
 * Garmin이 아니라 Cloudflare 차단과 무관하므로 WebView를 거치지 않고 직접 호출한다.
 * 그래서 별도 HTTP 라이브러리도 필요 없다.
 *
 * **소스가 셋이고 커버 범위가 다르다** (2026-08-26 실측):
 * | 순위 | 소스 | 제공자 | 커버 범위 |
 * |---|---|---|---|
 * | 1 | Open-Meteo `archive`(ERA5) | Open-Meteo | 1940-01-01 ~ **오늘** |
 * | 2 | Open-Meteo `forecast` | Open-Meteo | 최근 ~3개월 |
 * | 3 | NASA POWER(MERRA-2) | **NASA** | 1981 ~ **약 5일 전** |
 *
 * 앞의 둘은 **같은 회사**라 그 회사가 죽으면 같이 죽는다. 그래서 완전히 다른 기관인 NASA POWER를
 * 3순위로 둔다(키 불필요). 다만 NASA는 5일가량 지연이 있어 최근 러닝은 못 받는다 — 최근 며칠은
 * 사실상 Open-Meteo가 유일한 무료·무키 소스다.
 *
 * 앞에서부터 시도해 성공하면 멈춘다. 어느 쪽을 썼는지는 [HourlyWeather.source]에 남겨 JSON에서
 * 구분할 수 있게 한다.
 */
object WeatherClient {

    private const val TAG = "WeatherClient"
    private const val TIMEOUT_MS = 20_000

    /** 일시적 실패 재시도 횟수/간격. Garmin 쪽(`GarminWebBridge`)과 같은 정책으로 맞춘다. */
    private const val MAX_ATTEMPTS = 3
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)

    private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val NASA_POWER_URL = "https://power.larc.nasa.gov/api/temporal/hourly/point"

    const val SOURCE_ARCHIVE = "OPEN_METEO_ARCHIVE_ERA5"
    const val SOURCE_FORECAST = "OPEN_METEO_FORECAST"
    const val SOURCE_NASA_POWER = "NASA_POWER_MERRA2"

    /** NASA POWER가 결측을 표시하는 값. 그대로 쓰면 -999°C가 되어버린다. */
    private const val NASA_FILL_VALUE = -999.0

    /**
     * [aroundUtc] 전후 하루씩 여유를 두고 조회한다. 러닝 시각의 UTC 날짜와 현지 날짜가
     * 어긋나는 경우(예: 한국 새벽 러닝은 UTC로 전날)에도 필요한 시간대가 빠지지 않게 하려는 것.
     *
     * 하나가 실패해도 다른 하나로 살려본다. 예전에는 archive 하나만 썼는데, **오늘 뛴 러닝을
     * 그날 바로 가져오면 `end_date`(=내일)가 archive 허용 범위를 넘어 HTTP 400이 나고 날씨가
     * 통째로 빠졌다.** 아침에 뛰고 바로 받는 게 가장 흔한 사용이라 실패가 잦았다.
     */
    suspend fun fetchHourly(latitude: Double, longitude: Double, aroundUtc: Date): HourlyWeather =
        withContext(Dispatchers.IO) {
            val failures = mutableListOf<String>()

            val attempts: List<Pair<String, suspend () -> HourlyWeather>> = listOf(
                SOURCE_ARCHIVE to {
                    fetchOpenMeteo(ARCHIVE_URL, SOURCE_ARCHIVE, latitude, longitude, aroundUtc)
                },
                SOURCE_FORECAST to {
                    fetchOpenMeteo(FORECAST_URL, SOURCE_FORECAST, latitude, longitude, aroundUtc)
                },
                SOURCE_NASA_POWER to { fetchNasaPower(latitude, longitude, aroundUtc) }
            )

            for ((source, fetch) in attempts) {
                try {
                    val weather = fetch()
                    if (weather.hasUsableData()) return@withContext weather
                    failures.add("$source: 값이 전부 비어 있음")
                    Log.w(TAG, "$source 응답에 값이 없어 다음 소스로 넘어갑니다")
                } catch (e: Exception) {
                    failures.add("$source: ${e.message ?: e}")
                    Log.w(TAG, "$source 조회 실패: ${e.message}")
                }
            }
            throw WeatherException("날씨를 가져오지 못했습니다 (${failures.joinToString(" / ")})")
        }

    /**
     * NASA POWER(MERRA-2). Open-Meteo가 통째로 죽었을 때를 위한 **다른 기관** 소스다.
     *
     * 주의할 점 셋:
     * - **기본 시간 기준이 LST(지방 태양시)다.** 그대로 쓰면 UTC와 8시간 넘게 어긋난다
     *   (실측: 같은 키 `2026021600`이 LST면 -2.83°C, UTC면 -0.48°C). `time-standard=UTC`를
     *   반드시 붙여야 한다.
     * - 결측을 null이 아니라 [NASA_FILL_VALUE](-999.0)로 채워 보낸다.
     * - 이슬점·체감온도는 제공하지 않는다(기온·습도·풍속·풍향 넷뿐). 없는 값은 비워둔다.
     * - 풍속이 이미 m/s라 Open-Meteo와 달리 ÷3.6 보정을 하면 안 된다.
     */
    private suspend fun fetchNasaPower(
        latitude: Double,
        longitude: Double,
        aroundUtc: Date
    ): HourlyWeather {
        val (start, end) = requestWindow(aroundUtc, COMPACT_DATE_FORMAT)
        val url = "$NASA_POWER_URL?parameters=T2M,RH2M,WS10M,WD10M&community=RE" +
            "&longitude=$longitude&latitude=$latitude&start=$start&end=$end" +
            "&format=JSON&time-standard=UTC"

        val root = parseJson(getWithRetry(url))
        val parameter = root.optJSONObject("properties")?.optJSONObject("parameter")
            ?: throw WeatherException("NASA POWER 응답에 시간별 데이터가 없습니다")

        val temps = parameter.optJSONObject("T2M")
            ?: throw WeatherException("NASA POWER 응답에 기온이 없습니다")
        val humidity = parameter.optJSONObject("RH2M")
        val windSpeed = parameter.optJSONObject("WS10M")
        val windDirection = parameter.optJSONObject("WD10M")

        // 키가 "yyyyMMddHH"라 문자열 정렬이 곧 시간순 정렬이다.
        val keys = temps.keys().asSequence().toList().sorted()
        if (keys.isEmpty()) throw WeatherException("NASA POWER 응답이 비어 있습니다")

        val timeMillis = ArrayList<Long>(keys.size)
        val tempList = ArrayList<Double?>(keys.size)
        val humidityList = ArrayList<Double?>(keys.size)
        val windSpeedList = ArrayList<Double?>(keys.size)
        val windDirectionList = ArrayList<Double?>(keys.size)
        for (key in keys) {
            val parsed = HOUR_COMPACT_FORMAT.parse(key) ?: continue
            timeMillis.add(parsed.time)
            tempList.add(temps.nasaValue(key))
            humidityList.add(humidity?.nasaValue(key))
            windSpeedList.add(windSpeed?.nasaValue(key))
            windDirectionList.add(windDirection?.nasaValue(key))
        }

        val blanks = List<Double?>(timeMillis.size) { null }
        return HourlyWeather(
            latitude = latitude,
            longitude = longitude,
            source = SOURCE_NASA_POWER,
            timesUtcMillis = timeMillis,
            temperaturesC = tempList,
            humidityPct = humidityList,
            dewPointC = blanks,
            feelsLikeC = blanks,
            windSpeedMps = windSpeedList,
            windDirectionDeg = windDirectionList
        )
    }

    /** -999.0(결측)을 null로 걸러낸다. */
    private fun JSONObject.nasaValue(key: String): Double? {
        val v = opt(key)
        if (v !is Number) return null
        val d = v.toDouble()
        return if (d <= NASA_FILL_VALUE + 1) null else d
    }

    /**
     * 러닝 시각 전후 하루씩. 다만 **끝은 오늘로 자른다** — 러닝은 이미 지난 일이라 오늘까지면
     * 충분한데, 자르지 않으면 오늘 뛴 러닝에서 끝 날짜가 내일이 되어 API가
     * "out of allowed range"로 통째로 거절한다(이 때문에 당일 러닝의 날씨가 늘 비어 있었다).
     */
    private fun requestWindow(aroundUtc: Date, format: SimpleDateFormat): Pair<String, String> {
        val day = 24 * 60 * 60 * 1000L
        val start = format.format(Date(aroundUtc.time - day))
        val requestedEnd = Date(aroundUtc.time + day)
        val today = Date()
        val end = format.format(if (requestedEnd.after(today)) today else requestedEnd)
        return start to end
    }

    private fun parseJson(body: String): JSONObject {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw WeatherException("날씨 응답을 해석하지 못했습니다: ${e.message}")
        }
        if (root.optBoolean("error", false)) {
            throw WeatherException(root.optString("reason", "날씨 API 오류"))
        }
        return root
    }

    private suspend fun fetchOpenMeteo(
        base: String,
        source: String,
        latitude: Double,
        longitude: Double,
        aroundUtc: Date
    ): HourlyWeather {
        val (start, end) = requestWindow(aroundUtc, DATE_FORMAT)

        val url = "$base?latitude=$latitude&longitude=$longitude" +
            "&start_date=$start&end_date=$end" +
            "&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
            "wind_speed_10m,wind_direction_10m&timezone=UTC"

        val root = parseJson(getWithRetry(url))

        val hourly = root.optJSONObject("hourly")
            ?: throw WeatherException("날씨 응답에 시간별 데이터가 없습니다")

        val times = hourly.optJSONArray("time")
        val temps = hourly.optJSONArray("temperature_2m")
        val humidity = hourly.optJSONArray("relative_humidity_2m")
        val dewPoint = hourly.optJSONArray("dew_point_2m")
        val feelsLike = hourly.optJSONArray("apparent_temperature")
        // Open-Meteo는 풍속을 km/h로 준다. m/s로 통일하려면 ÷3.6 해야 한다.
        val windSpeedKmh = hourly.optJSONArray("wind_speed_10m")
        val windDirection = hourly.optJSONArray("wind_direction_10m")
        if (times == null || times.length() == 0) {
            throw WeatherException("조회한 기간의 날씨 데이터가 비어 있습니다")
        }

        val timeMillis = ArrayList<Long>(times.length())
        val tempList = ArrayList<Double?>(times.length())
        val humidityList = ArrayList<Double?>(times.length())
        val dewPointList = ArrayList<Double?>(times.length())
        val feelsLikeList = ArrayList<Double?>(times.length())
        val windSpeedList = ArrayList<Double?>(times.length())
        val windDirectionList = ArrayList<Double?>(times.length())
        for (i in 0 until times.length()) {
            val parsed = HOUR_FORMAT.parse(times.optString(i)) ?: continue
            timeMillis.add(parsed.time)
            tempList.add((temps?.opt(i) as? Number)?.toDouble())
            humidityList.add((humidity?.opt(i) as? Number)?.toDouble())
            dewPointList.add((dewPoint?.opt(i) as? Number)?.toDouble())
            feelsLikeList.add((feelsLike?.opt(i) as? Number)?.toDouble())
            windSpeedList.add((windSpeedKmh?.opt(i) as? Number)?.toDouble()?.div(3.6))
            windDirectionList.add((windDirection?.opt(i) as? Number)?.toDouble())
        }

        return HourlyWeather(
            // 요청 좌표가 아니라 응답 좌표를 쓴다. 격자 중심으로 스냅되므로 실제로
            // 어느 지점의 값인지 남겨두는 편이 정확하다.
            latitude = root.optDouble("latitude", latitude),
            longitude = root.optDouble("longitude", longitude),
            source = source,
            timesUtcMillis = timeMillis,
            temperaturesC = tempList,
            humidityPct = humidityList,
            dewPointC = dewPointList,
            feelsLikeC = feelsLikeList,
            windSpeedMps = windSpeedList,
            windDirectionDeg = windDirectionList
        )
    }

    /**
     * 일시적인 실패(네트워크 끊김, 5xx, 429)는 잠깐 쉬었다 다시 시도한다. Garmin 쪽
     * (`GarminWebBridge`)은 진작 이렇게 하고 있었는데 날씨만 단발 요청이라, **네트워크가 한 번
     * 삐끗하면 그 러닝의 날씨가 영영 비었다.** 하루에 러닝 여러 건을 받을 때 특히 티가 났다.
     *
     * 400처럼 다시 해도 결과가 같을 실패는 재시도하지 않는다 — 그 시간에 다음 소스로 넘어가는
     * 편이 낫다.
     */
    private suspend fun getWithRetry(url: String): String {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
            try {
                return get(url)
            } catch (e: WeatherHttpException) {
                if (!e.retryable) throw WeatherException(e.message ?: "날씨 API 오류")
                lastError = e
                Log.w(TAG, "날씨 요청 실패 (${attempt + 1}/$MAX_ATTEMPTS): ${e.message}")
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "날씨 요청 실패 (${attempt + 1}/$MAX_ATTEMPTS): ${e.message}")
            }
        }
        throw WeatherException("날씨 요청에 실패했습니다 (${lastError?.message})")
    }

    /** HTTP 상태를 함께 들고 있는 실패. 재시도할 가치가 있는지 판단하는 데 쓴다. */
    private class WeatherHttpException(val code: Int, message: String) : Exception(message) {
        /** 5xx는 서버가 잠시 흔들린 것, 429는 잠깐 몰린 것 — 둘 다 기다렸다 하면 될 수 있다. */
        val retryable: Boolean get() = code >= 500 || code == 429
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "날씨 API HTTP $code: ${body.take(300)}")
                throw WeatherHttpException(code, "날씨 API가 HTTP $code 를 반환했습니다")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** NASA POWER는 날짜를 구분자 없이 받는다. */
    private val COMPACT_DATE_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** 응답의 시간 문자열은 `timezone=UTC`로 요청했으므로 UTC 기준이다. */
    private val HOUR_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** NASA POWER의 시간 키. `time-standard=UTC`로 요청했으므로 UTC 기준이다. */
    private val HOUR_COMPACT_FORMAT = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
