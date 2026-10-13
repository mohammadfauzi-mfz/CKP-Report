package id.dsngroup.ckpreport

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

/** Checklist Activity (Losses / Grading TPH / Kalibrasi BJR). Rincian muncul hanya jika dicentang. */
class ActivityForm(context: Context) : LinearLayout(context) {

    private val cbLosses = MaterialCheckBox(context)
    private val cbGrading = MaterialCheckBox(context)
    private val cbKal = MaterialCheckBox(context)
    private val pLosses = panel()
    private val pGrading = panel()
    private val pKal = panel()

    // Losses
    private lateinit var etAncak: TextInputEditText
    private lateinit var etLuas: TextInputEditText
    private lateinit var etBrondol: TextInputEditText
    private lateinit var etJanjang: TextInputEditText
    private lateinit var etBjr: TextInputEditText
    private lateinit var tvLossesJjg: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvPerHa: TextView

    // Grading
    private lateinit var etTphG: TextInputEditText
    private lateinit var etRipe: TextInputEditText
    private lateinit var etMengkal: TextInputEditText
    private lateinit var etMentah: TextInputEditText
    private lateinit var etOverripe: TextInputEditText
    private lateinit var tvPRipe: TextView
    private lateinit var tvPMengkal: TextView
    private lateinit var tvPMentah: TextView
    private lateinit var tvPOverripe: TextView
    private lateinit var tvTotalJjg: TextView
    private lateinit var etBjrG: TextInputEditText
    private lateinit var tvTonase: TextView
    private lateinit var etBrondolG: TextInputEditText
    private lateinit var tvPBrondol: TextView

    // Kalibrasi
    private lateinit var etTphK: TextInputEditText
    private lateinit var etJjgK: TextInputEditText
    private lateinit var etTonK: TextInputEditText
    private lateinit var tvBjrK: TextView

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = "Pilih Activity :"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        link(cbLosses, "Losses", pLosses)
        link(cbGrading, "Grading TPH", pGrading)
        link(cbKal, "Kalibrasi BJR", pKal)
        addView(cbLosses); addView(pLosses)
        addView(cbGrading); addView(pGrading)
        addView(cbKal); addView(pKal)
        buildLosses()
        buildGrading()
        buildKalibrasi()
        recalc()
    }

    private fun panel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(12), dp(10), dp(12), dp(6))
        setBackgroundColor(0xFFF1F8E9.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun link(cb: MaterialCheckBox, label: String, p: LinearLayout) {
        cb.text = label
        cb.textSize = 16f
        cb.setOnCheckedChangeListener { _, checked -> p.visibility = if (checked) View.VISIBLE else View.GONE }
    }

    private fun title(p: LinearLayout, t: String) {
        p.addView(TextView(context).apply {
            text = t
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF1B5E20.toInt())
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun num(p: LinearLayout, hint: String): TextInputEditText {
        val (t, e) = inputField(context, hint, NUM_TYPE)
        p.addView(t)
        e.watch { recalc() }
        return e
    }

    private fun result(p: LinearLayout, label: String): TextView {
        val (card, v) = resultRow(context, label)
        p.addView(card)
        return v
    }

    private fun buildLosses() {
        title(pLosses, "Losses :")
        val (t, e) = inputField(context, "Nama Ancak", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        etAncak = e
        pLosses.addView(t)
        etLuas = num(pLosses, "Luas CKP (Ha)")
        etBrondol = num(pLosses, "Brondol Tinggal (Kg)")
        etJanjang = num(pLosses, "Janjang Tinggal (JJG)")
        etBjr = num(pLosses, "BJR (Kg)")
        tvLossesJjg = result(pLosses, "Losses JJG (Kg)")
        tvTotal = result(pLosses, "Total Losses (Kg)")
        tvPerHa = result(pLosses, "Losses/Ha (Kg/Ha)")
    }

    private fun criteriaRow(label: String): Pair<TextInputEditText, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val (t, e) = inputField(context, "$label (JJG)", NUM_TYPE)
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { bottomMargin = dp(8) }
        e.watch { recalc() }
        val pct = TextView(context).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
        }
        row.addView(t)
        row.addView(pct)
        pGrading.addView(row)
        return Pair(e, pct)
    }

    private fun buildGrading() {
        title(pGrading, "Grading TPH :")
        val (t, e) = inputField(context, "No TPH", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        etTphG = e
        pGrading.addView(t)
        pGrading.addView(TextView(context).apply { text = "Kriteria  (JJG  /  %)"; textSize = 13f; setPadding(0, 0, 0, dp(4)) })
        criteriaRow("Ripe").let { etRipe = it.first; tvPRipe = it.second }
        criteriaRow("Mengkal").let { etMengkal = it.first; tvPMengkal = it.second }
        criteriaRow("Mentah").let { etMentah = it.first; tvPMentah = it.second }
        criteriaRow("Overripe").let { etOverripe = it.first; tvPOverripe = it.second }
        tvTotalJjg = result(pGrading, "Total JJG")
        etBjrG = num(pGrading, "BJR (Kg)")
        tvTonase = result(pGrading, "Tonase (Ton)")
        etBrondolG = num(pGrading, "Brondol (Kg)")
        tvPBrondol = result(pGrading, "Brondol terhadap Tonase (%)")
    }

    private fun buildKalibrasi() {
        title(pKal, "Kalibrasi BJR :")
        val (t, e) = inputField(context, "No. TPH", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        etTphK = e
        pKal.addView(t)
        etJjgK = num(pKal, "Jumlah Janjang (JJG)")
        etTonK = num(pKal, "Tonase (Kg)")
        tvBjrK = result(pKal, "BJR (Kg)")
    }

    // ---------- hitung otomatis (rumus sama dengan Excel) ----------

    private fun recalc() {
        // Panel dibuat berurutan di init, jadi lewati jika belum lengkap
        if (!::tvBjrK.isInitialized) return
        val l = losses(force = true)!!
        tvLossesJjg.text = Laporan.angka(l.lossesJjg)
        tvTotal.text = Laporan.angka(l.total)
        tvPerHa.text = Laporan.angka(l.perHa)

        val g = grading(force = true)!!
        tvPRipe.text = Laporan.persen(g.pct(g.ripe))
        tvPMengkal.text = Laporan.persen(g.pct(g.mengkal))
        tvPMentah.text = Laporan.persen(g.pct(g.mentah))
        tvPOverripe.text = Laporan.persen(g.pct(g.overripe))
        tvTotalJjg.text = Laporan.angka(g.total)
        tvTonase.text = Laporan.angka(g.tonase, 3)
        tvPBrondol.text = Laporan.persen(g.brondolPct)

        val k = kalibrasi(force = true)!!
        tvBjrK.text = Laporan.angka(k.bjr)
    }

    // ---------- data ----------

    fun losses(force: Boolean = false): LossesData? =
        if (!force && !cbLosses.isChecked) null
        else LossesData(etAncak.text.toString().trim(), etLuas.num(), etBrondol.num(), etJanjang.num(), etBjr.num())

    fun grading(force: Boolean = false): GradingData? =
        if (!force && !cbGrading.isChecked) null
        else GradingData(
            etTphG.text.toString().trim(), etRipe.num(), etMengkal.num(), etMentah.num(), etOverripe.num(),
            etBjrG.num(), etBrondolG.num(),
        )

    fun kalibrasi(force: Boolean = false): KalibrasiData? =
        if (!force && !cbKal.isChecked) null
        else KalibrasiData(etTphK.text.toString().trim(), etJjgK.num(), etTonK.num())

    /** @return pesan kesalahan, atau null jika isian activity sudah lengkap. */
    fun validate(): String? {
        losses()?.let { if (it.namaAncak.isEmpty()) return "Losses: Nama Ancak wajib diisi" }
        grading()?.let {
            if (it.noTph.isEmpty()) return "Grading TPH: No TPH wajib diisi"
            if (it.total <= 0.0) return "Grading TPH: isi jumlah janjang (Ripe/Mengkal/Mentah/Overripe)"
        }
        kalibrasi()?.let {
            if (it.noTph.isEmpty()) return "Kalibrasi BJR: No. TPH wajib diisi"
            if (it.janjang <= 0.0) return "Kalibrasi BJR: Jumlah Janjang harus lebih dari 0"
        }
        return null
    }

    fun reset() {
        listOf(etAncak, etLuas, etBrondol, etJanjang, etBjr, etTphG, etRipe, etMengkal, etMentah, etOverripe,
            etBjrG, etBrondolG, etTphK, etJjgK, etTonK).forEach { it.setText("") }
        cbLosses.isChecked = false
        cbGrading.isChecked = false
        cbKal.isChecked = false
    }
}
