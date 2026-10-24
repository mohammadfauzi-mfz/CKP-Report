package id.dsngroup.ckpreport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Pembaruan aplikasi langsung dari GitHub Releases (APK dibuat otomatis oleh GitHub Actions). */
object Updater {
    const val REPO = "mohammadfauzi-mfz/CKP-Report"

    class Info(val versi: String, val catatan: String, val apkUrl: String, val ukuran: Long)

    class Gagal(msg: String) : Exception(msg)

    fun versiTerpasang(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    /** true bila [baru] lebih tinggi dari [lama] (membandingkan angka per ruas: 2.10 > 2.9). */
    fun lebihBaru(baru: String, lama: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").removePrefix("V").split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(baru); val b = parts(lama)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String, readMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = readMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CKPReport-Updater")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    /** Ambil rilis terbaru. null = belum ada rilis. Panggil dari thread latar. */
    fun terbaru(): Info? {
        var c: HttpURLConnection? = null
        try {
            c = open("https://api.github.com/repos/$REPO/releases/latest", 30000)
            val code = c.responseCode
            if (code == 404) return null
            if (code == 403) throw Gagal("GitHub membatasi permintaan sementara. Coba lagi beberapa menit lagi.")
            if (code !in 200..299) throw Gagal("GitHub menjawab kode $code. Pastikan repository bersifat publik.")
            val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = js.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) {
                    return Info(js.optString("tag_name"), js.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        } catch (e: Gagal) {
            throw e
        } catch (e: Exception) {
            throw Gagal("Tidak ada koneksi / sinyal lemah")
        } finally {
            c?.disconnect()
        }
    }

    fun folder(ctx: Context) = File(ctx.cacheDir, "update").apply { mkdirs() }

    /** Unduh APK. [progres] menerima persen (0-100, -1 bila ukuran tidak diketahui). */
    fun unduh(ctx: Context, info: Info, progres: (Int) -> Unit, batal: () -> Boolean): File? {
        folder(ctx).listFiles()?.forEach { it.delete() }
        val out = File(folder(ctx), "CKPReport-${info.versi.removePrefix("v")}.apk")
        var c: HttpURLConnection? = null
        try {
            c = open(info.apkUrl, 60000)
            if (c.responseCode !in 200..299) throw Gagal("Gagal mengunduh (kode ${c.responseCode}).")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.ukuran
            var got = 0L
            var last = -2
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (batal()) { out.delete(); return null }
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        got += n
                        val p = if (total > 0) (got * 100 / total).toInt().coerceAtMost(100) else -1
                        if (p != last) { last = p; progres(p) }
                    }
                }
            }
            if (total > 0 && got < total) { out.delete(); throw Gagal("Unduhan terputus. Coba lagi.") }
            return out
        } catch (e: Gagal) {
            out.delete(); throw e
        } catch (e: Exception) {
            out.delete(); throw Gagal("Unduhan gagal: tidak ada koneksi / sinyal lemah")
        } finally {
            c?.disconnect()
        }
    }

    /** Mengembalikan false bila izin "pasang dari sumber ini" belum diberikan (layar pengaturannya dibuka). */
    fun pasang(ctx: Context, apk: File): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }
}
