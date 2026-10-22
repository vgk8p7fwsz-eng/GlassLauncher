package dev.glasslauncher.tv.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import dev.glasslauncher.tv.system.VolumeLevel

/**
 * Компактный стеклянный индикатор громкости поверх любых приложений - в правом верхнем углу,
 * как в tvOS. Окно TYPE_ACCESSIBILITY_OVERLAY доступно службе спец. возможностей без разрешения
 * «поверх других окон». Обычный View, без Compose: окно живёт вне Activity.
 */
class VolumeHud(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: HudView? = null
    private var hiding = false

    private val hide = Runnable {
        val v = view ?: return@Runnable
        hiding = true
        v.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(220)
            .setInterpolator(EASE)
            .withEndAction { if (hiding) remove() }
            .start()
    }

    /** false - окно добавить не удалось (тогда вызывающий покажет системный индикатор). */
    fun show(level: VolumeLevel): Boolean {
        val fresh = view == null
        val v = view ?: create() ?: return false
        hiding = false
        v.setLevel(level, animate = !fresh)
        v.animate().cancel()
        if (fresh) {
            v.alpha = 0f
            v.scaleX = 0.92f
            v.scaleY = 0.92f
        }
        if (fresh || v.alpha < 1f || v.scaleX < 1f) {
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(EASE).start()
        }
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, VISIBLE_MS)
        return true
    }

    private fun create(): HudView? = runCatching {
        val density = service.resources.displayMetrics.density
        val v = HudView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (48 * density).toInt()
            y = (40 * density).toInt()
        }
        wm.addView(v, lp)
        view = v
        v
    }.getOrNull()

    private fun remove() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        hiding = false
    }

    fun release() {
        handler.removeCallbacks(hide)
        remove()
    }

    companion object {
        private const val VISIBLE_MS = 1600L
        private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}

/**
 * Капсула 220×44 dp: дымчатое стекло с тонкой светлой кромкой, маленький значок динамика
 * и тонкая дорожка. Заполнение плавно доезжает до нового значения. Без цифр - как в tvOS.
 */
@SuppressLint("ViewConstructor")
private class HudView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val w = (220 * d).toInt()
    private val h = (44 * d).toInt()

    private var level = VolumeLevel()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    private val rect = RectF()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path()

    fun setLevel(value: VolumeLevel, animate: Boolean) {
        level = value
        val target = value.fraction
        animator?.cancel()
        if (!animate) {
            shown = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 200
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(w, h)

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        body.shader = LinearGradient(
            0f, 0f, 0f, nh.toFloat(),
            intArrayOf(0xD9343A46.toInt(), 0xE61B1F27.toInt()), null, Shader.TileMode.CLAMP,
        )
        sheen.shader = LinearGradient(
            0f, 0f, 0f, nh * 0.6f,
            intArrayOf(0x26FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            0f, 0f, 0f, nh.toFloat(),
            intArrayOf(0x8CFFFFFF.toInt(), 0x14FFFFFF, 0x33FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0.5f * d, 0.5f * d, width - 0.5f * d, height - 0.5f * d)
        canvas.drawRoundRect(rect, r, r, body)
        canvas.drawRoundRect(rect, r, r, sheen)
        canvas.drawRoundRect(rect, r, r, rim)

        val cy = height / 2f
        drawSpeaker(canvas, 18 * d, cy)

        val left = 54 * d
        val right = width - 20 * d
        val th = 4 * d
        rect.set(left, cy - th / 2, right, cy + th / 2)
        canvas.drawRoundRect(rect, th / 2, th / 2, track)
        val end = left + (right - left) * shown
        if (end > left + th / 2) {
            rect.set(left, cy - th / 2, end, cy + th / 2)
            canvas.drawRoundRect(rect, th / 2, th / 2, fill)
        }
    }

    private fun drawSpeaker(canvas: Canvas, x0: Float, cy: Float) {
        path.reset()
        path.moveTo(x0, cy - 3.5f * d)
        path.lineTo(x0 + 4f * d, cy - 3.5f * d)
        path.lineTo(x0 + 9.5f * d, cy - 8f * d)
        path.lineTo(x0 + 9.5f * d, cy + 8f * d)
        path.lineTo(x0 + 4f * d, cy + 3.5f * d)
        path.lineTo(x0, cy + 3.5f * d)
        path.close()
        canvas.drawPath(path, icon)
        val wx = x0 + 12 * d
        if (level.muted || level.level == 0) {
            canvas.drawLine(wx + 1 * d, cy - 4 * d, wx + 9 * d, cy + 4 * d, wave)
            canvas.drawLine(wx + 1 * d, cy + 4 * d, wx + 9 * d, cy - 4 * d, wave)
            return
        }
        val waves = when {
            level.fraction < 0.34f -> 1
            level.fraction < 0.67f -> 2
            else -> 3
        }
        for (i in 1..waves) {
            val rr = (1.5f + i * 3.5f) * d
            rect.set(wx - rr, cy - rr, wx + rr, cy + rr)
            canvas.drawArc(rect, -45f, 90f, false, wave)
        }
    }
}
