package dev.glasslauncher.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.glasslauncher.tv.R
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object GlassColors {
    val Text = Color(0xFFF5F7FA)
    val TextDim = Color(0xB3F5F7FA)
    val TextFaint = Color(0x80F5F7FA)
    val Accent = Color(0xFF8FD3FF)
    val Warning = Color(0xFFFFC46B)
    val Scrim = Color(0xFF05070A)
}

/**
 * Inter (SIL OFL 1.1, github.com/rsms/inter) - свободный шрифт в духе SF Pro.
 * Используется в часах и заголовках; лицензия - third_party/inter/LICENSE.txt.
 */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object GlassType {
    val Title = TextStyle(fontFamily = Inter, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = GlassColors.Text)
    val Headline = TextStyle(fontFamily = Inter, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = GlassColors.Text)
    val Body = TextStyle(fontSize = 17.sp, color = GlassColors.Text)
    val BodyDim = TextStyle(fontSize = 15.sp, color = GlassColors.TextDim)
    val Label = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlassColors.Text)
    val Mono = TextStyle(fontSize = 14.sp, color = GlassColors.TextDim, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

/**
 * Всё, что нужно стеклу для отрисовки. Размытая подложка считается один раз (WallpaperRepository),
 * здесь только ссылка на неё.
 */
@Immutable
data class GlassStyle(
    val blurred: ImageBitmap? = null,
    val baseColor: Color = Color(0xFF101820),
    val tintAlpha: Float = 0.35f,
    /** FULL: AGSL-преломление края (API 33+) и blur контента под модальными окнами (API 31+). */
    val full: Boolean = false,
    val animations: Boolean = true,
    val rootSize: IntSize = IntSize.Zero,
    /** Радиус размытия того, что за стеклом (FULL), в пикселях. */
    val blurPx: Float = 48f,
)

val LocalGlass = staticCompositionLocalOf { GlassStyle() }

/**
 * Записанный фон текущего экрана (обои + Top Shelf). Стеклянные панели рисуют его у себя
 * в экранных координатах - так сквозь стекло видно ровно то, что за ним.
 */
val LocalBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/**
 * Уже размытая картинка того, что сейчас за стеклом (обои или баннер приложения в Top Shelf).
 * Динамический local: при смене меняются только стеклянные панели, а не весь экран.
 */
val LocalBackdropImage = compositionLocalOf<ImageBitmap?> { null }

@Composable
fun GlassTheme(style: GlassStyle, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = GlassColors.Accent,
            onPrimary = Color.Black,
            surface = Color(0xFF14181F),
            onSurface = GlassColors.Text,
            background = GlassColors.Scrim,
            onBackground = GlassColors.Text,
        ),
    ) {
        CompositionLocalProvider(
            LocalGlass provides style,
            LocalContentColor provides GlassColors.Text,
            content = content,
        )
    }
}
