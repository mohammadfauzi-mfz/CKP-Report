package id.dsngroup.ckpreport

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Daftar laporan: yang menunggu upload (antrean) dan yang sudah terupload. */
class QueueView(context: Context) : LinearLayout(context) {

    private val adapter = Adapter()
    private val tvEmpty = TextView(context)

    init {
        orientation = LinearLayout.VERTICAL
        addView(MaterialButton(context).apply {
            text = "Upload Semua yang Tertunda"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(16), dp(12), dp(16), dp(4)) }
            setOnClickListener { uploadAll() }
        })
        tvEmpty.text = "Belum ada laporan."
        tvEmpty.setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(tvEmpty)
        addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@QueueView.adapter
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(24))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun refresh() {
        adapter.items = ReportStore.all(context)
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (adapter.items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toast(t: String) = post { Toast.makeText(context, t, Toast.LENGTH_LONG).show() }

    private fun uploadAll() {
        val pending = ReportStore.all(context).filter { it.status == ST_PENDING }.reversed()
        if (pending.isEmpty()) { toast("Tidak ada laporan yang tertunda"); return }
        toast("Mengupload ${pending.size} laporan...")
        Thread {
            var ok = 0
            var fail = 0
            for (r in pending) {
                if (Sync.uploadOne(context, r.id) == Sync.Outcome.OK) ok++ else fail++
            }
            toast(if (fail == 0) "$ok laporan terupload ✅" else "$ok terupload, $fail gagal (cek sinyal / pengaturan)")
        }.start()
    }

    private fun uploadOne(r: Report) {
        toast("Mengupload...")
        Thread {
            val o = Sync.uploadOne(context, r.id)
            toast(when (o) {
                Sync.Outcome.OK -> "Terupload ✅"
                Sync.Outcome.NETWORK -> "Belum ada sinyal. Akan diupload otomatis saat ada sinyal."
                Sync.Outcome.SERVER -> ReportStore.get(context, r.id)?.error ?: "Gagal"
                Sync.Outcome.SKIPPED -> "Sedang diproses"
            })
        }.start()
    }

    private fun confirmDelete(r: Report) {
        val msg = if (r.status == ST_PENDING)
            "Laporan ini BELUM terupload. Jika dihapus, datanya hilang. Hapus?"
        else "Hapus laporan ini dari daftar di HP? (Data di spreadsheet tidak terhapus)"
        MaterialAlertDialogBuilder(context)
            .setMessage(msg)
            .setPositiveButton("Hapus") { _, _ -> ReportStore.remove(context, r.id) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Report> = emptyList()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = MaterialCardView(parent.context).apply {
                radius = dp(12).toFloat()
                cardElevation = 0f
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val r = items[position]
            val card = h.itemView as MaterialCardView
            card.removeAllViews()
            val pending = r.status == ST_PENDING
            card.setCardBackgroundColor(if (pending) 0xFFFFF3E0.toInt() else 0xFFE8F5E9.toInt())

            val col = LinearLayout(card.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(8))
            }
            col.addView(TextView(card.context).apply {
                text = "${r.est} • ${r.afd} • ${r.blok}"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            })
            col.addView(TextView(card.context).apply { text = r.waktuText; textSize = 12f; setTextColor(0xFF666666.toInt()) })
            col.addView(TextView(card.context).apply {
                text = when {
                    !pending -> "✅ Terupload ke spreadsheet & Drive"
                    r.error.isBlank() -> "⏳ Menunggu upload (otomatis saat ada sinyal)"
                    else -> "⚠ ${r.error}"
                }
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            })
            col.addView(TextView(card.context).apply { text = r.problem; textSize = 14f; maxLines = 3 })

            val row = LinearLayout(card.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(6), 0, 0)
            }
            fun btn(label: String, onClick: () -> Unit) = MaterialButton(
                card.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener { onClick() }
            }
            row.addView(btn("Share WA") { Share.whatsApp(context, r) })
            if (pending) row.addView(btn("Upload") { uploadOne(r) })
            row.addView(btn("Hapus") { confirmDelete(r) })
            col.addView(row)
            card.addView(col)
        }
    }
}
