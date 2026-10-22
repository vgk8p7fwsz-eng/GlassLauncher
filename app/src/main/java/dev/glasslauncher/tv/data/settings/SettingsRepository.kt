package dev.glasslauncher.tv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class GlassQuality { AUTO, LIGHT, FULL }
enum class ShelfMode { FOCUSED_APP, WALLPAPER }
enum class WallpaperType { PRESET, COLOR, FILE, VIDEO }

/** Значение привязки кнопки, означающее «открыть главный экран лаунчера». */
const val BINDING_HOME = "@home"

/** Значение привязки кнопки, означающее «выключить / включить звук». */
const val BINDING_MUTE = "@mute"

data class LauncherSettings(
    val columns: Int = 6,
    val glassQuality: GlassQuality = GlassQuality.AUTO,
    val blurRadius: Int = 24,
    val glassAlpha: Float = 0.35f,
    val animations: Boolean = true,
    val shelfMode: ShelfMode = ShelfMode.FOCUSED_APP,
    val showSources: Boolean = true,
    val wallpaperType: WallpaperType = WallpaperType.PRESET,
    val wallpaperPreset: Int = 0,
    val wallpaperColor: Int = 0xFF1B2735.toInt(),
    val wallpaperStamp: Long = 0L,
    val onboardingDone: Boolean = false,
    val favoritesSeeded: Boolean = false,
    val redirectStockLauncher: Boolean = true,
    val volumeControl: Boolean = true,
    val longHomeOpensPanel: Boolean = true,
    val stockWasDisabled: Boolean = false,
    val keyBindings: Map<Int, String> = emptyMap(),
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object K {
        val columns = intPreferencesKey("columns")
        val glassQuality = stringPreferencesKey("glass_quality")
        val blurRadius = intPreferencesKey("blur_radius")
        val glassAlpha = floatPreferencesKey("glass_alpha")
        val animations = booleanPreferencesKey("animations")
        val shelfMode = stringPreferencesKey("shelf_mode")
        val showSources = booleanPreferencesKey("show_sources")
        val wallpaperType = stringPreferencesKey("wallpaper_type")
        val wallpaperPreset = intPreferencesKey("wallpaper_preset")
        val wallpaperColor = intPreferencesKey("wallpaper_color")
        val wallpaperStamp = longPreferencesKey("wallpaper_stamp")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val favoritesSeeded = booleanPreferencesKey("favorites_seeded")
        val redirectStock = booleanPreferencesKey("redirect_stock")
        val volumeControl = booleanPreferencesKey("volume_control")
        val longHomePanel = booleanPreferencesKey("long_home_panel")
        val stockWasDisabled = booleanPreferencesKey("stock_was_disabled")
        val keyBindings = stringPreferencesKey("key_bindings")
    }

    val settings: Flow<LauncherSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val d = LauncherSettings()
            LauncherSettings(
                columns = (p[K.columns] ?: d.columns).coerceIn(4, 7),
                glassQuality = enumOr(p[K.glassQuality], d.glassQuality),
                blurRadius = (p[K.blurRadius] ?: d.blurRadius).coerceIn(4, 64),
                glassAlpha = (p[K.glassAlpha] ?: d.glassAlpha).coerceIn(0.05f, 0.9f),
                animations = p[K.animations] ?: d.animations,
                shelfMode = enumOr(p[K.shelfMode], d.shelfMode),
                showSources = p[K.showSources] ?: d.showSources,
                wallpaperType = enumOr(p[K.wallpaperType], d.wallpaperType),
                wallpaperPreset = p[K.wallpaperPreset] ?: d.wallpaperPreset,
                wallpaperColor = p[K.wallpaperColor] ?: d.wallpaperColor,
                wallpaperStamp = p[K.wallpaperStamp] ?: d.wallpaperStamp,
                onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
                favoritesSeeded = p[K.favoritesSeeded] ?: d.favoritesSeeded,
                redirectStockLauncher = p[K.redirectStock] ?: d.redirectStockLauncher,
                volumeControl = p[K.volumeControl] ?: d.volumeControl,
                longHomeOpensPanel = p[K.longHomePanel] ?: d.longHomeOpensPanel,
                stockWasDisabled = p[K.stockWasDisabled] ?: d.stockWasDisabled,
                keyBindings = decodeBindings(p[K.keyBindings]),
            )
        }
        .distinctUntilChanged()

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        runCatching { dataStore.edit { block(it) } }
    }

    suspend fun setColumns(value: Int) = edit { it[K.columns] = value.coerceIn(4, 7) }
    suspend fun setGlassQuality(value: GlassQuality) = edit { it[K.glassQuality] = value.name }
    suspend fun setBlurRadius(value: Int) = edit { it[K.blurRadius] = value.coerceIn(4, 64) }
    suspend fun setGlassAlpha(value: Float) = edit { it[K.glassAlpha] = value.coerceIn(0.05f, 0.9f) }
    suspend fun setAnimations(value: Boolean) = edit { it[K.animations] = value }
    suspend fun setShelfMode(value: ShelfMode) = edit { it[K.shelfMode] = value.name }
    suspend fun setShowSources(value: Boolean) = edit { it[K.showSources] = value }
    suspend fun setOnboardingDone() = edit { it[K.onboardingDone] = true }
    suspend fun setFavoritesSeeded() = edit { it[K.favoritesSeeded] = true }
    suspend fun setRedirectStock(value: Boolean) = edit { it[K.redirectStock] = value }
    suspend fun setVolumeControl(value: Boolean) = edit { it[K.volumeControl] = value }
    suspend fun setLongHomeOpensPanel(value: Boolean) = edit { it[K.longHomePanel] = value }
    suspend fun setStockWasDisabled(value: Boolean) = edit { it[K.stockWasDisabled] = value }

    suspend fun setWallpaperPreset(index: Int) = edit {
        it[K.wallpaperType] = WallpaperType.PRESET.name
        it[K.wallpaperPreset] = index
    }

    suspend fun setWallpaperColor(color: Int) = edit {
        it[K.wallpaperType] = WallpaperType.COLOR.name
        it[K.wallpaperColor] = color
    }

    suspend fun setWallpaperVideo(stamp: Long) = edit {
        it[K.wallpaperType] = WallpaperType.VIDEO.name
        it[K.wallpaperStamp] = stamp
    }

    suspend fun setWallpaperFile(stamp: Long) = edit {
        it[K.wallpaperType] = WallpaperType.FILE.name
        it[K.wallpaperStamp] = stamp
    }

    suspend fun setKeyBinding(keyCode: Int, target: String?) = edit { prefs ->
        val map = decodeBindings(prefs[K.keyBindings]).toMutableMap()
        if (target == null) map.remove(keyCode) else map[keyCode] = target
        prefs[K.keyBindings] = encodeBindings(map)
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T =
        raw?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun encodeBindings(map: Map<Int, String>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun decodeBindings(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val code = part.substring(0, idx).toIntOrNull() ?: return@mapNotNull null
            val target = part.substring(idx + 1).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            code to target
        }.toMap()
    }
}
