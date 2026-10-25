package id.dsngroup.ckpreport

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** ImageView yang bisa dizoom dengan cubit, digeser, dan ketuk dua kali. */
class ZoomImageView(context: Context) : ImageView(context) {
    private val m = Matrix()
    private val v = FloatArray(9)
    private var minScale = 1f
    private var ready = false
    private var lastX = 0f
    private var lastY = 0f
    private var pointerId = -1

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = scale()
            val target = (cur * d.scaleFactor).coerceIn(minScale, minScale * 8f)
            val f = target / cur
            m.postScale(f, f, d.focusX, d.focusY)
            fix(); imageMatrix = m
            return true
        }
    })
    private val tapper = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cur = scale()
            val target = if (cur > minScale * 1.5f) minScale else minScale * 3f
            val f = target / cur
            m.postScale(f, f, e.x, e.y)
            fix(); imageMatrix = m
            return true
        }
        override fun onDown(e: MotionEvent) = true
    })

    init { scaleType = ScaleType.MATRIX }

    private fun scale(): Float { m.getValues(v); return v[Matrix.MSCALE_X] }

    fun setBitmap(b: Bitmap?) { setImageBitmap(b); ready = false; post { reset() } }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); ready = false; reset() }

    private fun reset() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        minScale = min(width / dw, height / dh)
        m.reset()
        m.postScale(minScale, minScale)
        m.postTranslate((width - dw * minScale) / 2f, (height - dh * minScale) / 2f)
        imageMatrix = m
        ready = true
    }

    /** Jaga gambar tetap di dalam layar (atau di tengah bila lebih kecil dari layar). */
    private fun fix() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ready) return true
        scaler.onTouchEvent(e)
        tapper.onTouchEvent(e)
        if (!scaler.isInProgress) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; pointerId = e.getPointerId(0) }
                MotionEvent.ACTION_MOVE -> {
                    val i = e.findPointerIndex(pointerId)
                    if (i >= 0 && e.pointerCount == 1) {
                        if (scale() > minScale * 1.01f) {
                            m.postTranslate(e.getX(i) - lastX, e.getY(i) - lastY)
                            fix(); imageMatrix = m
                        }
                        lastX = e.getX(i); lastY = e.getY(i)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val up = e.actionIndex
                    if (e.getPointerId(up) == pointerId) {
                        val n = if (up == 0) 1 else 0
                        pointerId = e.getPointerId(n); lastX = e.getX(n); lastY = e.getY(n)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerId = -1
            }
        } else {
            lastX = e.x; lastY = e.y
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}

object FotoViewer {
    /** Tampilkan foto layar penuh; cubit untuk zoom, ketuk dua kali untuk memperbesar. */
    fun tampil(ctx: Context, f: File, judul: String = "") {
        if (!f.exists()) return
        val bmp = ImageUtil.thumb(f, 2400) ?: return
        val d = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xFF000000.toInt()) }
        val iv = ZoomImageView(ctx)
        iv.setBitmap(bmp)
        root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dp = ctx.resources.displayMetrics.density
        if (judul.isNotBlank()) root.addView(TextView(ctx).apply {
            text = judul; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            setBackgroundColor(0x88000000.toInt()); setPadding((16 * dp).toInt(), (10 * dp).toInt(), (64 * dp).toInt(), (10 * dp).toInt())
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(TextView(ctx).apply {
            text = "✕"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x88000000.toInt())
            setOnClickListener { d.dismiss() }
        }, FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.TOP or Gravity.END))
        root.addView(TextView(ctx).apply {
            text = "Cubit untuk zoom • ketuk dua kali untuk perbesar"; textSize = 11f
            setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, (12 * dp).toInt())
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        d.setContentView(root)
        d.show()
    }
}
