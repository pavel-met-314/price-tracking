package com.example.otsled.data.parser

import android.content.Context
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Общая CookieJar для OkHttp поверх куки-хранилища WebView.
 *
 * Смысл: проверка браузера проходит один раз внутри WebView, а её «клиренс» — это cookie.
 * Если OkHttp о ней не знает, каждый фоновый запрос снова получает заглушку, и проверка цены
 * вырождается в медленный WebView-путь. Общая jar делает быстрый путь рабочим.
 */
class WebViewCookieJar(context: Context) : CookieJar {
    private val manager: CookieManager = CookieManager.getInstance().also {
        runCatching { it.setAcceptCookie(true) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = runCatching { manager.getCookie(url.toString()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        return header.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .mapNotNull { pair -> runCatching { Cookie.parse(url, pair) }.getOrNull() }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return

        var changed = false
        cookies.forEach { cookie ->
            val applied = runCatching { manager.setCookie(url.toString(), cookie.toString()) }
                .getOrDefault(false)
            if (applied) changed = true
        }

        // Без flush куки остаются только в памяти WebView и теряются при остановке процесса —
        // для фоновой проверки цен это означало бы «прогретая сессия» до первой перезагрузки.
        if (changed) runCatching { manager.flush() }
    }
}
