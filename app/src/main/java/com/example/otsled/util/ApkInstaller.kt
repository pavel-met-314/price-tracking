package com.example.otsled.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Запуск системного установщика для APK, скачанного в кэш приложения.
 *
 * FileProvider обязателен: на `file://` система с Android 7 отвечает FileUriExposedException, и
 * чужому приложению (установщику) такой URI не читается. Отдельное разрешение на Android 8+ —
 * «установка неизвестных приложений» для самого Otsled: без него `startActivity` на установщик
 * просто не сработает, и об этом нужно сказать прямо, а не молча не отреагировать на тап.
 */
object ApkInstaller {
    /** Совпадает с <provider android:authorities> в манифесте; меняется там — менять и здесь. */
    const val FILE_PROVIDER_AUTHORITY = "com.example.otsled.fileprovider"

    private const val APK_MIME = "application/vnd.android.package-archive"

    fun installIntent(context: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apk), APK_MIME)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }

    /** true — разрешение на установку ещё не выдано (для Android 8 и новее). */
    fun needsInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching { !context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }

    /** Экран настроек с переключателем «разрешить установку» для этого приложения. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
