package io.github.leosonus.runningpull.network

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.resumeWithException

/** Garmin API 호출 실패 전반. */
open class GarminApiException(message: String) : Exception(message)

/** 세션이 만료돼(401/403, 또는 로그인 페이지로 리다이렉트) 재로그인이 필요한 상황. 재시도해도 소용없다. */
class GarminAuthException(message: String) : GarminApiException(message)

/** 네트워크가 끊겼거나 서버가 일시적으로 실패한 상황. 잠시 뒤 재시도할 가치가 있다. */
class GarminNetworkException(message: String) : GarminApiException(message)

/** 지표 값 하나와 그 값이 **실제로 측정된 날짜**. 러닝 날짜와 몇 달 차이 날 수 있다. */
data class DatedValue(val value: Double, val measuredDate: String)

/** 지정 시점의 LT 심박수/속도. `measuredDate`는 이 값이 측정된 날. */
data class LactateThreshold(
    val heartRate: Double?,
    val speedMps: Double?,
    val measuredDate: String?
)

/** 지정 시점의 러닝 젖산 역치 파워. `measuredDate`는 이 값이 측정된 날. */
data class ThresholdPower(
    val watts: Double?,
    val wattsPerKg: Double?,
    val measuredDate: String?
)

data class GarminActivity(
    val id: Long,
    val name: String,
    val startTimeLocal: String,
    val typeKey: String
) {
    val isRunning: Boolean get() = typeKey.contains("running", ignoreCase = true)
}

/**
 * connect.garmin.com에 로그인되어 있는 WebView 안에서 그대로 fetch()를 실행해 데이터를 가져온다.
 * 외부 HTTP 클라이언트(OkHttp 등)로 같은 요청을 보내면 Cloudflare 봇 차단에 걸려 403이 나기 때문에,
 * 실제 로그인 세션을 쥐고 있는 브라우저 컨텍스트(쿠키, csrf, TLS 지문 전부 포함)를 그대로 재사용한다.
 *
 * 실패는 세 갈래로 나눠서 올려보낸다. 세션 만료([GarminAuthException])는 재로그인 말고는 방법이
 * 없으니 즉시 위로 던지고, 일시적 오류([GarminNetworkException])는 여기서 몇 번 재시도한 뒤에
 * 던진다. 응답이 영영 안 오는 경우를 대비해 모든 대기에는 타임아웃이 걸려 있다.
 */
class GarminWebBridge(private val webView: WebView) {

    private enum class PageState { LOADING, READY, FAILED }

    /** JS 콜백은 WebView의 JavaBridge 스레드에서 들어오므로 스레드 안전한 맵이어야 한다. */
    private val pending = ConcurrentHashMap<String, CancellableContinuation<String>>()

    private val lock = Any()
    private val readyWaiters = mutableListOf<CancellableContinuation<Unit>>()
    private var state = PageState.LOADING
    private var loadFailure: GarminApiException? = null

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                if (isSignInUrl(url)) {
                    // 세션이 만료되면 Garmin이 로그인 페이지로 돌려보낸다. 다만 로그인이
                    // 멀쩡해도 sso 도메인을 잠깐 거쳐 갈 때가 있어서 여기서 바로 만료로
                    // 단정하면 오탐이 난다. 잠시 뒤에도 여전히 로그인 페이지에 머물러 있을
                    // 때만 세션이 끊긴 것으로 본다.
                    view.postDelayed({
                        val settled = view.url
                        if (settled != null && isSignInUrl(settled)) {
                            markFailed(GarminAuthException("Garmin 로그인 페이지에 머물러 있습니다 (세션 만료)"))
                        }
                    }, SIGN_IN_CONFIRM_DELAY_MS)
                    return
                }
                if (url.startsWith("https://connect.garmin.com/")) markReady()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                markFailed(GarminNetworkException("$PAGE_LOAD_FAILED (${error.description})"))
            }
        }
        webView.loadUrl(BASE_URL)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onSuccess(requestId: String, data: String) {
            pending.remove(requestId)?.resume(data, onCancellation = null)
        }

        @JavascriptInterface
        fun onError(requestId: String, kind: String, message: String) {
            val exception = when (kind) {
                KIND_AUTH -> GarminAuthException(message)
                KIND_NETWORK -> GarminNetworkException(readableNetworkMessage(message))
                KIND_SERVER -> GarminNetworkException(message)
                else -> GarminApiException(message)
            }
            pending.remove(requestId)?.resumeWithException(exception)
        }
    }

    // ---- 페이지 로딩 상태 ---------------------------------------------------

    private fun markReady() {
        val waiters = synchronized(lock) {
            if (state == PageState.READY) return
            state = PageState.READY
            loadFailure = null
            readyWaiters.toList().also { readyWaiters.clear() }
        }
        waiters.forEach { it.resume(Unit, onCancellation = null) }
    }

    private fun markFailed(error: GarminApiException) {
        val waiters = synchronized(lock) {
            state = PageState.FAILED
            loadFailure = error
            readyWaiters.toList().also { readyWaiters.clear() }
        }
        Log.w(TAG, "페이지 로딩 실패: ${error.message}")
        waiters.forEach { it.resumeWithException(error) }
    }

    /** 지난 로드가 실패했으면 다음 요청을 위해 한 번 더 띄워본다. */
    private fun reloadPage() {
        synchronized(lock) {
            if (state == PageState.LOADING) return
            state = PageState.LOADING
            loadFailure = null
        }
        webView.post { webView.loadUrl(BASE_URL) }
    }

    private suspend fun awaitReady() {
        val current = synchronized(lock) { state }
        if (current == PageState.READY) return
        if (current == PageState.FAILED) reloadPage()

        try {
            withTimeout(READY_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val resumeNow: (() -> Unit)? = synchronized(lock) {
                        when (state) {
                            PageState.READY -> ({ cont.resume(Unit, onCancellation = null) })
                            PageState.FAILED -> {
                                val error = loadFailure ?: GarminNetworkException(PAGE_LOAD_FAILED)
                                ({ cont.resumeWithException(error) })
                            }
                            PageState.LOADING -> {
                                readyWaiters.add(cont)
                                null
                            }
                        }
                    }
                    cont.invokeOnCancellation { synchronized(lock) { readyWaiters.remove(cont) } }
                    resumeNow?.invoke()
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GarminNetworkException(
                "Garmin 페이지를 여는 데 너무 오래 걸립니다. 네트워크 상태를 확인해주세요."
            )
        }
    }

    // ---- 요청 --------------------------------------------------------------

    /**
     * 한 번의 fetch. 응답이 영영 안 돌아오는 경우(WebView가 죽거나 요청이 묶여버린 경우)를 대비해
     * 타임아웃을 걸고, 타임아웃되면 대기 목록에서 스스로를 지운다.
     */
    private suspend fun fetchOnce(url: String, binary: Boolean): String {
        awaitReady()
        val requestId = UUID.randomUUID().toString()
        val timeoutMs = if (binary) BINARY_TIMEOUT_MS else TEXT_TIMEOUT_MS
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    pending[requestId] = cont
                    cont.invokeOnCancellation { pending.remove(requestId) }
                    val js = buildFetchJs(url, requestId, binary)
                    webView.post { webView.evaluateJavascript(js, null) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            pending.remove(requestId)
            throw GarminNetworkException("응답이 없어 요청을 중단했습니다 (${timeoutMs / 1000}초 초과)")
        }
    }

    /**
     * 일시적인 실패는 잠깐 쉬었다 다시 시도한다. 세션 만료나 4xx처럼 다시 해도 결과가 같을
     * 실패는 재시도하지 않고 그대로 올려보낸다.
     */
    private suspend fun fetchViaPage(url: String, binary: Boolean): String {
        var lastError: GarminNetworkException? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
            try {
                val body = fetchOnce(url, binary)
                if (!binary) requireJsonBody(body)
                return body
            } catch (e: GarminNetworkException) {
                lastError = e
                Log.w(TAG, "요청 실패 (${attempt + 1}/$MAX_ATTEMPTS): ${e.message}")
            }
        }
        throw lastError ?: GarminApiException("요청에 실패했습니다")
    }

    /** 세션이 끊기면 API가 JSON 대신 로그인 페이지 HTML을 200으로 내려주기도 한다. */
    private fun requireJsonBody(body: String) {
        if (body.trimStart().startsWith("<")) {
            throw GarminAuthException("JSON 대신 HTML 페이지가 돌아왔습니다 (세션 만료로 보임)")
        }
    }

    private fun buildFetchJs(url: String, requestId: String, binary: Boolean): String {
        val resultExpr = if (binary) {
            "r.arrayBuffer().then(function(buf){" +
                "var b=new Uint8Array(buf);var s='';" +
                "for (var i=0;i<b.length;i++) s+=String.fromCharCode(b[i]);" +
                "return btoa(s);})"
        } else {
            "r.text()"
        }
        return """
            (function() {
              function findCsrf() {
                try {
                  var m = document.querySelector('meta[name="csrf-token"]');
                  if (m && m.content) return m.content;
                } catch (e) {}
                try { if (window.Garmin && window.Garmin.csrfToken) return window.Garmin.csrfToken; } catch (e) {}
                try { var l = localStorage.getItem('csrfToken'); if (l) return l; } catch (e) {}
                try { var s = sessionStorage.getItem('csrfToken'); if (s) return s; } catch (e) {}
                return '';
              }
              function isSignIn(u) {
                return !!u && (u.indexOf('/signin') >= 0 || u.indexOf('sso.garmin.com') >= 0);
              }
              fetch("$url", {
                method: 'GET',
                credentials: 'include',
                headers: {
                  'accept': '*/*',
                  'cache-control': 'no-cache',
                  'pragma': 'no-cache',
                  'x-app-ver': '5.24.1.1',
                  'connect-csrf-token': findCsrf()
                }
              }).then(function(r) {
                if (isSignIn(r.url)) {
                  AndroidBridge.onError("$requestId", '$KIND_AUTH', '로그인 페이지로 리다이렉트됨');
                  return;
                }
                if (!r.ok) {
                  var kind = (r.status === 401 || r.status === 403) ? '$KIND_AUTH'
                           : (r.status >= 500 ? '$KIND_SERVER' : '$KIND_API');
                  AndroidBridge.onError("$requestId", kind, 'HTTP ' + r.status);
                  return;
                }
                return $resultExpr.then(function(data) {
                  AndroidBridge.onSuccess("$requestId", data);
                });
              }).catch(function(e) {
                AndroidBridge.onError("$requestId", '$KIND_NETWORK', String(e));
              });
            })();
        """.trimIndent()
    }

    // ---- 엔드포인트 --------------------------------------------------------

    private suspend fun fetchActivitiesPage(limit: Int, start: Int): List<GarminActivity> {
        val url = "https://connect.garmin.com/gc-api/activitylist-service/activities/search/activities" +
            "?limit=$limit&start=$start"
        val body = fetchViaPage(url, binary = false)
        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            throw GarminApiException("활동 목록 응답을 해석하지 못했습니다: ${e.message}")
        }
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val typeKey = obj.optJSONObject("activityType")?.optString("typeKey", "") ?: ""
            GarminActivity(
                id = obj.getLong("activityId"),
                name = obj.optString("activityName", ""),
                startTimeLocal = obj.optString("startTimeLocal", ""),
                typeKey = typeKey
            )
        }
    }

    /**
     * 지정한 날짜(yyyy-MM-dd, 기기 로컬 기준)에 시작한 활동을 찾는다. 활동 목록은 최신순으로
     * 내려오므로, 페이지를 넘기다가 지정한 날짜보다 오래된 활동이 나오면 더 뒤질 필요가 없다.
     */
    suspend fun fetchActivitiesForDate(date: String): List<GarminActivity> {
        val pageSize = 20
        val maxPages = 20
        val matches = mutableListOf<GarminActivity>()

        for (page in 0 until maxPages) {
            val batch = fetchActivitiesPage(pageSize, page * pageSize)
            if (batch.isEmpty()) break

            matches += batch.filter { it.startTimeLocal.startsWith(date) }

            val oldestDateInBatch = batch.last().startTimeLocal.take(10)
            if (oldestDateInBatch.isNotEmpty() && oldestDateInBatch < date) break
        }
        return matches
    }

    /**
     * 활동의 원본 파일(.fit)을 내려받는다. Garmin은 zip으로 감싸서 내려주는 경우가 있어
     * zip이면 안의 첫 .fit 항목을 풀어서 반환한다.
     */
    suspend fun downloadFitFile(activityId: Long): ByteArray {
        val url = "https://connect.garmin.com/gc-api/download-service/files/activity/$activityId"
        val base64 = fetchViaPage(url, binary = true)
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw GarminApiException("내려받은 파일을 읽지 못했습니다: ${e.message}")
        }
        if (bytes.isEmpty()) throw GarminApiException("내려받은 파일이 비어 있습니다")

        val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return bytes

        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".fit", ignoreCase = true)) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        throw GarminApiException("zip 안에서 .fit 파일을 찾지 못했습니다")
    }

    /**
     * 지정한 날짜 **시점의** VO2max 추정치. fit 파일에는 없는 값이라 별도 조회가 필요하다.
     *
     * 함정 둘:
     * - `maxmet/latest/{date}`는 이름과 달리 **날짜를 무시하고 현재값**을 준다(실측: 3월 날짜로
     *   불러도 8월 값이 왔다). 절대 쓰면 안 된다.
     * - `daily/{date}/{date}`처럼 하루로 잡으면 그날 갱신이 없었을 때 빈 배열이 온다.
     *
     * 그래서 조금 소급한 범위로 조회하고 날짜 이전의 마지막 값을 고른다. VO2max는 LT와 달리
     * 러닝할 때마다 거의 갱신되므로 1년까지 볼 필요 없이 90일이면 충분하다(응답 크기도 아낀다).
     */
    suspend fun fetchVo2Max(date: String): DatedValue? {
        val start = LocalDate.parse(date).minusDays(VO2MAX_LOOKBACK_DAYS).toString()
        val url = "https://connect.garmin.com/gc-api/metrics-service/metrics/maxmet/daily/$start/$date"
        val body = fetchViaPage(url, binary = false)
        Log.d(TAG, "vo2max raw response: ${body.take(600)}")

        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            Log.d(TAG, "vo2max 응답 파싱 실패: ${e.message}")
            return null
        }

        var latest: DatedValue? = null
        for (i in 0 until array.length()) {
            val generic = array.optJSONObject(i)?.optJSONObject("generic") ?: continue
            val calendarDate = generic.optString("calendarDate", "")
            val value = generic.opt("vo2MaxValue") ?: generic.opt("vo2MaxPreciseValue")
            if (calendarDate.isBlank() || value !is Number) continue
            if (latest == null || calendarDate > latest.measuredDate) {
                latest = DatedValue(value.toDouble(), calendarDate)
            }
        }
        return latest
    }

    /**
     * 지정한 날짜 **시점의** 지표 하나를 가져온다.
     *
     * LT류는 매일 측정되는 값이 아니라 조건을 만족하는 러닝을 했을 때만 갱신된다. 그래서 날짜
     * 하루로 범위를 잡으면 대부분 빈 배열이 온다(실측: 3월 한 달에 4일, 2025년 11월에 6일뿐).
     * 그 시점에 유효했던 값을 알려면 **1년을 소급한 범위로 조회해 날짜 이전의 마지막 값**을
     * 골라야 한다. 범위 끝이 date이므로 응답의 모든 항목은 date 이하이고, `from`이 가장 늦은
     * 항목이 곧 "그때 유효했던 값"이다.
     *
     * 응답 형태: `[{"from":"2026-03-15","until":"...","series":"running","value":310.0,...}, ...]`
     */
    private suspend fun fetchStatAsOf(metric: String, date: String, query: String): DatedValue? {
        val start = LocalDate.parse(date).minusYears(1).toString()
        val url = "https://connect.garmin.com/gc-api/biometric-service/stats/$metric" +
            "/range/$start/$date?$query"
        val body = fetchViaPage(url, binary = false)
        Log.d(TAG, "$metric raw response: ${body.take(600)}")

        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            Log.d(TAG, "$metric 응답 파싱 실패: ${e.message}")
            return null
        }

        var latest: DatedValue? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val from = item.optString("from", "")
            val value = item.opt("value")
            if (from.isBlank() || value !is Number) continue
            // ISO 날짜라 문자열 비교로 최신 판별이 된다.
            if (latest == null || from > latest.measuredDate) {
                latest = DatedValue(value.toDouble(), from)
            }
        }
        return latest
    }

    /**
     * 지정한 날짜 시점의 LT 심박수/페이스를 가져온다.
     *
     * 예전에는 날짜 없는 `latestLactateThreshold`를 써서 **몇 달 전 러닝에도 현재 LT가 붙는**
     * 문제가 있었다. 실측으로 3월 LT 심박수는 176~178, 현재는 173으로 실제로 다르다.
     */
    suspend fun fetchLactateThreshold(date: String): LactateThreshold {
        val heartRate = fetchStatAsOf("lactateThresholdHeartRate", date, LT_QUERY)
        val speed = fetchStatAsOf("lactateThresholdSpeed", date, LT_QUERY)
        return LactateThreshold(
            heartRate = heartRate?.value,
            // 원본값은 실제 m/s의 1/10로 내려온다(예: 0.40이면 실제 4.0m/s). 보정해서 반환한다.
            // 날짜별 엔드포인트도 같은 스케일임을 실측으로 확인했다.
            speedMps = speed?.value?.times(10),
            measuredDate = heartRate?.measuredDate ?: speed?.measuredDate
        )
    }

    /** 지정한 날짜 시점의 러닝 젖산 역치 파워(FTP, W / W/kg)를 가져온다. */
    suspend fun fetchLactateThresholdPower(date: String): ThresholdPower {
        val watts = fetchStatAsOf("functionalThresholdPower", date, POWER_QUERY)
        val perKg = fetchStatAsOf("powerToWeight", date, POWER_QUERY)
        return ThresholdPower(
            watts = watts?.value,
            wattsPerKg = perKg?.value,
            measuredDate = watts?.measuredDate ?: perKg?.measuredDate
        )
    }

    private fun parseJsonLenient(body: String): Any? = try {
        if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
    } catch (e: Exception) {
        Log.d(TAG, "JSON 파싱 실패: ${e.message}")
        null
    }

    /**
     * Garmin API 응답 구조가 문서화되어 있지 않아, 후보 키 목록 중 먼저 일치하는 숫자 필드를
     * 트리 전체에서 재귀적으로 찾는 방식으로 방어적으로 파싱한다.
     */
    private fun findNumber(node: Any?, exactKeys: List<String>): Double? {
        when (node) {
            is JSONObject -> {
                for (key in exactKeys) {
                    val v = node.opt(key)
                    if (v is Number) return v.toDouble()
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findNumber(node.opt(keys.next()), exactKeys)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findNumber(node.opt(i), exactKeys)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "GarminWebBridge"
        private const val BASE_URL = "https://connect.garmin.com/modern/"
        private const val PAGE_LOAD_FAILED = "Garmin 페이지를 열지 못했습니다"

        /** JS 쪽에서 실패 종류를 구분해 넘겨주는 값. */
        private const val KIND_AUTH = "auth"
        private const val KIND_NETWORK = "network"
        private const val KIND_SERVER = "server"
        private const val KIND_API = "api"

        /** 로그인 페이지에 머무는 게 진짜 만료인지 확인하기 전에 기다리는 시간. */
        private const val SIGN_IN_CONFIRM_DELAY_MS = 2_500L
        private const val READY_TIMEOUT_MS = 30_000L
        private const val TEXT_TIMEOUT_MS = 30_000L
        private const val BINARY_TIMEOUT_MS = 120_000L
        private const val MAX_ATTEMPTS = 3

        // 웹이 실제로 쓰는 쿼리 그대로. sport 값의 대소문자가 지표마다 다르니 건드리지 말 것
        // (LT는 RUNNING, 파워는 Running).
        private const val LT_QUERY = "aggregation=daily&aggregationStrategy=LATEST&sport=RUNNING"
        private const val POWER_QUERY = "aggregation=daily&sport=Running"
        private const val VO2MAX_LOOKBACK_DAYS = 90L
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)

        private fun isSignInUrl(url: String): Boolean =
            url.contains("/signin") || url.contains("sso.garmin.com")

        /**
         * 연결이 끊기면 JS가 "TypeError: Failed to fetch"를 던지는데, 그대로 화면에 내보내면
         * 무슨 상황인지 알 수 없다. 사람이 읽을 수 있는 말로 바꿔준다.
         */
        private fun readableNetworkMessage(raw: String): String =
            if (raw.contains("Failed to fetch", ignoreCase = true)) "네트워크 연결이 끊겼습니다" else raw
    }
}
