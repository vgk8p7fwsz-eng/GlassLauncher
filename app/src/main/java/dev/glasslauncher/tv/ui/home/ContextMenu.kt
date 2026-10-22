package dev.glasslauncher.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.glasslauncher.tv.data.apps.AppImage
import dev.glasslauncher.tv.data.apps.AppInfo
import dev.glasslauncher.tv.ui.MenuAction
import dev.glasslauncher.tv.ui.components.CENTER_KEYS
import dev.glasslauncher.tv.ui.components.requestFocusWhenReady
import dev.glasslauncher.tv.ui.glass.GlassPanel
import dev.glasslauncher.tv.ui.theme.GlassColors
import dev.glasslauncher.tv.ui.theme.GlassType

private data class MenuItem(val action: MenuAction, val title: String, val enabled: Boolean = true)

/**
 * Контекстное стеклянное меню. Фокус держит один контейнер, а выбор пункта ведём сами:
 * так фокус гарантированно не «убежит» на плитки под меню.
 */
@Composable
fun AppContextMenu(
    app: AppInfo,
    isFavorite: Boolean,
    favoritesLimit: Int,
    favoritesCount: Int,
    onAction: (MenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember(app, isFavorite, favoritesLimit, favoritesCount) {
        val full = !isFavorite && favoritesCount >= favoritesLimit
        listOf(
            MenuItem(MenuAction.MOVE, "Переместить"),
            when {
                isFavorite -> MenuItem(MenuAction.FAVORITE, "Убрать из избранного")
                full -> MenuItem(MenuAction.FAVORITE, "Избранное заполнено ($favoritesLimit из $favoritesLimit)", enabled = false)
                else -> MenuItem(MenuAction.FAVORITE, "Добавить в избранное ($favoritesCount из $favoritesLimit)")
            },
            MenuItem(MenuAction.HIDE, "Скрыть"),
            MenuItem(MenuAction.INFO, "О приложении"),
            MenuItem(MenuAction.UNINSTALL, if (app.isSystem) "Удалить (системное)" else "Удалить", enabled = !app.isSystem),
        )
    }
    var index by remember { mutableIntStateOf(0) }
    // Отпускание OK после долгого нажатия, открывшего меню, приходит уже сюда - игнорируем его.
    var armed by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocusWhenReady() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            Modifier
                .width(460.dp)
                .focusRequester(requester)
                .onKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    val down = n.action == NativeKeyEvent.ACTION_DOWN
                    when (n.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_UP -> {
                            if (down) index = prevEnabled(items, index)
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (down) index = nextEnabled(items, index)
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_LEFT, NativeKeyEvent.KEYCODE_DPAD_RIGHT -> true
                        NativeKeyEvent.KEYCODE_MENU -> {
                            if (!down) onDismiss()
                            true
                        }
                        in CENTER_KEYS -> {
                            if (down && n.repeatCount == 0) {
                                armed = true
                            } else if (!down && armed) {
                                armed = false
                                items.getOrNull(index)?.takeIf { it.enabled }?.let { onAction(it.action) }
                            }
                            true
                        }
                        else -> false // Back уходит в Activity → onBack() закрывает меню
                    }
                }
                .focusable(),
            cornerRadius = 30.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                    AsyncImage(
                        model = app.image(AppImage.Kind.BANNER),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp, 54.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Text(
                        app.label,
                        style = GlassType.Headline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Box(Modifier.height(10.dp))
                items.forEachIndexed { i, item ->
                    val selected = i == index
                    Text(
                        item.title,
                        style = GlassType.Body.copy(
                            color = when {
                                !item.enabled -> GlassColors.TextFaint
                                selected -> Color.Black
                                else -> GlassColors.Text
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                    )
                }
            }
        }
    }
}

private fun nextEnabled(items: List<MenuItem>, from: Int): Int {
    var i = from
    while (i + 1 <= items.lastIndex) {
        i++
        if (items[i].enabled) return i
    }
    return from
}

private fun prevEnabled(items: List<MenuItem>, from: Int): Int {
    var i = from
    while (i - 1 >= 0) {
        i--
        if (items[i].enabled) return i
    }
    return from
}

/** Подсказка внизу экрана в режиме перемещения. */
@Composable
fun MoveHint(modifier: Modifier = Modifier) {
    GlassPanel(modifier, cornerRadius = 40.dp) {
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text("← ↑ → ↓  переместить", style = GlassType.Label)
            Text("OK  сохранить", style = GlassType.Label)
            Text("Назад  отменить", style = GlassType.Label)
        }
    }
}
