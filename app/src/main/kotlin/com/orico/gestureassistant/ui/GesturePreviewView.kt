package com.orico.gestureassistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.orico.gestureassistant.R
import com.orico.gestureassistant.recognizer.trajectory.ImuPoint

/**
 * 手势缩略图。两种模式：
 * - PATH：把重建的 2D 轨迹(最显著投影平面)画成折线，直观看形状；
 * - WAVE：三轴世界系加速度波形，无漂移、可靠(备用)。
 */
class GesturePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.FILL
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private fun wavePaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val wavePaints by lazy {
        listOf(
            wavePaint(ContextCompat.getColor(context, R.color.primary)),
            wavePaint(ContextCompat.getColor(context, R.color.accent)),
            wavePaint(Color.parseColor("#FF922B")),
        )
    }

    private enum class Mode { PATH, WAVE }
    private var mode = Mode.PATH
    private var points: List<Pair<Float, Float>> = emptyList()
    private var channels: List<FloatArray> = emptyList()

    /** 形状模式：传入重建好的归一化 2D 轨迹。 */
    fun setPath(points: List<Pair<Float, Float>>) {
        mode = Mode.PATH
        this.points = try {
            points.filter { (x, y) -> x.isFinite() && y.isFinite() }
                .map { (x, y) -> x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f) }
        } catch (_: Exception) {
            emptyList()
        }
        invalidate()
    }

    /** 波形模式：传入原始模板，取前三维(世界系 E/N/U)画三轴波形。 */
    fun setWaveform(pts: List<ImuPoint>) {
        mode = Mode.WAVE
        channels = try {
            if (pts.size < 2) emptyList()
            else List(3) { axis -> FloatArray(pts.size) { i -> pts[i][axis].toFloat().let { if (it.isFinite()) it else 0f } } }
        } catch (_: Exception) {
            emptyList()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            val inset = 9f * density
            val w = (width - inset * 2f).coerceAtLeast(0f)
            val h = (height - inset * 2f).coerceAtLeast(0f)
            if (w <= 0f || h <= 0f) return
            if (mode == Mode.WAVE) drawWave(canvas, inset, w, h) else drawPath(canvas, inset, w, h)
        } catch (_: Exception) {
            // 坏模板只影响自己的缩略图，绝不让整个管理页崩溃。
        }
    }

    private fun drawPath(canvas: Canvas, inset: Float, w: Float, h: Float) {
        if (points.isEmpty()) {
            val cy = height / 2f
            canvas.drawLine(width * 0.34f, cy, width * 0.66f, cy, placeholderPaint)
            return
        }
        fun map(p: Pair<Float, Float>) = inset + p.first * w to inset + (1f - p.second) * h
        val first = map(points.first())
        val path = Path().apply { moveTo(first.first, first.second) }
        points.drop(1).forEach { p -> val m = map(p); path.lineTo(m.first, m.second) }
        canvas.drawPath(path, strokePaint)
        canvas.drawCircle(first.first, first.second, 4f * density, startPaint)
    }

    private fun drawWave(canvas: Canvas, inset: Float, w: Float, h: Float) {
        val n = channels.firstOrNull()?.size ?: 0
        if (n < 2) return
        var maxAbs = 0.01f
        channels.forEach { c -> c.forEach { v -> val a = kotlin.math.abs(v); if (a > maxAbs) maxAbs = a } }
        channels.forEachIndexed { axis, series ->
            val path = Path()
            for (i in series.indices) {
                val x = inset + w * (i.toFloat() / (n - 1))
                val y = height / 2f - (series[i] / maxAbs).coerceIn(-1f, 1f) * (h / 2f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, wavePaints[axis])
        }
    }
}
