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
        var ltMeasuredDate: String? = null
        var ltError: String? = null
        try {
            val lt = webBridge.fetchLactateThreshold(targetDate)
            ltHeartRate = lt.heartRate
            ltPace = lt.speedMps?.let { formatPace(it) }
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

        val savedFileNames = mutableListOf<String>()
        val failures = mutableListOf<String>()

        runs.forEachIndexed { index, run ->
            val label = "(${index + 1}/${runs.size})"
            try {
                statusText.text = "$label \"${run.name}\" fit 파일 다운로드 중..."
                val fitBytes = webBridge.downloadFitFile(run.id)

                statusText.text = "$label \"${run.name}\" JSON으로 변환 중..."
                val fit = FitParser.parse(fitBytes)
                val json = fit.json.apply {
                    put("activityId", run.id)
                    put("activityName", run.name)
                    put("activityDate", targetDate)
                    vo2Max?.let { put("vo2Max", it) }
                    vo2MaxMeasuredDate?.let { put("vo2MaxMeasuredDate", it) }
                    vo2MaxError?.let { put("vo2MaxError", it) }
                    ltHeartRate?.let { put("lactateThresholdHeartRate", it) }
                    ltPace?.let { put("lactateThresholdPacePerKm", it) }
                    // 이 LT가 실제로 측정된 날. 러닝 날짜와 몇 달 차이 날 수 있으므로,
                    // 나중에 분석할 때 값이 얼마나 오래된 것인지 구분하려면 이게 있어야 한다.
                    ltMeasuredDate?.let { put("lactateThresholdMeasuredDate", it) }
                    ltError?.let { put("lactateThresholdError", it) }
                    ltPowerWatts?.let { put("lactateThresholdPowerWatts", it) }
                    ltPowerPerKg?.let { put("lactateThresholdPowerPerKg", it) }
                    ltPowerMeasuredDate?.let { put("lactateThresholdPowerMeasuredDate", it) }
                    ltPowerError?.let { put("lactateThresholdPowerError", it) }
                }

                statusText.text = "$label \"${run.name}\" 날씨 조회 중..."
                applyWeather(json, fit)

                val fileName =
                    "${sanitizeFileNamePart(run.name)}_${dateTimeSuffix(run.startTimeLocal)}.json"
                DownloadSaver.saveJson(this@MainActivity, fileName, json.toString(2))
                savedFileNames.add(fileName)
            } catch (e: GarminAuthException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.add("\"${run.name}\": ${e.message ?: e}")
            }
        }

        statusText.text = buildResultSummary(savedFileNames, failures)
        if (savedFileNames.isNotEmpty()) showQuoteToast(RunningQuotes.random())
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
    private suspend fun applyWeather(json: JSONObject, fit: FitResult) {
        val latitude = fit.startLatitude
        val longitude = fit.startLongitude
        val startTime = fit.startTimeUtc
        if (latitude == null || longitude == null || startTime == null) {
            // 트레드밀처럼 GPS가 없는 러닝. 오류가 아니므로 조용히 건너뛴다.
            json.put("weatherSkippedReason", "위치 정보가 없는 러닝입니다 (실내/트레드밀 등)")
            return
        }

        try {
            val weather = WeatherClient.fetchHourly(latitude, longitude, startTime)
            json.put("weather", JSONObject().apply {
                put("source", "open-meteo archive (ERA5)")
                put("latitude", round2(weather.latitude))
                put("longitude", round2(weather.longitude))
                weather.temperatureAt(startTime)?.let { put("startTemperatureC", round1(it)) }
                weather.humidityAt(startTime)?.let { put("startHumidityPct", round1(it)) }
                fit.endTimeUtc?.let { end ->
                    weather.temperatureAt(end)?.let { put("endTemperatureC", round1(it)) }
                    weather.humidityAt(end)?.let { put("endHumidityPct", round1(it)) }
                }
            })

            if (fit.splits.isNotEmpty()) {
                json.put("weatherSplits", JSONArray().apply {
                    fit.splits.forEach { split ->
                        put(JSONObject().apply {
                            put("distanceKm", split.distanceKm)
                            put("time", ISO_UTC.format(split.timeUtc))
                            weather.temperatureAt(split.timeUtc)
                                ?.let { put("temperatureC", round1(it)) }
                            weather.humidityAt(split.timeUtc)
                                ?.let { put("humidityPct", round1(it)) }
                        })
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            json.put("weatherError", e.message ?: e.toString())
        }
    }

    private fun round1(value: Double): Double = Math.round(value * 10) / 10.0

    private fun round2(value: Double): Double = Math.round(value * 100) / 100.0

    private fun buildResultSummary(saved: List<String>, failures: List<String>): String {
        val summary = StringBuilder()
        if (saved.isNotEmpty()) {
            summary.append("저장 완료: Downloads/strongRunner/ (러닝 ${saved.size}건)\n")
            summary.append(saved.joinToString("\n"))
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
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        /** 날씨 구간의 시각 표기. fit 타임스탬프와 같은 UTC 기준으로 맞춘다. */
        private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
