package io.github.leosonus.runningpull.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.ActivityMesgListener
import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesg
import com.garmin.fit.FileIdMesgListener
import com.garmin.fit.GarminProduct
import com.garmin.fit.LapMesg
import com.garmin.fit.LapMesgListener
import com.garmin.fit.Manufacturer
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.TimeInZoneMesg
import com.garmin.fit.TimeInZoneMesgListener
import com.garmin.fit.UserProfileMesg
import com.garmin.fit.UserProfileMesgListener
import com.garmin.fit.ZonesTargetMesg
import com.garmin.fit.ZonesTargetMesgListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
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
 * 그 러닝을 기록할 때 시계에 설정돼 있던 값들. `zones_target`/`time_in_zone`/`user_profile`
 * 메시지에서 온다.
 *
 * **이 값들이 중요한 이유**: 같은 지표를 Garmin REST에서도 받을 수 있지만, 최대심박수
 * (`biometric-service/heartRateZones`)는 날짜 파라미터가 아예 없어 **언제 물어봐도 현재값**이
 * 온다. 5개월 전 러닝에 오늘 설정이 붙는다는 뜻이다. fit 파일은 그 시점에 기록된 파일이라
 * 그때 값을 그대로 갖고 있다 — 실측으로 확인했다(skills/fit_sdk_update.md 2-3절).
 */
data class FitAthleteSettings(
    val maxHeartRateBpm: Int?,
    val thresholdHeartRateBpm: Int?,
    val functionalThresholdPowerW: Int?,
    val restingHeartRateBpm: Int?,
    val weightKg: Double?,
    /** FTP ÷ 체중. 둘 다 fit에 있을 때만 계산한다. */
    val powerToWeight: Double?
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
    val normalizedPowerWatts: Int?,
    /** 그 러닝 당시 시계 설정값. 해당 메시지가 없는 fit이면 null. */
    val athleteSettings: FitAthleteSettings?,
    /** 기기가 있던 UTC 오프셋(초). activity 메시지가 없으면 null이고, 그때는 폰 타임존을 쓴다. */
    val localOffsetSec: Int?
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
        // 개수가 1~80개뿐이라 그대로 담아도 부담이 없다. record처럼 수천 개인 메시지만
        // 훑으면서 버린다(전량 보관 시 33분 러닝에 10MB — fit_sdk_update.md Step 2).
        val timeInZones = mutableListOf<TimeInZoneMesg>()
        var zonesTarget: ZonesTargetMesg? = null
        var userProfile: UserProfileMesg? = null
        var activityMesg: ActivityMesg? = null
        var fileId: FileIdMesg? = null
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
        broadcaster.addListener(ZonesTargetMesgListener { zonesTarget = it })
        broadcaster.addListener(TimeInZoneMesgListener { timeInZones.add(it) })
        broadcaster.addListener(UserProfileMesgListener { userProfile = it })
        broadcaster.addListener(ActivityMesgListener { activityMesg = it })
        broadcaster.addListener(FileIdMesgListener { fileId = it })
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

        // activity 메시지의 local_timestamp와 timestamp 차이가 곧 기기의 UTC 오프셋이다.
        // 예전에는 폰의 현재 타임존으로 로컬 시각을 찍어서, 해외에서 뛴 러닝이나 폰 타임존을
        // 바꾼 뒤 받은 과거 러닝의 startTimeLocal이 틀리게 나갔다.
        // (activity.timestamp를 종료 시각으로 쓰면 안 된다 — 실측해 보니 시작 시각이 들어 있다.)
        val localOffsetSec = activityMesg?.let { activity ->
            val utc = activity.timestamp?.timestamp
            val local = activity.localTimestamp
            if (utc != null && local != null) (local - utc).toInt() else null
        }
        val fmt = LocalTimeFormatter(localOffsetSec)
        val athleteSettings = buildAthleteSettings(zonesTarget, timeInZones, userProfile)

        val avgSpeed = session.enhancedAvgSpeed ?: session.avgSpeed
        val maxSpeed = session.enhancedMaxSpeed ?: session.maxSpeed
        val avgPaceSecPerKm = avgSpeed?.let { if (it > 0f) 1000.0 / it else null }
        val maxInstantPaceSecPerKm = maxSpeed?.let { if (it > 0f) 1000.0 / it else null }

        val json = JSONObject()
        json.put("activity", buildActivityBlock(session, fmt))
        json.put("summary", buildSummaryBlock(session, avgPaceSecPerKm, maxInstantPaceSecPerKm))
        json.put("timing", buildTimingBlock(session, records, fmt))
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
            buildDeviceBlock(fileId)?.let { put("device", it) }
        })
        json.put("laps", JSONArray().apply {
            var cumulativeDistanceM = 0.0
            laps.forEachIndexed { index, lap ->
                val lapDistance = (lap.totalDistance ?: 0f).toDouble()
                put(lap.toJson(index + 1, cumulativeDistanceM, cumulativeDistanceM + lapDistance, fmt))
                cumulativeDistanceM += lapDistance
            }
        })
        json.put("records", JSONArray().apply { records.forEach { put(it.toJson(fmt)) } })

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
            normalizedPowerWatts = session.normalizedPower,
            athleteSettings = athleteSettings,
            localOffsetSec = localOffsetSec
        )
    }

    /**
     * 이 러닝을 기록한 기기. fit의 `file_id`는 **이름이 아니라 숫자 코드**로만 갖고 있어서
     * (예: 4315), 그대로 담으면 파일을 읽는 쪽이 알아볼 수 없다. SDK의 변환 테이블로 이름을
     * 붙이되(`GarminProduct.getStringFromValue`), **원본 코드도 함께 남긴다** — 테이블에 없는
     * 신형 기기가 나오면 이름은 못 붙여도 코드로 추적할 수 있어야 하기 때문이다.
     *
     * 기기를 담는 실질적인 이유: 이 사용자의 데이터는 두 기기에 걸쳐 있고(2025년 11월경
     * FR55 → FR965), **FR55에는 젖산 역치 기능 자체가 없다.** 기기 정보가 없으면 그 시절
     * 러닝에 LT가 비어 있는 것이 기기 한계인지 조회 실패인지 파일만 보고 구분할 수 없다.
     *
     * `getStringFromValue`는 모르는 코드에 **null이 아니라 빈 문자열**을 준다(실측). 시리얼
     * 번호는 분석에 쓸모가 없고 기기를 특정하는 값이라 담지 않는다.
     */
    private fun buildDeviceBlock(fileId: FileIdMesg?): JSONObject? {
        if (fileId == null) return null
        val manufacturerCode = fileId.manufacturer
        val productCode = fileId.garminProduct ?: fileId.product
        val manufacturerName = manufacturerCode
            ?.let { Manufacturer.getStringFromValue(it) }
            ?.takeIf { it.isNotEmpty() }
        // garmin_product 테이블은 제조사가 Garmin일 때만 의미가 있다.
        val productName = productCode
            ?.takeIf { manufacturerCode == Manufacturer.GARMIN }
            ?.let { GarminProduct.getStringFromValue(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: fileId.productName?.takeIf { it.isNotEmpty() }
        if (manufacturerName == null && productName == null &&
            manufacturerCode == null && productCode == null
        ) {
            return null
        }
        return JSONObject().apply {
            putIfNotNull("manufacturer", manufacturerName)
            putIfNotNull("product", productName)
            putIfNotNull("manufacturerCode", manufacturerCode)
            putIfNotNull("productCode", productCode)
            put(
                "note",
                "이 러닝을 기록한 기기다. fit의 file_id에서 왔고, 이름은 FIT SDK의 코드→이름 " +
                    "변환 표로 붙였다. 기기에 따라 기능 자체가 없어 특정 지표가 비는 경우가 " +
                    "있으므로(예: Forerunner 55에는 젖산 역치 기능이 없다), 값이 없을 때 " +
                    "조회 실패로 단정하기 전에 기기를 확인할 것."
            )
        }
    }

    private fun buildActivityBlock(session: SessionMesg, fmt: LocalTimeFormatter): JSONObject = JSONObject().apply {
        putIfNotNull("sport", session.sport?.name)
        putIfNotNull("startTimeUtc", session.startTime?.date?.toIso())
        putIfNotNull("startTimeLocal", session.startTime?.date?.toLocalIso(fmt))
        val endTime = session.startTime?.date?.let { s ->
            session.totalElapsedTime?.let { Date(s.time + (it * 1000).toLong()) }
        }
        putIfNotNull("endTimeUtc", endTime?.toIso())
        putIfNotNull("endTimeLocal", endTime?.toLocalIso(fmt))
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
        cadenceRpm(session.avgRunningCadence, session.avgFractionalCadence)?.let {
            put("avgRunningCadenceFitRpm", it)
            put("avgRunningCadenceSpm", round1(it * 2.0))
        }
        cadenceRpm(session.maxRunningCadence, session.maxFractionalCadence)?.let {
            put("maxRunningCadenceFitRpm", it)
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
    private fun buildTimingBlock(
        session: SessionMesg,
        records: List<RunningRecord>,
        fmt: LocalTimeFormatter
    ): JSONObject =
        JSONObject().apply {
            putIfNotNull("startTimeUtc", session.startTime?.date?.toIso())
            putIfNotNull("startTimeLocal", session.startTime?.date?.toLocalIso(fmt))
            val endTime = session.startTime?.date?.let { s ->
                session.totalElapsedTime?.let { Date(s.time + (it * 1000).toLong()) }
            }
            putIfNotNull("endTimeUtc", endTime?.toIso())
            putIfNotNull("endTimeLocal", endTime?.toLocalIso(fmt))
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
        cadenceRpm(session.avgRunningCadence, session.avgFractionalCadence)?.let {
            put("avgFitRpm", it)
            put("avgSpm", round1(it * 2.0))
        }
        cadenceRpm(session.maxRunningCadence, session.maxFractionalCadence)?.let {
            put("maxFitRpm", it)
            put("maxSpm", round1(it * 2.0))
        }
        put(
            "note",
            "Garmin FIT running cadence is stored as cycles/rpm-style cadence " +
                "(profile units: strides/min); avgSpm/maxSpm are doubled for full-step " +
                "cadence. Values include the fractional_cadence component."
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

    private fun LapMesg.toJson(
        lapNumber: Int,
        startDistanceM: Double,
        endDistanceM: Double,
        fmt: LocalTimeFormatter
    ): JSONObject = JSONObject().apply {
        put("lap", lapNumber)
        putIfNotNull("startTimeUtc", startTime?.date?.toIso())
        putIfNotNull("startTimeLocal", startTime?.date?.toLocalIso(fmt))
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
            cadenceRpm(avgRunningCadence, avgFractionalCadence)?.let {
                put("avgFitRpm", it)
                put("avgSpm", round1(it * 2.0))
            }
            cadenceRpm(maxRunningCadence, maxFractionalCadence)?.let {
                put("maxFitRpm", it)
                put("maxSpm", round1(it * 2.0))
            }
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
            cadenceFitRpm = cadenceRpm(cadence, fractionalCadence),
            powerW = power,
            groundContactTimeMs = stanceTime.rounded(1),
            stepLengthMm = stepLength.rounded(1),
            verticalOscillationMm = verticalOscillation.rounded(1),
            verticalRatioPct = verticalRatio.rounded(2)
        )
    }

    private fun RunningRecord.toJson(fmt: LocalTimeFormatter): JSONObject = JSONObject().apply {
        put("timestampUtc", timestampUtc.toIso())
        put("timestampLocal", timestampUtc.toLocalIso(fmt))
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

    private fun Date.toLocalIso(fmt: LocalTimeFormatter): String = fmt.format(this)

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

    /**
     * 기기 로컬(=러너가 실제로 뛴) 시각 포맷터.
     *
     * fit의 activity 메시지가 알려주는 **그 러닝 당시 기기의 UTC 오프셋**을 쓴다. 오프셋을
     * 못 구하면 예전처럼 시스템 기본 타임존으로 떨어진다(없느니 폰 기준이라도 낫다).
     */
    private class LocalTimeFormatter(offsetSec: Int?) {
        private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            if (offsetSec != null) timeZone = TimeZone.getTimeZone(gmtId(offsetSec))
        }

        fun format(date: Date): String = format.format(date)

        private fun gmtId(offsetSec: Int): String {
            val sign = if (offsetSec < 0) "-" else "+"
            val absSec = abs(offsetSec)
            return String.format(Locale.US, "GMT%s%02d:%02d", sign, absSec / 3600, (absSec % 3600) / 60)
        }
    }

    /**
     * 그 러닝 당시 시계 설정값을 모은다. `zones_target`이 1순위이고, 없으면 `time_in_zone`이
     * 같은 값을 한 번 더 갖고 있어 그쪽에서 줍는다(실측한 두 파일 모두 값이 일치했다).
     * 체중은 `user_profile`에서 오며, FTP와 함께 있을 때만 W/kg을 계산한다.
     */
    private fun buildAthleteSettings(
        zonesTarget: ZonesTargetMesg?,
        timeInZones: List<TimeInZoneMesg>,
        userProfile: UserProfileMesg?
    ): FitAthleteSettings? {
        val zoneInfo = timeInZones.firstOrNull {
            it.maxHeartRate != null || it.functionalThresholdPower != null
        }
        val maxHr = (zonesTarget?.maxHeartRate ?: zoneInfo?.maxHeartRate)?.toInt()
        val thresholdHr = (zonesTarget?.thresholdHeartRate ?: zoneInfo?.thresholdHeartRate)?.toInt()
        val ftp = zonesTarget?.functionalThresholdPower ?: zoneInfo?.functionalThresholdPower
        val restingHr = zoneInfo?.restingHeartRate?.toInt()
        val weightKg = userProfile?.weight?.let { round1(it.toDouble()) }
        if (maxHr == null && thresholdHr == null && ftp == null && restingHr == null && weightKg == null) {
            return null
        }
        return FitAthleteSettings(
            maxHeartRateBpm = maxHr,
            thresholdHeartRateBpm = thresholdHr,
            functionalThresholdPowerW = ftp,
            restingHeartRateBpm = restingHr,
            weightKg = weightKg,
            powerToWeight = if (ftp != null && weightKg != null && weightKg > 0) {
                round2(ftp / weightKg)
            } else {
                null
            }
        )
    }

    /**
     * FIT은 케이던스를 정수부(rpm)와 소수부(scale 128)로 나눠 기록한다. 정수부만 쓰면 최대
     * 1 rpm(=2 spm)이 깎인다 — 실측한 러닝에서 84 rpm(168.0 spm)으로 나가던 것이 실제로는
     * 84.72 rpm(169.4 spm)이었다.
     */
    private fun cadenceRpm(whole: Short?, fractional: Float?): Double? {
        if (whole == null) return null
        return round3(whole.toDouble() + (fractional?.toDouble() ?: 0.0))
    }
}
