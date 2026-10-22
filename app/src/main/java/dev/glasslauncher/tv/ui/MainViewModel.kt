package dev.glasslauncher.tv.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.glasslauncher.tv.GlassLauncherApp
import dev.glasslauncher.tv.data.apps.AppInfo
import dev.glasslauncher.tv.data.apps.AppsState
import dev.glasslauncher.tv.data.settings.GlassQuality
import dev.glasslauncher.tv.data.settings.LauncherSettings
import dev.glasslauncher.tv.data.settings.ShelfMode
import dev.glasslauncher.tv.data.tv.TvSource
import dev.glasslauncher.tv.data.wallpaper.Wallpaper
import dev.glasslauncher.tv.service.KeyEventBus
import dev.glasslauncher.tv.system.DeviceProfile
import dev.glasslauncher.tv.system.SystemIntents
import dev.glasslauncher.tv.system.SystemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Onboarding : Screen
    data class Settings(val page: SettingsPage, val arg: Int = 0) : Screen
}

enum class SettingsPage { ROOT, WALLPAPER, FILES, HIDDEN, GLASS, KEYS, KEY_TARGET, DIAGNOSTICS }

enum class MenuAction { MOVE, FAVORITE, HIDE, INFO, UNINSTALL }

@Immutable
data class SystemState(
    val accessibilityEnabled: Boolean = true,
    val serviceConnected: Boolean = false,
    val canWriteSecure: Boolean = false,
    val defaultHome: String? = null,
    val isDefaultHome: Boolean = false,
    val stockEnabled: List<String> = emptyList(),
    /** Стоковый лаунчер был отключён, а теперь снова включён (обычно - обновление прошивки). */
    val stockReenabled: Boolean = false,
)

@Immutable
data class MoveState(val packageName: String, val inFavorites: Boolean, val order: List<String>)

@Immutable
data class UiState(
    val apps: AppsState = AppsState(),
    val sources: List<TvSource> = emptyList(),
    val settings: LauncherSettings = LauncherSettings(),
    val quality: GlassQuality = GlassQuality.LIGHT,
    val system: SystemState = SystemState(),
    val menuFor: AppInfo? = null,
    val moving: MoveState? = null,
    val bannerDismissed: Boolean = false,
)

private data class Overlay(
    val menu: AppInfo?,
    val moving: MoveState?,
    val system: SystemState,
    val bannerDismissed: Boolean,
)

class MainViewModel(private val app: GlassLauncherApp) : ViewModel() {

    private val c = app.container
    val device: DeviceProfile.Info = c.device

    private val menu = MutableStateFlow<AppInfo?>(null)
    private val moving = MutableStateFlow<MoveState?>(null)
    private val system = MutableStateFlow(SystemState())
    private val bannerDismissed = MutableStateFlow(false)

    private val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val screen: StateFlow<Screen> = stack.map { it.last() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Home)

    /**
     * Запрос «Home нажат, когда лаунчер уже открыт»: прокрутить к началу и сфокусировать первую иконку.
     * StateFlow, а не одноразовое событие: главный экран мог быть не скомпонован (открыты настройки).
     */
    private val _scrollTop = MutableStateFlow(false)
    val scrollTopRequest: StateFlow<Boolean> = _scrollTop

    fun consumeScrollTop() {
        _scrollTop.value = false
    }

    val wallpaper: StateFlow<Wallpaper> = c.wallpaperRepository.wallpaper
    val volume = c.volumeMonitor.state
    val keyLog = KeyEventBus.log
    val stockWindows = KeyEventBus.stockWindows
    val learning = KeyEventBus.learning

    private val sources = c.tvInputsRepository.observe().catch { emit(emptyList()) }

    private val overlay = combine(menu, moving, system, bannerDismissed) { m, mv, s, b -> Overlay(m, mv, s, b) }

    val state: StateFlow<UiState> = combine(
        c.appsRepository.state,
        sources,
        c.settingsRepository.settings,
        overlay,
    ) { apps, src, settings, o ->
        UiState(
            apps = applyMove(apps, o.moving),
            sources = src,
            settings = settings,
            quality = DeviceProfile.effectiveQuality(settings.glassQuality, device),
            system = o.system,
            menuFor = o.menu,
            moving = o.moving,
            bannerDismissed = o.bannerDismissed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            val s = c.settingsRepository.settings.first()
            val enabled = withContext(Dispatchers.IO) { SystemStatus.isAccessibilityEnabled(app) }
            if (!s.onboardingDone && !enabled) stack.value = listOf(Screen.Home, Screen.Onboarding)
        }
        viewModelScope.launch {
            val s = c.settingsRepository.settings.first()
            if (!s.favoritesSeeded) {
                val apps = c.appsRepository.state.first { it.loaded }
                if (apps.favorites.isEmpty()) c.appsRepository.seedFavorites(apps.all, s.columns)
                c.settingsRepository.setFavoritesSeeded()
            }
        }
        viewModelScope.launch {
            KeyEventBus.learned.collect { code -> push(Screen.Settings(SettingsPage.KEY_TARGET, code)) }
        }
        viewModelScope.launch {
            KeyEventBus.serviceConnected.collect { refreshSystemStatus() }
        }
    }

    private fun applyMove(apps: AppsState, m: MoveState?): AppsState {
        if (m == null) return apps
        fun reorder(list: List<AppInfo>): List<AppInfo> {
            val byPkg = list.associateBy { it.packageName }
            val inOrder = m.order.toSet()
            return m.order.mapNotNull { byPkg[it] } + list.filter { it.packageName !in inOrder }
        }
        return if (m.inFavorites) apps.copy(favorites = reorder(apps.favorites)) else apps.copy(apps = reorder(apps.apps))
    }

    // ---------- системный статус ----------

    fun refreshSystemStatus() {
        c.volumeMonitor.refresh()
        viewModelScope.launch {
            val settings = c.settingsRepository.settings.first()
            val snapshot = withContext(Dispatchers.IO) {
                var enabled = SystemStatus.isAccessibilityEnabled(app)
                if (!enabled) enabled = SystemStatus.tryEnableAccessibility(app)
                val home = SystemStatus.currentHomePackage(app)
                val stock = SystemStatus.enabledStockLaunchers(app)
                SystemState(
                    accessibilityEnabled = enabled,
                    serviceConnected = KeyEventBus.serviceConnected.value,
                    canWriteSecure = SystemStatus.canWriteSecureSettings(app),
                    defaultHome = home,
                    isDefaultHome = home == app.packageName,
                    stockEnabled = stock,
                    stockReenabled = settings.stockWasDisabled && stock.isNotEmpty(),
                )
            }
            if (snapshot.stockEnabled.isEmpty() && !settings.stockWasDisabled) {
                c.settingsRepository.setStockWasDisabled(true)
            }
            system.value = snapshot
            if (snapshot.accessibilityEnabled && stack.value.lastOrNull() == Screen.Onboarding) completeOnboarding()
        }
    }

    fun acknowledgeStockLauncher() {
        viewModelScope.launch {
            c.settingsRepository.setStockWasDisabled(false)
            refreshSystemStatus()
        }
    }

    // ---------- навигация ----------

    fun push(screen: Screen) {
        stack.value = stack.value + screen
    }

    fun pop() {
        if (stack.value.size > 1) stack.value = stack.value.dropLast(1)
    }

    fun onBack() {
        when {
            KeyEventBus.learning.value -> KeyEventBus.learning.value = false
            moving.value != null -> moving.value = null
            menu.value != null -> menu.value = null
            stack.value.lastOrNull() == Screen.Onboarding -> completeOnboarding()
            stack.value.size > 1 -> pop()
            else -> Unit // главный экран: Back ничего не делает, лаунчер не закрывается
        }
    }

    fun onHomePressed() {
        KeyEventBus.learning.value = false
        moving.value = null
        menu.value = null
        if (stack.value != listOf(Screen.Home)) stack.value = listOf(Screen.Home)
        _scrollTop.value = true
    }

    fun completeOnboarding() {
        viewModelScope.launch { c.settingsRepository.setOnboardingDone() }
        stack.value = stack.value.filter { it != Screen.Onboarding }.ifEmpty { listOf(Screen.Home) }
    }

    // ---------- приложения ----------

    fun launchApp(target: AppInfo) {
        if (moving.value != null) {
            commitMove()
            return
        }
        try {
            app.startActivity(c.appsRepository.launchIntent(target))
        } catch (e: Exception) {
            toast("Не удалось запустить «${target.label}»")
            viewModelScope.launch { c.appsRepository.reload() }
        }
    }

    fun launchSource(source: TvSource) {
        KeyEventBus.suppressRedirect(3000)
        if (!c.tvInputsRepository.launch(source)) toast("Не удалось открыть «${source.label}»")
    }

    fun openMenu(target: AppInfo) {
        if (moving.value == null) menu.value = target
    }

    fun closeMenu() {
        menu.value = null
    }

    fun onMenuAction(target: AppInfo, action: MenuAction) {
        menu.value = null
        val isFavorite = state.value.apps.favorites.any { it.packageName == target.packageName }
        when (action) {
            MenuAction.MOVE -> startMove(target)
            MenuAction.FAVORITE -> {
                val limit = state.value.settings.columns
                if (!isFavorite && state.value.apps.favorites.size >= limit) {
                    toast("В избранном максимум $limit - столько, сколько иконок в ряду")
                } else {
                    viewModelScope.launch { c.appsRepository.setFavorite(target.packageName, !isFavorite) }
                }
            }
            MenuAction.HIDE -> viewModelScope.launch {
                c.appsRepository.setHidden(target.packageName, true)
                toast("«${target.label}» скрыто. Вернуть: Настройки → Скрытые приложения")
            }
            MenuAction.INFO -> SystemIntents.openAppInfo(app, target.packageName)
            MenuAction.UNINSTALL -> SystemIntents.uninstall(app, target.packageName)
        }
    }

    private fun startMove(target: AppInfo) {
        val apps = state.value.apps
        val inFav = apps.favorites.any { it.packageName == target.packageName }
        val list = if (inFav) apps.favorites else apps.apps
        moving.value = MoveState(target.packageName, inFav, list.map { it.packageName })
    }

    fun moveBy(dx: Int, dy: Int) {
        val m = moving.value ?: return
        val delta = dx + dy * state.value.settings.columns
        val list = m.order.toMutableList()
        val from = list.indexOf(m.packageName)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from == to) return
        list.removeAt(from)
        list.add(to, m.packageName)
        moving.value = m.copy(order = list)
    }

    fun commitMove() {
        val m = moving.value ?: return
        viewModelScope.launch {
            if (m.inFavorites) c.appsRepository.saveFavoriteOrder(m.order) else c.appsRepository.saveOrder(m.order)
            moving.value = null
        }
    }

    fun setHidden(target: AppInfo, hidden: Boolean) {
        viewModelScope.launch { c.appsRepository.setHidden(target.packageName, hidden) }
    }

    fun resetOrder() {
        viewModelScope.launch {
            c.appsRepository.resetOrder()
            toast("Порядок сброшен: по алфавиту")
        }
    }

    // ---------- верхняя панель / баннеры ----------

    fun onBarAction(key: String) {
        when (key) {
            BAR_LAUNCHER_SETTINGS -> push(Screen.Settings(SettingsPage.ROOT))
            BAR_TV_SETTINGS -> SystemIntents.openSystemSettings(app)
            BAR_FIX_SERVICE -> {
                if (!SystemStatus.tryEnableAccessibility(app)) SystemIntents.openAccessibilitySettings(app)
                refreshSystemStatus()
            }
            BAR_HELP -> push(Screen.Settings(SettingsPage.DIAGNOSTICS))
            BAR_DISMISS -> bannerDismissed.value = true
            BAR_STOCK_OK -> acknowledgeStockLauncher()
        }
    }

    fun openAccessibilitySettings() {
        SystemIntents.openAccessibilitySettings(app)
    }

    fun openSystemSettings() {
        SystemIntents.openSystemSettings(app)
    }

    fun openQuickSettings() {
        SystemIntents.openQuickSettings(app)
    }

    fun openHomeSettings() {
        SystemIntents.openHomeSettings(app)
    }

    // ---------- настройки ----------

    private fun settings(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setColumns(v: Int) = settings { c.settingsRepository.setColumns(v) }
    fun setGlassQuality(v: GlassQuality) = settings { c.settingsRepository.setGlassQuality(v) }
    fun setBlurRadius(v: Int) = settings { c.settingsRepository.setBlurRadius(v) }
    fun setGlassAlpha(v: Float) = settings { c.settingsRepository.setGlassAlpha(v) }
    fun setAnimations(v: Boolean) = settings { c.settingsRepository.setAnimations(v) }
    fun setShelfMode(v: ShelfMode) = settings { c.settingsRepository.setShelfMode(v) }
    fun setShowSources(v: Boolean) = settings { c.settingsRepository.setShowSources(v) }
    fun setRedirectStock(v: Boolean) = settings { c.settingsRepository.setRedirectStock(v) }
    fun setVolumeControl(v: Boolean) = settings { c.settingsRepository.setVolumeControl(v) }
    fun setLongHomeOpensPanel(v: Boolean) = settings { c.settingsRepository.setLongHomeOpensPanel(v) }

    fun setWallpaperPreset(index: Int) = settings { c.wallpaperRepository.setPreset(index) }
    fun setWallpaperColor(color: Int) = settings { c.wallpaperRepository.setColor(color) }

    fun importWallpaper(uri: Uri) = settings {
        val ok = c.wallpaperRepository.importFromUri(uri)
        toast(if (ok) "Обои установлены" else "Не удалось открыть изображение")
    }

    fun importWallpaper(file: File) = settings {
        val ok = c.wallpaperRepository.importFromFile(file)
        toast(if (ok) "Обои установлены" else "Не удалось открыть изображение")
        if (ok) pop()
    }

    // ---------- кнопки пульта ----------

    fun startLearning() {
        if (!KeyEventBus.serviceConnected.value) {
            toast("Служба не активна: поймаются только кнопки, которые доходят до лаунчера")
        }
        KeyEventBus.learning.value = true
    }

    fun stopLearning() {
        KeyEventBus.learning.value = false
    }

    fun bindKey(keyCode: Int, target: String?) {
        viewModelScope.launch {
            c.settingsRepository.setKeyBinding(keyCode, target)
            pop()
        }
    }

    fun removeBinding(keyCode: Int) = settings { c.settingsRepository.setKeyBinding(keyCode, null) }

    private fun toast(text: String) {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val BAR_LAUNCHER_SETTINGS = "bar:launcher"
        const val BAR_TV_SETTINGS = "bar:tv"
        const val BAR_FIX_SERVICE = "bar:fix"
        const val BAR_HELP = "bar:help"
        const val BAR_DISMISS = "bar:dismiss"
        const val BAR_STOCK_OK = "bar:stock_ok"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as GlassLauncherApp)
            }
        }
    }
}
