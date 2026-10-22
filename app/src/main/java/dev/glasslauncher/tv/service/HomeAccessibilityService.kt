package dev.glasslauncher.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.glasslauncher.tv.GlassLauncherApp
import dev.glasslauncher.tv.data.settings.BINDING_HOME
import dev.glasslauncher.tv.data.settings.BINDING_MUTE
import dev.glasslauncher.tv.data.settings.LauncherSettings
import dev.glasslauncher.tv.system.SystemIntents
import dev.glasslauncher.tv.system.SystemStatus
import dev.glasslauncher.tv.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Основной механизм работы лаунчера на Google TV (как в Projectivy):
 *  1. onKeyEvent ловит KEYCODE_HOME раньше системного лаунчера и открывает наш.
 *  2. TYPE_WINDOW_STATE_CHANGED: если на экран всё-таки вылез launcherx (после загрузки, из уведомления,
 *     по Home, который прошивка обработала до нас) - сразу перекрываем его своим.
 *  3. Переназначаемые кнопки пульта (Netflix / Prime Video / YouTube и т.п.).
 *  4. Громкость: шаг / удержание 2 с = mute.
 * Медиа-кнопки и всё неизвестное возвращаем системе (return false).
 */
class HomeAccessibilityService : AccessibilityService() {

    private val scope = MainScope()

    @Volatile
    private var settings = LauncherSettings()
    private var hud: VolumeHud? = null

    private var lastRedirect = 0L
    private var homeLongFired = false
    private var homeDownSeen = false
    private val swallowUp = HashSet<Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        KeyEventBus.serviceConnected.value = true
        val container = (application as GlassLauncherApp).container
        val volumeHud = VolumeHud(this)
        hud = volumeHud
        scope.launch { container.settingsRepository.settings.collect { settings = it } }
        // Саундбар по HDMI ARC/eARC: кнопки громкости обрабатывает HDMI-CEC в системе раньше службы,
        // а системный ползунок для ARC не показывается. Поэтому индикатор показываем по любому
        // изменению громкости (системная рассылка VOLUME_CHANGED_ACTION), кто бы её ни поменял.
        scope.launch {
            container.volumeMonitor.state.drop(1).collect { level ->
                volumeHud.show(level)
            }
        }
        Log.i(TAG, "connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) KeyEventBus.record(event)

        // UP от кнопки, чей DOWN мы уже поглотили, системе не отдаём - иначе получится «полунажатие».
        if (!down && swallowUp.remove(code)) return true

        if (KeyEventBus.learning.value && code !in KeyEventBus.NON_BINDABLE) {
            if (down && event.repeatCount == 0) {
                KeyEventBus.learning.value = false
                KeyEventBus.learned.tryEmit(code)
                swallowUp += code
            }
            return true
        }

        if (code == KeyEvent.KEYCODE_HOME) return onHomeKey(event)

        val target = settings.keyBindings[code]
        if (target != null && code !in KeyEventBus.NON_BINDABLE) {
            if (down && event.repeatCount == 0) {
                swallowUp += code
                launchBinding(target)
            }
            return true
        }

        // KEYCODE_MEDIA_*, ассистент, SETTINGS, MENU и прочее - системное поведение не ломаем.
        return false
    }

    /** Короткий Home - наш лаунчер. Долгий Home - системная панель (на Google TV это поведение launcherx). */
    private fun onHomeKey(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    homeDownSeen = true
                    homeLongFired = false
                } else if (!homeLongFired && event.isLongPress && settings.longHomeOpensPanel) {
                    homeLongFired = true
                    SystemIntents.openQuickSettings(this)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (homeDownSeen && !homeLongFired) openLauncher()
                homeDownSeen = false
                homeLongFired = false
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!SystemStatus.isStockLauncher(pkg)) return
        val cls = event.className?.toString().orEmpty()
        KeyEventBus.recordStockWindow("$pkg / $cls")
        if (!settings.redirectStockLauncher) return
        if (!looksLikeHomeScreen(cls)) return
        val now = SystemClock.uptimeMillis()
        if (now < KeyEventBus.suppressRedirectUntil || now - lastRedirect < REDIRECT_DEBOUNCE_MS) return
        lastRedirect = now
        Log.i(TAG, "stock launcher on top ($pkg/$cls) -> redirect")
        openLauncher()
    }

    /**
     * У launcherx в том же пакете живут панель быстрых настроек, переключатель входов, профили.
     * Перекрываем только сам главный экран, иначе пользователь потеряет системные панели.
     */
    private fun looksLikeHomeScreen(className: String): Boolean {
        if (className.isBlank()) return false
        val simple = className.substringAfterLast('.').lowercase()
        if (EXCLUDE_WORDS.any { it in simple }) return false
        return INCLUDE_WORDS.any { it in simple }
    }

    private fun openLauncher() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_HOME_KEY, true)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "start launcher failed", it) }
    }

    private fun launchBinding(target: String) {
        if (target == BINDING_HOME) {
            openLauncher()
            return
        }
        if (target == BINDING_MUTE) {
            toggleMute()
            return
        }
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(target) ?: pm.getLaunchIntentForPackage(target)
        if (intent == null) {
            Log.w(TAG, "binding target missing: $target")
            return
        }
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    /**
     * Выключить / включить звук. На саундбаре по HDMI ARC система отправит команду Mute по CEC;
     * индикатор покажется сам - по системной рассылке об изменении громкости.
     */
    private fun toggleMute() {
        val am = getSystemService(AudioManager::class.java) ?: return
        runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0) }
            .onFailure { Log.w(TAG, "toggle mute failed", it) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        KeyEventBus.serviceConnected.value = false
        KeyEventBus.learning.value = false
        hud?.release()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GlassHomeService"
        private const val REDIRECT_DEBOUNCE_MS = 1200L

        private val INCLUDE_WORDS = listOf("home", "main", "launcher")
        private val EXCLUDE_WORDS = listOf(
            "dashboard", "setting", "input", "panel", "notification", "dialog", "search",
            "assistant", "account", "profile", "picker", "setup", "overlay", "voice",
        )

        @Volatile
        private var instance: WeakReference<HomeAccessibilityService>? = null

        fun isRunning(): Boolean = instance?.get() != null

        /** GLOBAL_ACTION_QUICK_SETTINGS. На части ТВ-прошивок ничего не делает - тогда вызывающий откроет настройки. */
        fun openQuickSettings(): Boolean =
            instance?.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: false
    }
}
