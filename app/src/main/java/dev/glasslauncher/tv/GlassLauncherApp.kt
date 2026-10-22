package dev.glasslauncher.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import dev.glasslauncher.tv.data.apps.AppImageFetcher
import dev.glasslauncher.tv.data.apps.AppImageKeyer
import dev.glasslauncher.tv.di.AppContainer

class GlassLauncherApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Coil: иконки и баннеры приложений идут через собственный Fetcher и кэшируются в памяти. */
    override fun newImageLoader(): ImageLoader {
        val lowEnd = container.device.isLowEnd
        return ImageLoader.Builder(this)
            .components {
                add(AppImageKeyer())
                add(AppImageFetcher.Factory(container.appImageLoader))
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (lowEnd) 0.15 else 0.25)
                    .build()
            }
            .diskCache(null)
            .allowRgb565(lowEnd)
            .crossfade(false)
            .build()
    }
}
