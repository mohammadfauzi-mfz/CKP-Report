package id.dsngroup.ckpreport

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Kelola pengguna Tim (khusus peran Admin): lihat, tambah, ubah, hapus. Datanya di sheet "Pengguna". */
class PenggunaUi(private val act: Activity) {
    private val ui = Handler(Looper.getMainLooper())
    private val peranList = listOf("Anggota", "Supervisi", "Manager", "Admin")
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

    private var dlg: AlertDialog? = null
    private val list = LinearLayout(act)
    private val tvInfo = TextView(act)

    fun tampil() {
        val u = Tim.userCache(act)
        if (u == null || !u.admin) { toast("Menu ini hanya untuk peran Admin. Simpan NPK Admin di Pengaturan dulu."); return }
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(16), dp(4), dp(16), dp(8))
        tvInfo.textSize = 12f
        tvInfo.setPadding(dp(20), dp(4), dp(20), dp(4))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(MaterialButton(act).apply {
            text = "＋ Tambah Pengguna"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(8), dp(16), 0) }
            setOnClickListener { form(null) }
        })
        col.addView(tvInfo)
        col.addView(list)
        dlg = MaterialAlertDialogBuilder(act).setTitle("Kelola Pengguna Tim")
            .setView(ScrollView(act).apply { addView(col) })
            .setNegativeButton("Tutup", null).show()
        muat()
    }

    private fun muat() {
        tvInfo.text = "Memuat daftar…"
        list.removeAllViews()
        val app = act.applicationContext
        Thread {
            val r = try { Result.success(Tim.daftarPengguna(app)) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                r.onSuccess { rows ->
                    tvInfo.text = "${rows.size} pengguna. Ketuk untuk mengubah atau menghapus."
                    for (p in rows.sortedBy { it.nama.lowercase() }) list.addView(baris(p))
                }.onFailure { tvInfo.text = "Gagal memuat: ${it.message}" }
            }
        }.start()
    }

    private fun baris(p: Tim.Pengguna): View = TextView(act).apply {
        text = p.nama + "  •  NPK " + p.npk + "\n" + p.peran + " • " + p.estate.ifBlank { "-" } + " / " + p.afdeling.ifBlank { "-" }
        textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(0xFFF1F8E9.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        setOnClickListener { form(p) }
    }

    private fun form(lama: Tim.Pengguna?) {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val (tNpk, eNpk) = inputField(act, "NPK", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, lama?.npk ?: "")
        val (tNama, eNama) = inputField(act, "Nama", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, lama?.nama ?: "")
        val (tEst, eEst) = inputField(act, "Estate (beberapa: pisahkan koma, atau SEMUA)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, lama?.estate ?: "")
        val (tAfd, eAfd) = inputField(act, "Afdeling (beberapa: pisahkan koma, atau SEMUA)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, lama?.afdeling ?: "")
        if (lama != null) eNpk.isEnabled = false
        val sp = Spinner(act)
        sp.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, peranList)
        sp.setSelection(peranList.indexOfFirst { it.equals(lama?.peran ?: "Anggota", true) }.coerceAtLeast(0))
        col.addView(tNpk); col.addView(tNama)
        col.addView(TextView(act).apply { text = "Peran"; textSize = 12f; setPadding(dp(4), dp(8), 0, 0) })
        col.addView(sp)
        col.addView(tEst); col.addView(tAfd)
        col.addView(TextView(act).apply {
            text = "Manager dan Admin otomatis melihat semua estate/afdeling."
            textSize = 11f; setPadding(0, dp(4), 0, 0)
        })
        val b = MaterialAlertDialogBuilder(act).setTitle(if (lama == null) "Tambah Pengguna" else "Ubah Pengguna")
            .setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton("Simpan", null).setNegativeButton("Batal", null)
        if (lama != null) b.setNeutralButton("Hapus", null)
        val d = b.create()
        d.show()
        fun kerja(pesan: String, aksi: () -> Unit) {
            val app = act.applicationContext
            toast(pesan)
            Thread {
                val err = try { aksi(); null } catch (e: Exception) { e.message ?: "Gagal" }
                ui.post {
                    if (err != null) toast("Gagal: $err") else { d.dismiss(); toast("Tersimpan"); muat() }
                }
            }.start()
        }
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val npk = eNpk.text.toString().trim().uppercase()
            val nama = eNama.text.toString().trim()
            if (npk.isEmpty()) { tNpk.error = "NPK wajib diisi"; return@setOnClickListener }
            if (nama.isEmpty()) { tNama.error = "Nama wajib diisi"; return@setOnClickListener }
            val p = Tim.Pengguna(npk, nama, peranList[sp.selectedItemPosition], eEst.text.toString().trim(), eAfd.text.toString().trim())
            val app = act.applicationContext
            kerja("Menyimpan…") { Tim.simpanPengguna(app, p) }
        }
        if (lama != null) d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            MaterialAlertDialogBuilder(act).setTitle("Hapus pengguna?")
                .setMessage("${lama.nama} (NPK ${lama.npk}) tidak bisa lagi masuk ke Tim. Temuan yang sudah dibuat tetap ada.")
                .setPositiveButton("Hapus") { _, _ ->
                    val app = act.applicationContext
                    kerja("Menghapus…") { Tim.hapusPengguna(app, lama.npk) }
                }.setNegativeButton("Batal", null).show()
        }
    }
}
