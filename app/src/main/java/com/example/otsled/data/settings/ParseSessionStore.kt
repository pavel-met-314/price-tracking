package com.example.otsled.data.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Память о том, как сайт пускает на страницу товара.
 *
 * Анти-бот проверка стоит дорого: WebView-загрузка — это до 35 секунд на один товар. Поэтому
 * после первого же «проверьте браузер» мы на время перестаём дёргать быстрый HTTP-путь и идём
 * сразу в WebView, а после успеха, наоборот, некоторое время пробуем HTTP — куки защиты уже
 * сохранены в общий CookieManager (см. WebViewCookieJar).
 */
class ParseSessionStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** true — HTTP-запрос почти наверняка упрётся в проверку браузера, нужен WebView. */
    fun shouldPreferWebView(now: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong(KEY_CHALLENGE_UNTIL, 0L) > now

    fun challengeFailures(): Int = prefs.getInt(KEY_CHALLENGE_FAILURES, 0)

    fun markChallengeHit(now: Long = System.currentTimeMillis()) {
        val failures = prefs.getInt(KEY_CHALLENGE_FAILURES, 0) + 1
        val cooldown = CHALLENGE_COOLDOWN_BASE_MS * failures.coerceAtMost(MAX_COOLDOWN_STEPS)
        prefs.edit()
            .putInt(KEY_CHALLENGE_FAILURES, failures)
            .putLong(KEY_CHALLENGE_UNTIL, now + cooldown)
            .apply()
    }

    fun markSuccess(now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(KEY_CHALLENGE_FAILURES, 0)
            .putLong(KEY_CHALLENGE_UNTIL, 0L)
            .putLong(KEY_HTTP_TRUSTED_UNTIL, now + HTTP_TRUST_WINDOW_MS)
            .apply()
    }

    /** OkHttp-путь имеет смысл, пока общий CookieManager ещё «прогрет». */
    fun isHttpTrusted(now: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong(KEY_HTTP_TRUSTED_UNTIL, 0L) > now

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "otsled_parse_session"
        private const val KEY_CHALLENGE_UNTIL = "challenge_until"
        private const val KEY_CHALLENGE_FAILURES = "challenge_failures"
        private const val KEY_HTTP_TRUSTED_UNTIL = "http_trusted_until"

        private const val CHALLENGE_COOLDOWN_BASE_MS = 10 * 60_000L
        private const val MAX_COOLDOWN_STEPS = 6
        private const val HTTP_TRUST_WINDOW_MS = 6 * 60 * 60_000L
    }
}
