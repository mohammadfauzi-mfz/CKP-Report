package id.dsngroup.ckpreport

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Lux Meter memakai sensor cahaya HP, dengan faktor kalibrasi. */
class LuxCard(context: Context, private val prefs: SharedPreferences) : LinearLayout(context), SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val samples = ArrayList<Float>()
    private var raw = -1f
    private var running = false

    /** Nilai (lux, sudah dikalibrasi) yang disimpan ke laporan; -1 = belum ada. */
    var saved: Double = -1.0
        private set

    private val tvValue = TextView(context)
    private val tvInfo = TextView(context)
    private val tvSaved = TextView(context)
    private val btnStart = MaterialButton(context)

    private val factor: Float get() = prefs.getFloat("lux_factor", 1f)

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFE3F2FD.toInt())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        addView(TextView(context).apply {
            text = "Cahaya (Lux Meter)"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        })
        tvValue.textSize = 34f
        tvValue.setTypeface(null, Typeface.BOLD)
        tvValue.setTextColor(0xFF0D47A1.toInt())
        tvValue.gravity = Gravity.CENTER
        tvValue.setPadding(0, dp(6), 0, dp(2))
        addView(tvValue)
        tvInfo.textSize = 12f
        tvInfo.gravity = Gravity.CENTER
        addView(tvInfo)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun btn(b: MaterialButton, label: String, onClick: () -> Unit): MaterialButton {
            b.text = label
            b.textSize = 12f
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(2), 0, dp(2), 0) }
            b.setOnClickListener { onClick() }
            return b
        }
        row.addView(btn(btnStart, "Mulai Ukur") { if (running) stop() else start() })
        row.addView(btn(MaterialButton(context), "Simpan Nilai") { saveValue() })
        row.addView(btn(MaterialButton(context), "Kalibrasi") { showCalibration() })
        addView(row)

        tvSaved.textSize = 13f
        tvSaved.setTypeface(null, Typeface.BOLD)
        tvSaved.setPadding(0, dp(8), 0, 0)
        tvSaved.setOnClickListener { if (saved >= 0) { saved = -1.0; refresh() } }
        addView(tvSaved)
        refresh()
    }

    private fun calibrated(): Double = raw * factor.toDouble()

    private fun refresh() {
        tvValue.text = if (sensor == null) "—" else if (raw < 0) "— lx" else "${Laporan.angka(calibrated(), if (calibrated() < 100) 1 else 0)} lx"
        tvInfo.text = when {
            sensor == null -> "HP ini tidak memiliki sensor cahaya"
            running -> "Mengukur... arahkan sensor cahaya HP (dekat kamera depan) ke sumber cahaya • Faktor kalibrasi: ${Laporan.angka(factor.toDouble(), 3)}"
            else -> "Tekan Mulai Ukur • Faktor kalibrasi: ${Laporan.angka(factor.toDouble(), 3)}"
        }
        btnStart.text = if (running) "Berhenti" else "Mulai Ukur"
        tvSaved.text = if (saved >= 0) "Nilai tersimpan di laporan: ${Laporan.angka(saved, 0)} lx (ketuk untuk menghapus)"
        else "Belum ada nilai cahaya yang disimpan ke laporan (opsional)"
    }

    fun start() {
        val s = sensor
        if (s == null) { toast("HP ini tidak memiliki sensor cahaya"); return }
        samples.clear()
        raw = -1f
        running = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        refresh()
    }

    fun stop() {
        if (running) sm.unregisterListener(this)
        running = false
        refresh()
    }

    /** Kosongkan nilai tersimpan (dipakai setelah laporan selesai). */
    fun reset() {
        saved = -1.0
        stop()
    }

    private fun saveValue() {
        if (raw < 0) { toast("Tekan Mulai Ukur dulu, tunggu angka muncul"); return }
        saved = calibrated()
        stop()
        toast("Nilai cahaya disimpan: ${Laporan.angka(saved, 0)} lx")
    }

    override fun onSensorChanged(e: SensorEvent) {
        samples.add(e.values[0])
        if (samples.size > 5) samples.removeAt(0)
        raw = samples.average().toFloat()   // rata-rata 5 bacaan terakhir supaya stabil
        refresh()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    private fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    private fun showCalibration() {
        val f = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        f.addView(TextView(context).apply {
            text = "Kalibrasi mencocokkan hasil HP dengan alat Lux Meter pembanding.\n\n" +
                "Cara 1: Ukur cahaya yang sama dengan alat pembanding. Tekan Mulai Ukur di HP, " +
                "lalu isi \"Nilai alat pembanding\" di bawah. Faktor dihitung otomatis.\n\n" +
                "Cara 2: Isi langsung \"Faktor kalibrasi\" (pengali).\n\n" +
                "Bacaan sensor mentah saat ini: " + (if (raw >= 0) "${Laporan.angka(raw.toDouble(), 1)} lx" else "belum ada (tekan Mulai Ukur)")
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })
        val (tRef, eRef) = inputField(context, "Nilai alat pembanding (lux) - opsional", NUM_TYPE)
        val (tFac, eFac) = inputField(context, "Faktor kalibrasi (pengali)", NUM_TYPE, Laporan.angka(factor.toDouble(), 4).replace(".", ""))
        f.addView(tRef)
        f.addView(tFac)

        val d = MaterialAlertDialogBuilder(context)
            .setTitle("Kalibrasi Lux Meter")
            .setView(f)
            .setPositiveButton("Simpan", null)
            .setNeutralButton("Reset (1)", null)
            .setNegativeButton("Batal", null)
            .create()
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val ref = parseNum(eRef.text.toString())
            val newFactor: Double? = if (ref != null && ref > 0) {
                if (raw > 0) ref / raw else { tRef.error = "Tekan Mulai Ukur dulu sampai angka muncul"; null }
            } else {
                val v = parseNum(eFac.text.toString())
                if (v != null && v > 0) v else { tFac.error = "Isi angka lebih dari 0"; null }
            }
            if (newFactor != null) {
                prefs.edit().putFloat("lux_factor", newFactor.toFloat()).apply()
                d.dismiss()
                refresh()
            }
        }
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            prefs.edit().putFloat("lux_factor", 1f).apply()
            d.dismiss()
            refresh()
        }
    }
}
