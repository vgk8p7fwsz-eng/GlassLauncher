package dev.glasslauncher.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@Immutable
data class VolumeLevel(
    val level: Int = 0,
    val max: Int = 15,
    val muted: Boolean = false,
    val fixed: Boolean = false,
) {
    val fraction: Float get() = if (max <= 0 || muted) 0f else (level.toFloat() / max).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).roundToInt()
}

/**
 * Текущая громкость STREAM_MUSIC. Обновляется по системному VOLUME_CHANGED_ACTION, поэтому
 * видит и изменения, сделанные не нами (приложения, HDMI-CEC, системная панель).
 */
class VolumeMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val am = appContext.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<VolumeLevel> = _state

    init {
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED_ACTION)
            addAction(STREAM_MUTE_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refresh()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    fun refresh(): VolumeLevel {
        val value = read()
        _state.value = value
        return value
    }

    private fun read(): VolumeLevel {
        val am = am ?: return VolumeLevel()
        return runCatching {
            VolumeLevel(
                level = am.getStreamVolume(AudioManager.STREAM_MUSIC),
                max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                muted = am.isStreamMute(AudioManager.STREAM_MUSIC),
                fixed = am.isVolumeFixed,
            )
        }.getOrDefault(VolumeLevel())
    }

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
    }
}
