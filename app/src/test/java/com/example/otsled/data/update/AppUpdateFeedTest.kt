package com.example.otsled.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ответа GitHub про последний релиз и сравнение версий.
 *
 * Оба места ошибаются «в тихую»: неверно взятая версия приводит к «обновлений нет» навсегда, а
 * строковое сравнение версий считает «1.10» старее «1.9». Ответ API моделируется здесь буквально —
 * с `author.name` и массивом `assets`, потому что именно эти поля раньше всего подсовывают парсеру
 * чужие значения.
 */
class AppUpdateFeedTest {

    private val releaseJson = """
        {
          "url": "https://api.github.com/repos/pavel-met-314/price-tracking/releases/386290378",
          "assets_url": "https://api.github.com/repos/pavel-met-314/price-tracking/releases/386290378/assets",
          "html_url": "https://github.com/pavel-met-314/price-tracking/releases/tag/latest",
          "author": { "login": "pavel-met-314", "name": "Pavel 9" },
          "tag_name": "latest",
          "name": "Последняя сборка (1.2)",
          "body": "Как поставить: скачать файл, открыть.",
          "prerelease": false,
          "assets": [
            {
              "name": "app-debug.apk",
              "size": 19400343,
              "browser_download_url": "https://github.com/pavel-met-314/price-tracking/releases/download/latest/app-debug.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesVersionAndApkUrl() {
        val info = AppUpdateFeed.parseRelease(releaseJson)!!

        assertEquals("1.2", info.versionName)
        assertEquals(
            "https://github.com/pavel-met-314/price-tracking/releases/download/latest/app-debug.apk",
            info.apkUrl,
        )
        assertEquals("Последняя сборка (1.2)", info.title)
    }

    @Test
    fun versionComesFromReleaseTitleNotFromAuthor() {
        // У author.name тоже есть цифры («Pavel 9»): взять его — значит вечное «обновлений нет».
        val info = AppUpdateFeed.parseRelease(releaseJson)!!

        assertEquals("1.2", info.versionName)
        assertEquals("Последняя сборка (1.2)", info.title)
    }

    @Test
    fun missingApkAssetIsNotReportedAsUpToDate() {
        val json = releaseJson.replace("\"browser_download_url\"", "\"url\"")

        assertNull(AppUpdateFeed.parseRelease(json))
    }

    @Test
    fun titleWithoutVersionIsRejected() {
        val json = releaseJson.replace("Последняя сборка (1.2)", "Последняя сборка")

        assertNull(AppUpdateFeed.parseRelease(json))
    }

    @Test
    fun garbageAndEmptyResponsesParseToNull() {
        assertNull(AppUpdateFeed.parseRelease(null))
        assertNull(AppUpdateFeed.parseRelease(""))
        assertNull(AppUpdateFeed.parseRelease("""{"message":"Not Found"}"""))
    }

    @Test
    fun escapedQuotesInsideTitleSurvive() {
        val json = """
            { "tag_name": "latest", "name": "Сборка \"тест\" (1.4)",
              "assets": [ { "browser_download_url": "https://example.com/a.apk" } ] }
        """.trimIndent()

        val info = AppUpdateFeed.parseRelease(json)!!

        assertEquals("1.4", info.versionName)
        assertEquals("Сборка \"тест\" (1.4)", info.title)
    }

    @Test
    fun versionCompareIsNumericBySegments() {
        assertTrue(AppUpdateFeed.isNewer("1.2", "1.3"))
        // Ровно то, где сравнивание строк врёт: «1.10» лексикографически меньше «1.9».
        assertTrue(AppUpdateFeed.isNewer("1.9", "1.10"))
        assertTrue(AppUpdateFeed.isNewer("1.2", "1.2.1"))
        assertFalse(AppUpdateFeed.isNewer("1.2", "1.2"))
        assertFalse(AppUpdateFeed.isNewer("2.0", "1.9"))
        assertFalse(AppUpdateFeed.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun unknownVersionsAreNotInvented() {
        // Своей версии не знаем — считаем, что удалённая новее: хуже от лишней установки не будет,
        // а вот от «обновлений нет» при сломанном AppBuildInfo — будет.
        assertTrue(AppUpdateFeed.isNewer(null, "1.2"))
        assertTrue(AppUpdateFeed.isNewer("", "1.2"))
        assertFalse(AppUpdateFeed.isNewer("1.2", null))
        assertFalse(AppUpdateFeed.isNewer(null, null))
    }

    @Test
    fun feedPointsToThePublicApiOfThisRepo() {
        assertEquals("pavel-met-314/price-tracking", AppUpdateFeed.REPO)
        assertEquals(
            "https://api.github.com/repos/pavel-met-314/price-tracking/releases/latest",
            AppUpdateFeed.LATEST_RELEASE_API,
        )
        assertEquals("app-debug.apk", AppUpdateFeed.APK_ASSET)
    }
}
