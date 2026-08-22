package com.example.runningpull.fit

import com.garmin.fit.Decode
import com.garmin.fit.LapMesg
import com.garmin.fit.LapMesgListener
import com.garmin.fit.MesgBroadcaster
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

/**
 * fit 파일(바이너리)에는 record 단위까지 너무 많은 정보가 들어있어, 세션 요약 + 랩 요약만
 * 추려서 JSON으로 변환한다. VO2max/LT 등 fit에 없는 지표는 Phase 5에서 이 JSON에 덧붙인다.
 */
object FitParser {

    fun parseToJson(fitBytes: ByteArray): JSONObject {
        val sessions = mutableListOf<SessionMesg>()
        val laps = mutableListOf<LapMesg>()

        val broadcaster = MesgBroadcaster(Decode())
        broadcaster.addListener(SessionMesgListener { sessions.add(it) })
        broadcaster.addListener(LapMesgListener { laps.add(it) })

        try {
            broadcaster.run(ByteArrayInputStream(fitBytes))
        } catch (e: Exception) {
            throw FitParseException("fit 파일 파싱 실패: ${e.message}")
        }

        val session = sessions.firstOrNull()
            ?: throw FitParseException("fit 파일에 세션 정보가 없습니다")

        val result = JSONObject()
        result.putIfNotNull("sport", session.sport?.name)
        result.applySessionFields(session)
        result.put("laps", JSONArray().apply { laps.forEach { put(it.toJson()) } })
        return result
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
    }

    private fun Date.toIso(): String = ISO_FORMAT.format(this)

    private fun JSONObject.putIfNotNull(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
