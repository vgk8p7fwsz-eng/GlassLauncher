package dev.glasslauncher.tv.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.glasslauncher.tv.system.VolumeLevel
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.glasslauncher.tv.data.apps.AppImage
import dev.glasslauncher.tv.data.wallpaper.Wallpaper
import dev.glasslauncher.tv.ui.glass.GlassPanel
import dev.glasslauncher.tv.ui.glass.WallpaperLayer
import dev.glasslauncher.tv.ui.theme.GlassColors
import dev.glasslauncher.tv.ui.theme.GlassType
import dev.glasslauncher.tv.ui.theme.Inter
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Что показывает Top Shelf: обои или размытый баннер приложения в фокусе. */
@Immutable
sealed interface ShelfContent {
    data object WallpaperShelf : ShelfContent
    data class AppShelf(val image: AppImage, val label: String) : ShelfContent
}

/**
 * Верхняя зона ~55% экрана. Кроссфейд 400 мс между обоями и размытым баннером.
 * Баннер размыт заранее (AppImage.Kind.SHELF, кэш Coil), здесь только растягивание маленькой картинки.
 */
@Composable
fun ShelfBackdrop(
    content: ShelfContent,
    wallpaper: Wallpaper,
    height: Dp,
    animations: Boolean,
    modifier: Modifier = Modifier,
    /** Под Top Shelf играют живые обои: на месте обоев оставляем прозрачное окно на видео. */
    videoBehind: Boolean = false,
) {
    Crossfade(
        targetState = content,
        animationSpec = if (animations) tween(400) else snap(),
        label = "shelf",
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawWithContent {
                drawContent()
                // Затемнение сверху (под часами) и снизу (под доком). Середину не трогаем: там градиент
                // прозрачный, а лишняя полноэкранная заливка на этом GPU стоит миллисекунды на кадр.
                val h = size.height
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.7f to Color.Black.copy(alpha = 0.12f),
                        1f to Color.Transparent,
                        endY = h * 0.32f,
                    ),
                    size = size.copy(height = h * 0.32f),
                )
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to GlassColors.Scrim.copy(alpha = 0.85f),
                        startY = h * 0.55f,
                        endY = h,
                    ),
                    topLeft = Offset(0f, h * 0.55f),
                    size = size.copy(height = h * 0.45f),
                )
            },
    ) { shelf ->
        when (shelf) {
            ShelfContent.WallpaperShelf -> if (videoBehind) {
                Box(Modifier.fillMaxSize())
            } else {
                WallpaperLayer(wallpaper, blurred = false, modifier = Modifier.fillMaxSize())
            }
            is ShelfContent.AppShelf -> AsyncImage(
                model = shelf.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Крупное название приложения в фокусе в левом верхнем углу - как логотип контента в Top Shelf tvOS. */
@Composable
fun HeroTitle(content: ShelfContent, animations: Boolean, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = (content as? ShelfContent.AppShelf)?.label.orEmpty(),
        animationSpec = if (animations) tween(400) else snap(),
        label = "heroTitle",
        modifier = modifier,
    ) { title ->
        Text(
            title.uppercase(),
            style = GlassType.Title.copy(
                fontSize = 46.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 9.sp,
                shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 12f),
            ),
            maxLines = 1,
        )
    }
}

/** Текущее время, обновляется ровно на смене минуты. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    return now
}

private data class ClockText(val time: String, val date: String)

@Composable
private fun rememberClockText(): ClockText {
    val context = LocalContext.current
    val now = rememberNow()
    return remember(now) {
        val locale = Locale.getDefault()
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        ClockText(
            time = SimpleDateFormat(pattern, locale).format(Date(now)),
            date = SimpleDateFormat("EEEE, d MMMM", locale).format(Date(now)).replaceFirstChar { it.titlecase(locale) },
        )
    }
}

private val ClockShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), blurRadius = 14f)

/**
 * Часы в духе экрана блокировки: время крупно и плотно сверху, дата мелко под ним, без подложки.
 * Шрифт - системный Roboto (на Android 12+ вариативный), жирность 600 и сжатый трекинг.
 */
@Composable
fun LockClock(modifier: Modifier = Modifier) {
    val clock = rememberClockText()
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            clock.time,
            style = GlassType.Title.copy(
                fontSize = 50.sp,
                lineHeight = 52.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1.5).sp,
                shadow = ClockShadow,
            ),
            maxLines = 1,
        )
        Text(
            clock.date,
            style = GlassType.Body.copy(
                fontFamily = Inter,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.88f),
                shadow = ClockShadow,
            ),
            maxLines = 1,
        )
    }
}

/** Та же пара «дата · время» в одну строку - для шапки настроек. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    val clock = rememberClockText()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            clock.date,
            style = GlassType.Body.copy(fontFamily = Inter, fontWeight = FontWeight.Medium, color = GlassColors.TextDim),
            maxLines = 1,
        )
        Text(
            clock.time,
            style = GlassType.Title.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/**
 * Размытая версия Top Shelf - фон под сеткой. Для баннера приложения это та же картинка
 * (она уже размыта), для обоев - их размытая копия.
 */
@Composable
fun ShelfBlurUnderlay(content: ShelfContent, wallpaper: Wallpaper, animations: Boolean, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = content,
        animationSpec = if (animations) tween(400) else snap(),
        label = "shelfBlur",
        modifier = modifier,
    ) { shelf ->
        when (shelf) {
            ShelfContent.WallpaperShelf -> WallpaperLayer(wallpaper, blurred = true, modifier = Modifier.fillMaxSize())
            is ShelfContent.AppShelf -> AsyncImage(
                model = shelf.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Баннер-предупреждение на главном экране (служба выключена, прошивка вернула стоковый лаунчер). */
@Composable
fun StatusBanner(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    GlassPanel(modifier.widthIn(max = 760.dp), cornerRadius = 22.dp, tintBoost = 0.15f) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(title, style = GlassType.Headline.copy(color = GlassColors.Warning))
            Text(text, style = GlassType.BodyDim, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        }
    }
}
