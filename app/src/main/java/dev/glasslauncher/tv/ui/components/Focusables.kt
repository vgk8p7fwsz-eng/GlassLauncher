package dev.glasslauncher.tv.ui.components

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.tv.ui.glass.glassFill
import dev.glasslauncher.tv.ui.theme.GlassColors
import dev.glasslauncher.tv.ui.theme.GlassType
import dev.glasslauncher.tv.ui.theme.LocalGlass

val CENTER_KEYS = setOf(
    NativeKeyEvent.KEYCODE_DPAD_CENTER,
    NativeKeyEvent.KEYCODE_ENTER,
    NativeKeyEvent.KEYCODE_NUMPAD_ENTER,
    NativeKeyEvent.KEYCODE_BUTTON_A,
)

/**
 * OK на пульте: клик - на отпускании, долгое нажатие - по флагу long-press автоповтора.
 * downSeen защищает от «чужого» ACTION_UP (например, когда фокус пришёл сюда после закрытия меню).
 */
/**
 * requestFocus() бросает исключение, если узел ещё не прикреплён (например, элемент LazyColumn
 * будет скомпонован только в фазе layout). Пробуем несколько кадров подряд.
 */
suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 10) {
    repeat(attempts) {
        if (runCatching { requestFocus() }.isSuccess) return
        withFrameNanos { }
    }
}

class CenterKeyState {
    var downSeen = false
    var longFired = false
}

fun handleCenterKey(
    event: KeyEvent,
    state: CenterKeyState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Boolean {
    val n = event.nativeKeyEvent
    if (n.keyCode !in CENTER_KEYS) return false
    when (n.action) {
        NativeKeyEvent.ACTION_DOWN -> {
            if (n.repeatCount == 0) {
                state.downSeen = true
                state.longFired = false
            } else if (onLongClick != null && state.downSeen && !state.longFired &&
                (n.isLongPress || n.repeatCount >= 3)
            ) {
                state.longFired = true
                onLongClick()
            }
        }
        NativeKeyEvent.ACTION_UP -> {
            if (state.downSeen && !state.longFired) onClick()
            state.downSeen = false
            state.longFired = false
        }
    }
    return true
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val keyState = remember { CenterKeyState() }
    val animations = LocalGlass.current.animations
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = if (animations) spring(dampingRatio = 0.7f, stiffness = 500f) else snap(),
        label = "buttonScale",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { handleCenterKey(it, keyState, onClick, null) }
            .focusable()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glassFill(shape, focused)
            .padding(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Text(text, style = GlassType.Label.copy(color = if (focused) Color.Black else GlassColors.Text))
    }
}

/**
 * Строка настроек. Стрелки влево/вправо меняют значение, если заданы onLeft/onRight,
 * иначе отдаются системе фокуса.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    subtitleMaxLines: Int = 1,
    focusRequester: FocusRequester? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val keyState = remember { CenterKeyState() }
    val shape = RoundedCornerShape(16.dp)
    val textColor = if (focused) Color.Black else GlassColors.Text
    val dimColor = if (focused) Color.Black.copy(alpha = 0.65f) else GlassColors.TextDim
    Row(
        modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val n = e.nativeKeyEvent
                when (n.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> if (onLeft != null) {
                        if (n.action == NativeKeyEvent.ACTION_DOWN) onLeft()
                        true
                    } else {
                        false
                    }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> if (onRight != null) {
                        if (n.action == NativeKeyEvent.ACTION_DOWN) onRight()
                        true
                    } else {
                        false
                    }
                    else -> handleCenterKey(e, keyState, onClick, onLongClick)
                }
            }
            .focusable()
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = GlassType.Body.copy(color = textColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = GlassType.BodyDim.copy(color = dimColor),
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            val shown = if (onLeft != null || onRight != null) "‹  $value  ›" else value
            Text(shown, style = GlassType.Body.copy(color = dimColor), maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = GlassType.BodyDim.copy(color = GlassColors.TextFaint),
        modifier = modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}
