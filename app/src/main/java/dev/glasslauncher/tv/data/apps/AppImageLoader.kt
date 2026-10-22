package dev.glasslauncher.tv.data.apps

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import dev.glasslauncher.tv.data.wallpaper.BlurUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Рендерит баннеры/иконки приложений в Bitmap фиксированного размера.
 * Любая ошибка (нет баннера, нет иконки, пакет удалён) превращается в аккуратную заглушку.
 */
class AppImageLoader(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    fun load(image: AppImage): Bitmap = runCatching {
        when (image.kind) {
            AppImage.Kind.BANNER -> banner(image)
            AppImage.Kind.ICON -> icon(image)
            // Для Top Shelf: маленький размытый баннер. Растянутый на весь экран выглядит как мягкий blur,
            // а стоит копейки - никакого live-blur.
            AppImage.Kind.SHELF -> BlurUtil.blur(banner(image), scale = 0.35f, radius = 4)
        }
    }.getOrElse { placeholder(image.label, BANNER_W, BANNER_H) }

    private fun banner(image: AppImage): Bitmap {
        CUSTOM_BANNERS[image.packageName]?.let { title -> return globeBanner(title) }
        val component = ComponentName(image.packageName, image.activityName)
        val banner: Drawable? = runCatching { pm.getActivityBanner(component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(image.packageName) }.getOrNull()
        if (banner != null) return drawFit(banner, BANNER_W, BANNER_H, background = null)

        val icon = iconDrawable(image) ?: return placeholder(image.label, BANNER_W, BANNER_H)
        return iconBanner(icon)
    }

    private fun icon(image: AppImage): Bitmap {
        val icon = iconDrawable(image) ?: return placeholder(image.label, ICON_SIZE, ICON_SIZE)
        return drawFit(icon, ICON_SIZE, ICON_SIZE, background = null)
    }

    private fun iconDrawable(image: AppImage): Drawable? {
        val component = ComponentName(image.packageName, image.activityName)
        return runCatching { pm.getActivityIcon(component) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(image.packageName) }.getOrNull()
    }

    /** Приложение без баннера: иконка по центру на градиенте из её среднего цвета. */
    private fun iconBanner(icon: Drawable): Bitmap {
        val iconBmp = drawFit(icon, 96, 96, background = null)
        val avg = averageColor(iconBmp)
        val out = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(),
            shade(avg, 0.55f), shade(avg, 0.25f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(), paint)
        val size = (BANNER_H * 0.62f).roundToInt()
        val left = (BANNER_W - size) / 2f
        val top = (BANNER_H - size) / 2f
        val scaled = Bitmap.createScaledBitmap(drawFit(icon, size, size, null), size, size, true)
        canvas.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Собственный нейтральный баннер браузера: глобус на сине-бирюзовом градиенте и подпись. */
    private fun globeBanner(title: String): Bitmap {
        val w = BANNER_W.toFloat()
        val h = BANNER_H.toFloat()
        val out = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, w, h, Color.rgb(28, 52, 110), Color.rgb(18, 140, 150), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bg)

        val cx = w * 0.27f
        val cy = h * 0.5f
        val r = h * 0.27f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = h * 0.028f
            color = Color.WHITE
        }
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.drawOval(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r, stroke)
        canvas.drawLine(cx, cy - r, cx, cy + r, stroke)
        canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
        val lat = r * 0.55f
        val half = kotlin.math.sqrt(r * r - lat * lat)
        canvas.drawLine(cx - half, cy - lat, cx + half, cy - lat, stroke)
        canvas.drawLine(cx - half, cy + lat, cx + half, cy + lat, stroke)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h * 0.2f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val y = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(title, w * 0.5f, y, text)
        return out
    }

    private fun drawFit(drawable: Drawable, w: Int, h: Int, background: Int?): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        background?.let { canvas.drawColor(it) }
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = min(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val left = (w - dw) / 2
        val top = (h - dh) / 2
        val oldBounds = drawable.copyBounds()
        drawable.setBounds(left, top, left + dw, top + dh)
        drawable.draw(canvas)
        drawable.bounds = oldBounds
        return out
    }

    private fun placeholder(label: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val hue = (label.hashCode() and 0x7fffffff) % 360
        val c1 = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.45f))
        val c2 = Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.55f, 0.25f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val letter = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = h / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, w / 2f, y, text)
        return out
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val px = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        return if (Color.alpha(px) < 40) Color.rgb(40, 52, 70) else px
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).roundToInt(),
        (Color.green(color) * factor).roundToInt(),
        (Color.blue(color) * factor).roundToInt(),
    )

    companion object {
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_SIZE = 192

        /** Приложения, чей баннер заменяем своим (пакет → подпись на баннере). */
        private val CUSTOM_BANNERS = mapOf(
            "com.phlox.tvwebbrowser" to "Браузер",
        )
    }
}

class AppImageKeyer : Keyer<AppImage> {
    override fun key(data: AppImage, options: Options): String =
        "${data.packageName}/${data.activityName}/${data.kind}/${data.stamp}"
}

class AppImageFetcher(
    private val data: AppImage,
    private val options: Options,
    private val loader: AppImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bitmap = loader.load(data)
        DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val loader: AppImageLoader) : Fetcher.Factory<AppImage> {
        override fun create(data: AppImage, options: Options, imageLoader: ImageLoader): Fetcher =
            AppImageFetcher(data, options, loader)
    }
}
