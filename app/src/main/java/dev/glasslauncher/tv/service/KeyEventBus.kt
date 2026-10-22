package dev.glasslauncher.tv.service

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Общая шина между AccessibilityService и UI (один процесс): лог кнопок, режим «обучения», статус. */
object KeyEventBus {

    private const val TAG = "GlassKeys"

    @Immutable
    data class LoggedKey(val keyCode: Int, val scanCode: Int, val name: String, val time: Long)

    val log = MutableStateFlow<List<LoggedKey>>(emptyList())
    val stockWindows = MutableStateFlow<List<String>>(emptyList())
    val serviceConnected = MutableStateFlow(false)

    /** Пока true - следующая «назначаемая» кнопка не выполняется, а уходит в [learned]. */
    val learning = MutableStateFlow(false)
    val learned = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Не перенаправлять со стокового лаунчера до этого момента (uptimeMillis). */
    @Volatile
    var suppressRedirectUntil = 0L

    /** Кнопки, без которых пульт перестанет работать: их нельзя переназначить. */
    val NON_BINDABLE = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE,
    )

    /** Ассистент по умолчанию не трогаем; назначить можно только явно, с предупреждением в UI. */
    val ASSISTANT_KEYS = setOf(
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH,
    )

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    fun record(event: KeyEvent) {
        val entry = LoggedKey(event.keyCode, event.scanCode, keyName(event.keyCode), System.currentTimeMillis())
        Log.d(TAG, "key ${entry.keyCode} (${entry.name}) scan=${entry.scanCode} device=${event.deviceId}")
        log.update { (listOf(entry) + it).take(20) }
    }

    fun recordStockWindow(desc: String) {
        stockWindows.update { (listOf(desc) + it.filter { s -> s != desc }).take(10) }
    }

    fun suppressRedirect(ms: Long) {
        suppressRedirectUntil = SystemClock.uptimeMillis() + ms
    }
}
