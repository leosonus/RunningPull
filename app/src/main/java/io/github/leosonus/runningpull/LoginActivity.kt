package io.github.leosonus.runningpull

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.leosonus.runningpull.data.SessionManager

/**
 * Garmin Connect의 실제 로그인 페이지를 WebView로 띄워 사용자가 직접 로그인하게 하고,
 * 로그인 성공 후 세션 쿠키를 뽑아 SessionManager에 저장한다.
 * 아이디/비번을 앱이 직접 다루지 않으므로 2단계 인증 등도 Garmin 페이지가 그대로 처리한다.
 *
 * 로그인이 끝나 connect.garmin.com 안쪽으로 돌아오는 순간 화면을 덮고 바로 빠져나간다.
 * 예전에는 여기서 csrf 토큰을 뽑으려고 `evaluateJavascript` 콜백을 기다렸는데, 그 사이
 * 로그인된 가민 대시보드가 그대로 그려져서 화면에 잠깐 스쳐 보였다(렌더 속도에 따라 간헐적).
 * 그렇게 저장한 csrf는 어디서도 읽지 않는다 — `GarminWebBridge`가 요청할 때마다 자기 페이지의
 * JS 컨텍스트에서 새로 뽑아 쓰기 때문이다. 그래서 기다리지 않고 쿠키만 챙겨 즉시 닫는다.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var webView: WebView
    private lateinit var progress: View
    private var sessionCaptured = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(applicationContext)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.loginProgress)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // 기본값은 "가림". 페이지 로딩이 끝나고 로그인된 가민 화면이 아님을 확인했을 때만
        // 보여준다. 반대로 두면(기본 보임 + 감지되면 가림) 감지가 한 박자라도 늦는 순간
        // 가민 화면이 새어 나오고, 그 타이밍이 리다이렉트 홉 수와 렌더 속도에 좌우돼
        // 간헐적으로 나타난다 — 테스트로는 잡히지 않는 종류의 버그가 된다.
        coverWebView()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 이동이 시작되는 순간 무조건 가린다. 로그인 제출 이후의 리다이렉트 체인은
                // 여기서 URL이 한 번만(체인의 첫 주소로) 보고되기 때문에, 도착지를 보고
                // 판단하면 늦는다. 일단 덮고 나서 도착지가 로그인 폼일 때만 다시 보여준다.
                coverWebView()
                if (!sessionCaptured && url != null && isLoggedInUrl(url)) tryCaptureSession(url)
            }

            /** 리다이렉트가 커밋될 때마다 불린다. 체인 중간에 대시보드가 떠도 여기서 다시 덮인다. */
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null && isLoggedInUrl(url)) coverWebView()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (sessionCaptured) return

                // 가려야 하는 건 "로그인이 끝난 가민 화면" 하나뿐이다. 그 외에는 전부 보여준다
                // — 로그인 폼, 2단계 인증, 소셜 로그인 같은 외부 인증 페이지까지. 반대로
                // "폼일 때만 보여준다"로 두면 예상 못 한 인증 페이지에서 영영 가린 채 갇힌다.
                if (url == null || !isLoggedInUrl(url)) {
                    revealWebView()
                    return
                }

                coverWebView()
                if (tryCaptureSession(url)) return
                // 쿠키가 아직 안 올라온 드문 경우. 잠깐 뒤 한 번 더 보고, 그래도 안 되면
                // 무한 로딩으로 붙잡아 두지 않도록 화면을 되돌린다.
                view.postDelayed({
                    if (!sessionCaptured && !tryCaptureSession(view.url)) revealWebView()
                }, RETRY_DELAY_MS)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                // 메인 프레임이 아예 안 열리면 빈 화면만 남아 사용자가 뭘 해야 할지 알 수 없다.
                if (!request.isForMainFrame) return
                revealWebView()
                Toast.makeText(
                    this@LoginActivity,
                    "로그인 페이지를 열지 못했습니다 (${error.description}). 네트워크 상태를 확인해주세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        webView.loadUrl(SIGN_IN_URL)
    }

    /**
     * 로그인이 끝나 connect.garmin.com 안쪽 화면으로 돌아온 시점. 세션으로 인정하는 유일한
     * 조건이자, 화면을 가려야 하는 유일한 조건이기도 하다.
     */
    private fun isLoggedInUrl(url: String): Boolean =
        url.startsWith("https://connect.garmin.com/") &&
            !url.contains("/signin") &&
            !url.contains("sso.garmin.com")

    /** 쿠키가 준비돼 있으면 저장하고 즉시 화면을 닫는다. 준비 안 됐으면 false. */
    private fun tryCaptureSession(url: String?): Boolean {
        if (sessionCaptured || url == null || !isLoggedInUrl(url)) return false
        val cookieHeader = CookieManager.getInstance().getCookie("https://connect.garmin.com")
        if (cookieHeader.isNullOrBlank()) return false

        sessionCaptured = true
        sessionManager.saveSession(cookieHeader)
        // 실제 인증은 prefs가 아니라 WebView 쿠키 저장소가 쥐고 있다. WebView는 알아서
        // 주기적으로만 디스크에 쓰기 때문에, 여기서 바로 flush하지 않으면 방금 받은 로그인
        // 쿠키가 디스크에 닿기 전에 프로세스가 죽을 수 있다(= 다시 로그인해야 함).
        CookieManager.getInstance().flush()
        setResult(RESULT_OK)
        finish()
        return true
    }

    private fun coverWebView() {
        webView.visibility = View.INVISIBLE
        progress.visibility = View.VISIBLE
    }

    private fun revealWebView() {
        webView.visibility = View.VISIBLE
        progress.visibility = View.GONE
    }

    companion object {
        // 끝에 슬래시가 없으면 Garmin 서버가 http://.../signin/ 로 잠깐 리다이렉트했다가 다시
        // https로 돌아오는데, WebView는 targetSdk 28+에서 그 중간 http 홉을 cleartext로 차단한다
        // (ERR_CLEARTEXT_NOT_PERMITTED). 처음부터 슬래시를 붙여 그 홉을 건너뛴다.
        private const val SIGN_IN_URL = "https://connect.garmin.com/signin/"

        private const val RETRY_DELAY_MS = 500L
    }
}
