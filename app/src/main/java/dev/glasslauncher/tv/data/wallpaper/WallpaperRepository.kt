package dev.glasslauncher.tv.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.glasslauncher.tv.data.settings.SettingsRepository
import dev.glasslauncher.tv.data.settings.WallpaperType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@Immutable
data class Wallpaper(
    /** Обои в разрешении экрана (null для однотонного цвета). */
    val sharp: ImageBitmap?,
    /** Маленькая размытая копия - подложка для всех стеклянных панелей. Считается один раз. */
    val blurred: ImageBitmap?,
    val baseColor: Color,
    /** Видеообои: файл ролика (sharp/blurred тогда - его первый кадр, для стекла и экранов настроек). */
    val video: File? = null,
    /** Меняется при каждой новой загрузке ролика - ключ для пересоздания плеера. */
    val videoStamp: Long = 0L,
)

/** Расширения роликов, которые можно поставить живыми обоями. */
val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov")

object WallpaperPresets {
    data class Preset(val name: String, val colors: IntArray)

    val all = listOf(
        Preset("Аврора", intArrayOf(0xFF0B1E3A.toInt(), 0xFF1F6F78.toInt(), 0xFF7B4FB8.toInt())),
        Preset("Океан", intArrayOf(0xFF061826.toInt(), 0xFF0E4D72.toInt(), 0xFF2FA3C7.toInt())),
        Preset("Закат", intArrayOf(0xFF2A0F2E.toInt(), 0xFFB2475B.toInt(), 0xFFF2A65A.toInt())),
        Preset("Графит", intArrayOf(0xFF0D0F12.toInt(), 0xFF2B3038.toInt(), 0xFF5A6474.toInt())),
        Preset("Лес", intArrayOf(0xFF07160F.toInt(), 0xFF1E5137.toInt(), 0xFF8DBE6A.toInt())),
        Preset("Лаванда", intArrayOf(0xFF1A1433.toInt(), 0xFF5B4B9A.toInt(), 0xFFD3A6E8.toInt())),
    )

    val solidColors = listOf(
        0xFF0B0F14.toInt(), 0xFF1B2735.toInt(), 0xFF2D1B3D.toInt(), 0xFF13322B.toInt(),
        0xFF3A1F1F.toInt(), 0xFF2E2E2E.toInt(), 0xFF0F2A47.toInt(), 0xFF3B3355.toInt(),
    )

    fun render(preset: Preset, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val (c0, c1, c2) = preset.colors.let { Triple(it[0], it[1], it[2]) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c0, c1, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        fun glow(cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
            val c = (color and 0x00FFFFFF) or (alpha shl 24)
            paint.shader = RadialGradient(cx, cy, r, c, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
        glow(w * 0.22f, h * 0.28f, w * 0.55f, c2, 0xB0)
        glow(w * 0.85f, h * 0.85f, w * 0.5f, c1, 0x90)
        glow(w * 0.65f, h * 0.1f, w * 0.35f, c2, 0x50)
        return out
    }
}

class WallpaperRepository(
    context: Context,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
    private val lowEnd: Boolean,
) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "wallpaper.img")
    private val videoFile = File(appContext.filesDir, "wallpaper.video")

    private data class Spec(
        val type: WallpaperType,
        val preset: Int,
        val color: Int,
        val stamp: Long,
        val blurRadius: Int,
    )

    val wallpaper: StateFlow<Wallpaper> = settings.settings
        .map { Spec(it.wallpaperType, it.wallpaperPreset, it.wallpaperColor, it.wallpaperStamp, it.blurRadius) }
        .distinctUntilChanged()
        .mapLatest { spec -> runCatching { build(spec) }.getOrElse { fallback() } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, Wallpaper(null, null, Color(0xFF0B0F14)))

    private fun fallback() = Wallpaper(null, null, Color(0xFF1B2735))

    private fun build(spec: Spec): Wallpaper {
        val maxW = if (lowEnd) 1280 else 1920
        val maxH = maxW * 9 / 16
        val sharp: Bitmap = when (spec.type) {
            WallpaperType.COLOR -> return Wallpaper(null, null, Color(spec.color))
            WallpaperType.FILE -> decode(file, maxW, maxH) ?: preset(0)
            WallpaperType.PRESET -> preset(spec.preset)
            WallpaperType.VIDEO -> videoFrame(videoFile, maxW) ?: preset(0)
        }
        // Радиус в настройках «на экране»; картинку уменьшаем в 8 раз, радиус - пропорционально.
        val blurred = BlurUtil.blur(sharp, scale = 0.125f, radius = max(1, spec.blurRadius / 6))
        val avg = Bitmap.createScaledBitmap(blurred, 1, 1, true).getPixel(0, 0)
        val video = if (spec.type == WallpaperType.VIDEO && videoFile.exists()) videoFile else null
        return Wallpaper(sharp.asImageBitmap(), blurred.asImageBitmap(), Color(avg), video, spec.stamp)
    }

    /**
     * Кадр ролика для неподвижного фона и размытой подложки стекла. Вытаскивать кадр из видео долго
     * (сотни миллисекунд на каждом старте), поэтому он один раз сохраняется рядом как JPEG.
     */
    private fun videoFrame(f: File, maxW: Int): Bitmap? {
        if (!f.exists()) return null
        val poster = File(f.parentFile, f.name + ".poster.jpg")
        if (f == videoFile && poster.exists() && poster.lastModified() >= f.lastModified()) {
            BitmapFactory.decodeFile(poster.absolutePath)?.let { return it }
        }
        val frame = extractVideoFrame(f, maxW) ?: return null
        if (f == videoFile) {
            runCatching { poster.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
        }
        return frame
    }

    /** Кадр ролика (первая секунда), уменьшенный до ширины экрана. */
    private fun extractVideoFrame(f: File, maxW: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(f.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            val soft = if (frame.config == Bitmap.Config.HARDWARE) frame.copy(Bitmap.Config.ARGB_8888, false) else frame
            if (soft.width <= maxW) soft else Bitmap.createScaledBitmap(soft, maxW, soft.height * maxW / soft.width, true)
        } catch (e: Exception) {
            Log.w(TAG, "video frame failed", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun preset(index: Int): Bitmap {
        val p = WallpaperPresets.all.getOrElse(index) { WallpaperPresets.all.first() }
        // Градиенты гладкие - рендерим в половинном размере, на экране растягивается без потерь.
        return WallpaperPresets.render(p, 960, 540)
    }

    private fun decode(f: File, maxW: Int, maxH: Int): Bitmap? {
        if (!f.exists()) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(f)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    var sample = 1
                    while (w / (sample * 2) >= maxW && h / (sample * 2) >= maxH) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.onFailure { Log.w(TAG, "decode failed", it) }.getOrNull()
    }

    suspend fun setPreset(index: Int) = settings.setWallpaperPreset(index)

    suspend fun setColor(color: Int) = settings.setWallpaperColor(color)

    /** Копируем картинку к себе: не нужно держать разрешение на Uri/USB, флешку можно вынуть. */
    suspend fun importFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(appContext.cacheDir, "wallpaper.tmp")
        val ok = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: error("no stream")
        }.isSuccess
        ok && commit(tmp)
    }

    suspend fun importFromFile(source: File): Boolean = withContext(Dispatchers.IO) {
        if (source.extension.lowercase() in VIDEO_EXTENSIONS) return@withContext importVideo(source)
        val tmp = File(appContext.cacheDir, "wallpaper.tmp")
        val ok = runCatching { source.copyTo(tmp, overwrite = true) }.isSuccess
        ok && commit(tmp)
    }

    /** Ролик копируем к себе (флешку можно вынуть) и проверяем, что из него читается кадр. */
    private suspend fun importVideo(source: File): Boolean {
        val tmp = File(appContext.cacheDir, "wallpaper.video.tmp")
        if (runCatching { source.copyTo(tmp, overwrite = true) }.isFailure) return false
        if (videoFrame(tmp, 64) == null) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(videoFile)) {
            runCatching { tmp.copyTo(videoFile, overwrite = true) }.onFailure { return false }
            tmp.delete()
        }
        settings.setWallpaperVideo(System.currentTimeMillis())
        return true
    }

    private suspend fun commit(tmp: File): Boolean {
        val probe = decode(tmp, 64, 36)
        if (probe == null) {
            tmp.delete()
            return false
        }
        probe.recycle()
        if (!tmp.renameTo(file)) {
            runCatching { tmp.copyTo(file, overwrite = true) }.onFailure { return false }
            tmp.delete()
        }
        settings.setWallpaperFile(System.currentTimeMillis())
        return true
    }

    companion object {
        private const val TAG = "Wallpaper"
    }
}
