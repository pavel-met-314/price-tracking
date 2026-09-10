package com.example.otsled.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun severalApksDoNotPickTheWrongOne() {
        // JSON собран склейкой: вложенный сырой литерал с кавычками внутри читается хуже, чем
        // этот список полей, а проверять надо именно порядок ассетов.
        val q = "\""
        fun field(key: String, value: String) = q + key + q + ": " + q + value + q
        val twoAssets = "{" +
            field("tag_name", "latest") + ", " +
            field("name", "Сборка (1.5)") + ", " +
            q + "assets" + q + ": [" +
            "{" + field("name", "app-release.apk") + ", " +
            field("browser_download_url", "https://example.com/app-release.apk") + "}, " +
            "{" + field("name", "app-debug.apk") + ", " +
            field("browser_download_url", "https://example.com/app-debug.apk") + "}" + "]}"

        val info = AppUpdateFeed.parseRelease(twoAssets)!!

        // Первый APK в списке — чужой (release-сборка); взять его значило бы предложить не то.
        assertEquals("https://example.com/app-debug.apk", info.apkUrl)
        assertEquals("1.5", info.versionName)
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

    @Test
    fun assetNameFollowsBuildType() {
        assertEquals("app-debug.apk", AppUpdateFeed.apkAssetFor(debuggable = true))
        assertEquals("app-release.apk", AppUpdateFeed.apkAssetFor(debuggable = false))
    }

    @Test
    fun debugBuildAcceptsAnyApkOfTheRelease() {
        // Прошим app-debug.apk, а в релизе только app-release.apk: совпадения нет, но запасной
        // путь разрешён — все debug-сборки подписаны одним ключом из репозитория, и перепутать
        // файлы между собой не страшно.
        val json = releaseWithApkNamed("app-release.apk")

        val info = AppUpdateFeed.parseRelease(json, AppUpdateFeed.APK_ASSET_DEBUG, allowAnyApkFallback = true)!!

        assertEquals("https://example.com/app-release.apk", info.apkUrl)
        assertEquals("app-release.apk", info.assetName)
    }

    @Test
    fun storeBuildRefusesForeignApk() {
        // Магазинной сборке debug-APK поставить нельзя: подписи разные, установщик откажет.
        // Честнее «в релизе нет app-release.apk», чем кнопка, которая гарантированно падает.
        val foreign = releaseWithApkNamed("app-debug.apk")

        assertNull(
            AppUpdateFeed.parseRelease(foreign, AppUpdateFeed.APK_ASSET_RELEASE, allowAnyApkFallback = false),
        )
        assertNotNull(
            AppUpdateFeed.parseRelease(foreign, AppUpdateFeed.APK_ASSET_RELEASE, allowAnyApkFallback = true),
        )
    }

    private fun releaseWithApkNamed(fileName: String): String {
        val q = "\""
        fun field(key: String, value: String) = q + key + q + ": " + q + value + q
        return "{" +
            field("tag_name", "latest") + ", " +
            field("name", "Сборка (1.6)") + ", " +
            q + "assets" + q + ": [ {" + field("browser_download_url", "https://example.com/$fileName") + "} ]}"
    }
}
