package com.example.otsled.data.update

/**
 * Ответ GitHub на вопрос «какая сборка последняя»: версия и ссылка на APK.
 *
 * Репозиторий и имя файла — часть контракта: CI выкладывает сборку в релиз с тегом `latest` под
 * именем `app-debug.apk`, а версию пишет в заголовок («Последняя сборка (1.2)»). Меняется имя или
 * формат заголовка — меняется и этот парсер, поэтому он вынесен отдельно и полностью покрыт
 * тестами: ловить расхождение «на телефоне ничего не нашлось» слишком дорого.
 */
object AppUpdateFeed {

    const val REPO = "pavel-met-314/price-tracking"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val APK_ASSET = "app-debug.apk"

    /** Ключи JSON-ответа, от которых зависит, где искать заголовок и ассеты. */
    private const val ASSETS_KEY = "\"assets\""
    private const val TAG_KEY = "\"tag_name\""

    /** Любая строка `"name": "…"`, включая `\"` внутри значения. */
    private val NAME_REGEX = Regex("""(?iu)"name"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
    private val APK_URL_REGEX = Regex(
        """(?iu)"browser_download_url"\s*:\s*"((?:[^"\\]|\\.)*\.apk(?:[^"\\]|\\.)*)"""",
        RegexOption.IGNORE_CASE,
    )

    /** Версия в кавычках/скобках/цифрах: «(1.2)», «1.2.3». */
    private val VERSION_REGEX = Regex("""(\d+(?:\.\d+){1,3})""")

    /**
     * Разбирает JSON релиза. `null` — когда нужного в ответе нет: тогда приложение говорит
     * «не удалось проверить», а не «обновлений нет»: молчаливое «всё актуально» при сломанном
     * парсере — худший из возможных ответов, пользователь перестает доверять проверке.
     */
    fun parseRelease(json: String?): ReleaseInfo? {
        if (json.isNullOrBlank()) return null

        // Ссылку ищем только внутри «assets»: иначе первое совпадение пришлось бы на url'ы самого
        // релиза, а это не файл.
        val assets = json.substringAfter(ASSETS_KEY, "")
        val urls = APK_URL_REGEX.findAll(assets).map { it.groupValues[1].unescape() }.toList()
        // В релизе может лежать не один APK (например, вместе с release-сборкой): берём свой,
        // а если имени не совпало — первый, чтобы не остаться вовсе без обновления.
        val apkUrl = urls.firstOrNull { it.substringAfterLast('/').equals(APK_ASSET, ignoreCase = true) }
            ?: urls.firstOrNull()
            ?: return null
        // Заголовок релиза ищем после «tag_name»: до него идёт объект author, у которого тоже есть
        // «name» — имя разработчика, а не название сборки.
        val title = NAME_REGEX.find(json.substringAfter(TAG_KEY, json))
            ?.groupValues?.get(1)?.unescape()
        val version = title?.let { VERSION_REGEX.find(it)?.groupValues?.get(1) } ?: return null

        return ReleaseInfo(versionName = version, apkUrl = apkUrl, title = title)
    }

    /**
     * Нужна ли установка: [remote] новее [local].
     *
     * Сравнение строк тут не годится — «1.10» меньше «1.9», если сравнивать посимвольно, а
     * разрядная версия именно так и растёт. Недостающие разряды считаются нулями, нечисловые
     * хвосты («1.2-rc1») отбрасываются.
     */
    fun isNewer(local: String?, remote: String?): Boolean {
        val a = local.segments()
        val b = remote.segments()
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (right != left) return right > left
        }
        return false
    }

    private fun String?.segments(): List<Int> = this
        ?.takeIf { it.isNotBlank() }
        ?.split('.', '-', '_')
        ?.mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
        .orEmpty()

    private fun String.unescape(): String = replace("\\\"", "\"").replace("\\\\", "\\")

    data class ReleaseInfo(
        val versionName: String,
        val apkUrl: String,
        val title: String? = null,
    )
}
