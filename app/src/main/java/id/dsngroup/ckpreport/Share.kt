package id.dsngroup.ckpreport

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object Share {
    fun teks(ctx: Context, r: Report): String {
        val url = ctx.getSharedPreferences("ckp", Context.MODE_PRIVATE).getString("url", "") ?: ""
        return Laporan.pesan(
            r.est, r.afd, r.blok, r.problem, r.lat, r.lon, r.fotoUrl, r.pelapor, r.waktuText,
            Laporan.petaUrl(url, r.lat, r.lon),
        )
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
}
