package dev.glasslauncher.tv.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import dev.glasslauncher.tv.service.HomeAccessibilityService

/** Запуск системных экранов с фолбэками: на ТВ-прошивках часть стандартных Activity вырезана. */
object SystemIntents {

    private const val TAG = "SystemIntents"

    fun start(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "not found: $intent")
            } catch (e: SecurityException) {
                Log.w(TAG, "denied: $intent", e)
            }
        }
        Toast.makeText(context, "Экран недоступен на этом устройстве", Toast.LENGTH_SHORT).show()
        return false
    }

    fun openSystemSettings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS))

    fun openAccessibilitySettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /**
     * Панель быстрых настроек Google TV. Публичного интента у неё нет: пробуем глобальное действие
     * через нашу службу, иначе открываем полные настройки.
     */
    fun openQuickSettings(context: Context) {
        if (HomeAccessibilityService.openQuickSettings()) return
        openSystemSettings(context)
    }

    fun openAppInfo(context: Context, pkg: String) = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
    )

    @Suppress("DEPRECATION")
    fun uninstall(context: Context, pkg: String) = start(
        context,
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")),
        Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")),
    )

    fun openHomeSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}
