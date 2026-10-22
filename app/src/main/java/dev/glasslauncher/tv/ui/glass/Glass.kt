package dev.glasslauncher.tv.ui.glass

import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.Matrix
import android.graphics.BitmapShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.glasslauncher.tv.data.wallpaper.Wallpaper
import dev.glasslauncher.tv.ui.theme.GlassStyle
import dev.glasslauncher.tv.ui.theme.LocalBackdropImage
import dev.glasslauncher.tv.ui.theme.LocalGlass
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Фон экрана, поверх которого лежит стекло. [visible] позволяет не рисовать фон, пока он целиком
 * закрыт непрозрачным Top Shelf (лишний полноэкранный проход GPU).
 */
@Composable
fun Modifier.backdropSource(visible: () -> Boolean = { true }): Modifier =
    this.drawWithContent { if (visible()) drawContent() }

/**
 * Liquid Glass панель.
 *
 * FULL (Android 13+): под панелью - уже размытая картинка того, что за ней (размытые обои или
 * баннер приложения из Top Shelf, [LocalBackdropImage]) в экранных координатах. Она рисуется
 * AGSL-шейдером прямо в панель, без внеэкранного прохода: у краёв фон преломляется к центру
 * с лёгкой хроматической аберрацией, по кромке - световой блик. Живого блюра на кадр нет -
 * RenderEffect пересчитывался на каждом кадре любой анимации и душил GPU телевизора.
 * Заливка почти прозрачная - форму панели дают блики и преломление, как у стекла.
 *
 * LIGHT: заранее размытые обои + плотнее тонировка, без эффектов.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    tintBoost: Float = 0f,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(cornerRadius)
    val origin = remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned {
                val p = it.positionInRoot()
                if (p != origin.value) origin.value = p
            }
            .clip(shape),
    ) {
        GlassBackdrop(glass, origin, cornerRadius, Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val alpha = if (glass.full) glass.tintAlpha * 0.45f else glass.tintAlpha
                    val tint = Color(0xFF0E131A).copy(alpha = (alpha + tintBoost).coerceIn(0f, 0.95f))
                    val sheen = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (glass.full) 0.20f else 0.16f),
                        0.35f to Color.White.copy(alpha = 0.03f),
                        1f to Color.Transparent,
                    )
                    val glint = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.16f, 0f),
                        radius = max(size.width, size.height) * 0.55f,
                    )
                    onDrawBehind {
                        drawRect(tint)
                        drawRect(sheen)
                        drawRect(glint)
                    }
                }
                .border(if (glass.full) 1.5.dp else 1.dp, GlassEdgeBrush, shape),
        )
        content()
    }
}

/** Кромка стекла: яркий верх, тёмная середина, отражённый свет снизу. */
val GlassEdgeBrush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.75f),
    0.18f to Color.White.copy(alpha = 0.22f),
    0.6f to Color.White.copy(alpha = 0.06f),
    1f to Color.White.copy(alpha = 0.30f),
)

@Composable
private fun GlassBackdrop(glass: GlassStyle, origin: MutableState<Offset>, cornerRadius: Dp, modifier: Modifier) {
    val image = LocalBackdropImage.current ?: glass.blurred
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val edgePx = with(density) { 22.dp.toPx() }
    if (glass.full && image != null && Build.VERSION.SDK_INT >= 33) {
        val lens = remember(image) { LensBrush(image) }
        Box(modifier.drawBehind { lens.draw(this, glass.rootSize, origin.value, radiusPx, edgePx) })
    } else {
        Box(modifier.drawBehind { drawBlurredWallpaper(glass.copy(blurred = image), origin.value) })
    }
}

/**
 * Линза как кисть: размытая картинка подаётся в AGSL входным BitmapShader с матрицей «экран → панель»,
 * результат рисуется одним drawRect. Стоимость - несколько выборок текстуры на пиксель панели.
 */
@RequiresApi(33)
private class LensBrush(image: ImageBitmap) {
    private val bitmap = image.asAndroidBitmap()
    private val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
    }
    private val shader = RuntimeShader(LIQUID_GLASS_SHADER)
    private val brush = ShaderBrush(shader)
    private val matrix = Matrix()

    fun draw(scope: DrawScope, rootSize: IntSize, origin: Offset, radiusPx: Float, edgePx: Float) = with(scope) {
        if (size.width <= 0f || size.height <= 0f) return@with
        val rw = if (rootSize == IntSize.Zero) size.width else rootSize.width.toFloat()
        val rh = if (rootSize == IntSize.Zero) size.height else rootSize.height.toFloat()
        val scale = max(rw / bitmap.width, rh / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate((rw - bitmap.width * scale) / 2f - origin.x, (rh - bitmap.height * scale) / 2f - origin.y)
        bitmapShader.setLocalMatrix(matrix)
        shader.setInputShader("content", bitmapShader)
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("radius", radiusPx)
        shader.setFloatUniform("edge", edgePx)
        shader.setFloatUniform("strength", edgePx * 0.9f)
        drawRect(brush)
    }
}

private fun DrawScope.drawBlurredWallpaper(glass: GlassStyle, origin: Offset) {
    val image = glass.blurred
    if (image == null) {
        drawRect(glass.baseColor)
        return
    }
    val root = if (glass.rootSize == IntSize.Zero) {
        IntSize(size.width.roundToInt(), size.height.roundToInt())
    } else {
        glass.rootSize
    }
    val scale = max(root.width / image.width.toFloat(), root.height / image.height.toFloat())
    val dw = image.width * scale
    val dh = image.height * scale
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((root.width - dw) / 2f - origin.x).roundToInt(), ((root.height - dh) / 2f - origin.y).roundToInt()),
        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
        filterQuality = FilterQuality.Low,
    )
}

/**
 * AGSL-линза. Расстояние до края скруглённого прямоугольника (SDF) даёт силу эффекта, его градиент -
 * нормаль. Фон у края сэмплируется со сдвигом внутрь (преломление толстого стекла), каналы R/G/B
 * сдвигаются чуть по-разному (дисперсия), по кромке со стороны света - тонкий блик.
 */
private const val LIQUID_GLASS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float edge;
uniform float strength;

float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 c = size * 0.5;
    float2 p = coord - c;
    float sd = sdRound(p, c, radius);
    float inside = -sd;
    float k = 1.0 - smoothstep(0.0, edge, inside);
    k = k * k;
    float2 e = float2(1.0, 0.0);
    float2 n = float2(
        sdRound(p + e.xy, c, radius) - sdRound(p - e.xy, c, radius),
        sdRound(p + e.yx, c, radius) - sdRound(p - e.yx, c, radius));
    n = n / max(length(n), 0.0001);
    float2 off = -n * k * strength;
    float2 lo = float2(0.5);
    float2 hi = size - 0.5;
    half r = content.eval(clamp(coord + off * 1.12, lo, hi)).r;
    half g = content.eval(clamp(coord + off, lo, hi)).g;
    half4 bs = content.eval(clamp(coord + off * 0.88, lo, hi));
    half3 col = half3(r, g, bs.b);
    float rim = 1.0 - smoothstep(0.0, 4.0, inside);
    float light = clamp(dot(n, normalize(float2(-0.5, -0.85))), 0.0, 1.0);
    float back = clamp(dot(n, normalize(float2(0.4, 0.9))), 0.0, 1.0);
    col += half3(rim * (light * 0.55 + back * 0.18) + k * 0.06);
    return half4(col, 1.0);
}
"""

/** Фон всего экрана: резкие или размытые обои, либо однотонный цвет. */
@Composable
fun WallpaperLayer(wallpaper: Wallpaper, blurred: Boolean, modifier: Modifier = Modifier) {
    val image = if (blurred) wallpaper.blurred ?: wallpaper.sharp else wallpaper.sharp ?: wallpaper.blurred
    if (image == null) {
        Box(modifier.background(wallpaper.baseColor))
    } else {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
        )
    }
}

/** Простая полупрозрачная заливка вместо стекла - для мелких элементов (пилюли, строки меню). */
fun Modifier.glassFill(shape: Shape, focused: Boolean): Modifier = this
    .clip(shape)
    .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f))
    .border(1.dp, if (focused) Brush.verticalGradient(listOf(Color.White, Color.White)) else GlassEdgeBrush, shape)
