package id.dsngroup.ckpreport

import android.content.Context
import android.graphics.Typeface
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Yang dibutuhkan tab Tim dari tab Peta. */
interface TimEnv {
    fun lokasi(): Location?
    fun keBpeta(lat: Double, lon: Double)
    fun navigasi(lat: Double, lon: Double)
    fun pickPhoto(camera: Boolean, onResult: (File?) -> Unit)
    fun temuanBaru()
    fun petaBerubah()
}

/** Tab "Tim": temuan bersama satu afdeling; semua bisa melapor dan menuntaskan. */
class TimView(context: Context, private val env: TimEnv) : LinearLayout(context) {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ckp", Context.MODE_PRIVATE)
    private val tvStatus = TextView(context)
    private val tvRingkas = TextView(context)
    private val list = LinearLayout(context)
    private lateinit var etCari: EditText
    private lateinit var btnSync: MaterialButton
    private lateinit var btnAfd: MaterialButton
    private val btnFilter = ArrayList<Pair<String, MaterialButton>>()
    private var filterStatus = "SEMUA"
    private var filterAfd = ""
    private var cari = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    private fun btn(label: String, onClick: () -> Unit) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label; textSize = 12f; isAllCaps = false
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        setOnClickListener { onClick() }
    }

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(2)) }
        btnSync = MaterialButton(context).apply {
            text = "⟳ Sinkron"; textSize = 12f
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) }
            setOnClickListener { sinkron(false) }
        }
        top.addView(btnSync)
        top.addView(MaterialButton(context).apply {
            text = "＋ Temuan Baru"; textSize = 12f
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) }
            setOnClickListener { env.temuanBaru() }
        })
        addView(top)

        tvStatus.textSize = 12f
        tvStatus.setTextColor(0xFF555555.toInt())
        tvStatus.setPadding(dp(16), dp(2), dp(16), 0)
        addView(tvStatus)
        tvRingkas.textSize = 14f
        tvRingkas.setTypeface(null, Typeface.BOLD)
        tvRingkas.setTextColor(0xFF1B5E20.toInt())
        tvRingkas.setPadding(dp(16), dp(2), dp(16), dp(2))
        addView(tvRingkas)

        val fl = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        for ((k, label) in listOf("SEMUA" to "Semua", Tim.OUTSTANDING to "🔴 Outstanding", Tim.TUNTAS to "🟢 Tuntas")) {
            val b = btn(label) { filterStatus = k; paintFilter(); tampil() }
            btnFilter.add(Pair(k, b)); fl.addView(b)
        }
        addView(fl)
        btnAfd = btn("Semua afdeling") { pilihAfd() }
        val fl2 = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        fl2.addView(btnAfd)
        etCari = EditText(context).apply {
            hint = "Cari blok / judul / pelapor"; textSize = 13f; inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { cari = s.toString().trim(); tampil() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        fl2.addView(etCari)
        addView(fl2)
        paintFilter()

        list.orientation = VERTICAL
        list.setPadding(dp(12), dp(6), dp(12), dp(32))
        addView(ScrollView(context).apply {
            addView(list)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
    }

    private fun paintFilter() {
        for ((k, b) in btnFilter) b.setBackgroundColor(if (k == filterStatus) 0xFFC8E6C9.toInt() else 0x00000000)
    }

    // ------------------------------------------------------------ sinkron

    fun onShown() {
        tampil()
        if (Tim.aktif(context)) {
            val last = prefs.getLong("tm_last", 0L)
            if (System.currentTimeMillis() - last > 60_000L) sinkron(true)
        }
    }

    /** [diam] = true: tanpa pesan bila tidak ada apa-apa. */
    fun sinkron(diam: Boolean) {
        if (!Tim.aktif(context)) {
            if (!diam) toast("Isi NPK dan URL Web App di Pengaturan dulu, lalu tekan Sinkron.")
            return
        }
        if (TimSync.sedangJalan()) return
        btnSync.isEnabled = false
        btnSync.text = "Menyinkronkan…"
        val app = context.applicationContext
        Thread {
            val h = try { TimSync.jalankan(app) } catch (e: Throwable) {
                CrashLog.error(app, "TimSync", if (e is Exception) e else RuntimeException(e.toString()))
                TimSync.Hasil(0, 0, 0, "Gagal sinkron: ${e.message}")
            }
            ui.post {
                btnSync.isEnabled = true
                btnSync.text = "⟳ Sinkron"
                tampil()
                env.petaBerubah()
                val msg = when {
                    h.pesan != null -> h.pesan
                    h.gagal > 0 -> "${h.gagal} perubahan ditolak server (lihat tanda ⚠ pada temuan)."
                    h.baru > 0 -> "${h.baru} temuan baru dari tim."
                    h.terkirim > 0 -> "${h.terkirim} perubahan terkirim."
                    !diam -> "Sudah yang terbaru."
                    else -> null
                }
                if (msg != null && (!diam || h.baru > 0 || h.gagal > 0)) toast(msg)
            }
        }.start()
    }

    // ------------------------------------------------------------ daftar

    private fun saya(t: Temuan): Boolean = norm(t.pNpk) == norm(Tim.npk(context))
    private fun norm(s: String) = s.trim().uppercase().trimStart('0')

    private fun pilihAfd() {
        val semua = TemuanStore.all(context).filter { it.sync != "D" }
        val afds = semua.map { it.est + " / " + it.afd }.distinct().sorted()
        if (afds.size <= 1) { toast("Hanya ada satu afdeling."); return }
        val items = (listOf("Semua afdeling") + afds).toTypedArray()
        MaterialAlertDialogBuilder(context).setTitle("Filter afdeling")
            .setItems(items) { _, i -> filterAfd = if (i == 0) "" else items[i]; tampil() }
            .setNegativeButton("Batal", null).show()
    }

    fun tampil() {
        val u = Tim.userCache(context)
        val last = prefs.getLong("tm_last", 0L)
        val tunda = TemuanStore.pendingCount(context)
        tvStatus.text = when {
            Tim.npk(context).isEmpty() -> "Belum login. Isi NPK di Pengaturan."
            else -> (if (u != null) "${u.nama} (${u.peran}) • NPK ${u.npk}" else "NPK ${Tim.npk(context)} (belum diverifikasi)") +
                (if (last > 0) " • sinkron ${SimpleDateFormat("dd/MM HH:mm", Locale("in", "ID")).format(Date(last))}" else " • belum pernah sinkron") +
                (if (tunda > 0) " • ⏳ $tunda menunggu kirim" else "")
        }
        val semua = TemuanStore.all(context).filter { it.sync != "D" }
        btnAfd.text = if (filterAfd.isEmpty()) "Semua afdeling" else filterAfd
        val q = cari.lowercase()
        val tampilList = semua.filter { t ->
            (filterStatus == "SEMUA" || t.status == filterStatus) &&
            (filterAfd.isEmpty() || (t.est + " / " + t.afd) == filterAfd) &&
            (q.isEmpty() || listOf(t.blok, t.judul, t.pNama, t.ket, t.afd).any { it.lowercase().contains(q) })
        }
        val out = semua.count { !it.tuntas }
        tvRingkas.text = "$out outstanding • ${semua.size - out} tuntas • ditampilkan ${tampilList.size}"
        list.removeAllViews()
        if (tampilList.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (semua.isEmpty()) "Belum ada temuan tim.\nTekan \"＋ Temuan Baru\" atau tandai di peta dengan centang \"Kirim ke Tim\"."
                else "Tidak ada temuan yang cocok dengan filter."
                textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)
            })
            return
        }
        for (t in tampilList) list.addView(kartu(t) {})
    }

    private fun thumb(t: Temuan, jenis: String): ImageView {
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFDDE8DD.toInt())
            layoutParams = LayoutParams(0, dp(110), 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        }
        val app = context.applicationContext
        val local = if (jenis == "p") TemuanStore.fotoP(app, t.id) else TemuanStore.fotoT(app, t.id)
        var tersedia: File? = null
        Thread {
            val f = if (local.exists()) local else TimSync.unduhFoto(app, t.id, jenis)
            val bmp = f?.let { ImageUtil.thumb(it, 400) }
            iv.post { if (bmp != null) { tersedia = f; iv.setImageBitmap(bmp) } }
        }.start()
        iv.setOnClickListener {
            val f = tersedia
            if (f != null) lihatFoto(f, if (jenis == "p") "Foto perbaikan" else "Foto temuan")
        }
        return iv
    }

    private fun lihatFoto(f: File, judul: String) {
        FotoViewer.tampil(context, f, judul)
    }

    private fun tgl(ms: Long) = MarkingText.tanggal(ms)

    private fun volumeText(t: Temuan) = if (t.volume == 0.0 && t.satuan.isBlank()) "" else (Laporan.angka(t.volume, 3) + " " + t.satuan).trim()

    /** Kartu satu temuan. [selesai] dipanggil setelah sebuah aksi dipilih (mis. menutup dialog rincian). */
    fun kartu(t: Temuan, selesai: () -> Unit): View {
        val ic = MarkerIcons.of(t.icon)
        val card = MaterialCardView(context).apply {
            radius = dp(12).toFloat(); cardElevation = 0f
            setCardBackgroundColor(if (t.tuntas) 0xFFE8F5E9.toInt() else 0xFFFFEBEE.toInt())
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(8)) }
        col.addView(TextView(context).apply {
            text = "${ic.emoji} ${t.judul}"; textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (t.tuntas) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt())
        })
        col.addView(TextView(context).apply {
            text = (if (t.tuntas) "🟢 TUNTAS" else "🔴 OUTSTANDING") +
                (if (t.sync.isNotEmpty() && t.err.isEmpty()) "   ⏳ menunggu kirim" else "")
            textSize = 12f; setTypeface(null, Typeface.BOLD)
        })
        if (t.err.isNotEmpty()) col.addView(TextView(context).apply {
            text = "⚠ Ditolak server: ${t.err}"; textSize = 12f; setTextColor(0xFFE65100.toInt())
        })
        col.addView(TextView(context).apply {
            text = buildString {
                append("${t.est} • ${t.afd}")
                if (t.blok.isNotBlank()) append(" • Blok ${t.blok}")
                val v = volumeText(t)
                if (v.isNotBlank()) append("\nVolume: $v")
                if (t.ket.isNotBlank()) append("\n${t.ket}")
                append("\nDilaporkan: ${t.pNama.ifBlank { t.pNpk }} • ${tgl(t.waktu)}")
                if (t.tuntas) {
                    append("\nTuntas: ${t.sNama.ifBlank { t.sNpk }} • ${tgl(t.tuntasAt)}")
                    if (t.catatan.isNotBlank()) append("\nCatatan: ${t.catatan}")
                }
            }
            textSize = 13f; setPadding(0, dp(4), 0, 0)
        })
        if (t.fotoT || t.fotoP) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            if (t.fotoT) row.addView(thumb(t, "t"))
            if (t.fotoP) row.addView(thumb(t, "p"))
            if (t.fotoT != t.fotoP) row.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
            col.addView(row)
        }
        val r1 = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        r1.addView(btn("Di Peta") { selesai(); env.keBpeta(t.lat, t.lon) })
        r1.addView(btn("Navigasi") { selesai(); env.navigasi(t.lat, t.lon) })
        r1.addView(btn("Share WA") { selesai(); bagikan(t) })
        col.addView(r1)
        val r2 = LinearLayout(context).apply { orientation = HORIZONTAL }
        if (!t.tuntas) r2.addView(btn("✔ Tandai Tuntas") { selesai(); dialogTuntas(t) })
        else r2.addView(btn("↩ Buka Kembali") { selesai(); bukaKembali(t) })
        val u = Tim.userCache(context)
        if (saya(t) || (u?.supervisi == true)) r2.addView(btn("Hapus") { selesai(); hapus(t) })
        col.addView(r2)
        card.addView(col)
        return card
    }

    /** Rincian satu temuan dalam dialog (dari ketukan titik di peta). */
    fun rincian(id: String) {
        val t = TemuanStore.get(context, id) ?: return
        var dlg: androidx.appcompat.app.AlertDialog? = null
        val sv = ScrollView(context).apply { setPadding(dp(8), dp(8), dp(8), 0); addView(kartu(t) { dlg?.dismiss() }) }
        dlg = MaterialAlertDialogBuilder(context).setTitle("Temuan Tim").setView(sv).setNegativeButton("Tutup", null).show()
    }

    // ------------------------------------------------------------ aksi

    private fun bagikan(t: Temuan) {
        val ic = MarkerIcons.of(t.icon)
        val teks = buildString {
            append("${ic.emoji} *TEMUAN ${if (t.tuntas) "TUNTAS" else "OUTSTANDING"}*\n")
            append("*${t.judul}*\n")
            append("${t.est} • ${t.afd}")
            if (t.blok.isNotBlank()) append(" • Blok ${t.blok}")
            append("\n")
            val v = volumeText(t)
            if (v.isNotBlank()) append("Volume : $v\n")
            if (t.ket.isNotBlank()) append("Keterangan : ${t.ket}\n")
            append("Dilaporkan : ${t.pNama.ifBlank { t.pNpk }} • ${tgl(t.waktu)}\n")
            if (t.tuntas) {
                append("Diselesaikan : ${t.sNama.ifBlank { t.sNpk }} • ${tgl(t.tuntasAt)}\n")
                if (t.catatan.isNotBlank()) append("Perbaikan : ${t.catatan}\n")
            }
            append("\nKoordinat : ${Laporan.koordinatSalin(t.lat, t.lon)}\n")
            append("Google Maps : ${Laporan.mapsUrl(t.lat, t.lon)}")
        }
        val foto = (if (t.tuntas) TemuanStore.fotoP(context, t.id).takeIf { it.exists() } else null)
            ?: TemuanStore.fotoT(context, t.id).takeIf { it.exists() }
        Share.textWithPhoto(context, teks, foto)
    }

    private fun dialogTuntas(t: Temuan) {
        if (!Tim.aktif(context)) { toast("Isi NPK di Pengaturan dulu."); return }
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        col.addView(TextView(context).apply {
            text = "Foto hasil perbaikan wajib (diberi watermark koordinat, judul, dan waktu otomatis)."
            textSize = 12f; setPadding(0, 0, 0, dp(6))
        })
        var raw: File? = null
        val pv = ImageView(context).apply {
            adjustViewBounds = true; maxHeight = dp(220); scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE
        }
        fun ambil(kamera: Boolean) = env.pickPhoto(kamera) { f ->
            if (f != null) { raw = f; pv.setImageBitmap(ImageUtil.thumb(f, 700)); pv.visibility = View.VISIBLE; pv.setOnClickListener { FotoViewer.tampil(context, f, "Foto perbaikan") } }
        }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(btn("📷 Kamera") { ambil(true) })
        row.addView(btn("🖼 Galeri") { ambil(false) })
        col.addView(row)
        col.addView(pv)
        val (til, et) = inputField(context, "Catatan perbaikan (opsional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        et.minLines = 2
        til.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        col.addView(til)
        val dlg = MaterialAlertDialogBuilder(context).setTitle("Tandai Tuntas: ${t.judul}")
            .setView(ScrollView(context).apply { addView(col) })
            .setPositiveButton("Tandai Tuntas", null).setNegativeButton("Batal", null).create()
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val r = raw
            if (r == null || !r.exists()) { toast("Ambil foto hasil perbaikan dulu."); return@setOnClickListener }
            val u = Tim.userCache(context)
            val npk = Tim.npk(context)
            val now = System.currentTimeMillis()
            val loc = env.lokasi()
            val (lat, lon) = if (loc != null) Pair(loc.latitude, loc.longitude) else Pair(t.lat, t.lon)
            val dest = TemuanStore.fotoP(context, t.id)
            try {
                r.copyTo(dest, overwrite = true); r.delete()
                val wm = listOf(
                    "PERBAIKAN: ${t.judul}",
                    if (t.blok.isNotBlank()) "Blok ${t.blok}" else "",
                    Laporan.koordinatSalin(lat, lon),
                    tgl(now),
                    (u?.nama ?: "") + " (" + npk + ")",
                ).filter { it.isNotBlank() }.joinToString("  |  ")
                if (!ImageUtil.watermark(dest, wm)) toast("Watermark foto gagal")
            } catch (e: Exception) {
                toast("Foto gagal disimpan: ${e.message}"); return@setOnClickListener
            }
            TemuanStore.ubah(context, t.id) {
                it.copy(status = Tim.TUNTAS, tuntasAt = now, sNpk = npk, sNama = u?.nama ?: "",
                    catatan = et.text.toString().trim(), fotoP = true, sync = if (it.sync == "N") "N" else "S", err = "")
            }
            dlg.dismiss()
            tampil(); env.petaBerubah()
            sinkron(true)
        }
    }

    private fun bukaKembali(t: Temuan) {
        MaterialAlertDialogBuilder(context).setTitle("Buka kembali?")
            .setMessage("\"${t.judul}\" kembali menjadi Outstanding dan foto perbaikan dihapus.")
            .setPositiveButton("Buka kembali") { _, _ ->
                try { TemuanStore.fotoP(context, t.id).delete() } catch (e: Exception) {}
                TemuanStore.ubah(context, t.id) {
                    it.copy(status = Tim.OUTSTANDING, tuntasAt = 0L, sNpk = "", sNama = "", catatan = "", fotoP = false,
                        sync = if (it.sync == "N") "N" else "S", err = "")
                }
                tampil(); env.petaBerubah(); sinkron(true)
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun hapus(t: Temuan) {
        MaterialAlertDialogBuilder(context).setTitle("Hapus temuan?")
            .setMessage("\"${t.judul}\" akan dihapus untuk semua anggota tim.")
            .setPositiveButton("Hapus") { _, _ ->
                if (t.sync == "N") TemuanStore.hapusLokal(context, t.id)       // belum pernah terkirim
                else { TemuanStore.ubah(context, t.id) { it.copy(sync = "D", err = "") }; sinkron(true) }
                tampil(); env.petaBerubah()
            }
            .setNegativeButton("Batal", null).show()
    }

    // ------------------------------------------------------------ buat dari tanda

    companion object {
        /** Buat temuan baru (antrean kirim) dari tanda yang baru disimpan. Foto tanda disalin ke cache tim. */
        fun dariTanda(ctx: Context, m: Marker, est: String, afd: String) {
            val u = Tim.userCache(ctx)
            val npk = Tim.npk(ctx)
            var adaFoto = false
            if (m.photo.isNotBlank()) {
                val pf = MarkerStore.photoFile(ctx, m.id)
                if (pf.exists()) try { pf.copyTo(TemuanStore.fotoT(ctx, m.id), overwrite = true); adaFoto = true } catch (e: Exception) {}
            }
            TemuanStore.put(ctx, Temuan(
                id = m.id, waktu = m.createdAt, est = est.trim().uppercase(), afd = afd.trim().uppercase(), blok = m.blok,
                judul = m.title, icon = m.icon, ket = m.note, volume = m.volume, satuan = m.unit,
                lat = m.lat, lon = m.lon, acc = m.acc, pNpk = npk, pNama = u?.nama ?: "", fotoT = adaFoto, sync = "N",
            ))
        }
    }
}
