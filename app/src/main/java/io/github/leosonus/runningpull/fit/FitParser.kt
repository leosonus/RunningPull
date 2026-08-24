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
import kotlin.math.roundToInt

class FitParseException(message: String) : Exception(message)

/** 러닝 중 특정 거리 지점을 지난 순간. 그 시각의 날씨를 붙이는 데 쓴다. */
data class SplitPoint(
    val distanceKm: Int,
    val timeUtc: Date,
    val latitude: Double?,
    val longitude: Double?,
    /** 목표 거리(distanceKm*1000)가 아니라 그 순간 실제 누적 거리. GPS가 튀면 목표와 다를 수 있다. */
    val actualDistanceM: Double
)

/** record 하나(대략 1초 간격)를 스키마의 records[] 항목 그대로 담은 것. */
data class RunningRecord(
    val timestampUtc: Date,
    val distanceM: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val elevationM: Double?,
    val speedMps: Double?,
    val heartRateBpm: Int?,
    val cadenceFitRpm: Double?,
    val powerW: Int?,
    val groundContactTimeMs: Double?,
    val stepLengthMm: Double?,
    val verticalOscillationMm: Double?,
    val verticalRatioPct: Double?
)

/**
 * fit 파싱 결과. JSON은 FIT 파일만으로 채울 수 있는 부분(스키마 v3의 대부분 블록 + laps[] +
 * records[])까지 다 채워서 넘긴다. athleteContext/physiology/derivedIntensityContext/weather처럼
 * Garmin API·날씨 API 조회가 필요한 블록은 MainActivity가 이 JSON에 이어서 채운다.
 *
 * summary 지표 일부(avgHeartRate 등)는 derivedIntensityContext 계산에 다시 필요해서, JSON을
 * 도로 파싱하지 않도록 별도 필드로도 노출한다.
 */
data class FitResult(
    val json: JSONObject,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val startTimeUtc: Date?,
    val endTimeUtc: Date?,
    val splits: List<SplitPoint>,
    val fitTemperatureAvailable: Boolean,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgPaceSecPerKm: Double?,
    val avgPowerWatts: Int?,
    val normalizedPowerWatts: Int?
)

/**
 * fit 파일(바이너리)을 "AI 분석용" v3 스키마 JSON으로 변환한다. 세션/랩 요약뿐 아니라 초 단위
 * record 전체(records[])도 담는다 — 심박 드리프트, 오르막 영향, 케이던스 변화, 질주 구간까지
 * 분석하려면 요약만으로는 부족하기 때문이다.
 *
 * 5km 지점을 지난 시각·좌표(splits)는 날씨 조회용으로 별도 추출한다.
 */
object FitParser {

    /** 몇 미터마다 날씨 지점을 찍을지. MainActivity의 weather.samplingIntervalM에도 그대로 쓴다. */
    const val SPLIT_INTERVAL_M = 5000f

    /**
     * records[]를 몇 초 간격으로 남길지. fit은 보통 1초마다 record가 찍히는데, 그대로 다 담으면
     * (예: 50분 러닝 → 3000개) JSON이 지나치게 커진다. 분석에 필요한 추세는 10초 간격으로도
     * 충분히 보이므로 다운샘플링한다. 시작 record와 마지막(결승) record는 간격에 안 맞아도
     * 항상 포함한다.
     */
    private const val RECORD_SAMPLE_INTERVAL_SEC = 10L

    fun parse(fitBytes: ByteArray): FitResult {
        val sessions = mutableListOf<SessionMesg>()
        val laps = mutableListOf<LapMesg>()
        val records = mutableListOf<RunningRecord>()
        val splits = mutableListOf<SplitPoint>()

        var nextMilestoneM = SPLIT_INTERVAL_M
        var firstLatitude: Double? = null
        var firstLongitude: Double? = null
        var firstSplitEmitted = false
        var lastRecordTime: Date? = null
        var totalFitRecordCount = 0
        var nextRecordSampleTimeMs: Long? = null
        var lastRunningRecord: RunningRecord? = null

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
            if (time != null) {
                totalFitRecordCount++
                val runningRecord = record.toRunningRecord(time)
                lastRunningRecord = runningRecord
                val threshold = nextRecordSampleTimeMs
                if (threshold == null || time.time >= threshold) {
                    records.add(runningRecord)
                    nextRecordSampleTimeMs = time.time + RECORD_SAMPLE_INTERVAL_SEC * 1000L
                }
            }

            if (distance == null || time == null) return@RecordMesgListener

            // 0km 지점(사실상 첫 record)도 날씨 샘플에 넣어야 하므로 한 번만 먼저 찍는다.
            if (!firstSplitEmitted) {
                firstSplitEmitted = true
                splits.add(SplitPoint(0, time, latitude, longitude, distance.toDouble()))
            }
            // while로 도는 이유: GPS가 튀거나 일시정지 후 재개하면 한 번에 5km를 넘길 수 있다.
            while (distance >= nextMilestoneM) {
                splits.add(
                    SplitPoint((nextMilestoneM / 1000).toInt(), time, latitude, longitude, distance.toDouble())
                )
                nextMilestoneM += SPLIT_INTERVAL_M
            }
        })

        try {
            broadcaster.run(ByteArrayInputStream(fitBytes))
        } catch (e: Exception) {
            throw FitParseException("fit 파일 파싱 실패: ${e.message}")
        }

        // 결승 시점 record가 10초 간격에 안 맞아 빠졌을 수 있으니 항상 마지막 record를 보장한다.
        val finalRecord = lastRunningRecord
        if (finalRecord != null && records.lastOrNull()?.timestampUtc != finalRecord.timestampUtc) {
            records.add(finalRecord)
        }

        val session = sessions.firstOrNull()
            ?: throw FitParseException("fit 파일에 세션 정보가 없습니다")

        val avgSpeed = session.enhancedAvgSpeed ?: session.avgSpeed
        val maxSpeed = session.enhancedMaxSpeed ?: session.maxSpeed
        val avgPaceSecPerKm = avgSpeed?.let { if (it > 0f) 1000.0 / it else null }
        val maxInstantPaceSecPerKm = maxSpeed?.let { if (it > 0f) 1000.0 / it else null }

        val json = JSONObject()
        json.put("activity", buildActivityBlock(session))
        json.put("summary", buildSummaryBlock(session, avgPaceSecPerKm, maxInstantPaceSecPerKm))
        json.put("timing", buildTimingBlock(session, records))
        json.put("distance", buildDistanceBlock(session))
        json.put("paceSpeed", buildPaceSpeedBlock(avgSpeed, maxSpeed, avgPaceSecPerKm, maxInstantPaceSecPerKm))
        json.put("heartRate", JSONObject().apply {
            putIfNotNull("avgBpm", session.avgHeartRate?.toInt())
            putIfNotNull("maxBpm", session.maxHeartRate?.toInt())
        })
        json.put("cadence", buildCadenceBlock(session))
        json.put("power", buildPowerBlock(session))
        json.put("elevation", buildElevationBlock(session, records))
        json.put("gps", buildGpsBlock(session, records, firstLatitude, firstLongitude))
        json.put("runningDynamics", JSONObject().apply {
            putIfNotNull("avgGroundContactTimeMs", session.avgStanceTime.rounded(1))
            putIfNotNull("avgStepLengthMm", session.avgStepLength.rounded(1))
            putIfNotNull("avgVerticalOscillationMm", session.avgVerticalOscillation.rounded(1))
            putIfNotNull("avgVerticalRatioPct", session.avgVerticalRatio.rounded(2))
        })
        json.put("training", JSONObject().apply {
            putIfNotNull("aerobicTrainingEffect", session.totalTrainingEffect.rounded(1))
            putIfNotNull("anaerobicTrainingEffect", session.totalAnaerobicTrainingEffect.rounded(1))
            // FIT에 "trainingLoadPeak"라는 필드는 없다. Training Stress Score가 값 범위·의미상
            // 가장 가까운 대체 지표라 여기 매핑한다(엄밀히는 동일 지표가 아님).
            putIfNotNull("trainingLoadPeak", session.trainingStressScore.rounded(2))
            putIfNotNull("calories", session.totalCalories)
            putIfNotNull("totalStrides", session.totalStrides)
        })
        json.put("source", JSONObject().apply {
            put("fitRecordCount", totalFitRecordCount)
            put("recordsIncludedCount", records.size)
            put("recordSamplingIntervalSec", RECORD_SAMPLE_INTERVAL_SEC)
            put("fitTemperatureAvailable", session.avgTemperature != null)
        })
        json.put("laps", JSONArray().apply {
            var cumulativeDistanceM = 0.0
            laps.forEachIndexed { index, lap ->
                val lapDistance = (lap.totalDistance ?: 0f).toDouble()
                put(lap.toJson(index + 1, cumulativeDistanceM, cumulativeDistanceM + lapDistance))
                cumulativeDistanceM += lapDistance
            }
        })
        json.put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })

        val startTime = session.startTime?.date
        return FitResult(
            json = json,
            startLatitude = session.startPositionLat?.semicirclesToDegrees() ?: firstLatitude,
            startLongitude = session.startPositionLong?.semicirclesToDegrees() ?: firstLongitude,
            startTimeUtc = startTime,
            endTimeUtc = lastRecordTime
                ?: startTime?.let { s ->
                    session.totalElapsedTime?.let { Date(s.time + (it * 1000).toLong()) }
                },
            splits = splits,
            fitTemperatureAvailable = session.avgTemperature != null,
            avgHeartRate = session.avgHeartRate?.toInt(),
            maxHeartRate = session.maxHeartRate?.toInt(),
            avgPaceSecPerKm = avgPaceSecPerKm,
            avgPowerWatts = session.avgPower,
            normalizedPowerWatts = session.normalizedPower
        )
    }

    private fun buildActivityBlock(session: SessionMesg): JSONObject = JSONObject().apply {
        putIfNotNull("sport", session.sport?.name)
        putIfNotNull("startTimeUtc", session.startTime?.date?.toIso())
        putIfNotNull("startTimeLocal", session.startTime?.date?.toLocalIso())
        val endTime = session.startTime?.date?.let { s ->
            session.totalElapsedTime?.let { Date(s.time + (it * 1000).toLong()) }
        }
        putIfNotNull("endTimeUtc", endTime?.toIso())
        putIfNotNull("endTimeLocal", endTime?.toLocalIso())
    }

    private fun buildSummaryBlock(
        session: SessionMesg,
        avgPaceSecPerKm: Double?,
        maxInstantPaceSecPerKm: Double?
    ): JSONObject = JSONObject().apply {
        putIfNotNull("totalElapsedTimeSec", session.totalElapsedTime.rounded(3))
        putIfNotNull("totalTimerTimeSec", session.totalTimerTime.rounded(3))
        if (session.totalElapsedTime != null && session.totalTimerTime != null) {
            put("pausedTimeSec", round2((session.totalElapsedTime - session.totalTimerTime).toDouble()))
        }
        putIfNotNull("totalDistanceM", session.totalDistance.rounded(2))
        session.totalDistance?.let { put("totalDistanceKm", round3(it / 1000.0)) }
        putIfNotNull("totalCalories", session.totalCalories)
        putIfNotNull("avgSpeedMps", (session.enhancedAvgSpeed ?: session.avgSpeed).rounded(3))
        putIfNotNull("maxSpeedMps", (session.enhancedMaxSpeed ?: session.maxSpeed).rounded(3))
        avgPaceSecPerKm?.let { put("avgPace", it.toPaceString()); put("avgPaceSecPerKm", round1(it)) }
        maxInstantPaceSecPerKm?.let {
            put("maxInstantPace", it.toPaceString())
            put("maxInstantPaceSecPerKm", round1(it))
        }
        putIfNotNull("avgHeartRate", session.avgHeartRate?.toInt())
        putIfNotNull("maxHeartRate", session.maxHeartRate?.toInt())
        session.avgRunningCadence?.let {
            put("avgRunningCadenceFitRpm", it.toDouble())
            put("avgRunningCadenceSpm", round1(it * 2.0))
        }
        session.maxRunningCadence?.let {
            put("maxRunningCadenceFitRpm", it.toDouble())
            put("maxRunningCadenceSpm", round1(it * 2.0))
        }
        putIfNotNull("totalStrides", session.totalStrides)
        putIfNotNull("avgPowerWatts", session.avgPower)
        putIfNotNull("maxPowerWatts", session.maxPower)
        putIfNotNull("normalizedPowerWatts", session.normalizedPower)
        putIfNotNull("totalWorkJ", session.totalWork)
        putIfNotNull("totalAscentM", session.totalAscent)
        putIfNotNull("totalDescentM", session.totalDescent)
        putIfNotNull("avgGroundContactTimeMs", session.avgStanceTime.rounded(1))
        putIfNotNull("avgStepLengthMm", session.avgStepLength.rounded(1))
        putIfNotNull("avgVerticalOscillationMm", session.avgVerticalOscillation.rounded(1))
        putIfNotNull("avgVerticalRatio", session.avgVerticalRatio.rounded(2))
        putIfNotNull("aerobicTrainingEffect", session.totalTrainingEffect.rounded(1))
        putIfNotNull("anaerobicTrainingEffect", session.totalAnaerobicTrainingEffect.rounded(1))
        putIfNotNull("trainingLoadPeak", session.trainingStressScore.rounded(2))
        // 손목 온도계 값. 체온 영향을 받아 실제 기온이 아니므로 weather 블록과는 구분해서 봐야 한다.
        putIfNotNull("deviceAvgTemperatureC", session.avgTemperature?.toDouble())
        putIfNotNull("deviceMaxTemperatureC", session.maxTemperature?.toDouble())
        putIfNotNull("deviceMinTemperatureC", session.minTemperature?.toDouble())
    }

    /** records[]에 남은 항목들의 타임스탬프 간격 중앙값(초). 다운샘플링 결과라 보통
     * RECORD_SAMPLE_INTERVAL_SEC(10)에 가깝다. */
    private fun buildTimingBlock(session: SessionMesg, records: List<RunningRecord>): JSONObject =
        JSONObject().apply {
            putIfNotNull("startTimeUtc", session.startTime?.date?.toIso())
            putIfNotNull("startTimeLocal", session.startTime?.date?.toLocalIso())
            val endTime = session.startTime?.date?.let { s ->
                session.totalElapsedTime?.let { Date(s.time + (it * 1000).toLong()) }
            }
            putIfNotNull("endTimeUtc", endTime?.toIso())
            putIfNotNull("endTimeLocal", endTime?.toLocalIso())
            putIfNotNull("elapsedSec", session.totalElapsedTime.rounded(3))
            putIfNotNull("timerSec", session.totalTimerTime.rounded(3))
            if (session.totalElapsedTime != null && session.totalTimerTime != null) {
                put("pausedSec", round2((session.totalElapsedTime - session.totalTimerTime).toDouble()))
            }
            medianIntervalSec(records)?.let { put("recordIntervalMedianSec", round1(it)) }
        }

    private fun medianIntervalSec(records: List<RunningRecord>): Double? {
        if (records.size < 2) return null
        val deltas = records.zipWithNext { a, b ->
            (b.timestampUtc.time - a.timestampUtc.time) / 1000.0
        }.sorted()
        if (deltas.isEmpty()) return null
        val mid = deltas.size / 2
        return if (deltas.size % 2 == 0) (deltas[mid - 1] + deltas[mid]) / 2.0 else deltas[mid]
    }

    private fun buildDistanceBlock(session: SessionMesg): JSONObject = JSONObject().apply {
        putIfNotNull("totalM", session.totalDistance.rounded(2))
        session.totalDistance?.let { put("totalKm", round3(it / 1000.0)) }
    }

    private fun buildPaceSpeedBlock(
        avgSpeed: Float?,
        maxSpeed: Float?,
        avgPaceSecPerKm: Double?,
        maxInstantPaceSecPerKm: Double?
    ): JSONObject = JSONObject().apply {
        putIfNotNull("avgSpeedMps", avgSpeed.rounded(3))
        avgPaceSecPerKm?.let { put("avgPaceSecPerKm", round1(it)); put("avgPace", it.toPaceString()) }
        putIfNotNull("maxSpeedMps", maxSpeed.rounded(3))
        maxInstantPaceSecPerKm?.let {
            put("maxInstantPaceSecPerKm", round1(it))
            put("maxInstantPace", it.toPaceString())
        }
    }

    private fun buildCadenceBlock(session: SessionMesg): JSONObject = JSONObject().apply {
        putIfNotNull("avgFitRpm", session.avgRunningCadence?.toDouble())
        session.avgRunningCadence?.let { put("avgSpm", round1(it * 2.0)) }
        putIfNotNull("maxFitRpm", session.maxRunningCadence?.toDouble())
        session.maxRunningCadence?.let { put("maxSpm", round1(it * 2.0)) }
        put(
            "note",
            "Garmin FIT running cadence is stored as cycles/rpm-style cadence; " +
                "avgSpm/maxSpm are doubled for full-step cadence."
        )
    }

    private fun buildPowerBlock(session: SessionMesg): JSONObject = JSONObject().apply {
        putIfNotNull("avgW", session.avgPower)
        putIfNotNull("maxW", session.maxPower)
        putIfNotNull("normalizedW", session.normalizedPower)
        putIfNotNull("totalWorkJ", session.totalWork)
    }

    /** 세션에 고도 통계가 없으면(드묾) record에서 직접 계산해 대신한다. */
    private fun buildElevationBlock(session: SessionMesg, records: List<RunningRecord>): JSONObject {
        val elevations = records.mapNotNull { it.elevationM }
        val avgM = session.enhancedAvgAltitude ?: session.avgAltitude
        val minM = session.enhancedMinAltitude ?: session.minAltitude
        val maxM = session.enhancedMaxAltitude ?: session.maxAltitude
        return JSONObject().apply {
            putIfNotNull("startM", elevations.firstOrNull()?.let { round1(it) })
            putIfNotNull("endM", elevations.lastOrNull()?.let { round1(it) })
            putIfNotNull("minM", minM.rounded(1) ?: elevations.minOrNull()?.let { round1(it) })
            putIfNotNull("maxM", maxM.rounded(1) ?: elevations.maxOrNull()?.let { round1(it) })
            putIfNotNull(
                "avgM",
                avgM.rounded(1) ?: elevations.takeIf { it.isNotEmpty() }?.average()?.let { round1(it) }
            )
            putIfNotNull("ascentM", session.totalAscent)
            putIfNotNull("descentM", session.totalDescent)
        }
    }

    /** 세션에 시작/끝/경계상자 GPS가 없으면(드묾) record에서 직접 계산해 대신한다. */
    private fun buildGpsBlock(
        session: SessionMesg,
        records: List<RunningRecord>,
        firstLatitude: Double?,
        firstLongitude: Double?
    ): JSONObject {
        val withPosition = records.filter { it.latitude != null && it.longitude != null }
        val startLat = session.startPositionLat?.semicirclesToDegrees() ?: firstLatitude
        val startLon = session.startPositionLong?.semicirclesToDegrees() ?: firstLongitude
        val endLat = session.endPositionLat?.semicirclesToDegrees() ?: withPosition.lastOrNull()?.latitude
        val endLon = session.endPositionLong?.semicirclesToDegrees() ?: withPosition.lastOrNull()?.longitude
        val necLat = session.necLat?.semicirclesToDegrees() ?: withPosition.mapNotNull { it.latitude }.maxOrNull()
        val necLon = session.necLong?.semicirclesToDegrees() ?: withPosition.mapNotNull { it.longitude }.maxOrNull()
        val swcLat = session.swcLat?.semicirclesToDegrees() ?: withPosition.mapNotNull { it.latitude }.minOrNull()
        val swcLon = session.swcLong?.semicirclesToDegrees() ?: withPosition.mapNotNull { it.longitude }.minOrNull()

        return JSONObject().apply {
            if (startLat != null && startLon != null) {
                put("start", JSONObject().apply { put("latitude", startLat); put("longitude", startLon) })
            }
            if (endLat != null && endLon != null) {
                put("end", JSONObject().apply { put("latitude", endLat); put("longitude", endLon) })
            }
            if (necLat != null && necLon != null && swcLat != null && swcLon != null) {
                put("boundingBox", JSONObject().apply {
                    put("minLatitude", swcLat)
                    put("maxLatitude", necLat)
                    put("minLongitude", swcLon)
                    put("maxLongitude", necLon)
                })
            }
        }
    }

    private fun LapMesg.toJson(lapNumber: Int, startDistanceM: Double, endDistanceM: Double): JSONObject = JSONObject().apply {
        put("lap", lapNumber)
        putIfNotNull("startTimeUtc", startTime?.date?.toIso())
        putIfNotNull("startTimeLocal", startTime?.date?.toLocalIso())
        put("startDistanceM", round1(startDistanceM))
        put("endDistanceM", round1(endDistanceM))
        putIfNotNull("distanceM", totalDistance.rounded(1))
        putIfNotNull("elapsedSec", totalElapsedTime.rounded(2))
        putIfNotNull("timerSec", totalTimerTime.rounded(2))
        val avgSpeed = enhancedAvgSpeed ?: avgSpeed
        val maxSpeedValue = enhancedMaxSpeed ?: maxSpeed
        putIfNotNull("avgSpeedMps", avgSpeed.rounded(3))
        if (avgSpeed != null && avgSpeed > 0f) {
            val paceSecPerKm = 1000.0 / avgSpeed
            put("avgPaceSecPerKm", round1(paceSecPerKm))
            put("avgPace", paceSecPerKm.toPaceString())
        }
        putIfNotNull("maxSpeedMps", maxSpeedValue.rounded(3))
        put("heartRate", JSONObject().apply {
            putIfNotNull("avgBpm", avgHeartRate?.toInt())
            putIfNotNull("maxBpm", maxHeartRate?.toInt())
        })
        put("cadence", JSONObject().apply {
            putIfNotNull("avgFitRpm", avgRunningCadence?.toDouble())
            avgRunningCadence?.let { put("avgSpm", round1(it * 2.0)) }
            putIfNotNull("maxFitRpm", maxRunningCadence?.toDouble())
            maxRunningCadence?.let { put("maxSpm", round1(it * 2.0)) }
        })
        put("power", JSONObject().apply {
            putIfNotNull("avgW", avgPower)
            putIfNotNull("maxW", maxPower)
            putIfNotNull("normalizedW", normalizedPower)
        })
        put("elevation", JSONObject().apply {
            putIfNotNull("ascentM", totalAscent)
            putIfNotNull("descentM", totalDescent)
            val minM = enhancedMinAltitude ?: minAltitude
            val maxM = enhancedMaxAltitude ?: maxAltitude
            putIfNotNull("minM", minM.rounded(1))
            putIfNotNull("maxM", maxM.rounded(1))
        })
        put("runningDynamics", JSONObject().apply {
            putIfNotNull("groundContactTimeMs", avgStanceTime.rounded(1))
            putIfNotNull("stepLengthMm", avgStepLength.rounded(1))
            putIfNotNull("verticalOscillationMm", avgVerticalOscillation.rounded(1))
            putIfNotNull("verticalRatioPct", avgVerticalRatio.rounded(2))
        })
        putIfNotNull("calories", totalCalories)
    }

    private fun RecordMesg.toRunningRecord(time: Date): RunningRecord {
        val speed = (enhancedSpeed ?: speed).rounded(3)
        return RunningRecord(
            timestampUtc = time,
            distanceM = distance.rounded(2),
            latitude = positionLat?.semicirclesToDegrees(),
            longitude = positionLong?.semicirclesToDegrees(),
            elevationM = (enhancedAltitude ?: altitude).rounded(1),
            speedMps = speed,
            heartRateBpm = heartRate?.toInt(),
            cadenceFitRpm = cadence?.toDouble(),
            powerW = power,
            groundContactTimeMs = stanceTime.rounded(1),
            stepLengthMm = stepLength.rounded(1),
            verticalOscillationMm = verticalOscillation.rounded(1),
            verticalRatioPct = verticalRatio.rounded(2)
        )
    }

    private fun RunningRecord.toJson(): JSONObject = JSONObject().apply {
        put("timestampUtc", timestampUtc.toIso())
        put("timestampLocal", timestampUtc.toLocalIso())
        putIfNotNull("distanceM", distanceM)
        putIfNotNull("latitude", latitude)
        putIfNotNull("longitude", longitude)
        putIfNotNull("elevationM", elevationM)
        putIfNotNull("speedMps", speedMps)
        if (speedMps != null && speedMps > 0.0) put("paceSecPerKm", round1(1000.0 / speedMps))
        putIfNotNull("heartRateBpm", heartRateBpm)
        putIfNotNull("cadenceFitRpm", cadenceFitRpm)
        cadenceFitRpm?.let { put("cadenceSpm", round1(it * 2.0)) }
        putIfNotNull("powerW", powerW)
        putIfNotNull("groundContactTimeMs", groundContactTimeMs)
        putIfNotNull("stepLengthMm", stepLengthMm)
        putIfNotNull("verticalOscillationMm", verticalOscillationMm)
        putIfNotNull("verticalRatioPct", verticalRatioPct)
    }

    /** fit 좌표는 semicircle 단위(2^31이 180도)로 저장된다. 7자리(~1cm 정밀도)로 반올림해
     * double 변환 잡음을 없앤다. */
    private fun Int.semicirclesToDegrees(): Double = round7(this * (180.0 / 2147483648.0))

    private fun Date.toIso(): String = ISO_UTC_FORMAT.format(this)

    private fun Date.toLocalIso(): String = ISO_LOCAL_FORMAT.format(this)

    private fun Double.toPaceString(): String {
        val min = (this / 60).toInt()
        val sec = (this % 60).roundToInt()
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    private fun round1(value: Double): Double = Math.round(value * 10) / 10.0
    private fun round2(value: Double): Double = Math.round(value * 100) / 100.0
    private fun round3(value: Double): Double = Math.round(value * 1000) / 1000.0
    private fun round7(value: Double): Double = Math.round(value * 10_000_000) / 10_000_000.0

    /**
     * FIT의 Float 필드를 Double로 바꾸면서 float→double 변환 잡음(예: 2.936이 2.936000108718872가
     * 되는 것)을 없앤다. FIT 자체가 이 정도 정밀도로 기록하지 않으므로 반올림해도 정보 손실이 없다.
     */
    private fun Float?.rounded(decimals: Int): Double? {
        if (this == null) return null
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this.toDouble() * factor) / factor
    }

    private fun JSONObject.putIfNotNull(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    private val ISO_UTC_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** 기기 로컬(=러너가 실제로 뛴) 시각. 기기 타임존 오프셋 없이 시스템 기본 타임존으로 표시한다. */
    private val ISO_LOCAL_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
}
