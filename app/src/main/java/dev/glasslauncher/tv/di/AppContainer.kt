package dev.glasslauncher.tv.di

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dev.glasslauncher.tv.data.apps.AppImageLoader
import dev.glasslauncher.tv.data.apps.AppsRepository
import dev.glasslauncher.tv.data.db.LauncherDatabase
import dev.glasslauncher.tv.data.settings.SettingsRepository
import dev.glasslauncher.tv.data.tv.TvInputsRepository
import dev.glasslauncher.tv.data.settings.WallpaperType
import dev.glasslauncher.tv.data.wallpaper.VideoPlayerHolder
import dev.glasslauncher.tv.data.wallpaper.WallpaperRepository
import dev.glasslauncher.tv.system.DeviceProfile
import dev.glasslauncher.tv.system.VolumeMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private val Context.launcherDataStore by preferencesDataStore(name = "launcher_settings")

/** Ручной DI: один контейнер на процесс, общий для Activity, AccessibilityService и ресиверов. */
class AppContainer(app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val device: DeviceProfile.Info = DeviceProfile.detect(app)

    val settingsRepository = SettingsRepository(app.launcherDataStore)

    val appImageLoader = AppImageLoader(app)

    val volumeMonitor: VolumeMonitor by lazy { VolumeMonitor(app) }

    private val database: LauncherDatabase by lazy {
        Room.databaseBuilder(app, LauncherDatabase::class.java, "launcher.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val appsRepository: AppsRepository by lazy {
        AppsRepository(app, database, appScope)
    }

    val tvInputsRepository: TvInputsRepository by lazy { TvInputsRepository(app) }

    val wallpaperRepository: WallpaperRepository by lazy {
        WallpaperRepository(app, settingsRepository, appScope, device.isLowEnd)
    }

    /** Плеер живых обоев на весь процесс (см. VideoPlayerHolder). Только из главного потока. */
    val videoPlayer = VideoPlayerHolder()

    init {
        // Быстрый старт: всё, что нужно первому экрану, начинаем готовить сразу при создании процесса,
        // параллельно с запуском Activity и Compose, а не когда до этого дойдёт отрисовка.
        appScope.launch {
            val s = settingsRepository.settings.first()
            val video = File(app.filesDir, "wallpaper.video")
            if (s.wallpaperType == WallpaperType.VIDEO && video.exists()) {
                // Прямо из фонового потока: главный в этот момент занят запуском Activity.
                videoPlayer.acquire(video, s.wallpaperStamp)
                // Если главный экран так и не покажется (процесс поднят службой), не держим декодер.
                videoPlayer.releaseLater()
            }
        }
        appScope.launch { appsRepository }
        appScope.launch { wallpaperRepository }
    }
}
