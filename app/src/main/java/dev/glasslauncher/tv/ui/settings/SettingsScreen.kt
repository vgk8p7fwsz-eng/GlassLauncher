package dev.glasslauncher.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.glasslauncher.tv.data.apps.AppImage
import dev.glasslauncher.tv.data.settings.BINDING_HOME
import dev.glasslauncher.tv.data.settings.BINDING_MUTE
import dev.glasslauncher.tv.data.settings.GlassQuality
import dev.glasslauncher.tv.data.settings.ShelfMode
import dev.glasslauncher.tv.data.settings.WallpaperType
import dev.glasslauncher.tv.data.wallpaper.Wallpaper
import dev.glasslauncher.tv.data.wallpaper.WallpaperPresets
import dev.glasslauncher.tv.service.KeyEventBus
import dev.glasslauncher.tv.system.AdbCommands
import dev.glasslauncher.tv.system.SystemStatus
import dev.glasslauncher.tv.ui.MainViewModel
import dev.glasslauncher.tv.ui.Screen
import dev.glasslauncher.tv.ui.SettingsPage
import dev.glasslauncher.tv.ui.UiState
import dev.glasslauncher.tv.ui.components.SectionTitle
import dev.glasslauncher.tv.ui.components.SettingRow
import dev.glasslauncher.tv.ui.components.requestFocusWhenReady
import dev.glasslauncher.tv.ui.glass.GlassPanel
import dev.glasslauncher.tv.ui.glass.WallpaperLayer
import dev.glasslauncher.tv.ui.glass.backdropSource
import dev.glasslauncher.tv.ui.theme.GlassColors
import dev.glasslauncher.tv.ui.theme.GlassType
import kotlin.math.roundToInt

private fun onOff(value: Boolean) = if (value) "Вкл" else "Выкл"

private fun qualityName(q: GlassQuality) = when (q) {
    GlassQuality.AUTO -> "Авто"
    GlassQuality.LIGHT -> "Лёгкое"
    GlassQuality.FULL -> "Полное"
}

private fun pageTitle(page: SettingsPage) = when (page) {
    SettingsPage.ROOT -> "Настройки"
    SettingsPage.WALLPAPER -> "Обои"
    SettingsPage.FILES -> "Обои из файла"
    SettingsPage.HIDDEN -> "Скрытые приложения"
    SettingsPage.GLASS -> "Liquid Glass"
    SettingsPage.KEYS -> "Кнопки пульта"
    SettingsPage.KEY_TARGET -> "Назначить кнопку"
    SettingsPage.DIAGNOSTICS -> "Диагностика"
}

@Composable
fun SettingsScreen(screen: Screen.Settings, state: UiState, wallpaper: Wallpaper, vm: MainViewModel) {
    val learning by vm.learning.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(wallpaper, blurred = true, modifier = Modifier.fillMaxSize().backdropSource())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 36.dp)) {
            // Шапка в одну строку: название страницы слева, дата и время справа.
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pageTitle(screen.page),
                    style = GlassType.Title.copy(fontSize = 34.sp),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                dev.glasslauncher.tv.ui.home.Clock()
            }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            GlassPanel(Modifier.fillMaxWidth(0.62f).fillMaxHeight(), cornerRadius = 32.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    // key(screen): каждая страница - новый композиционный блок со своим первым фокусом.
                    key(screen) {
                        val first = remember { FocusRequester() }
                        LaunchedEffect(Unit) { first.requestFocusWhenReady() }
                        when (screen.page) {
                            SettingsPage.ROOT -> RootPage(state, vm, first)
                            SettingsPage.WALLPAPER -> WallpaperPage(state, vm, first)
                            SettingsPage.FILES -> FileBrowser(vm, first)
                            SettingsPage.HIDDEN -> HiddenPage(state, vm, first)
                            SettingsPage.GLASS -> GlassPage(state, vm, first)
                            SettingsPage.KEYS -> KeysPage(state, vm, first)
                            SettingsPage.KEY_TARGET -> KeyTargetPage(screen.arg, state, vm, first)
                            SettingsPage.DIAGNOSTICS -> DiagnosticsPage(state, vm, first)
                        }
                    }
                }
            }
            Spacer(Modifier.width(36.dp))
            SidePanel(screen.page, state, vm, Modifier.weight(1f).fillMaxHeight())
        }
        }
        if (learning) LearningOverlay()
    }
}

@Composable
private fun SettingsList(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxWidth(), content = content)
}

// ---------------- корень ----------------

@Composable
private fun RootPage(state: UiState, vm: MainViewModel, first: FocusRequester) {
    val s = state.settings
    val wallpaperName = when (s.wallpaperType) {
        WallpaperType.PRESET -> WallpaperPresets.all.getOrNull(s.wallpaperPreset)?.name ?: "Пресет"
        WallpaperType.COLOR -> "Цвет"
        WallpaperType.FILE -> "Своё изображение"
        WallpaperType.VIDEO -> "Видео"
    }
    val toggleShelf = {
        vm.setShelfMode(if (s.shelfMode == ShelfMode.FOCUSED_APP) ShelfMode.WALLPAPER else ShelfMode.FOCUSED_APP)
    }
    SettingsList {
        item {
            SettingRow("Обои", value = wallpaperName, focusRequester = first) {
                vm.push(Screen.Settings(SettingsPage.WALLPAPER))
            }
        }
        item {
            SettingRow(
                "Верхняя зона (Top Shelf)",
                value = if (s.shelfMode == ShelfMode.FOCUSED_APP) "Баннер в фокусе" else "Обои",
                onLeft = toggleShelf,
                onRight = toggleShelf,
                onClick = toggleShelf,
            )
        }
        item {
            SettingRow(
                "Иконок в ряду",
                value = "${s.columns}",
                onLeft = { vm.setColumns(s.columns - 1) },
                onRight = { vm.setColumns(s.columns + 1) },
                onClick = { vm.setColumns(if (s.columns >= 7) 4 else s.columns + 1) },
            )
        }
        item {
            SettingRow("Ряд «Источники» (HDMI, эфир)", value = onOff(s.showSources)) { vm.setShowSources(!s.showSources) }
        }
        item {
            SettingRow("Скрытые приложения", value = "${state.apps.hidden.size}") {
                vm.push(Screen.Settings(SettingsPage.HIDDEN))
            }
        }
        item {
            SettingRow(
                "Порядок иконок",
                subtitle = "Переместить: долгий OK на иконке → «Переместить». Здесь - сброс по алфавиту.",
                value = "Сбросить",
            ) { vm.resetOrder() }
        }
        item {
            SettingRow("Liquid Glass", value = qualityName(s.glassQuality)) {
                vm.push(Screen.Settings(SettingsPage.GLASS))
            }
        }
        item { SettingRow("Анимации", value = onOff(s.animations)) { vm.setAnimations(!s.animations) } }

        item { SectionTitle("Пульт и система") }
        item {
            SettingRow("Кнопки пульта", value = "назначено: ${s.keyBindings.size}") {
                vm.push(Screen.Settings(SettingsPage.KEYS))
            }
        }
        item {
            SettingRow(
                "Долгий Home → системная панель",
                value = onOff(s.longHomeOpensPanel),
            ) { vm.setLongHomeOpensPanel(!s.longHomeOpensPanel) }
        }
        item {
            SettingRow(
                "Перекрывать стоковый лаунчер",
                subtitle = "Если launcherx всплыл поверх - сразу открыть Glass Launcher",
                value = onOff(s.redirectStockLauncher),
            ) { vm.setRedirectStock(!s.redirectStockLauncher) }
        }
        item {
            SettingRow(
                "Диагностика и adb-команды",
                value = if (state.system.accessibilityEnabled) "OK" else "Служба выкл.",
            ) { vm.push(Screen.Settings(SettingsPage.DIAGNOSTICS)) }
        }
        item { SettingRow("Специальные возможности", onClick = vm::openAccessibilitySettings) }
        item {
            SettingRow("Лаунчер по умолчанию", value = state.system.defaultHome ?: "не выбран", onClick = vm::openHomeSettings)
        }
        item { SettingRow("Настройки Android TV", onClick = vm::openSystemSettings) }
        item { SettingRow("Быстрые настройки", onClick = vm::openQuickSettings) }
    }
}

// ---------------- стекло ----------------

@Composable
private fun GlassPage(state: UiState, vm: MainViewModel, first: FocusRequester) {
    val s = state.settings
    val qualities = GlassQuality.entries
    val nextQuality = { qualities[(qualities.indexOf(s.glassQuality) + 1) % qualities.size] }
    val prevQuality = { qualities[(qualities.indexOf(s.glassQuality) + qualities.size - 1) % qualities.size] }
    SettingsList {
        item {
            SettingRow(
                "Качество",
                subtitle = "Сейчас: ${qualityName(state.quality)}. Авто: «Лёгкое» при Android < 12 или ОЗУ < 3 ГБ " +
                    "(${vm.device.totalRamMb} МБ на этом устройстве).",
                value = qualityName(s.glassQuality),
                focusRequester = first,
                onLeft = { vm.setGlassQuality(prevQuality()) },
                onRight = { vm.setGlassQuality(nextQuality()) },
                onClick = { vm.setGlassQuality(nextQuality()) },
            )
        }
        item {
            SettingRow(
                "Размытие",
                subtitle = "Пересчитывается один раз при изменении, не на каждом кадре",
                value = "${s.blurRadius}",
                onLeft = { vm.setBlurRadius(s.blurRadius - 4) },
                onRight = { vm.setBlurRadius(s.blurRadius + 4) },
            )
        }
        item {
            SettingRow(
                "Затемнение стекла",
                value = "${(s.glassAlpha * 100).roundToInt()}%",
                onLeft = { vm.setGlassAlpha(s.glassAlpha - 0.05f) },
                onRight = { vm.setGlassAlpha(s.glassAlpha + 0.05f) },
            )
        }
        item { SettingRow("Анимации", value = onOff(s.animations)) { vm.setAnimations(!s.animations) } }
    }
}

// ---------------- скрытые ----------------

@Composable
private fun HiddenPage(state: UiState, vm: MainViewModel, first: FocusRequester) {
    val hidden = remember(state.apps.hidden) { state.apps.hidden.map { it.packageName }.toSet() }
    SettingsList {
        if (state.apps.all.isEmpty()) {
            item { SettingRow("Приложения не найдены", focusRequester = first) }
        }
        items(state.apps.all, key = { it.packageName }) { app ->
            val isHidden = app.packageName in hidden
            SettingRow(
                app.label,
                value = if (isHidden) "Скрыто" else "Показано",
                focusRequester = if (app == state.apps.all.first()) first else null,
                leading = { AppThumb(app.image(AppImage.Kind.BANNER)) },
            ) { vm.setHidden(app, !isHidden) }
        }
    }
}

@Composable
fun AppThumb(image: AppImage) {
    AsyncImage(
        model = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp)),
    )
}

// ---------------- кнопки пульта ----------------

@Composable
private fun KeysPage(state: UiState, vm: MainViewModel, first: FocusRequester) {
    val bindings = state.settings.keyBindings.entries.sortedBy { it.key }
    val labels = remember(state.apps.all) { state.apps.all.associate { it.packageName to it.label } }
    SettingsList {
        item {
            SettingRow(
                "Назначить кнопку…",
                subtitle = "Нажмите, затем нажмите нужную кнопку на пульте (Netflix, Prime Video, YouTube…)",
                focusRequester = first,
            ) { vm.startLearning() }
        }
        if (bindings.isNotEmpty()) item { SectionTitle("Назначено") }
        items(bindings, key = { it.key }) { (code, target) ->
            SettingRow(
                "${KeyEventBus.keyName(code)}  ($code)",
                value = when (target) {
                    BINDING_HOME -> "Главный экран"
                    BINDING_MUTE -> "Без звука"
                    else -> labels[target] ?: target
                },
                subtitle = "OK - изменить, долгий OK - удалить",
                onLongClick = { vm.removeBinding(code) },
            ) { vm.push(Screen.Settings(SettingsPage.KEY_TARGET, code)) }
        }
    }
}

@Composable
private fun KeyTargetPage(code: Int, state: UiState, vm: MainViewModel, first: FocusRequester) {
    SettingsList {
        item {
            Text(
                "Кнопка: ${KeyEventBus.keyName(code)} ($code)",
                style = GlassType.Headline,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
        }
        if (code in KeyEventBus.ASSISTANT_KEYS) {
            item {
                Text(
                    "Это кнопка Google Ассистента. После назначения голосовой поиск с неё работать не будет.",
                    style = GlassType.BodyDim.copy(color = GlassColors.Warning),
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
            }
        }
        item {
            SettingRow("Главный экран Glass Launcher", focusRequester = first) { vm.bindKey(code, BINDING_HOME) }
        }
        item {
            SettingRow(
                "Выключить / включить звук",
                subtitle = "Одно нажатие - без звука, ещё одно - звук вернётся на прежний уровень",
            ) { vm.bindKey(code, BINDING_MUTE) }
        }
        item { SettingRow("Убрать назначение (системное поведение)") { vm.bindKey(code, null) } }
        item { SectionTitle("Приложение") }
        items(state.apps.all, key = { it.packageName }) { app ->
            SettingRow(app.label, leading = { AppThumb(app.image(AppImage.Kind.BANNER)) }) {
                vm.bindKey(code, app.packageName)
            }
        }
    }
}

// ---------------- диагностика ----------------

@Composable
private fun DiagnosticsPage(state: UiState, vm: MainViewModel, first: FocusRequester) {
    val context = LocalContext.current
    val sys = state.system
    val d = vm.device
    SettingsList {
        item {
            SettingRow(
                "Служба специальных возможностей",
                value = if (sys.accessibilityEnabled) "включена" else "выключена",
                subtitle = if (sys.serviceConnected) "Работает" else "Не подключена системой",
                focusRequester = first,
                onClick = vm::openAccessibilitySettings,
            )
        }
        item {
            SettingRow(
                "Авто-восстановление службы",
                value = if (sys.canWriteSecure) "есть" else "нет",
                subtitle = "WRITE_SECURE_SETTINGS (выдаётся через adb) - лаунчер сам включит службу после сброса",
            )
        }
        item { SettingRow("Лаунчер по умолчанию", value = sys.defaultHome ?: "не выбран", onClick = vm::openHomeSettings) }
        item {
            SettingRow(
                "Стоковые лаунчеры",
                value = if (sys.stockEnabled.isEmpty()) "отключены" else "включены",
                subtitle = sys.stockEnabled.joinToString().ifEmpty { null },
            )
        }
        item {
            SettingRow(
                "Устройство",
                value = "Android API ${d.sdk}",
                subtitle = "${d.model}, ОЗУ ${d.totalRamMb} МБ${if (d.lowRamFlag) ", low-RAM" else ""}" +
                    ", режим стекла: ${qualityName(state.quality)}",
            )
        }

        item { SectionTitle("adb-команды (выполнять с компьютера)") }
        item {
            SettingRow(
                "Включить службу",
                subtitle = AdbCommands.enableService(context) + "\n" + AdbCommands.ENABLE_A11Y_FLAG,
                subtitleMaxLines = 6,
            )
        }
        item {
            SettingRow(
                "Разрешить авто-восстановление службы",
                subtitle = AdbCommands.grantSecure(context),
                subtitleMaxLines = 4,
            )
        }
        item {
            SettingRow(
                "Сделать лаунчером по умолчанию",
                subtitle = AdbCommands.setHome(context),
                subtitleMaxLines = 4,
            )
        }
        items(SystemStatus.STOCK_LAUNCHERS.filter { it in sys.stockEnabled }) { pkg ->
            SettingRow(
                "Отключить $pkg (риск, см. README)",
                subtitle = AdbCommands.disable(pkg) + "\nвернуть: " + AdbCommands.enable(pkg),
                subtitleMaxLines = 6,
            )
        }
    }
}

// ---------------- правая колонка ----------------

@Composable
private fun SidePanel(page: SettingsPage, state: UiState, vm: MainViewModel, modifier: Modifier) {
    val keyLog by vm.keyLog.collectAsStateWithLifecycle()
    val stockWindows by vm.stockWindows.collectAsStateWithLifecycle()
    Column(modifier.padding(top = 12.dp)) {
        when (page) {
            SettingsPage.KEYS, SettingsPage.KEY_TARGET -> {
                Text("Последние нажатия", style = GlassType.Headline)
                Text(
                    if (state.system.serviceConnected) "keycode пишутся также в logcat (тег GlassKeys)" else "Служба не активна - лог пуст",
                    style = GlassType.BodyDim,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                keyLog.take(12).forEach { k ->
                    Text("${k.name}   code=${k.keyCode}   scan=${k.scanCode}", style = GlassType.Mono)
                }
            }
            SettingsPage.DIAGNOSTICS -> {
                Text("Окна стокового лаунчера", style = GlassType.Headline)
                Text(
                    "Что launcherx показывал поверх. Перекрывается только главный экран (Home/Main/Launcher в имени).",
                    style = GlassType.BodyDim,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                stockWindows.forEach { Text(it, style = GlassType.Mono) }
                Spacer(Modifier.size(20.dp))
                Text("Последние нажатия", style = GlassType.Headline)
                keyLog.take(6).forEach { k -> Text("${k.name}  ${k.keyCode}", style = GlassType.Mono) }
            }
            SettingsPage.GLASS -> {
                Text("Предпросмотр", style = GlassType.Headline, modifier = Modifier.padding(bottom = 16.dp))
                GlassPanel(Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                    Column(Modifier.padding(28.dp)) {
                        Text("Liquid Glass", style = GlassType.Title)
                        Text(
                            "Подложка - размытые обои, посчитанные один раз. Край и блик рисуются поверх. " +
                                "В режиме «Полное» на Android 13+ край преломляется AGSL-шейдером.",
                            style = GlassType.BodyDim,
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun LearningOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(cornerRadius = 32.dp) {
            Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Нажмите кнопку на пульте…", style = GlassType.Title)
                Text(
                    "Netflix, Prime Video, YouTube и т.п. Стрелки, OK, Home, громкость назначить нельзя. Назад - отмена.",
                    style = GlassType.BodyDim,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
