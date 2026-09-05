package io.github.leosonus.runningpull

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.github.leosonus.runningpull.data.DownloadSaver
import io.github.leosonus.runningpull.data.RunningQuotes
import io.github.leosonus.runningpull.data.SessionManager
import io.github.leosonus.runningpull.fit.FitParser
import io.github.leosonus.runningpull.fit.FitResult
import io.github.leosonus.runningpull.network.GarminAuthException
import io.github.leosonus.runningpull.network.GarminNetworkException
import io.github.leosonus.runningpull.network.GarminWebBridge
import io.github.leosonus.runningpull.weather.WeatherClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var webBridge: GarminWebBridge
    private lateinit var dateButton: Button

    private val calendar = Calendar.getInstance()

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && sessionManager.hasSession()) {
            showMainScreen()
        } else {
            Toast.makeText(this, R.string.status_login_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.status_storage_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(applicationContext)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        if (sessionManager.hasSession()) {
            showMainScreen()
        } else {
            showWelcomeScreen()
        }
    }

    /**
     * 시작 화면은 히어로 이미지가 상태바 뒤까지 깔려야 해서 루트에 인셋을 먹이지 않는다.
     * 대신 로그인 시트는 아래쪽(내비게이션 바)만, 로그인 후 메인 화면은 사방을 패딩으로 받는다.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainContainer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val sheet = findViewById<View>(R.id.loginSheet)
        // 레이아웃에 적힌 값을 기준으로 삼는다. paddingBottom을 그때그때 읽으면 인셋이 누적된다.
        val sheetBottomPadding = sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = sheetBottomPadding + systemBars.bottom)
            insets
        }

        // 명언 카드는 mainContainer 밖(루트의 형제)이라 위 인셋을 못 받는다. 따로 챙겨주지 않으면
        // 카드 아래쪽이 내비게이션 바에 가려서 잘려 보인다.
        val quoteOverlay = findViewById<View>(R.id.quoteOverlayContainer)
        val quoteBottomMargin = (quoteOverlay.layoutParams as MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(quoteOverlay) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = quoteBottomMargin + systemBars.bottom
            }
            insets
        }
    }

    /** 로그인 전: 이미지 + 안내 + 로그인 버튼만 있는 시작 화면. 여기서 누를 때만 로그인을 시작한다. */
    private fun showWelcomeScreen() {
        findViewById<View>(R.id.welcomeContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.mainContainer).visibility = View.GONE
        // 히어로는 alpha로 배경과 섞이므로 라이트 테마에선 밝아지고 다크에선 어두워진다.
        // 상태바 아이콘도 그에 맞춰 뒤집어야 시계가 묻히지 않는다.
        setDarkStatusBarIcons(!isNightMode())
        findViewById<Button>(R.id.loginButton).setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }
    }

    private fun setDarkStatusBarIcons(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = dark
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun selectedDateString(): String = DATE_FORMAT.format(calendar.time)

    private fun updateDateButtonText() {
        dateButton.text = "날짜: ${selectedDateString()} (탭하여 변경)"
    }

    private fun showMainScreen() {
        findViewById<View>(R.id.welcomeContainer).visibility = View.GONE
        findViewById<View>(R.id.mainContainer).visibility = View.VISIBLE
        // 메인 화면 상단은 앱 배경색이라 상태바 아이콘은 테마를 따라가면 된다.
        setDarkStatusBarIcons(!isNightMode())

        webBridge = GarminWebBridge(findViewById<WebView>(R.id.dataWebView))

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission()) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val statusText = findViewById<TextView>(R.id.statusText)
        val fetchButton = findViewById<Button>(R.id.fetchButton)
        val progress = findViewById<ProgressBar>(R.id.fetchProgress)
        dateButton = findViewById(R.id.dateButton)

        findViewById<View>(R.id.logoutButton).setOnClickListener { confirmLogout() }
        findViewById<View>(R.id.appIconButton).setOnClickListener { askRanToday() }

        updateDateButtonText()
        dateButton.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth)
                    updateDateButtonText()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        statusText.setText(R.string.status_logged_in)
        fetchButton.setOnClickListener {
            fetchButton.isEnabled = false
            progress.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    runFetch(statusText)
                } catch (e: CancellationException) {
                    // 화면을 벗어나 코루틴이 취소된 것뿐이므로 오류로 표시하지 않는다.
                    throw e
                } catch (e: GarminAuthException) {
                    statusText.setText(R.string.status_session_expired)
                    promptRelogin()
                } catch (e: GarminNetworkException) {
                    statusText.text = "네트워크 오류로 가져오지 못했습니다.\n${e.message}\n" +
                        "연결 상태를 확인한 뒤 다시 시도해주세요."
                } catch (e: Exception) {
                    statusText.text = "오류 발생: ${e.message ?: e}"
                } finally {
                    fetchButton.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 지정한 날짜의 러닝을 전부 받아 저장한다. 러닝 한 건이 실패해도 나머지는 계속 저장하고
     * 마지막에 성공/실패를 함께 보고한다. 다만 세션이 만료된 경우엔 이어서 해도 전부 실패하므로
     * 즉시 위로 던져 재로그인 안내로 넘긴다.
     */
    private suspend fun runFetch(statusText: TextView) {
        if (!isOnline()) {
            statusText.setText(R.string.status_offline)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission()) {
            statusText.setText(R.string.status_storage_permission_needed)
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val targetDate = selectedDateString()
        statusText.text = "$targetDate 러닝 기록을 조회하는 중..."

        val activities = webBridge.fetchActivitiesForDate(targetDate)
        val runs = activities.filter { it.isRunning }.sortedBy { it.startTimeLocal }

        if (runs.isEmpty()) {
            statusText.text = "$targetDate 에 기록된 러닝이 없습니다. " +
                "(같은 날짜 전체 활동 ${activities.size}건 중 러닝 없음)"
            return
        }

        // VO2max/LT는 개별 활동이 아니라 날짜 단위 지표라 하루에 한 번만 조회해서
        // 아래에서 러닝마다 각자의 JSON에 똑같이 붙여준다. 이 지표들은 없어도 러닝 자체는
        // 저장할 수 있으니, 실패하면 JSON에 오류 문구만 남기고 계속 진행한다.
        statusText.text = "VO2max / LT 조회 중..."

        var vo2Max: Double? = null
        var vo2MaxMeasuredDate: String? = null
        var vo2MaxError: String? = null
        try {
            val maxMet = webBridge.fetchVo2Max(targetDate)
            vo2Max = maxMet?.value
            vo2MaxMeasuredDate = maxMet?.measuredDate
        } catch (e: GarminAuthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vo2MaxError = e.message ?: e.toString()
        }

        var ltHeartRate: Double? = null
        var ltPace: String? = null
        var ltPaceSecPerKm: Double? = null
        var ltMeasuredDate: String? = null
        var ltError: String? = null
        try {
            val lt = webBridge.fetchLactateThreshold(targetDate)
            ltHeartRate = lt.heartRate
            ltPace = lt.speedMps?.let { formatPace(it) }
            ltPaceSecPerKm = lt.speedMps?.let { if (it > 0) 1000.0 / it else null }
            ltMeasuredDate = lt.measuredDate
        } catch (e: GarminAuthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ltError = e.message ?: e.toString()
        }

        var ltPowerWatts: Double? = null
        var ltPowerPerKg: Double? = null
        var ltPowerMeasuredDate: String? = null
        var ltPowerError: String? = null
        try {
            val power = webBridge.fetchLactateThresholdPower(targetDate)
            ltPowerWatts = power.watts
            ltPowerPerKg = power.wattsPerKg?.let { Math.round(it * 100) / 100.0 }
            ltPowerMeasuredDate = power.measuredDate
        } catch (e: GarminAuthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ltPowerError = e.message ?: e.toString()
        }

        // 러닝 심박 존 계산에 쓰이는 최대심박수. 날짜 파라미터가 없어 항상 "현재" 설정값이다
        // (VO2max/LT처럼 그 시점 값을 못 가져온다 — GarminWebBridge.fetchMaximumHeartRate 참고).
        var maxHeartRateBpm: Double? = null
        var maxHeartRateError: String? = null
        try {
            maxHeartRateBpm = webBridge.fetchMaximumHeartRate()
        } catch (e: GarminAuthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            maxHeartRateError = e.message ?: e.toString()
        }

        val savedRuns = mutableListOf<SavedRun>()
        val failures = mutableListOf<String>()

        runs.forEachIndexed { index, run ->
            val label = "(${index + 1}/${runs.size})"
            try {
                statusText.text = "$label \"${run.name}\" fit 파일 다운로드 중..."
                val fitBytes = webBridge.downloadFitFile(run.id)

                statusText.text = "$label \"${run.name}\" JSON으로 변환 중..."
                val fit = FitParser.parse(fitBytes)

                // 최대심박수·LT 심박·LT 파워는 fit 파일이 "그 러닝 당시 설정값"을 갖고 있다.
                // REST는 최대심박수에 날짜 파라미터가 아예 없어 항상 현재값을 주므로, fit에
                // 값이 있으면 fit을 쓴다. 실측 근거는 skills/fit_sdk_update.md 2-3절.
                // REST 호출은 걷어내지 않고 두 값을 나란히 남긴다. 실측 결과 두 값이 갈리는
                // 것 자체가 "이 세션 중에 지표가 갱신됐다"는 신호라, 지우면 그 정보가 사라진다.
                val fitSettings = fit.athleteSettings
                val effectiveMaxHeartRate = fitSettings?.maxHeartRateBpm?.toDouble() ?: maxHeartRateBpm
                val effectiveLtHeartRate = fitSettings?.thresholdHeartRateBpm?.toDouble() ?: ltHeartRate
                val effectiveLtPowerWatts =
                    fitSettings?.functionalThresholdPowerW?.toDouble() ?: ltPowerWatts
                val effectiveLtPowerPerKg = fitSettings?.powerToWeight ?: ltPowerPerKg

                val json = fit.json.apply {
                    put("schemaVersion", SCHEMA_VERSION)
                    put("schemaPurpose", "SELF_CONTAINED_RUNNING_ANALYSIS_FOR_AI")

                    getJSONObject("source").apply {
                        put("activityFitFile", "${run.id}_ACTIVITY.fit")
                        put("externalEnrichment", JSONArray(listOf("GARMIN_CONNECT_OR_PROFILE", "WEATHER_API")))
                        fit.localOffsetSec?.let { put("deviceUtcOffsetSec", it) }
                        put(
                            "provenanceNote",
                            "Activity/lap/record metrics come from the activity FIT unless " +
                                "otherwise stated. Max HR and lactate-threshold HR/power come " +
                                "from the FIT itself when present, so they reflect the settings " +
                                "at the time of the run; each value carries its own source " +
                                "field. VO2max and threshold pace have no FIT field and always " +
                                "come from Garmin. Local timestamps use the device's own UTC " +
                                "offset from the FIT activity message, not the phone's."
                        )
                    }
                    getJSONObject("activity").apply {
                        put("activityId", run.id)
                        put("activityName", run.name)
                        put("activityDate", targetDate)
                    }

                    put("athleteContext", JSONObject().apply {
                        put("snapshotDate", targetDate)
                        effectiveMaxHeartRate?.let {
                            put("maximumHeartRate", JSONObject().apply {
                                put("bpm", it)
                                if (fitSettings?.maxHeartRateBpm != null) {
                                    put("source", SOURCE_FIT)
                                    put(
                                        "note",
                                        "fit의 zones_target 메시지. 이 러닝을 기록할 때 시계에 " +
                                            "설정돼 있던 값이라, 과거 러닝에도 그 시점 값이 붙는다."
                                    )
                                    // 대조용. 다르면 그 사이에 설정이 바뀐 것이다.
                                    maxHeartRateBpm?.let { rest -> put("garminRestBpm", rest) }
                                } else {
                                    put("source", "GARMIN_HEART_RATE_ZONES_RUNNING")
                                    put(
                                        "note",
                                        "fit에 zones_target이 없어 Garmin에서 받았다. " +
                                            "biometric-service/heartRateZones의 RUNNING 항목 " +
                                            "maxHeartRateUsed로, 날짜 파라미터가 없어 조회 시점의 " +
                                            "현재 설정값이다(과거 러닝이어도 동일)."
                                    )
                                }
                            })
                        }
                        maxHeartRateError?.let { put("maximumHeartRateError", it) }
                        fitSettings?.restingHeartRateBpm?.let {
                            put("restingHeartRate", JSONObject().apply {
                                put("bpm", it)
                                put("source", SOURCE_FIT)
                            })
                        }
                        fitSettings?.weightKg?.let {
                            put("bodyWeightKg", JSONObject().apply {
                                put("kg", it)
                                put("source", SOURCE_FIT)
                            })
                        }
                        vo2Max?.let {
                            put("vo2Max", JSONObject().apply {
                                put("value", it)
                                vo2MaxMeasuredDate?.let { d -> put("measuredDate", d) }
                                put("source", "GARMIN_CONNECT_OR_PROFILE")
                            })
                        }
                        vo2MaxError?.let { put("vo2MaxError", it) }
                        if (effectiveLtHeartRate != null || ltPace != null || effectiveLtPowerWatts != null) {
                            put("lactateThreshold", JSONObject().apply {
                                effectiveLtHeartRate?.let { put("heartRateBpm", it) }
                                put(
                                    "heartRateSource",
                                    if (fitSettings?.thresholdHeartRateBpm != null) SOURCE_FIT
                                    else "GARMIN_CONNECT_OR_PROFILE"
                                )
                                // 페이스는 fit 프로파일에 대응 필드(threshold_speed)가 아예
                                // 없어서 Garmin에서만 받을 수 있다.
                                ltPace?.let {
                                    put("pacePerKm", it)
                                    put("paceSource", "GARMIN_CONNECT_OR_PROFILE")
                                }
                                ltPaceSecPerKm?.let { put("paceSecPerKm", round1(it)) }
                                effectiveLtPowerWatts?.let { put("powerWatts", it) }
                                effectiveLtPowerPerKg?.let { put("powerPerKg", it) }
                                if (fitSettings?.functionalThresholdPowerW != null) {
                                    put("powerSource", SOURCE_FIT)
                                    put(
                                        "powerNote",
                                        "fit zones_target의 functional_threshold_power. " +
                                            "powerPerKg는 fit user_profile의 체중으로 나눈 값이다."
                                    )
                                } else if (effectiveLtPowerWatts != null) {
                                    put("powerSource", "GARMIN_CONNECT_OR_PROFILE")
                                }
                                (ltMeasuredDate ?: ltPowerMeasuredDate)?.let { put("measuredDate", it) }
                                // 대조용 Garmin 원본값. 갱신일에만 fit과 갈린다(아래 노트).
                                if (fitSettings != null) {
                                    put("garminRest", JSONObject().apply {
                                        ltHeartRate?.let { put("heartRateBpm", it) }
                                        ltPowerWatts?.let { put("powerWatts", it) }
                                        ltPowerPerKg?.let { put("powerPerKg", it) }
                                    })
                                    // fit을 실제로 쓴 값에 한해서만 비교한다. fit에 값이 없어
                                    // REST로 폴백했다면 두 값이 같을 수밖에 없어 노트가 무의미하다.
                                    val heartRateDiffers = fitSettings.thresholdHeartRateBpm != null &&
                                        ltHeartRate != null && effectiveLtHeartRate != ltHeartRate
                                    val powerDiffers = fitSettings.functionalThresholdPowerW != null &&
                                        ltPowerWatts != null && effectiveLtPowerWatts != ltPowerWatts
                                    if (heartRateDiffers || powerDiffers) {
                                        put("garminRestNote", GARMIN_REST_MISMATCH_NOTE)
                                    }
                                }
                            })
                        }
                        ltError?.let { put("lactateThresholdError", it) }
                        ltPowerError?.let { put("lactateThresholdPowerError", it) }
                    })

                    getJSONObject("heartRate").apply {
                        effectiveLtHeartRate?.let { put("lactateThresholdHeartRateBpm", it) }
                    }
                    getJSONObject("power").apply {
                        effectiveLtPowerWatts?.let { put("lactateThresholdPowerW", it) }
                    }

                    put("physiology", JSONObject().apply {
                        // 값마다 출처가 갈리게 됐다. 블록 단위 source로는 더 이상 정확히
                        // 말할 수 없어서, 값별 출처는 athleteContext 쪽을 보게 한다.
                        put("source", "SEE_ATHLETE_CONTEXT_PER_VALUE_SOURCE")
                        vo2Max?.let { put("vo2Max", it) }
                        vo2MaxMeasuredDate?.let { put("vo2MaxMeasuredDate", it) }
                        effectiveLtHeartRate?.let { put("lactateThresholdHeartRateBpm", it) }
                        ltPace?.let { put("lactateThresholdPacePerKm", it) }
                        ltMeasuredDate?.let { put("lactateThresholdMeasuredDate", it) }
                        effectiveLtPowerWatts?.let { put("lactateThresholdPowerW", it) }
                        effectiveLtPowerPerKg?.let { put("lactateThresholdPowerPerKg", it) }
                    })

                    put(
                        "derivedIntensityContext",
                        buildDerivedIntensityContext(
                            avgHeartRate = fit.avgHeartRate,
                            maxHeartRateActivity = fit.maxHeartRate,
                            maxHeartRateConfigured = effectiveMaxHeartRate,
                            ltHeartRate = effectiveLtHeartRate,
                            avgPowerWatts = fit.avgPowerWatts,
                            normalizedPowerWatts = fit.normalizedPowerWatts,
                            ltPowerWatts = effectiveLtPowerWatts,
                            avgPaceSecPerKm = fit.avgPaceSecPerKm,
                            ltPaceSecPerKm = ltPaceSecPerKm
                        )
                    )
                }

                statusText.text = "$label \"${run.name}\" 날씨 조회 중..."
                val weatherOutcome = applyWeather(json, fit)

                val fileName =
                    "${sanitizeFileNamePart(run.name)}_${dateTimeSuffix(run.startTimeLocal)}.json"
                DownloadSaver.saveJson(this@MainActivity, fileName, json.toString(2))
                savedRuns.add(SavedRun(fileName, weatherOutcome))
            } catch (e: GarminAuthException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.add("\"${run.name}\": ${e.message ?: e}")
            }
        }

        statusText.text = buildResultSummary(savedRuns, failures)
        if (savedRuns.isNotEmpty()) showQuoteToast(RunningQuotes.random())
    }

    /**
     * 러닝 좌표와 시각으로 그때의 기온·습도를 붙인다. fit의 시계 온도계는 값이 비어 있는 데다
     * 손목 체온에 영향을 받아, 실제 기온은 위치 기반 기상 데이터로 받아온다.
     *
     * 원본이 정시 단위라 5km 지점마다 보간해서 넣는다. 짧은 러닝은 값이 거의 같지만,
     * 새벽에 시작하는 장거리는 구간별로 확실히 달라진다.
     *
     * 날씨는 있으면 좋은 정보라 실패해도 러닝 저장 자체는 막지 않는다.
     */
    private suspend fun applyWeather(json: JSONObject, fit: FitResult): WeatherOutcome {
        val latitude = fit.startLatitude
        val longitude = fit.startLongitude
        val startTime = fit.startTimeUtc
        if (latitude == null || longitude == null || startTime == null) {
            // 트레드밀처럼 GPS가 없는 러닝. 오류가 아니므로 조용히 건너뛴다.
            json.put("weather", JSONObject().apply {
                put("source", "NONE")
                put("samplingIntervalM", FitParser.SPLIT_INTERVAL_M.toInt())
                put("FITContainsTemperature", fit.fitTemperatureAvailable)
                put("samples", JSONArray())
                put("skippedReason", "위치 정보가 없는 러닝입니다 (실내/트레드밀 등)")
            })
            return WeatherOutcome.SKIPPED_NO_GPS
        }

        val fetchedAtUtc = ISO_UTC.format(Date())
        try {
            val weather = WeatherClient.fetchHourly(latitude, longitude, startTime)
            json.put("weather", JSONObject().apply {
                // 폴백이 동작하면 archive가 아닐 수 있으므로 실제로 쓴 소스를 그대로 적는다.
                put("source", weather.source)
                put("samplingIntervalM", FitParser.SPLIT_INTERVAL_M.toInt())
                put("FITContainsTemperature", fit.fitTemperatureAvailable)
                put("samples", JSONArray().apply {
                    fit.splits.forEach { split ->
                        put(JSONObject().apply {
                            put("targetDistanceM", split.distanceKm * 1000)
                            put("gpsDistanceM", round2(split.actualDistanceM))
                            put("activityTimeUtc", ISO_UTC.format(split.timeUtc))
                            put("activityTimeLocal", ISO_LOCAL.format(split.timeUtc))
                            split.latitude?.let { put("latitude", it) }
                            split.longitude?.let { put("longitude", it) }
                            weather.temperatureAt(split.timeUtc)?.let { put("temperatureC", round1(it)) }
                            weather.humidityAt(split.timeUtc)?.let { put("humidityPct", round1(it)) }
                            weather.dewPointAt(split.timeUtc)?.let { put("dewPointC", round1(it)) }
                            weather.feelsLikeAt(split.timeUtc)?.let { put("feelsLikeC", round1(it)) }
                            weather.windSpeedAt(split.timeUtc)?.let { put("windSpeedMps", round1(it)) }
                            weather.windDirectionAt(split.timeUtc)?.let { put("windDirectionDeg", round1(it)) }
                            put("weatherObservedAt", ISO_UTC.format(split.timeUtc))
                            put("weatherFetchedAt", fetchedAtUtc)
                            put("status", "OK")
                        })
                    }
                })
            })
            return WeatherOutcome.ADDED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            json.put("weather", JSONObject().apply {
                // 모든 소스가 실패한 경우. 어느 소스가 왜 실패했는지는 error에 함께 담긴다.
                put("source", "NONE")
                put("samplingIntervalM", FitParser.SPLIT_INTERVAL_M.toInt())
                put("FITContainsTemperature", fit.fitTemperatureAvailable)
                put("error", e.message ?: e.toString())
                put("samples", JSONArray().apply {
                    fit.splits.forEach { split ->
                        put(JSONObject().apply {
                            put("targetDistanceM", split.distanceKm * 1000)
                            put("gpsDistanceM", round2(split.actualDistanceM))
                            put("activityTimeUtc", ISO_UTC.format(split.timeUtc))
                            put("activityTimeLocal", ISO_LOCAL.format(split.timeUtc))
                            split.latitude?.let { put("latitude", it) }
                            split.longitude?.let { put("longitude", it) }
                            put("status", "ERROR")
                            put("error", e.message ?: e.toString())
                        })
                    }
                })
            })
            return WeatherOutcome.FAILED
        }
    }

    /**
     * 날씨를 붙였는지 여부. 날씨 실패는 러닝 저장 자체를 막지 않으므로 조용히 지나가기 쉬운데,
     * 그러면 사용자는 JSON을 열어보고서야 빠진 걸 알게 된다. 결과 요약에 함께 보여준다.
     */
    private enum class WeatherOutcome { ADDED, FAILED, SKIPPED_NO_GPS }

    /** 저장한 러닝 한 건과 그 러닝의 날씨 결과. */
    private data class SavedRun(val fileName: String, val weather: WeatherOutcome)

    /**
     * LT(젖산 역치)·최대심박수 대비 이번 러닝 강도를 미리 계산해 붙인다. 값을 못 구하는
     * 필드는 null로 채우기보다 아예 비운다(이 프로젝트의 기존 관례 — vo2MaxError 등).
     */
    private fun buildDerivedIntensityContext(
        avgHeartRate: Int?,
        maxHeartRateActivity: Int?,
        maxHeartRateConfigured: Double?,
        ltHeartRate: Double?,
        avgPowerWatts: Int?,
        normalizedPowerWatts: Int?,
        ltPowerWatts: Double?,
        avgPaceSecPerKm: Double?,
        ltPaceSecPerKm: Double?
    ): JSONObject = JSONObject().apply {
        if (avgHeartRate != null && maxHeartRateConfigured != null && maxHeartRateConfigured > 0) {
            put("avgHeartRatePctOfMax", round1(avgHeartRate / maxHeartRateConfigured * 100))
        }
        if (maxHeartRateActivity != null && maxHeartRateConfigured != null && maxHeartRateConfigured > 0) {
            put("activityMaxHeartRatePctOfMax", round1(maxHeartRateActivity / maxHeartRateConfigured * 100))
        }
        if (avgHeartRate != null && ltHeartRate != null && ltHeartRate > 0) {
            put("avgHeartRatePctOfLactateThreshold", round1(avgHeartRate / ltHeartRate * 100))
        }
        if (maxHeartRateActivity != null && ltHeartRate != null && ltHeartRate > 0) {
            put("activityMaxHeartRatePctOfLactateThreshold", round1(maxHeartRateActivity / ltHeartRate * 100))
        }
        if (avgPowerWatts != null && ltPowerWatts != null && ltPowerWatts > 0) {
            put("avgPowerPctOfLactateThresholdPower", round1(avgPowerWatts / ltPowerWatts * 100))
        }
        if (normalizedPowerWatts != null && ltPowerWatts != null && ltPowerWatts > 0) {
            put(
                "normalizedPowerPctOfLactateThresholdPower",
                round1(normalizedPowerWatts / ltPowerWatts * 100)
            )
        }
        if (avgPaceSecPerKm != null && ltPaceSecPerKm != null) {
            put("avgPaceSlowerThanLactateThresholdSecPerKm", round1(avgPaceSecPerKm - ltPaceSecPerKm))
        }
        put("note", "Derived fields are convenience calculations, not additional Garmin measurements.")
    }

    private fun round1(value: Double): Double = Math.round(value * 10) / 10.0

    private fun round2(value: Double): Double = Math.round(value * 100) / 100.0

    /**
     * 날씨는 러닝 한 건마다 따로 조회하지만, 같은 날 같은 장소를 몇 초 안에 연달아 부르기 때문에
     * **실패하면 대개 그날 것 전부가 함께 실패한다.** 그래서 결과가 같으면 한 줄로 합친다
     * (러닝 3건에 같은 문구를 3번 붙이면 지저분하기만 하다).
     *
     * 갈릴 때만 건수로 나눠 쓴다 — 한 건만 순간적으로 실패했거나, 그중 하나가 실내 러닝이라
     * 위치 정보가 없는 경우.
     */
    private fun weatherSummaryLine(saved: List<SavedRun>): String {
        val outcomes = saved.map { it.weather }.toSet()
        if (outcomes.size == 1) {
            return when (outcomes.first()) {
                WeatherOutcome.ADDED -> "날씨 정보 추가 완료"
                WeatherOutcome.FAILED ->
                    "※ 날씨 정보 획득 실패 — 러닝 기록 자체는 정상 저장됐습니다. " +
                        "다시 가져오면 날씨가 채워질 수 있습니다."
                WeatherOutcome.SKIPPED_NO_GPS -> "날씨 없음 (위치 정보가 없는 러닝)"
            }
        }

        val parts = mutableListOf<String>()
        saved.count { it.weather == WeatherOutcome.ADDED }
            .let { if (it > 0) parts.add("추가 완료 ${it}건") }
        saved.count { it.weather == WeatherOutcome.FAILED }
            .let { if (it > 0) parts.add("획득 실패 ${it}건") }
        saved.count { it.weather == WeatherOutcome.SKIPPED_NO_GPS }
            .let { if (it > 0) parts.add("위치 정보 없음 ${it}건") }
        return "날씨: ${parts.joinToString(" / ")}"
    }

    private fun buildResultSummary(saved: List<SavedRun>, failures: List<String>): String {
        val summary = StringBuilder()
        if (saved.isNotEmpty()) {
            summary.append("저장 완료: Downloads/strongRunner/ (러닝 ${saved.size}건)\n")
            summary.append(saved.joinToString("\n") { it.fileName })
            summary.append("\n\n")
            summary.append(weatherSummaryLine(saved))
        }
        if (failures.isNotEmpty()) {
            if (summary.isNotEmpty()) summary.append("\n\n")
            summary.append("실패 ${failures.size}건 (다시 시도해보세요):\n")
            summary.append(failures.joinToString("\n"))
        }
        return summary.toString()
    }

    /** 세션이 만료됐을 때 저장된 세션을 버리고 다시 로그인할지 물어본다. */
    private fun promptRelogin() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_session_expired_title)
            .setMessage(R.string.dialog_session_expired_message)
            .setPositiveButton(R.string.dialog_relogin) { _, _ ->
                clearLoginState()
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
            .setNegativeButton(R.string.dialog_later, null)
            .show()
    }

    /**
     * 앱 아이콘을 누르면 나오는 물음. "달렸지!"면 저장 완료 때와 같은 명언 카드로 답해주고,
     * 아직이면 등을 떠민다. 데이터를 건드리지 않는 순수한 응원용 팝업이다.
     */
    private fun askRanToday() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_ran_today_title)
            .setMessage(R.string.dialog_ran_today_message)
            .setPositiveButton(R.string.dialog_ran_today_yes) { _, _ ->
                showQuoteToast(RunningQuotes.random())
            }
            .setNegativeButton(R.string.dialog_ran_today_no) { _, _ ->
                Toast.makeText(this, R.string.status_not_yet, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun confirmLogout() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_logout_title)
            .setMessage(R.string.dialog_logout_message)
            .setPositiveButton(R.string.menu_logout) { _, _ ->
                clearLoginState()
                showWelcomeScreen()
                Toast.makeText(this, R.string.status_logged_out, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 저장된 세션만 지우면 WebView에 남은 쿠키로 비밀번호 없이 다시 로그인돼버린다.
     * 실제로 로그아웃이 되려면 쿠키와 웹 저장소(csrf 토큰이 여기 있다)까지 함께 비워야 한다.
     */
    private fun clearLoginState() {
        sessionManager.clearSession()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        findViewById<WebView>(R.id.dataWebView).apply {
            clearCache(true)
            clearHistory()
            loadUrl("about:blank")
        }
    }

    private fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** 연결이 아예 없으면 fetch의 모호한 실패 메시지 대신 바로 알아볼 수 있는 안내를 띄운다. */
    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * "오늘도 달렸다" 제목 + 명언 카드를 화면 안에 직접 띄운다. Toast.show()를 반복 호출하는
     * 방식은 호출할 때마다 사라졌다 다시 나타나는 애니메이션이 타서 여러 번 뜨는 것처럼 보이는
     * 문제가 있어, 화면에 뷰를 붙였다 페이드로 부드럽게 지우는 방식으로 대체했다.
     */
    private fun showQuoteToast(quote: String) {
        val container = findViewById<FrameLayout>(R.id.quoteOverlayContainer)
        container.removeAllViews()

        val view = layoutInflater.inflate(R.layout.toast_running_quote, container, false)
        view.findViewById<TextView>(R.id.toastQuote).text = quote
        view.alpha = 0f
        container.addView(view)
        view.animate().alpha(1f).setDuration(250).start()

        view.postDelayed({
            view.animate().alpha(0f).setDuration(400).withEndAction {
                container.removeView(view)
            }.start()
        }, 6000L)
    }

    private fun formatPace(speedMps: Double): String {
        val secPerKm = 1000.0 / speedMps
        val min = (secPerKm / 60).toInt()
        val sec = (secPerKm % 60).roundToInt()
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    /** 파일명에 못 쓰는 문자를 걷어낸다. */
    private fun sanitizeFileNamePart(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "활동" }
    }

    /**
     * "yyyy-MM-dd HH:mm:ss" 형식의 startTimeLocal에서 "yyyyMMdd_HHmmss"를 뽑아낸다.
     * 시각만 쓰면 다른 날 같은 시각에 시작한 러닝끼리 파일명이 겹치므로 날짜를 함께 붙인다.
     */
    private fun dateTimeSuffix(startTimeLocal: String): String {
        val datePart = startTimeLocal.substringBefore(' ', missingDelimiterValue = "").replace("-", "")
        val timePart = startTimeLocal
            .substringAfter(' ', missingDelimiterValue = startTimeLocal)
            .replace(":", "")
        return if (datePart.isBlank()) timePart else "${datePart}_$timePart"
    }

    companion object {
        private const val SCHEMA_VERSION = "3.0"

        /**
         * fit 파일 자체가 출처라는 표시. Garmin REST에서 받은 값과 구분하려고 둔다
         * (REST의 최대심박수는 날짜 파라미터가 없어 항상 현재값이다).
         */
        private const val SOURCE_FIT = "FIT_ACTIVITY_FILE"

        /**
         * fit 값과 Garmin REST 값이 갈릴 때만 붙이는 설명. 실측(2026-09-05, 러닝 14건)으로
         * 확인된 패턴은 이렇다 — 두 값은 **젖산 역치가 갱신된 날에만**, 그것도 갱신을 유발한
         * 러닝 이전 활동에서만 갈린다. 시계가 고강도 러닝 중 새 값을 감지하면 그 러닝이 끝난
         * 뒤 시계 설정이 바뀌므로, 같은 날 나중 활동의 fit부터 새 값이 들어 있다.
         * 비갱신일에는 두 값이 완전히 일치한다. 근거는 skills/PROGRESS.md 참고.
         */
        private const val GARMIN_REST_MISMATCH_NOTE =
            "garminRest가 위 값과 다르다 = 이 러닝 도중 또는 직후에 젖산 역치가 갱신됐다는 뜻이다. " +
                "위 값은 이 러닝을 시작할 때 시계에 설정돼 있던 값이고, garminRest는 Garmin이 " +
                "그 날짜에 최종 기록한 값이다. 강도 계산(derivedIntensityContext)에는 러닝 시작 " +
                "시점 값을 쓴다 — 그 러닝 자체가 만들어낸 값을 분모로 쓰면 순환이 되기 때문이다. " +
                "같은 날 더 늦게 시작한 활동의 fit에는 갱신된 값이 들어 있으므로, 하루치 파일을 " +
                "함께 읽을 때 파일마다 이 값이 다를 수 있다."

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        /** 날씨 구간의 시각 표기. fit 타임스탬프와 같은 UTC 기준으로 맞춘다. */
        private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /** 기기 로컬(=러너가 실제로 뛴) 시각. FitParser의 로컬 포맷과 동일하게 맞춘다. */
        private val ISO_LOCAL = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    }
}
