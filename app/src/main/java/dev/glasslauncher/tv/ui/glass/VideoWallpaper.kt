package dev.glasslauncher.tv.ui.glass

import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glasslauncher.tv.GlassLauncherApp
import kotlinx.coroutines.delay
import java.io.File

/** Новой поверхности хватает одного-двух кадров ролика, чтобы перестать быть пустой. */
private const val SURFACE_WARMUP_MS = 120L

/**
 * Живые обои: ролик без звука по кругу.
 *
 * Системный MediaPlayer + SurfaceView: видео декодируется аппаратно и выводится отдельным слоем
 * композитора, почти не нагружая GPU. Плеер живёт в [dev.glasslauncher.tv.data.wallpaper.VideoPlayerHolder]
 * на весь процесс: готовится при старте лаунчера и переживает уход в другое приложение, поэтому при
 * возврате по Home ролик продолжается сразу. Масштаб - «заполнить с обрезкой».
 *
 * [playing] = false ставит ролик на паузу (например, когда фон закрыт сеткой приложений),
 * а при уходе из лаунчера (onStop) пауза ставится автоматически.
 */
@Composable
fun VideoWallpaper(
    video: File,
    stamp: Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
    /** true - на экране уже живые кадры; до этого вызывающий закрывает окно неподвижным кадром. */
    onShowingChange: (Boolean) -> Unit = {},
) {
    val playerHolder = (LocalContext.current.applicationContext as GlassLauncherApp).container.videoPlayer
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // На экране: держим плеер готовым. Ушли из лаунчера или экрана - пауза и освобождение позже.
    LaunchedEffect(video, stamp, started) {
        if (started) playerHolder.acquire(video, stamp) else playerHolder.releaseLater()
    }
    DisposableEffect(video, stamp) {
        onDispose { playerHolder.releaseLater() }
    }

    val prepared by playerHolder.prepared.collectAsStateWithLifecycle()
    val rendering by playerHolder.rendering.collectAsStateWithLifecycle()
    val videoSize by playerHolder.videoSize.collectAsStateWithLifecycle()

    LaunchedEffect(prepared, playing, started) {
        playerHolder.setPlaying(prepared && playing && started)
    }

    // Счётчик новых поверхностей: пока свежая поверхность не получила кадр, вызывающий
    // показывает неподвижный кадр. С Android 14 поверхность переживает уход из лаунчера,
    // и при возврате ждать вообще не нужно.
    var surfaceGeneration by remember { mutableIntStateOf(0) }
    var surfaceWarm by remember(video, stamp) { mutableStateOf(false) }
    LaunchedEffect(started, rendering, surfaceGeneration) {
        if (!(started && rendering && surfaceGeneration > 0)) {
            surfaceWarm = false
            return@LaunchedEffect
        }
        delay(SURFACE_WARMUP_MS)
        surfaceWarm = true
    }
    LaunchedEffect(surfaceWarm) { onShowingChange(surfaceWarm) }

    BoxWithConstraints(modifier.clipToBounds()) {
        val boxW = maxWidth
        val boxH = maxHeight
        // «Cover»: растягиваем по большей стороне, сохраняя пропорции ролика.
        val (w, h) = if (videoSize == IntSize.Zero) {
            boxW to boxH
        } else {
            val scale = maxOf(boxW.value / videoSize.width, boxH.value / videoSize.height)
            (videoSize.width * scale).dp to (videoSize.height * scale).dp
        }
        Box(Modifier.requiredSize(w, h).align(Alignment.Center)) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        if (Build.VERSION.SDK_INT >= 34) {
                            setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
                        }
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(sh: SurfaceHolder) {
                                playerHolder.attachSurface(sh)
                                surfaceGeneration++
                            }

                            override fun surfaceChanged(sh: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                            override fun surfaceDestroyed(sh: SurfaceHolder) {
                                playerHolder.attachSurface(null)
                            }
                        })
                    }
                },
                modifier = Modifier.requiredSize(w, h),
            )
        }
    }
}
