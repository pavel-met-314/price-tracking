package com.example.otsled.util

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Версия и тип установленной сборки — строка для экрана настроек.
 *
 * Появилась не для красоты: «новой функции не видно» и «APK не установился» на глаз одинаковы,
 * а лечатся по-разному. Подпись debug-сборки берётся из ключа в репозитории именно для того,
 * чтобы сборки из разных прогонов CI ставились поверх установленной версии; если ключ всё же
 * сменился, `adb install -r` отказывает, и в настройках это видно по старой версии.
 */
object AppBuildInfo {
    /** Версия этой же сборки в человекочитаемом виде («1.2») — с ней сравниваем релиз на GitHub. */
    fun versionName(context: Context): String? = info(context)?.versionName

    private fun info(context: Context) = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    /** Например «1.1 (2) — debug». */
    fun describe(context: Context): String {
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        val version = info?.versionName ?: "?"
        val code = info?.let {
            @Suppress("DEPRECATION")
            it.versionCode
        }
        val debuggable = runCatching {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)

        val build = if (code == null) version else "$version ($code)"
        return if (debuggable) "$build — debug" else "$build — release"
    }
}
