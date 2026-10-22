package dev.glasslauncher.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glasslauncher.tv.data.settings.GlassQuality
import dev.glasslauncher.tv.ui.home.HomeFocus
import dev.glasslauncher.tv.ui.home.HomeScreen
import dev.glasslauncher.tv.ui.onboarding.OnboardingScreen
import dev.glasslauncher.tv.ui.settings.SettingsScreen
import dev.glasslauncher.tv.ui.theme.GlassColors
import dev.glasslauncher.tv.ui.theme.GlassStyle
import dev.glasslauncher.tv.ui.theme.GlassTheme
import dev.glasslauncher.tv.ui.theme.LocalBackdrop

@Composable
fun LauncherRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val scrollTop by vm.scrollTopRequest.collectAsStateWithLifecycle()

    // Состояние главного экрана живёт выше переключения экранов: после настроек
    // возвращаемся на ту же прокрутку и ту же иконку.
    val focus = remember { HomeFocus() }
    val listState = rememberLazyListState()
    // Фон текущего экрана, который просвечивает сквозь стеклянные панели.
    val backdrop = rememberGraphicsLayer()

    // Без собственного фона: каждый экран сам рисует непрозрачный фон (лишняя заливка = лишний проход GPU).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rootSize = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val s = state.settings
        val blurPx = with(density) { s.blurRadius.dp.toPx() }
        val style = remember(wallpaper, s.glassAlpha, s.animations, state.quality, rootSize, blurPx) {
            GlassStyle(
                blurred = wallpaper.blurred,
                baseColor = wallpaper.baseColor,
                tintAlpha = s.glassAlpha,
                full = state.quality == GlassQuality.FULL,
                animations = s.animations,
                rootSize = rootSize,
                blurPx = blurPx,
            )
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            GlassTheme(style) {
                when (val current = screen) {
                    Screen.Home -> HomeScreen(state, wallpaper, vm, focus, listState, scrollTop)
                    Screen.Onboarding -> OnboardingScreen(state, wallpaper, vm)
                    is Screen.Settings -> SettingsScreen(current, state, wallpaper, vm)
                }
            }
        }
    }
}
