package dev.glasslauncher.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.glasslauncher.tv.data.apps.AppImage
import dev.glasslauncher.tv.data.apps.AppInfo
import dev.glasslauncher.tv.data.tv.TvSource
import dev.glasslauncher.tv.ui.components.CenterKeyState
import dev.glasslauncher.tv.ui.components.handleCenterKey
import dev.glasslauncher.tv.ui.components.requestFocusWhenReady
import dev.glasslauncher.tv.ui.glass.glassFill
import dev.glasslauncher.tv.ui.theme.GlassColors
import dev.glasslauncher.tv.ui.theme.GlassType
import dev.glasslauncher.tv.ui.theme.LocalGlass

val TileShape = RoundedCornerShape(14.dp)
const val LABEL_SPACE_DP = 40

/** Пропорции иконки tvOS (400×240). Баннеры Android 16:9 обрезаются по бокам на ~6%. */
const val TILE_ASPECT = 0.6f

/**
 * Модификатор «элемент главного экрана»: фокус по ключу (см. [HomeFocus]), OK / долгий OK / Menu.
 */
@Composable
private fun Modifier.homeFocusable(
    key: String,
    focus: HomeFocus,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val requester = remember(key) { focus.requester(key) }
    val pending by remember(key) { derivedStateOf { focus.pendingKey == key } }
    LaunchedEffect(pending) {
        if (pending) {
            requester.requestFocusWhenReady(attempts = 3)
            if (focus.pendingKey == key) focus.pendingKey = null
        }
    }
    val keyState = remember { CenterKeyState() }
    return this
        .focusRequester(requester)
        .onFocusChanged {
            onFocus(it.isFocused)
            if (it.isFocused) {
                focus.focusedKey = key
                focus.focusedNow = key
            } else if (focus.focusedNow == key) {
                focus.focusedNow = null
            }
        }
        .onKeyEvent { e ->
            val n = e.nativeKeyEvent
            if (n.keyCode == NativeKeyEvent.KEYCODE_MENU && onLongClick != null) {
                if (n.action == NativeKeyEvent.ACTION_UP) onLongClick()
                true
            } else {
                handleCenterKey(e, keyState, onClick, onLongClick)
            }
        }
        .focusable()
}

/**
 * Плитка в стиле tvOS: при фокусе - spring-увеличение до 1.1x, подъём, мягкая тень,
 * блик, который «пробегает» со стороны, откуда пришёл фокус (parallax), подпись снизу.
 * Все анимируемые значения читаются только в graphicsLayer/draw - без рекомпозиции на каждый кадр.
 */
@Composable
fun FocusTile(
    key: String,
    label: String,
    width: Dp,
    focus: HomeFocus,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
    dimmed: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val animations = LocalGlass.current.animations
    var focused by remember { mutableStateOf(false) }
    val active = focused || moving
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        // Жёсткая пружина: анимация заканчивается за ~8-10 кадров. На слабом GPU телевизора каждый
        // кадр дорогой, поэтому чем меньше кадров анимации, тем меньше рывков.
        animationSpec = if (animations) spring(dampingRatio = 0.78f, stiffness = 600f) else snap(),
        label = "tileFocus",
    )
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        val dir = focus.lastDx
        val target = (dir * 0.35f).coerceIn(-0.35f, 0.35f)
        if (animations) {
            sheen.snapTo(if (dir < 0f) 1.3f else -1.3f)
            sheen.animateTo(target, tween(durationMillis = 380, easing = FastOutSlowInEasing))
        } else {
            sheen.snapTo(target)
        }
    }
    val height = width * TILE_ASPECT
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, height)
                .homeFocusable(key, focus, { focused = it }, onClick, onLongClick)
                .graphicsLayer {
                    val p = progress
                    val s = 1f + (if (moving) 0.12f else 0.10f) * p
                    scaleX = s
                    scaleY = s
                    translationY = -8.dp.toPx() * p
                    shadowElevation = 20.dp.toPx() * p
                    shape = TileShape
                    clip = true
                    alpha = if (dimmed && !active) 0.5f else 1f
                }
                .drawWithContent {
                    drawContent()
                    val p = progress
                    if (p > 0.01f) {
                        val cx = size.width * (0.5f + 0.5f * sheen.value)
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.30f * p), Color.Transparent),
                                center = Offset(cx, -size.height * 0.15f),
                                radius = size.width * 0.75f,
                            ),
                        )
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.12f * p),
                                0.45f to Color.Transparent,
                            ),
                        )
                    }
                }
                .then(if (moving) Modifier.border(3.dp, Color.White, TileShape) else Modifier),
        ) {
            // Отдельный слой для parallax картинки есть только у плитки в фокусе: у остальных 30+ плиток
            // лишний RenderNode каждый кадр только нагружал бы render thread.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (active) {
                            Modifier.graphicsLayer {
                                // Лёгкий parallax самой картинки в сторону блика.
                                val p = progress
                                val s = 1f + 0.04f * p
                                scaleX = s
                                scaleY = s
                                translationX = -sheen.value * 4.dp.toPx() * p
                            }
                        } else {
                            Modifier
                        },
                    ),
                content = content,
            )
        }
        val labelModifier = Modifier
            .padding(top = 14.dp)
            .height((LABEL_SPACE_DP - 14).dp)
        if (active) {
            Text(
                text = if (moving) "Перемещение" else label,
                style = GlassType.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = labelModifier.graphicsLayer { alpha = progress },
            )
        } else {
            Spacer(labelModifier)
        }
    }
}

@Composable
fun AppTile(
    app: AppInfo,
    width: Dp,
    focus: HomeFocus,
    moving: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusTile(
        key = app.key,
        label = app.label,
        width = width,
        focus = focus,
        moving = moving,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        AsyncImage(
            model = app.image(AppImage.Kind.BANNER),
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x22FFFFFF)),
        )
    }
}

@Composable
fun SourceTile(source: TvSource, width: Dp, focus: HomeFocus, onClick: () -> Unit) {
    FocusTile(
        key = source.key,
        label = source.label,
        width = width,
        focus = focus,
        dimmed = !source.connected,
        onClick = onClick,
    ) {
        val (glyph, colors) = when (source.kind) {
            TvSource.Kind.TUNER -> "TV" to listOf(Color(0xFF2B5876), Color(0xFF4E4376))
            TvSource.Kind.HDMI -> "HDMI" to listOf(Color(0xFF1F2A38), Color(0xFF3A4A5F))
            TvSource.Kind.OTHER -> "AV" to listOf(Color(0xFF2E2A38), Color(0xFF4A3F5F))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(glyph, style = GlassType.Title.copy(fontSize = 28.sp))
                Text(
                    source.label,
                    style = GlassType.BodyDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

enum class ActionGlyph { GEAR, TV }

/** Системная плитка в сетке («Настройки» лаунчера, «Настройки TV») - как приложение «Настройки» в tvOS. */
@Composable
fun ActionTile(key: String, label: String, glyph: ActionGlyph, width: Dp, focus: HomeFocus, onClick: () -> Unit) {
    FocusTile(key = key, label = label, width = width, focus = focus, onClick = onClick) {
        val colors = when (glyph) {
            ActionGlyph.GEAR -> listOf(Color(0xFF9A9AA0), Color(0xFF4A4A50))
            ActionGlyph.TV -> listOf(Color(0xFF3B4A63), Color(0xFF1C2433))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize(0.52f)) {
                val c = center
                val r = size.minDimension / 2f
                when (glyph) {
                    ActionGlyph.GEAR -> {
                        repeat(8) { i ->
                            rotate(i * 45f, c) {
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(c.x - r * 0.15f, c.y - r),
                                    size = Size(r * 0.30f, r * 0.45f),
                                    cornerRadius = CornerRadius(r * 0.06f),
                                )
                            }
                        }
                        drawCircle(Color.White, radius = r * 0.74f, center = c)
                        drawCircle(colors.last(), radius = r * 0.32f, center = c)
                    }
                    ActionGlyph.TV -> {
                        val w = r * 1.9f
                        val h = r * 1.2f
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(c.x - w / 2, c.y - h / 2 - r * 0.15f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(r * 0.12f),
                            style = Stroke(width = r * 0.14f),
                        )
                        drawLine(
                            Color.White,
                            Offset(c.x - r * 0.45f, c.y + h / 2 + r * 0.2f),
                            Offset(c.x + r * 0.45f, c.y + h / 2 + r * 0.2f),
                            strokeWidth = r * 0.14f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

/** Пилюля (действия баннеров-предупреждений). Тоже часть сетки фокуса - ряд 0. */
@Composable
fun BarPill(key: String, text: String, focus: HomeFocus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val animations = LocalGlass.current.animations
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = if (animations) spring(dampingRatio = 0.65f, stiffness = 420f) else snap(),
        label = "pill",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .homeFocusable(key, focus, { focused = it }, onClick, null)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glassFill(shape, focused)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(text, style = GlassType.Label.copy(color = if (focused) Color.Black else GlassColors.Text))
    }
}
