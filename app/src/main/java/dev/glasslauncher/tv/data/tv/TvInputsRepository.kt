package dev.glasslauncher.tv.data.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Immutable
data class TvSource(
    val inputId: String,
    val label: String,
    val kind: Kind,
    val connected: Boolean,
) {
    enum class Kind { TUNER, HDMI, OTHER }

    val key: String get() = "src:$inputId"
}

/**
 * Ряд «Источники»: HDMI-входы и эфирный тюнер через TV Input Framework.
 * Без этого после отключения стокового лаунчера на телевизоре пропадает переключение входов.
 */
class TvInputsRepository(context: Context) {

    private val appContext = context.applicationContext

    fun observe(): Flow<List<TvSource>> = callbackFlow {
        val tim = runCatching { appContext.getSystemService(TvInputManager::class.java) }.getOrNull()
        if (tim == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        fun emit() {
            val list = runCatching { load(tim) }.onFailure { Log.w(TAG, "load inputs failed", it) }.getOrDefault(emptyList())
            Log.i(TAG, "tv inputs: ${list.size}")
            trySend(list)
        }
        val callback = object : TvInputManager.TvInputCallback() {
            override fun onInputAdded(inputId: String) = emit()
            override fun onInputRemoved(inputId: String) = emit()
            override fun onInputUpdated(inputId: String) = emit()
            override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = emit()
            override fun onInputStateChanged(inputId: String, state: Int) = emit()
        }
        runCatching { tim.registerCallback(callback, Handler(Looper.getMainLooper())) }
        emit()
        awaitClose { runCatching { tim.unregisterCallback(callback) } }
    }.distinctUntilChanged()

    private fun load(tim: TvInputManager): List<TvSource> {
        return tim.tvInputList
            .asSequence()
            .filter { it.parentId == null } // HDMI-CEC устройства за входом - не отдельные источники
            .filter { !runCatching { it.isHidden(appContext) }.getOrDefault(false) }
            .mapNotNull { info ->
                val kind = when {
                    info.type == TvInputInfo.TYPE_TUNER -> TvSource.Kind.TUNER
                    info.type == TvInputInfo.TYPE_HDMI -> TvSource.Kind.HDMI
                    info.isPassthroughInput -> TvSource.Kind.OTHER
                    else -> return@mapNotNull null // сторонние «каналы» приложений - не входы
                }
                val label = runCatching { info.loadCustomLabel(appContext)?.toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { info.loadLabel(appContext).toString() }.getOrDefault(info.id)
                val state = runCatching { tim.getInputState(info.id) }
                    .getOrDefault(TvInputManager.INPUT_STATE_CONNECTED)
                TvSource(
                    inputId = info.id,
                    label = label,
                    kind = kind,
                    connected = state != TvInputManager.INPUT_STATE_DISCONNECTED,
                )
            }
            .sortedWith(compareBy<TvSource> { it.kind.ordinal }.thenBy { it.label })
            .toList()
    }

    /**
     * HDMI: стандартный passthrough-URI, его открывает системное ТВ-приложение.
     * Тюнер: URI каналов входа. Если на прошивке нет обработчика - пробуем известные ТВ-приложения.
     */
    fun launch(source: TvSource): Boolean {
        val uri = if (source.kind == TvSource.Kind.TUNER) {
            TvContract.buildChannelsUriForInput(source.inputId)
        } else {
            TvContract.buildChannelUriForPassthroughInput(source.inputId)
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No handler for $uri", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open $uri", e)
        }
        val pm = appContext.packageManager
        for (pkg in LIVE_TV_PACKAGES) {
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
            if (runCatching { appContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "TvInputs"
        private val LIVE_TV_PACKAGES = listOf(
            "com.google.android.tv",
            "com.mitv.tvplayer",
            "com.xiaomi.mitv.tvplayer",
            "com.android.tv",
            "com.droidlogic.tvsource",
            "com.mediatek.wwtv.tvcenter",
        )
    }
}
