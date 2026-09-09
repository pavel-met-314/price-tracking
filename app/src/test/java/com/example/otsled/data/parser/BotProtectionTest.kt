package com.example.otsled.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `stubSummary` — единственная строка, по которой на расстоянии видно, что именно сайт показал
 * вместо страницы: автотест, капчу или «доступ ограничен». Если она начнёт врать, вся диагностика
 * блокировок превратится в гадание, поэтому проверяем её как разбор цен.
 */
class BotProtectionTest {

    @Test
    fun stubSummaryKeepsVisibleTextOnly() {
        val html = """
            <html><head><style>body{color:red}</style><script>var hack = "ключ";</script></head>
            <body><div class="wrap">Выполняется проверка  вашего
              веб-браузера…</div><!-- служебный комментарий --></body></html>
        """.trimIndent()

        assertEquals("Выполняется проверка вашего веб-браузера…", BotProtection.stubSummary(html))
    }

    @Test
    fun stubSummaryIgnoresScriptsInsideBody() {
        val html = "<body><p>Откройте страницу снова</p><script>var text = 'СЕКРЕТНЫЙ ТЕКСТ';</script></body>"

        assertEquals("Откройте страницу снова", BotProtection.stubSummary(html))
    }

    @Test
    fun stubSummaryIsLimitedInLength() {
        val html = "<body>" + "а".repeat(500) + "</body>"

        assertEquals(160, BotProtection.stubSummary(html)!!.length)
    }

    @Test
    fun stubSummaryIsSilentOnEmptyPage() {
        assertNull(BotProtection.stubSummary(null))
        assertNull(BotProtection.stubSummary("   "))
        assertNull(BotProtection.stubSummary("<html><body>   </body></html>"))
    }

    @Test
    fun challengeDetectionStillWorks() {
        assertTrue(BotProtection.isChallengeHtml("<html><body>Выполняется проверка вашего браузера</body></html>"))
        assertTrue(BotProtection.stubSummary("<body>captcha: подтвердите</body>")!!.contains("captcha"))
    }

    @Test
    fun `page with catalog links is not a challenge even when captcha script present`() {
        // Живой случай с телефона: страница поиска содержит ссылку на каталог и текст меню,
        // а «captcha» в ней — только имя стороннего скрипта. Значит, страница настоящая.
        val html = """
            <html><head><script src="https://api-site.com/captcha.js"></script></head>
            <body>
            <div>0 Мои желания Самая большая коллекция пробников Вход / Регистрация</div>
            <a href="/katalog/na_muzhchinye-arekate-123.html">Ganymede</a>
            """ + "x".repeat(7_000) + """
            </body></html>
        """.trimIndent()
        assertFalse(BotProtection.isChallengeHtml(html))
    }

    @Test
    fun `tiny page with marker is a challenge despite product link`() {
        // Подозрительная страница в несколько строк — это обёртка заглушки, а не выдача каталога.
        val html = """<html><body><p>Обнаружена капча. <a href="/katalog/x.html">?</a></p></body></html>"""
        assertTrue(BotProtection.isChallengeHtml(html))
    }

    @Test
    fun `stub summary of header-only page shows menu text`() {
        val html = "<html><body class=\"b-catalog\"><div>0 Мои желания Главная Бренды</div></body></html>"
        assertEquals("0 Мои желания Главная Бренды", BotProtection.stubSummary(html))
    }
}
