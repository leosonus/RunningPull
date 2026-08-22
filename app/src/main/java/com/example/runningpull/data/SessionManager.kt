package com.example.runningpull.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Garmin Connect 로그인 세션(쿠키)을 암호화 저장소에 보관한다.
 * 앱을 재실행해도 저장된 세션이 있으면 다시 로그인하지 않도록 하기 위함.
 *
 * csrf 토큰은 저장하지 않는다. `GarminWebBridge`가 요청할 때마다 로그인된 페이지의 JS
 * 컨텍스트에서 새로 뽑아 쓰기 때문에 여기 보관해봐야 아무도 읽지 않는다.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveSession(cookieHeader: String) {
        prefs.edit()
            .putString(KEY_COOKIE_HEADER, cookieHeader)
            .apply()
    }

    fun cookieHeader(): String? = prefs.getString(KEY_COOKIE_HEADER, null)

    fun hasSession(): Boolean = !cookieHeader().isNullOrBlank()

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "runningpull_session"
        private const val KEY_COOKIE_HEADER = "cookie_header"
    }
}
