package id.dsngroup.ckpreport

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object Share {
    fun teks(ctx: Context, r: Report): String {
        val pt = ctx.getSharedPreferences("ckp", Context.MODE_PRIVATE).getString("pt", Laporan.DEFAULT_PT)
            ?: Laporan.DEFAULT_PT
        return Laporan.pesan(r, pt)
    }

    /** Bagikan foto + teks ke WhatsApp. Tetap bisa dipakai walau belum terupload (tanpa link foto Drive). */
    fun whatsApp(ctx: Context, r: Report) {
        val photo = File(r.photoPath)
        val hasPhoto = photo.exists()
        val uri = if (hasPhoto) FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photo) else null
        fun build(pkg: String?): Intent {
            val i = Intent(Intent.ACTION_SEND)
            if (uri != null) {
                i.type = "image/jpeg"
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                i.type = "text/plain"
            }
            i.putExtra(Intent.EXTRA_TEXT, teks(ctx, r))
            if (pkg != null) i.setPackage(pkg)
            return i
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { ctx.startActivity(build(pkg)); return } catch (e: ActivityNotFoundException) {}
        }
        ctx.startActivity(Intent.createChooser(build(null), "Bagikan laporan"))
    }

    /** Bagikan teks + foto (bila ada) ke WhatsApp. */
    fun textWithPhoto(ctx: Context, teks: String, photo: File?) {
        if (photo == null || !photo.exists()) { text(ctx, teks); return }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photo)
        fun build(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, teks)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { ctx.startActivity(build(pkg)); return } catch (e: ActivityNotFoundException) {}
        }
        ctx.startActivity(Intent.createChooser(build(null), "Bagikan"))
    }

    /** Bagikan teks ke WhatsApp (WA biasa -> WA Business -> pilihan aplikasi). */
    fun text(ctx: Context, teks: String) {
        fun build(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, teks)
            if (pkg != null) setPackage(pkg)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { ctx.startActivity(build(pkg)); return } catch (e: ActivityNotFoundException) {}
        }
        ctx.startActivity(Intent.createChooser(build(null), "Bagikan"))
    }

    /** Bagikan sebuah file (data tracking, gambar) lewat pilihan aplikasi. */
    fun file(ctx: Context, f: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title))
    }
}
