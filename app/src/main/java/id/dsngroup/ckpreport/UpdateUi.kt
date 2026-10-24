package id.dsngroup.ckpreport

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Dialog cek, unduh, dan pasang pembaruan dari GitHub. */
class UpdateUi(private val act: AppCompatActivity, private val prefs: SharedPreferences) {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var batal = false
    private var apkSiap: File? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_LONG).show()

    /** [diam] = true: dari pengecekan otomatis saat aplikasi dibuka (tidak menampilkan apa pun bila tidak ada update/gagal). */
    fun cek(diam: Boolean) {
        if (!Sync.hasNetwork(act)) { if (!diam) toast("Tidak ada koneksi internet."); return }
        if (!diam) toast("Memeriksa pembaruan…")
        Thread {
            var info: Updater.Info? = null
            var err: String? = null
            try { info = Updater.terbaru() } catch (e: Updater.Gagal) { err = e.message } catch (e: Throwable) { err = e.message }
            ui.post {
                val sekarang = Updater.versiTerpasang(act)
                when {
                    err != null -> if (!diam) MaterialAlertDialogBuilder(act).setTitle("Cek pembaruan gagal").setMessage(err).setPositiveButton("OK", null).show()
                    info == null -> if (!diam) MaterialAlertDialogBuilder(act).setTitle("Belum ada rilis")
                        .setMessage("Belum ada versi yang dipublikasikan di GitHub. Versi terpasang: $sekarang").setPositiveButton("OK", null).show()
                    !Updater.lebihBaru(info.versi, sekarang) -> if (!diam) MaterialAlertDialogBuilder(act).setTitle("Sudah terbaru")
                        .setMessage("Versi terpasang: $sekarang\nVersi terbaru di GitHub: ${info.versi.removePrefix("v")}").setPositiveButton("OK", null).show()
                    else -> tawarkan(info, sekarang)
                }
            }
        }.start()
    }

    /** Pengecekan otomatis paling sering sekali sehari. */
    fun cekOtomatis() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("upd_last", 0L) < 24L * 3600 * 1000) return
        prefs.edit().putLong("upd_last", now).apply()
        cek(true)
    }

    private fun tawarkan(info: Updater.Info, sekarang: String) {
        val mb = if (info.ukuran > 0) " (%.1f MB)".format(java.util.Locale.US, info.ukuran / 1048576.0) else ""
        MaterialAlertDialogBuilder(act)
            .setTitle("Versi baru tersedia")
            .setMessage(
                "Terpasang: $sekarang\nTerbaru: ${info.versi.removePrefix("v")}$mb\n\n" +
                (if (info.catatan.isNotBlank()) info.catatan.take(600) + "\n\n" else "") +
                "Data aplikasi (laporan, tanda, tracking, peta) tidak terhapus saat memperbarui."
            )
            .setPositiveButton("Perbarui sekarang") { _, _ -> unduh(info) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun unduh(info: Updater.Info) {
        batal = false
        val tv = TextView(act).apply { text = "Mengunduh… 0%"; textSize = 15f; setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val dlg = MaterialAlertDialogBuilder(act).setTitle("Mengunduh pembaruan").setView(tv)
            .setCancelable(false).setNegativeButton("Batal") { _, _ -> batal = true }.show()
        Thread {
            var f: File? = null
            var err: String? = null
            try {
                f = Updater.unduh(act, info, { p -> ui.post { tv.text = if (p >= 0) "Mengunduh… $p%" else "Mengunduh…" } }, { batal })
            } catch (e: Updater.Gagal) { err = e.message } catch (e: Throwable) { err = e.message }
            ui.post {
                dlg.dismiss()
                when {
                    err != null -> MaterialAlertDialogBuilder(act).setTitle("Pembaruan gagal").setMessage(err).setPositiveButton("OK", null).show()
                    f == null -> toast("Dibatalkan.")
                    else -> { apkSiap = f; pasang(f) }
                }
            }
        }.start()
    }

    private fun pasang(f: File) {
        try {
            if (!Updater.pasang(act, f)) {
                MaterialAlertDialogBuilder(act).setTitle("Izinkan pemasangan")
                    .setMessage("Aktifkan \"Izinkan dari sumber ini\" untuk CKP Report di layar yang terbuka, lalu kembali ke aplikasi dan tekan Pasang.")
                    .setPositiveButton("Pasang") { _, _ -> pasang(f) }
                    .setNegativeButton("Batal", null).show()
            }
        } catch (e: Exception) {
            toast("Tidak dapat memulai pemasangan: ${e.message}")
        }
    }
}
