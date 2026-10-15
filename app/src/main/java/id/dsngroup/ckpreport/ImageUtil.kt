package id.dsngroup.ckpreport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtil {
    /** Kecilkan (sisi terpanjang maxSide px), putar sesuai EXIF, simpan JPEG kualitas 80. */
    fun prepare(open: () -> InputStream?, out: File, maxSide: Int = 1280): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

            val orientation = try {
                open()?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            }
            val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
            m.postScale(scale, scale)

            val result = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            FileOutputStream(out).use { result.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    const val MAX_PHOTOS = 6

    /** Gambar kecil untuk pratinjau. */
    fun thumb(f: File, size: Int = 200): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= size) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    /**
     * 1 foto -> disalin apa adanya. 2+ foto -> dijadikan kolase (maks. 6):
     * 2: berdampingan, 3: 1 besar di atas + 2, 4: 2x2, 5: 2 + 3, 6: 3 + 3.
     */
    fun collage(srcs: List<File>, out: File, width: Int = 1280, extra: File? = null): Boolean {
        if (srcs.isEmpty()) return false
        return try {
            if (srcs.size == 1 && extra == null) {
                srcs[0].copyTo(out, overwrite = true)
                return true
            }
            val list = srcs.take(MAX_PHOTOS)
            val layout = when (list.size) {
                1 -> listOf(1)
                2 -> listOf(2)
                3 -> listOf(1, 2)
                4 -> listOf(2, 2)
                5 -> listOf(2, 3)
                else -> listOf(3, 3)
            }
            val rowH = if (layout.max() == 3) 480 else 640
            val gap = 6
            // Baris terakhir (lebar penuh): tangkapan area tracking
            val extraH = if (extra != null) 600 else 0
            val rows = layout.size + (if (extra != null) 1 else 0)
            val totalH = rowH * layout.size + extraH + gap * (rows - 1)
            val bmp = Bitmap.createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var idx = 0
            for ((r, cols) in layout.withIndex()) {
                val cellW = (width - gap * (cols - 1)) / cols
                for (c in 0 until cols) {
                    val left = c * (cellW + gap)
                    val top = r * (rowH + gap)
                    drawCover(canvas, list[idx++], Rect(left, top, left + cellW, top + rowH), paint)
                }
            }
            if (extra != null) {
                val top = layout.size * (rowH + gap)
                drawCover(canvas, extra, Rect(0, top, width, top + extraH), paint)
            }
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    /**
     * Tulis watermark satu baris (kiri ke kanan) di tepi paling bawah foto, memanjang selama lebar foto.
     * Ukuran huruf otomatis dikecilkan agar seluruh teks muat dalam satu baris.
     */
    fun watermark(f: File, text: String): Boolean {
        return try {
            val src = BitmapFactory.decodeFile(f.path) ?: return false
            val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            val pad = w * 0.015f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = w * 0.032f
            }
            val avail = w - 2 * pad
            val tw = paint.measureText(text)
            if (tw > avail) paint.textSize = paint.textSize * avail / tw
            val fm = paint.fontMetrics
            val textH = fm.descent - fm.ascent
            val stripH = textH + pad
            val canvas = Canvas(bmp)
            val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
            canvas.drawRect(0f, h - stripH, w, h, bg)
            canvas.drawText(text, pad, h - pad / 2f - fm.descent, paint)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    /** Gambar foto memenuhi kotak tujuan (dipotong di tengah, tanpa distorsi). */
    private fun drawCover(canvas: Canvas, f: File, dst: Rect, paint: Paint) {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return
        var sample = 1
        while (b.outWidth / (sample * 2) >= dst.width() && b.outHeight / (sample * 2) >= dst.height()) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val scale = max(dst.width().toFloat() / bmp.width, dst.height().toFloat() / bmp.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val sl = (bmp.width - sw) / 2f
        val st = (bmp.height - sh) / 2f
        val src = Rect(sl.toInt(), st.toInt(), (sl + sw).toInt().coerceAtMost(bmp.width), (st + sh).toInt().coerceAtMost(bmp.height))
        canvas.drawBitmap(bmp, src, dst, paint)
        bmp.recycle()
    }
}
