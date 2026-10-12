package id.dsngroup.ckpreport

import android.graphics.Bitmap
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
}
