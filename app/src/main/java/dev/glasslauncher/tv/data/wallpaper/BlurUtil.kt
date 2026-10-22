package dev.glasslauncher.tv.data.wallpaper

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Дешёвое размытие на CPU: уменьшаем картинку и трижды прогоняем box-blur (≈ гауссиан).
 * Выполняется один раз при смене обоев/для баннера, результат кэшируется. Работает на любом API.
 */
object BlurUtil {

    fun blur(src: Bitmap, scale: Float, radius: Int): Bitmap {
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val r = radius.coerceIn(1, max(1, minOf(w, h) / 2))
        repeat(3) {
            boxHorizontal(pixels, tmp, w, h, r)
            boxVertical(tmp, pixels, w, h, r)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                a += c ushr 24; rr += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xff) - ((out shr 16) and 0xff)
                g += ((inn shr 8) and 0xff) - ((out shr 8) and 0xff)
                b += (inn and 0xff) - (out and 0xff)
            }
        }
    }

    private fun boxVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                a += c ushr 24; rr += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xff) - ((out shr 16) and 0xff)
                g += ((inn shr 8) and 0xff) - ((out shr 8) and 0xff)
                b += (inn and 0xff) - (out and 0xff)
            }
        }
    }
}
