package io.github.leosonus.runningpull.fit

import com.garmin.fit.Decode
import com.garmin.fit.LapMesg
import com.garmin.fit.LapMesgListener
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FitParseException(message: String) : Exception(message)

/** 러닝 중 특정 거리 지점을 지난 순간. 그 시각의 날씨를 붙이는 데 쓴다. */
data class SplitPoint(
    val distanceKm: Int,
    val timeUtc: Date,
    val latitude: Double?,
    val longitude: Double?
)

/**
 * fit 파싱 결과. JSON 본문과 함께, 날씨 조회에 필요한 좌표·시각을 따로 넘긴다
 * (JSON에서 도로 꺼내 쓰는 것보다 명확하다).
 */
data class FitResult(
    val json: JSONObject,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val startTimeUtc: Date?,
    val endTimeUtc: Date?,
    val splits: List<SplitPoint>
)

/**
 * fit 파일(바이너리)에는 record 단위까지 너무 많은 정보가 들어있어, 세션 요약 + 랩 요약만
 * 추려서 JSON으로 변환한다. VO2max/LT 등 fit에 없는 지표는 이 JSON에 덧붙인다.
 *
 * record는 여전히 JSON에 담지 않지만, **5km 지점을 지난 시각과 좌표를 뽑기 위해** 훑기는 한다.
 * 전부 보관하지 않고 경계를 지나는 순간만 집어내므로 메모리 부담은 없다.
 */
object FitParser {

    /** 몇 미터마다 지점을 찍을지. */
    private const val SPLIT_INTERVAL_M = 5000f

    fun parse(fitBytes: ByteArray): FitResult {
        val sessions = mutableListOf<SessionMesg>()
        val laps = mutableListOf<LapMesg>()
        val splits = mutableListOf<SplitPoint>()

        var nextMilestoneM = SPLIT_INTERVAL_M
        var firstLatitude: Double? = null
        var firstLongitude: Double? = null
        var lastRecordTime: Date? = null

        val broadcaster = MesgBroadcaster(Decode())
        broadcaster.addListener(SessionMesgListener { sessions.add(it) })
        broadcaster.addListener(LapMesgListener { laps.add(it) })
        broadcaster.addListener(RecordMesgListener { record ->
            val time = record.timestamp?.date
            if (time != null) lastRecordTime = time

            val latitude = record.positionLat?.semicirclesToDegrees()
            val longitude = record.positionLong?.semicirclesToDegrees()
            if (firstLatitude == null && latitude != null && longitude != null) {
                firstLatitude = latitude
                firstLongitude = longitude
            }

            val distance = record.distance
            if (distance == null || time == null) return@RecordMesgListener
            // while로 도는 이유: GPS가 튀거나 일시정지 후 재개하면 한 번에 5km를 넘길 수 있다.
            while (distance >= nextMilestoneM) {
                splits.add(
                    SplitPoint((nextMilestoneM / 1000).toInt(), time, latitude, longitude)
                )
                nextMilestoneM += SPLIT_INTERVAL_M
            }
        })

        try {
            broadcaster.run(ByteArrayInputStream(fitBytes))
        } catch (e: Exception) {
            throw FitParseException("fit 파일 파싱 실패: ${e.message}")
        }

        val session = sessions.firstOrNull()
            ?: throw FitParseException("fit 파일에 세션 정보가 없습니다")

        val json = JSONObject()
        json.putIfNotNull("sport", session.sport?.name)
        json.applySessionFields(session)
        json.put("laps", JSONArray().apply { laps.forEach { put(it.toJson()) } })

        val startTime = session.startTime?.date
        return FitResult(
            json = json,
            // 세션에 시작 좌표가 없으면 첫 record 좌표로 대신한다(트레드밀 등은 둘 다 없다).
            startLatitude = session.startPositionLat?.semicirclesToDegrees() ?: firstLatitude,
            startLongitude = session.startPositionLong?.semicirclesToDegrees() ?: firstLongitude,
            startTimeUtc = startTime,
            endTimeUtc = lastRecordTime
                ?: startTime?.let { s ->
                    session.totalElapsedTime?.let { Date(s.time + (it * 1000).toLong()) }
                },
            splits = splits
        )
    }

    private fun JSONObject.applySessionFields(session: SessionMesg) {
        putIfNotNull("startTime", session.startTime?.date?.toIso())
        putIfNotNull("totalElapsedTimeSec", session.totalElapsedTime)
        putIfNotNull("totalDistanceM", session.totalDistance)
        putIfNotNull("totalCalories", session.totalCalories)
        putIfNotNull("avgHeartRate", session.avgHeartRate)
        putIfNotNull("maxHeartRate", session.maxHeartRate)
        // 최신 기기는 속도를 legacy avgSpeed/maxSpeed가 아니라 enhanced 필드에만 기록하는
        // 경우가 있어(Forerunner 965 등), enhanced를 우선하고 없으면 legacy로 대체한다.
        putIfNotNull("avgSpeedMps", session.enhancedAvgSpeed ?: session.avgSpeed)
        putIfNotNull("maxSpeedMps", session.enhancedMaxSpeed ?: session.maxSpeed)
        putIfNotNull("avgRunningCadence", session.avgRunningCadence)
        putIfNotNull("totalAscentM", session.totalAscent)
        putIfNotNull("totalDescentM", session.totalDescent)
        putIfNotNull("totalStrides", session.totalStrides)
        putIfNotNull("avgPowerWatts", session.avgPower)
        putIfNotNull("maxPowerWatts", session.maxPower)
        putIfNotNull("normalizedPowerWatts", session.normalizedPower)
        putIfNotNull("avgGroundContactTimeMs", session.avgStanceTime)
        putIfNotNull("avgStepLengthMm", session.avgStepLength)
        putIfNotNull("avgVerticalOscillationMm", session.avgVerticalOscillation)
        putIfNotNull("avgVerticalRatio", session.avgVerticalRatio)
        putIfNotNull("aerobicTrainingEffect", session.totalTrainingEffect)
        putIfNotNull("anaerobicTrainingEffect", session.totalAnaerobicTrainingEffect)
        // 시계 내장 온도계 값. Forerunner 965는 필드만 있고 값이 비어 있는 경우가 많아
        // 대개 빠지지만, 기록되는 기기/설정을 대비해 있으면 담는다. 손목 체온의 영향을 받아
        // 실제 기온보다 높게 나오므로 기상 데이터(weather)와는 구분해서 봐야 한다.
        putIfNotNull("deviceAvgTemperatureC", session.avgTemperature)
        putIfNotNull("deviceMaxTemperatureC", session.maxTemperature)
        putIfNotNull("deviceMinTemperatureC", session.minTemperature)
    }

    private fun LapMesg.toJson(): JSONObject = JSONObject().apply {
        putIfNotNull("startTime", startTime?.date?.toIso())
        putIfNotNull("totalElapsedTimeSec", totalElapsedTime)
        putIfNotNull("totalDistanceM", totalDistance)
        putIfNotNull("totalCalories", totalCalories)
        putIfNotNull("avgHeartRate", avgHeartRate)
        putIfNotNull("maxHeartRate", maxHeartRate)
        putIfNotNull("avgSpeedMps", enhancedAvgSpeed ?: avgSpeed)
        putIfNotNull("maxSpeedMps", enhancedMaxSpeed ?: maxSpeed)
        putIfNotNull("avgRunningCadence", avgRunningCadence)
        putIfNotNull("totalStrides", totalStrides)
        putIfNotNull("avgPowerWatts", avgPower)
        putIfNotNull("maxPowerWatts", maxPower)
        putIfNotNull("normalizedPowerWatts", normalizedPower)
        putIfNotNull("avgGroundContactTimeMs", avgStanceTime)
        putIfNotNull("avgStepLengthMm", avgStepLength)
        putIfNotNull("avgVerticalOscillationMm", avgVerticalOscillation)
        putIfNotNull("avgVerticalRatio", avgVerticalRatio)
        putIfNotNull("deviceAvgTemperatureC", avgTemperature)
        putIfNotNull("deviceMaxTemperatureC", maxTemperature)
    }

    /** fit 좌표는 semicircle 단위(2^31이 180도)로 저장된다. */
    private fun Int.semicirclesToDegrees(): Double = this * (180.0 / 2147483648.0)

    private fun Date.toIso(): String = ISO_FORMAT.format(this)

    private fun JSONObject.putIfNotNull(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
