package dev.glasslauncher.tv.data.wallpaper

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Плеер живых обоев на весь процесс, а не на экран. Методы потокобезопасны: подготовку можно
 * начинать из фонового потока при старте процесса, не дожидаясь, пока освободится главный.
 *
 * Раньше MediaPlayer создавался и готовился заново при каждом показе главного экрана - это
 * ~0,8 с до первого кадра. Теперь:
 *  - подготовка начинается сразу при старте процесса (см. AppContainer), параллельно с интерфейсом;
 *  - при уходе из лаунчера плеер ставится на паузу и держится [KEEP_WARM_MS]: быстрый возврат по Home
 *    продолжает ролик мгновенно. Потом плеер освобождается, чтобы аппаратный декодер не мешал
 *    видео в других приложениях;
 *  - поверхность (SurfaceHolder) хранится здесь же и сразу отдаётся новому плееру.
 */
class VideoPlayerHolder {

    private data class Key(val path: String, val stamp: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var key: Key? = null
    private var player: MediaPlayer? = null
    private var surface: SurfaceHolder? = null
    private var wantPlaying = false

    private val _prepared = MutableStateFlow(false)
    val prepared: StateFlow<Boolean> = _prepared

    private val _videoSize = MutableStateFlow(IntSize.Zero)
    val videoSize: StateFlow<IntSize> = _videoSize

    /** Первый кадр уже выведен на текущую поверхность. */
    private val _rendering = MutableStateFlow(false)
    val rendering: StateFlow<Boolean> = _rendering

    private val releaseRunnable = Runnable { release() }

    /** Готовит (или переиспользует уже готовый) плеер для этого ролика. */
    @Synchronized
    fun acquire(file: File, stamp: Long) {
        handler.removeCallbacks(releaseRunnable)
        val k = Key(file.absolutePath, stamp)
        if (k == key && player != null) return
        release()
        key = k
        _prepared.value = false
        _rendering.value = false
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) _videoSize.value = IntSize(w, h) }
                setOnPreparedListener {
                    _prepared.value = true
                    applyPlaying()
                }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) _rendering.value = true
                    false
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "player error $what/$extra")
                    true
                }
                surface?.let { runCatching { setDisplay(it) } }
                prepareAsync()
            }
        }.onFailure { Log.w(TAG, "cannot open $file", it) }.getOrNull()
    }

    @Synchronized
    fun attachSurface(holder: SurfaceHolder?) {
        surface = holder
        runCatching { player?.setDisplay(holder) }
    }

    @Synchronized
    fun setPlaying(playing: Boolean) {
        wantPlaying = playing
        applyPlaying()
    }

    @Synchronized
    private fun applyPlaying() {
        val p = player ?: return
        if (!_prepared.value) return
        runCatching {
            if (wantPlaying && !p.isPlaying) p.start() else if (!wantPlaying && p.isPlaying) p.pause()
        }
    }

    /** Освободить через [delayMs], если за это время плеер снова не понадобится. */
    @Synchronized
    fun releaseLater(delayMs: Long = KEEP_WARM_MS) {
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, delayMs)
    }

    @Synchronized
    fun isReady(file: File, stamp: Long): Boolean = player != null && key == Key(file.absolutePath, stamp)

    @Synchronized
    fun release() {
        handler.removeCallbacks(releaseRunnable)
        player?.let { runCatching { it.release() } }
        player = null
        key = null
        _prepared.value = false
        _rendering.value = false
    }

    companion object {
        private const val TAG = "VideoWallpaper"
        const val KEEP_WARM_MS = 15_000L
    }
}
