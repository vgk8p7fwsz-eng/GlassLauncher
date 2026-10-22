package dev.glasslauncher.tv.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.glasslauncher.tv.data.settings.GlassQuality

object DeviceProfile {

    data class Info(
        val totalRamMb: Long,
        val lowRamFlag: Boolean,
        val sdk: Int,
        val isLowEnd: Boolean,
        val model: String,
    )

    /**
     * «Слабое» устройство: isLowRamDevice, либо меньше ~3 ГБ ОЗУ, либо Android < 12.
     * totalMem у «3 ГБ» устройств показывает ~2.7-2.9 ГБ (часть съедает ядро/видео),
     * поэтому порог 2560 МБ: 2 ГБ телевизоры (~1.8-1.9 ГБ) уходят в облегчённый режим, 3 ГБ - нет.
     */
    fun detect(context: Context): Info {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(mi) }
        val totalMb = mi.totalMem / (1024L * 1024L)
        val lowFlag = am?.isLowRamDevice == true
        val lowEnd = lowFlag || (totalMb in 1L until 2560L) || Build.VERSION.SDK_INT < 31
        return Info(totalMb, lowFlag, Build.VERSION.SDK_INT, lowEnd, "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    /**
     * Что реально рисуем. FULL требует Android 12+ (RenderEffect). Размытие и линза считаются на GPU
     * и только когда меняется фон, поэтому «Авто» включает FULL и на 2 ГБ телевизорах - кроме
     * устройств с флагом isLowRamDevice (Android Go, совсем слабые приставки).
     */
    fun effectiveQuality(setting: GlassQuality, info: Info): GlassQuality = when {
        Build.VERSION.SDK_INT < 31 -> GlassQuality.LIGHT
        setting == GlassQuality.AUTO -> if (info.lowRamFlag) GlassQuality.LIGHT else GlassQuality.FULL
        else -> setting
    }
}
