package io.github.leosonus.runningpull.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    private val timesUtcMillis: List<Long>,
    private val temperaturesC: List<Double?>,
    private val humidityPct: List<Double?>
) {
    fun temperatureAt(time: Date): Double? = interpolate(time, temperaturesC)

    fun humidityAt(time: Date): Double? = interpolate(time, humidityPct)

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
 * Open-Meteo의 과거 기상 이력을 가져온다. API 키가 필요 없고 지난 날짜도 조회된다
 * (어제 러닝까지 값이 채워져 있는 것을 실측으로 확인).
 *
 * Garmin이 아니라 Cloudflare 차단과 무관하므로 WebView를 거치지 않고 직접 호출한다.
 * 그래서 별도 HTTP 라이브러리도 필요 없다.
 */
object WeatherClient {

    private const val TAG = "WeatherClient"
    private const val TIMEOUT_MS = 20_000

    /**
     * [aroundUtc] 전후 하루씩 여유를 두고 조회한다. 러닝 시각의 UTC 날짜와 현지 날짜가
     * 어긋나는 경우(예: 한국 새벽 러닝은 UTC로 전날)에도 필요한 시간대가 빠지지 않게 하려는 것.
     */
    suspend fun fetchHourly(latitude: Double, longitude: Double, aroundUtc: Date): HourlyWeather =
        withContext(Dispatchers.IO) {
            val day = 24 * 60 * 60 * 1000L
            val start = DATE_FORMAT.format(Date(aroundUtc.time - day))
            val end = DATE_FORMAT.format(Date(aroundUtc.time + day))

            val url = "https://archive-api.open-meteo.com/v1/archive" +
                "?latitude=$latitude&longitude=$longitude" +
                "&start_date=$start&end_date=$end" +
                "&hourly=temperature_2m,relative_humidity_2m&timezone=UTC"

            val body = get(url)
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw WeatherException("날씨 응답을 해석하지 못했습니다: ${e.message}")
            }
            if (root.has("error")) {
                throw WeatherException(root.optString("reason", "날씨 API 오류"))
            }

            val hourly = root.optJSONObject("hourly")
                ?: throw WeatherException("날씨 응답에 시간별 데이터가 없습니다")

            val times = hourly.optJSONArray("time")
            val temps = hourly.optJSONArray("temperature_2m")
            val humidity = hourly.optJSONArray("relative_humidity_2m")
            if (times == null || times.length() == 0) {
                throw WeatherException("조회한 기간의 날씨 데이터가 비어 있습니다")
            }

            val timeMillis = ArrayList<Long>(times.length())
            val tempList = ArrayList<Double?>(times.length())
            val humidityList = ArrayList<Double?>(times.length())
            for (i in 0 until times.length()) {
                val parsed = HOUR_FORMAT.parse(times.optString(i)) ?: continue
                timeMillis.add(parsed.time)
                tempList.add((temps?.opt(i) as? Number)?.toDouble())
                humidityList.add((humidity?.opt(i) as? Number)?.toDouble())
            }

            HourlyWeather(
                // 요청 좌표가 아니라 응답 좌표를 쓴다. 격자 중심으로 스냅되므로 실제로
                // 어느 지점의 값인지 남겨두는 편이 정확하다.
                latitude = root.optDouble("latitude", latitude),
                longitude = root.optDouble("longitude", longitude),
                timesUtcMillis = timeMillis,
                temperaturesC = tempList,
                humidityPct = humidityList
            )
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
                throw WeatherException("날씨 API가 HTTP $code 를 반환했습니다")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** 응답의 시간 문자열은 `timezone=UTC`로 요청했으므로 UTC 기준이다. */
    private val HOUR_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
