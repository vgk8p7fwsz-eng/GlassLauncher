package dev.glasslauncher.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glasslauncher.tv.system.SystemStatus
import dev.glasslauncher.tv.ui.MainActivity

/**
 * После загрузки / обновления лаунчера:
 *  1. Если выдано WRITE_SECURE_SETTINGS - возвращаем свою службу спец. возможностей (прошивка могла её сбросить).
 *  2. Пытаемся вывести лаунчер на экран. Запуск Activity из фона на Android 10+ разрешён,
 *     только если наша AccessibilityService уже привязана системой; иначе это сделает сама служба,
 *     когда увидит launcherx (TYPE_WINDOW_STATE_CHANGED).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val enabled = SystemStatus.tryEnableAccessibility(context)
        Log.i(TAG, "$action: accessibility enabled=$enabled")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val home = SystemStatus.currentHomePackage(context)
            if (home != context.packageName) {
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(MainActivity.EXTRA_HOME_KEY, true),
                    )
                }.onFailure { Log.w(TAG, "background start blocked, service will redirect", it) }
            }
        }
    }

    companion object {
        private const val TAG = "GlassBoot"
    }
}
