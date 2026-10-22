package dev.glasslauncher.tv.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import dev.glasslauncher.tv.service.HomeAccessibilityService

/** Проверки «лаунчер жив и настроен»: служба спец. возможностей, дефолтный HOME, стоковые лаунчеры. */
object SystemStatus {

    private const val TAG = "SystemStatus"

    val STOCK_LAUNCHERS = listOf(
        "com.google.android.apps.tv.launcherx",   // Google TV
        "com.google.android.tvlauncher",          // Android TV (стоковый)
        "com.google.android.leanbacklauncher",    // старый Android TV
        "com.mitv.tvhome",                         // Xiaomi PatchWall
        "com.mitv.tvhome.atv",
        "com.android.tv.launcher",
    )

    fun serviceComponent(context: Context) = ComponentName(context, HomeAccessibilityService::class.java)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val cr = context.contentResolver
        val enabled = runCatching { Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) }
            .getOrDefault(0) == 1
        if (!enabled) return false
        val flat = runCatching {
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val me = serviceComponent(context)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Если через adb выдано WRITE_SECURE_SETTINGS - сами включаем свою службу
     * (после обновления прошивки Google/Xiaomi любят сбрасывать спец. возможности).
     */
    fun tryEnableAccessibility(context: Context): Boolean {
        if (isAccessibilityEnabled(context)) return true
        if (!canWriteSecureSettings(context)) return false
        return runCatching {
            val cr = context.contentResolver
            val me = serviceComponent(context).flattenToString()
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = current.split(':').filter { it.isNotBlank() }.toMutableList()
            if (list.none { ComponentName.unflattenFromString(it) == serviceComponent(context) }) list += me
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.onFailure { Log.w(TAG, "enable a11y failed", it) }.isSuccess && isAccessibilityEnabled(context)
    }

    /** Пакет текущего HOME по умолчанию; null - не выбран (откроется диалог выбора). */
    fun currentHomePackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        // ResolverActivity из пакета "android" = выбор не сделан.
        return if (pkg == "android") null else pkg
    }

    fun enabledStockLaunchers(context: Context): List<String> {
        val pm = context.packageManager
        return STOCK_LAUNCHERS.filter { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)
        }
    }

    fun isStockLauncher(pkg: String?): Boolean = pkg != null && pkg in STOCK_LAUNCHERS
}

/** adb-команды, которые показываем пользователю в диагностике и онбординге. */
object AdbCommands {
    fun enableService(context: Context): String =
        "adb shell settings put secure enabled_accessibility_services " +
            SystemStatus.serviceComponent(context).flattenToString()

    const val ENABLE_A11Y_FLAG = "adb shell settings put secure accessibility_enabled 1"

    fun grantSecure(context: Context) =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun setHome(context: Context) =
        "adb shell cmd package set-home-activity ${context.packageName}/.ui.MainActivity"

    fun disable(pkg: String) = "adb shell pm disable-user --user 0 $pkg"
    fun enable(pkg: String) = "adb shell pm enable $pkg"
}
