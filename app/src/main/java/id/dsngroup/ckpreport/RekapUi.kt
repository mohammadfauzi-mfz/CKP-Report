package id.dsngroup.ckpreport

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Dialog "Unduh Rekap Excel" dan "Share Excel" (dipanggil dari menu Pengaturan). */
class RekapUi(
    private val act: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val mintaSimpan: (String) -> Unit,     // buka pemilih lokasi simpan (nama file yang disarankan)
) {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var batal = false
    private var fileSiap: File? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_LONG).show()

    private val fmtId = SimpleDateFormat("dd/MM/yyyy", Locale("in", "ID")).apply { isLenient = false }
    private val fmtIso = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun keIso(s: String): String? = try {
        if (!Regex("""\d{1,2}/\d{1,2}/\d{4}""").matches(s.trim())) null else fmtIso.format(fmtId.parse(s.trim())!!)
    } catch (e: Exception) { null }

    // ------------------------------------------------------------ Unduh

    fun dialogUnduh() {
        val url = prefs.getString("url", "") ?: ""
        if (url.isBlank()) { toast("Isi dan simpan URL Web App di Pengaturan dulu."); return }
        if (!Sync.hasNetwork(act)) { toast("Tidak ada koneksi internet."); return }

        val cal = Calendar.getInstance()
        val sampai = fmtId.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -7)
        val dari = fmtId.format(cal.time)

        val f = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }
        f.addView(TextView(act).apply {
            text = "Mengunduh laporan yang SUDAH terupload ke Spreadsheet, lalu membuatnya menjadi file Excel lengkap dengan foto."
            textSize = 13f; setPadding(0, 0, 0, dp(10))
        })
        val (tDari, eDari) = inputField(act, "Dari tanggal (dd/MM/yyyy)", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, dari)
        val (tSampai, eSampai) = inputField(act, "Sampai tanggal (dd/MM/yyyy)", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, sampai)
        val caps = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        val (tEst, eEst) = inputField(act, "Estate (opsional)", caps, prefs.getString("est", "") ?: "")
        val (tAfd, eAfd) = inputField(act, "Afdeling (opsional)", caps)
        val (tBlok, eBlok) = inputField(act, "Blok (opsional)", caps)
        f.addView(tDari); f.addView(tSampai)
        f.addView(tEst); f.addView(tAfd); f.addView(tBlok)
        val pelapor = prefs.getString("pelapor", "") ?: ""
        val cbSaya = MaterialCheckBox(act).apply {
            text = "Hanya laporan saya (pelapor: $pelapor)"
            visibility = if (pelapor.isBlank()) View.GONE else View.VISIBLE
        }
        f.addView(cbSaya)
        val cbFoto = MaterialCheckBox(act).apply { text = "Sertakan foto di dalam Excel"; isChecked = true }
        f.addView(cbFoto)

        val d = MaterialAlertDialogBuilder(act)
            .setTitle("Unduh Rekap Excel")
            .setView(ScrollView(act).apply { addView(f) })
            .setPositiveButton("Unduh", null)
            .setNegativeButton("Batal", null)
            .create()
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val a = keIso(eDari.text.toString())
            val b = keIso(eSampai.text.toString())
            if (a == null) { tDari.error = "Format dd/MM/yyyy"; return@setOnClickListener }
            if (b == null) { tSampai.error = "Format dd/MM/yyyy"; return@setOnClickListener }
            if (a > b) { tDari.error = "Tanggal awal setelah tanggal akhir"; return@setOnClickListener }
            d.dismiss()
            jalankan(
                Rekap.Filter(a, b, eEst.text.toString().trim(), eAfd.text.toString().trim(), eBlok.text.toString().trim(),
                    if (cbSaya.visibility == View.VISIBLE && cbSaya.isChecked) pelapor else ""),
                cbFoto.isChecked,
            )
        }
    }

    private fun jalankan(filter: Rekap.Filter, foto: Boolean) {
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        batal = false
        val tv = TextView(act).apply { text = "Menyiapkan…"; textSize = 14f; setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val prog = MaterialAlertDialogBuilder(act)
            .setTitle("Rekap Excel")
            .setView(tv)
            .setCancelable(false)
            .setNegativeButton("Batal") { _, _ -> batal = true }
            .show()
        val app = act.applicationContext
        Thread {
            var hasil: Rekap.Hasil? = null
            var err: String? = null
            try {
                hasil = Rekap.buat(app, url, token, filter, foto, { t -> ui.post { tv.text = t } }, { batal })
            } catch (e: Rekap.Gagal) {
                err = e.message
            } catch (e: Throwable) {
                CrashLog.error(app, "Rekap.buat", if (e is Exception) e else RuntimeException(e.toString()))
                err = "Gagal membuat Excel: ${e.message}"
            }
            ui.post {
                prog.dismiss()
                when {
                    err != null -> MaterialAlertDialogBuilder(act).setTitle("Rekap gagal").setMessage(err).setPositiveButton("OK", null).show()
                    hasil == null -> toast("Dibatalkan.")
                    else -> tampilHasil(hasil)
                }
            }
        }.start()
    }

    private fun tampilHasil(h: Rekap.Hasil) {
        fileSiap = h.file
        val kb = h.file.length() / 1024
        MaterialAlertDialogBuilder(act)
            .setTitle("Rekap siap")
            .setMessage(
                "${h.laporan} laporan • ${h.foto} foto • ${if (kb >= 1024) "%.1f MB".format(Locale.US, kb / 1024.0) else "$kb KB"}\n" +
                (if (h.gagalFoto > 0) "${h.gagalFoto} foto tidak berhasil diunduh (kosong di Excel).\n" else "") +
                (if (h.terpotong) "Data melebihi 500 baris; yang diambil 500 laporan terbaru. Persempit rentang tanggal untuk sisanya.\n" else "") +
                "\nFile: ${h.file.name}"
            )
            .setPositiveButton("Share ke WA") { _, _ -> Share.dokumen(act, h.file, Rekap.MIME) }
            .setNeutralButton("Simpan ke HP") { _, _ -> mintaSimpan(h.file.name) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    /** Salin rekap ke lokasi yang dipilih pengguna (Penyimpanan HP / Drive). */
    fun simpan(uri: Uri?) {
        val f = fileSiap ?: Rekap.terakhir(act)
        if (uri == null || f == null) return
        try {
            act.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            toast("Excel disimpan.")
        } catch (e: Exception) {
            toast("Gagal menyimpan: ${e.message}")
        }
    }

    // ------------------------------------------------------------ Share

    fun bagikanTerakhir() {
        val f = Rekap.terakhir(act)
        if (f == null) { toast("Belum ada rekap Excel. Tekan \"Unduh Rekap Excel\" dulu."); return }
        fileSiap = f
        Share.dokumen(act, f, Rekap.MIME)
    }
}
