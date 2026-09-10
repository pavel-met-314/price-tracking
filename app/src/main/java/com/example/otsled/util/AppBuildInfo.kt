package com.example.otsled.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo

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

    /**
     * Debug-сборка ли это. По нему выбирается, какой APK предлагать: поставить debug поверх
     * магазинной сборки нельзя (разные подписи), и предложить его — значит получить отказ
     * установщика без объяснения причины.
     */
    fun isDebuggable(context: Context): Boolean = runCatching {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }.getOrDefault(true)

    /** Например «1.1 (2) — debug». */
    fun describe(context: Context): String {
        val build = info(context)?.let { format(it) } ?: "?"
        return if (isDebuggable(context)) "$build — debug" else "$build — release"
    }

    private fun format(info: PackageInfo): String {
        val version = info.versionName ?: "?"
        val code = runCatching {
            @Suppress("DEPRECATION")
            info.versionCode
        }.getOrNull()
        return if (code == null) version else "$version ($code)"
    }

    private fun info(context: Context): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
}
