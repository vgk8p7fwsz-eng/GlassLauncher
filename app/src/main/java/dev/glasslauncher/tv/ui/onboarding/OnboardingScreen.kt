package dev.glasslauncher.tv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.tv.data.wallpaper.Wallpaper
import dev.glasslauncher.tv.system.AdbCommands
import dev.glasslauncher.tv.ui.MainViewModel
import dev.glasslauncher.tv.ui.UiState
import dev.glasslauncher.tv.ui.components.GlassButton
import dev.glasslauncher.tv.ui.components.requestFocusWhenReady
import dev.glasslauncher.tv.ui.glass.GlassPanel
import dev.glasslauncher.tv.ui.glass.WallpaperLayer
import dev.glasslauncher.tv.ui.glass.backdropSource
import dev.glasslauncher.tv.ui.theme.GlassColors
import dev.glasslauncher.tv.ui.theme.GlassType

/**
 * Первый запуск: объясняем, зачем нужна служба специальных возможностей, и ведём в настройки.
 * Как только служба включена (проверка в onResume), экран закрывается сам.
 */
@Composable
fun OnboardingScreen(state: UiState, wallpaper: Wallpaper, vm: MainViewModel) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }

    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(wallpaper, blurred = false, modifier = Modifier.fillMaxSize().backdropSource())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        GlassPanel(Modifier.align(Alignment.Center).width(1000.dp), cornerRadius = 36.dp) {
            Column(Modifier.padding(44.dp)) {
                Text("Добро пожаловать в Glass Launcher", style = GlassType.Title)
                Text(
                    "На Google TV кнопка Home зашита в стоковый лаунчер, и выбрать другой «по умолчанию» " +
                        "система не даёт. Поэтому Glass Launcher (как Projectivy) использует службу специальных " +
                        "возможностей:",
                    style = GlassType.Body,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Column(Modifier.padding(top = 12.dp, start = 12.dp)) {
                    Text("•  ловит нажатие Home и открывает этот экран;", style = GlassType.BodyDim)
                    Text("•  если стоковый лаунчер всё же появился - сразу перекрывает его;", style = GlassType.BodyDim)
                    Text("•  даёт переназначить кнопки Netflix / Prime Video / YouTube на пульте;", style = GlassType.BodyDim)
                    Text("•  управляет громкостью (удержание 2 с - без звука).", style = GlassType.BodyDim)
                    Text(
                        "Служба не читает содержимое экранов и вводимый текст.",
                        style = GlassType.BodyDim.copy(color = GlassColors.Accent),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    "Откройте «Специальные возможности» → «Glass Launcher: кнопка Home» → Включить.",
                    style = GlassType.Body,
                    modifier = Modifier.padding(top = 18.dp),
                )
                if (state.system.accessibilityEnabled) {
                    Text("✓ Служба включена", style = GlassType.Headline.copy(color = GlassColors.Accent))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    GlassButton("Открыть спец. возможности", vm::openAccessibilitySettings, focusRequester = first)
                    GlassButton("Готово", vm::completeOnboarding)
                    GlassButton("Пропустить", vm::completeOnboarding)
                }
                Text(
                    "Нет пункта в настройках? С компьютера через adb:",
                    style = GlassType.BodyDim,
                    modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
                )
                Text(AdbCommands.enableService(context), style = GlassType.Mono)
                Text(AdbCommands.ENABLE_A11Y_FLAG, style = GlassType.Mono)
                Text(AdbCommands.grantSecure(context), style = GlassType.Mono)
                Text(
                    "Последняя команда разрешает лаунчеру самому включать службу обратно, если обновление прошивки её сбросит.",
                    style = GlassType.BodyDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
