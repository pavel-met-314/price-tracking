package com.example.otsled.data.update

import android.content.Context
import com.example.otsled.data.update.AppUpdateFeed.ReleaseInfo
import com.example.otsled.util.AppBuildInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Проверка обновлений и скачивание APK.
 *
 * Наружу ходит только на `api.github.com`: репозиторий публичный, токен не нужен, и этот адрес не
 * зависит от вёрстки магазина (в отличие от пути к релизным ассетам на самом GitHub — он ведёт на
 * временное хранилище, и его мы не парсим: берём `browser_download_url` из ответа API).
 *
 * Ошибки возвращаются текстом, который можно показать человеку: «проверка» — действие фоновое по
 * ощущению, и «что-то пошло не так» в нём бесполезно.
 */
class UpdateChecker(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Может ли эта сборка ставить себе обновления сама. Магазинная сборка обновляется магазином, и
     * лезть к системному установщику для неё — обход правил площадки; поэтому проверка обновлений в
     * ней остаётся справочной, а кнопки скачивания и установки исчезают.
     */
    val canInstallUpdates: Boolean
        get() = AppBuildInfo.isDebuggable(context)

    /** Какой файл релиза считать своим: у debug и release-сборок разные подписи. */
    private val assetName: String
        get() = AppUpdateFeed.apkAssetFor(AppBuildInfo.isDebuggable(context))

    /** Что ответил GitHub про последний релиз, или причина, по которой он не ответил. */
    suspend fun fetchLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(AppUpdateFeed.LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) throw IOException("GitHub ответил ${response.code}")
                AppUpdateFeed.parseRelease(
                    json = body,
                    assetName = assetName,
                    allowAnyApkFallback = canInstallUpdates,
                ) ?: throw IOException(
                    "в последнем релизе нет $assetName с версией — проверь, что CI его выложил",
                )
            }
        }
    }

    /**
     * Скачивает APK в кэш и возвращает файл. Прогресс — доля от 0 до 1, если GitHub сообщил длину;
     * без `Content-Length` доля не показывается, а не врёт.
     */
    suspend fun downloadApk(
        info: ReleaseInfo,
        onProgress: (Float?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, APK_DIR)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("не удалось создать папку кэша")
            // Папку чистим целиком, а не только свой файл: APK, оставшийся от сборки другого типа,
            // — это готовый «успех» установки не того файла.
            dir.listFiles()?.forEach { stale ->
                if (!stale.delete()) throw IOException("не удалось очистить ${stale.name}")
            }
            val target = File(dir, info.assetName)

            val request = Request.Builder().url(info.apkUrl).header("User-Agent", USER_AGENT).build()
            // Счётчик живёт вне `use`: после закрытия ответа он ещё нужен для проверки размера.
            var written = 0L
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("скачивание не удалось (${response.code})")
                val stream = response.body?.byteStream() ?: throw IOException("пустой ответ")
                val total = response.body?.contentLength()?.takeIf { it > 0 }

                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(total?.let { (written.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }

            if (written < MIN_APK_BYTES) throw IOException("скачалось только $written байт")
            if (!isZipArchive(target)) throw IOException("это не APK: у файла нет сигнатуры архива")
            target
        }
    }

    /** APK — zip; первые байты проверяем, потому что «200 OK» ничего не гарантирует. */
    private fun isZipArchive(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    /** Короткое человеческое объяснение ошибки — то, что видно под кнопкой. */
    fun describe(error: Throwable): String = when (error) {
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "сеть недоступна"
        else -> error.message ?: error.javaClass.simpleName
    }

    /**
     * Скачанный APK, если он ещё в кэше. Ищем любой `.apk` в папке обновлений, а не файл по имени:
     * система могла почистить кэш между этапами, и предлагать установщику несуществующий путь
     * нельзя — на тапе он бы просто сообщил об ошибке без причины.
     */
    fun downloadedApk(): File? = File(context.cacheDir, APK_DIR)
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".apk") && it.length() > MIN_APK_BYTES }
        .maxByOrNull { it.lastModified() }

    fun forgetDownload() {
        runCatching {
            File(context.cacheDir, APK_DIR).listFiles()?.forEach { file -> runCatching { file.delete() } }
        }
    }

    companion object {
        const val APK_DIR = "updates"
        private const val USER_AGENT = "Otsled-update-check"
        private const val MIN_APK_BYTES = 200_000L
        private const val BUFFER_SIZE = 32 * 1024

        /**
         * Отдельный клиент: тут нужны длинные read-таймауты (файл ~20 МБ по мобильной сети), а
         * клиенту парсинга страниц такие таймауты только вредят.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
