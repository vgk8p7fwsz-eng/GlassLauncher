package dev.glasslauncher.tv.data.apps

import androidx.compose.runtime.Immutable

@Immutable
data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isLeanback: Boolean,
    /** Системное приложение без обновлений: удалить нельзя, только отключить. */
    val isSystem: Boolean,
    /** Время изменения APK: ключ кэша иконок, меняется при обновлении приложения. */
    val stamp: Long,
) {
    val key: String get() = "app:$packageName"

    fun image(kind: AppImage.Kind) = AppImage(packageName, activityName, label, kind, stamp)
}

/** Модель для Coil: что именно нарисовать для приложения. */
@Immutable
data class AppImage(
    val packageName: String,
    val activityName: String,
    val label: String,
    val kind: Kind,
    val stamp: Long,
) {
    enum class Kind { BANNER, ICON, SHELF }
}

@Immutable
data class AppsState(
    val loaded: Boolean = false,
    val favorites: List<AppInfo> = emptyList(),
    val apps: List<AppInfo> = emptyList(),
    val hidden: List<AppInfo> = emptyList(),
    val all: List<AppInfo> = emptyList(),
)
