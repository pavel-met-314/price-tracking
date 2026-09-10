package com.example.otsled.data.update

import android.content.Context
import com.example.otsled.data.update.AppUpdateFeed.ReleaseInfo
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
                AppUpdateFeed.parseRelease(body) ?: throw IOException(
                    "в последнем релизе нет APK с версией — проверь, что CI выложил $APK_NAME",
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
            val target = File(dir, APK_NAME)
            // Неудалившийся старый файл опаснее лишнего гигабайта: установщик взял бы прошлую
            // версию и показал бы «успех» на несуществующем обновлении.
            if (target.exists() && !target.delete()) throw IOException("старый файл установки занят")

            val request = Request.Builder().url(info.apkUrl).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("скачивание не удалось (${response.code})")
                val stream = response.body?.byteStream() ?: throw IOException("пустой ответ")
                val total = response.body?.contentLength()?.takeIf { it > 0 }

                var written = 0L
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

    /** Устанавливать можно только по тапу пользователя: файл лежит в кэше, путь наружу закрыт. */
    fun downloadedApk(): File? = File(context.cacheDir, APK_DIR).let { dir ->
        File(dir, APK_NAME).takeIf { it.exists() && it.length() > MIN_APK_BYTES }
    }

    fun forgetDownload() {
        runCatching { downloadedApk()?.delete() }
    }

    companion object {
        const val APK_DIR = "updates"
        const val APK_NAME = "app-debug.apk"
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
