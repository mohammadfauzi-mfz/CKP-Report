package id.dsngroup.ckpreport

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

interface PetaHost {
    fun importData()
    fun toggleTracking()
    fun togglePause()
    fun hasLocationPermission(): Boolean
    fun requestLocationPermission()
    fun defaultBlok(): String
    /** Ambil foto (kamera / galeri), sudah diperkecil & diputar benar. Hasil null jika dibatalkan. */
    fun pickPhoto(camera: Boolean, onResult: (File?) -> Unit)
}

/**
 * Tab Peta: (1) peta offline interaktif dengan posisi saya, tracking, tanda, dan pengukuran;
 * (2) riwayat tracking; (3) rekap tanda dengan pencarian. Semua data tersimpan di HP.
 */
class PetaView(context: Context, private val host: PetaHost) : LinearLayout(context), MapCanvasView.Listener {

    private val idn = Locale("in", "ID")
    private val prefs = context.getSharedPreferences("ckp", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    // panel
    private val tabs = TabLayout(context)
    private val pane = FrameLayout(context)
    private val mapPane = LinearLayout(context)
    private val historyPane = LinearLayout(context)
    private val rekapPane = LinearLayout(context)
    private val timEnv = object : TimEnv {
        override fun lokasi(): Location? = lastLoc
        override fun keBpeta(lat: Double, lon: Double) { goTo(lat, lon) }
        override fun navigasi(lat: Double, lon: Double) { this@PetaView.navigasi(lat, lon) }
        override fun pickPhoto(camera: Boolean, onResult: (File?) -> Unit) { host.pickPhoto(camera, onResult) }
        override fun temuanBaru() { this@PetaView.temuanBaru() }
        override fun petaBerubah() { reloadOverlays(); updateTimTab() }
    }
    private val timView = TimView(context, timEnv)

    // peta
    private val canvas = MapCanvasView(context)
    private val tvInfo = TextView(context)
    private lateinit var btnMap: MaterialButton
    private lateinit var btnTrack: MaterialButton
    private lateinit var btnPause: MaterialButton
    private val barNormal = LinearLayout(context)
    private val barMeasure = LinearLayout(context)
    private var curMap: MapInfo? = null
    private var loadGen = 0
    private var pending: (() -> Unit)? = null
    private var lastLoc: Location? = null
    private var lastGps = 0L
    private var centeredOnce = false
    private var selected: Pair<Double, Double>? = null
    private var shown = false
    private var followMe = false
    private var hires: PdfRegion? = null
    private lateinit var btnFollow: MaterialButton

    // riwayat
    private val historyList = LinearLayout(context)

    // rekap
    private val rekapList = LinearLayout(context)
    private lateinit var etSearch: EditText
    private lateinit var btnKategori: MaterialButton
    private lateinit var btnUrut: MaterialButton
    private val tvRekapInfo = TextView(context)
    private var kategori: String? = null
    private var urutDekat = false
    private var rekapShown: List<Marker> = emptyList()

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onLoc(location) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    init {
        orientation = VERTICAL
        tabs.addTab(tabs.newTab().setText("Peta"))
        tabs.addTab(tabs.newTab().setText("Riwayat"))
        tabs.addTab(tabs.newTab().setText("Rekap"))
        tabs.addTab(tabs.newTab().setText("Tim"))
        tabs.tabMode = TabLayout.MODE_FIXED
        addView(tabs)
        addView(pane, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildMapPane()
        buildHistoryPane()
        buildRekapPane()
        pane.addView(mapPane)
        pane.addView(historyPane)
        pane.addView(rekapPane)
        pane.addView(timView)
        selectPane(0)
        updateTimTab()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { selectPane(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        canvas.listener = this
    }

    // =====================================================================
    // Umum
    // =====================================================================

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    private fun btn(label: String, filled: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (filled) MaterialButton(context) else MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        )
        b.text = label
        b.textSize = 12f
        b.isAllCaps = false
        b.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun selectPane(i: Int) {
        mapPane.visibility = if (i == 0) View.VISIBLE else View.GONE
        historyPane.visibility = if (i == 1) View.VISIBLE else View.GONE
        rekapPane.visibility = if (i == 2) View.VISIBLE else View.GONE
        timView.visibility = if (i == 3) View.VISIBLE else View.GONE
        if (tabs.selectedTabPosition != i) tabs.getTabAt(i)?.select()
        if (i == 1) refreshHistory()
        if (i == 2) refreshRekap()
        if (i == 3) timView.onShown()
    }

    /** Label tab Tim memuat jumlah temuan yang masih outstanding. */
    private fun updateTimTab() {
        val n = TemuanStore.all(context).count { it.sync != "D" && !it.tuntas }
        tabs.getTabAt(3)?.text = if (n > 0) "Tim ($n)" else "Tim"
    }

    /** Sinkron senyap (dipanggil saat aplikasi dibuka). */
    fun sinkronTim() { if (Tim.aktif(context)) timView.sinkron(true) }

    private fun temuanBaru() {
        if (!Tim.aktif(context)) { toast("Isi NPK dan URL Web App di Pengaturan dulu."); return }
        val l = lastLoc
        val sel = selected
        val (la, lo) = when {
            sel != null -> sel
            l != null -> Pair(l.latitude, l.longitude)
            else -> { toast("Posisi belum ada. Nyalakan GPS atau pilih titik di peta dulu."); selectPane(0); return }
        }
        editor(null, la, lo, null, null, true)
    }

    /** Dipanggil saat tab Peta (utama) ditampilkan. */
    // ---- kompas ----
    private val sensorMgr = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private var azimuth = -1f
    private var lastShownAz = -999f
    private val rotMat = FloatArray(9)
    private val orient = FloatArray(3)
    private val compassListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            try {
                android.hardware.SensorManager.getRotationMatrixFromVector(rotMat, e.values)
                android.hardware.SensorManager.getOrientation(rotMat, orient)
            } catch (x: Exception) { return }
            var az = Math.toDegrees(orient[0].toDouble()).toFloat()
            if (az < 0) az += 360f
            // haluskan (rata-rata melingkar) supaya kerucut tidak bergetar
            azimuth = if (azimuth < 0) az else {
                var d = az - azimuth
                if (d > 180) d -= 360f else if (d < -180) d += 360f
                ((azimuth + d * 0.25f) + 360f) % 360f
            }
            var diff = Math.abs(azimuth - lastShownAz)
            if (diff > 180) diff = 360f - diff
            if (diff >= 2f) {
                lastShownAz = azimuth
                canvas.setHeading(azimuth)
                updateInfo()
            }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun startCompass() {
        try {
            val s = sensorMgr.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) ?: return
            sensorMgr.registerListener(compassListener, s, android.hardware.SensorManager.SENSOR_DELAY_UI)
        } catch (e: Exception) {
        }
    }

    private fun stopCompass() {
        try { sensorMgr.unregisterListener(compassListener) } catch (e: Exception) {}
        azimuth = -1f; lastShownAz = -999f
        canvas.setHeading(null)
    }

    private fun arah8(deg: Float): String {
        val n = arrayOf("Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut")
        return n[(((deg + 22.5f) % 360f) / 45f).toInt() % 8]
    }

    fun onShown() {
        shown = true
        startLocation()
        startCompass()
        if (curMap == null) initMap() else reloadOverlays()
        handler.removeCallbacks(tick)
        handler.post(tick)
        refreshHistory()
        refreshRekap()
    }

    fun onHidden() {
        shown = false
        stopLocation()
        stopCompass()
        handler.removeCallbacks(tick)
    }

    /** Muat ulang data (setelah import / tracking selesai). */
    fun refresh() {
        reloadOverlays()
        refreshHistory()
        refreshRekap()
    }

    override fun onDetachedFromWindow() {
        onHidden()
        super.onDetachedFromWindow()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!shown) return
            canvas.setLive(if (Tracker.active) Tracker.snapshot() else emptyList())
            btnTrack.text = if (Tracker.active) "■ Stop" else "● Tracking"
            btnPause.visibility = if (Tracker.active) View.VISIBLE else View.GONE
            btnPause.text = if (Tracker.paused) "▶ Lanjut" else "⏸ Pause"
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    // =====================================================================
    // Lokasi
    // =====================================================================

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!host.hasLocationPermission()) { updateInfo(); return }
        stopLocation()
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper())
            } catch (e: Exception) {
            }
        }
        // posisi terakhir yang diketahui, supaya titik biru langsung muncul
        if (lastLoc == null) {
            try {
                val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (l != null && System.currentTimeMillis() - l.time < 10 * 60 * 1000L) onLoc(l)
            } catch (e: Exception) {
            }
        }
    }

    private fun stopLocation() {
        try { lm.removeUpdates(locListener) } catch (e: Exception) {}
    }

    private fun onLoc(l: Location) {
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGps = now
        else if (now - lastGps < 15000L) return          // GPS masih segar: abaikan lokasi jaringan
        lastLoc = l
        if (followMe) { selected = Pair(l.latitude, l.longitude) }
        val m = curMap
        if (m == null) {
            if (MapStore.all(context).isEmpty()) showMap(MapCanvasView.plainMap(l.latitude, l.longitude))
            else initMap()
            return
        }
        canvas.setMe(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
        if (followMe) canvas.setSelected(l.latitude, l.longitude)
        if (!centeredOnce && m.contains(l.latitude, l.longitude)) {
            centeredOnce = true
            canvas.centerOn(l.latitude, l.longitude, zoomFor(m, 250.0))
        }
        updateInfo()
    }

    private fun zoomFor(m: MapInfo, meters: Double): Float {
        val w = canvas.width.coerceAtLeast(300)
        return (w * m.metersPerPixel / meters).toFloat()
    }

    // =====================================================================
    // Pane 1: peta
    // =====================================================================

    private fun buildMapPane() {
        mapPane.orientation = VERTICAL
        tvInfo.textSize = 12f
        tvInfo.setTextColor(0xFF263238.toInt())
        tvInfo.setBackgroundColor(0xFFF1F5EE.toInt())
        tvInfo.setPadding(dp(10), dp(4), dp(10), dp(4))
        tvInfo.minLines = 2
        tvInfo.maxLines = 3
        tvInfo.setOnClickListener { copySelected() }

        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), dp(2), dp(6), 0)
        btnMap = btn("Pilih peta") { pilihPeta() }
        head.addView(btnMap)
        mapPane.addView(head)
        mapPane.addView(tvInfo)

        val fr = FrameLayout(context)
        fr.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val zoom = LinearLayout(context)
        zoom.orientation = VERTICAL
        zoom.addView(round("+") { canvas.zoomBy(1.5f) })
        zoom.addView(round("−") { canvas.zoomBy(1f / 1.5f) })
        zoom.addView(round("◎") { ke("me") })
        btnFollow = round("📌") { toggleFollow() }
        zoom.addView(btnFollow)
        fr.addView(zoom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { rightMargin = dp(8) })
        mapPane.addView(fr, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        barNormal.orientation = HORIZONTAL
        barNormal.setPadding(dp(4), dp(4), dp(4), dp(4))
        btnTrack = btn("● Tracking", true) { host.toggleTracking() }
        btnTrack.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
        barNormal.addView(btnTrack)
        btnPause = btn("⏸ Pause") { host.togglePause() }
        btnPause.visibility = View.GONE
        barNormal.addView(btnPause)
        barNormal.addView(btn("📍 Tandai") { tandai() })
        barNormal.addView(btn("📏 Ukur") { pilihUkur() })
        barNormal.addView(btn("⋯ Lainnya") { menuLainnya() })
        mapPane.addView(barNormal)

        barMeasure.orientation = HORIZONTAL
        barMeasure.setPadding(dp(4), dp(4), dp(4), dp(4))
        barMeasure.visibility = View.GONE
        barMeasure.addView(btn("↶ Undo") { canvas.undoMeasure() })
        barMeasure.addView(btn("＋ Posisi Saya") { ukurPosisiSaya() })
        barMeasure.addView(btn("Simpan Tanda") { simpanUkur() })
        barMeasure.addView(btn("Selesai", true) { selesaiUkur() })
        mapPane.addView(barMeasure)
    }

    private fun round(label: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(context)
        b.text = label
        b.textSize = 18f
        b.insetTop = 0
        b.insetBottom = 0
        b.setPadding(0, 0, 0, 0)
        b.minimumWidth = 0
        b.minWidth = 0
        b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
        b.setTextColor(0xFF1B5E20.toInt())
        b.layoutParams = LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(6) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun initMap() {
        val maps = MapStore.all(context)
        val me = lastLoc
        val saved = prefs.getString("peta_map", "")
        val pick = (if (me != null) maps.firstOrNull { it.contains(me.latitude, me.longitude) } else null)
            ?: maps.firstOrNull { it.id == saved }
            ?: maps.firstOrNull()
        when {
            pick != null -> showMap(pick)
            me != null -> showMap(MapCanvasView.plainMap(me.latitude, me.longitude))
            else -> {
                btnMap.text = "Belum ada peta"
                updateInfo()
            }
        }
    }

    private var onlineSrc: MapCanvasView.TileSrc? = null
    private var onlineRef: Pair<Double, Double>? = null

    private fun showMap(m: MapInfo) {
        curMap = m
        centeredOnce = false
        selected = null
        onlineSrc = null
        canvas.tileSrc = null
        if (m.id != "plain") prefs.edit().putString("peta_map", m.id).apply()
        btnMap.text = if (m.id == "plain") "Tanpa peta (grid 100 m)" else "Peta: ${m.name}"
        val gen = ++loadGen
        if (m.id == "plain") {
            canvas.setMap(m, null)
            setHiRes(null)
            afterMap()
            return
        }
        Thread {
            val b = MapCanvasView.loadBitmap(MapStore.imageFile(context, m.id).path)
            canvas.post {
                if (gen != loadGen) return@post
                if (b == null) toast("Gambar peta tidak dapat dimuat (memori kurang / file rusak)")
                canvas.setMap(m, b)
                setHiRes(m)
                afterMap()
            }
        }.start()
    }

    /** Peta online (Google Maps / Satelit / Hybrid / OSM) berpusat di titik acuan. */
    private fun showOnline(src: MapCanvasView.TileSrc, lat: Double, lon: Double) {
        loadGen++
        val m = MapCanvasView.onlineMap(lat, lon)
        curMap = m
        centeredOnce = true
        selected = null
        onlineSrc = src
        onlineRef = Pair(lat, lon)
        canvas.tileSrc = src
        canvas.setMap(m, null)
        setHiRes(null)
        btnMap.text = "🌐 ${src.name}"
        if (pending == null) canvas.centerOn(lat, lon, zoomFor(m, 300.0))
        afterMap()
    }

    /** Titik acuan untuk peta online: posisi saya, titik terpilih, atau pusat peta offline. */
    private fun onlineRefPoint(): Pair<Double, Double>? {
        val l = lastLoc
        if (l != null) return Pair(l.latitude, l.longitude)
        selected?.let { return it }
        onlineRef?.let { return it }
        val m = curMap
        if (m != null && m.id != "plain" && m.id != "online") return m.latLon(m.w / 2.0, m.h / 2.0)
        return null
    }

    private fun pilihOnline(src: MapCanvasView.TileSrc) {
        val r = onlineRefPoint()
        if (r == null) { toast("Posisi GPS belum didapat. Tunggu titik biru muncul, atau pilih peta offline dulu."); return }
        showOnline(src, r.first, r.second)
    }

    private fun afterMap() {
        val m = curMap ?: return
        reloadOverlays()
        val l = lastLoc
        if (l != null) {
            canvas.setMe(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
            if (m.contains(l.latitude, l.longitude) && pending == null) {
                centeredOnce = true
                canvas.centerOn(l.latitude, l.longitude, zoomFor(m, 250.0))
            }
        }
        val p = pending
        pending = null
        p?.invoke()
        updateInfo()
    }

    private fun reloadOverlays() {
        val m = curMap ?: return
        val tem = TemuanStore.all(context).filter { it.sync != "D" }
        val ids = tem.map { it.id }.toSet()
        canvas.setMarkers(MarkerStore.all(context).filter { it.id !in ids } + tem.map { TemuanStore.sebagaiMarker(it) })
        canvas.setHistory(TrackStore.all(context).filter { r ->
            r.points.isNotEmpty() && m.contains(r.points[r.points.size / 2].lat, r.points[r.points.size / 2].lon)
        }.map { it.points })
        canvas.setLive(if (Tracker.active) Tracker.snapshot() else emptyList())
    }

    private fun pilihPeta() {
        val maps = MapStore.all(context)
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        for (m in maps) { names.add("🗺 ${m.name} (offline)"); acts.add { showMap(m) } }
        for (src in MapCanvasView.SOURCES) { names.add("🌐 ${src.name} (online)"); acts.add { pilihOnline(src) } }
        names.add("⬜ Tanpa peta (grid 100 m)")
        acts.add {
            val l = lastLoc
            if (l != null) showMap(MapCanvasView.plainMap(l.latitude, l.longitude)) else toast("Posisi GPS belum didapat")
        }
        if (maps.isEmpty()) {
            names.add("ℹ Cara import peta offline")
            acts.add {
                MaterialAlertDialogBuilder(context).setTitle("Peta offline")
                    .setMessage("Import peta GeoPDF di Pengaturan (ikon ⚙ di tab Laporan) atau tombol Import di tab Riwayat.")
                    .setPositiveButton("OK", null).show()
            }
        }
        MaterialAlertDialogBuilder(context).setTitle("Pilih peta")
            .setItems(names.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun setFollow(on: Boolean) {
        followMe = on
        btnFollow.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) 0xFFFFB74D.toInt() else 0xE6FFFFFF.toInt())
    }

    /** Titik terpilih mengikuti posisi saya (untuk menandai tepat di posisi sendiri). */
    private fun toggleFollow() {
        if (followMe) {
            setFollow(false)
            toast("Titik terpilih tidak lagi mengikuti posisi saya")
            updateInfo()
            return
        }
        val l = lastLoc
        if (l == null) { toast("Posisi GPS belum didapat"); return }
        setFollow(true)
        selected = Pair(l.latitude, l.longitude)
        canvas.setSelected(l.latitude, l.longitude)
        toast("Titik terpilih mengikuti posisi saya. Tekan Tandai untuk menandai di posisi Anda.")
        updateInfo()
    }

    private fun setHiRes(m: MapInfo?) {
        canvas.hiRes = null
        hires?.close()
        hires = null
        if (m == null || m.id == "plain" || m.id == "online") return
        val pf = MapStore.pdfFile(context, m.id)
        if (!pf.exists()) return
        val pr = PdfRegion(pf, m)
        hires = pr
        canvas.hiRes = { a, b, c, d, e, f -> pr.render(a, b, c, d, e, f) }
    }

    private fun ke(what: String) {
        when (what) {
            "me" -> {
                val l = lastLoc
                val m = curMap
                when {
                    !host.hasLocationPermission() -> host.requestLocationPermission()
                    l == null -> toast("Posisi GPS belum didapat. Pastikan lokasi HP aktif dan di area terbuka.")
                    m != null && m.id == "online" && !m.contains(l.latitude, l.longitude) -> {
                        val src = onlineSrc
                        if (src != null) showOnline(src, l.latitude, l.longitude)
                    }
                    m != null && !m.contains(l.latitude, l.longitude) -> {
                        val other = MapStore.pick(context, l.latitude, l.longitude)
                        if (other != null) {
                            pending = { canvas.centerOn(l.latitude, l.longitude, zoomFor(other, 250.0)) }
                            showMap(other)
                        } else toast("Anda berada di luar area peta ini.")
                    }
                    m != null -> canvas.centerOn(l.latitude, l.longitude, zoomFor(m, 250.0))
                }
            }
        }
    }

    private fun updateInfo() {
        val sb = StringBuilder()
        val l = lastLoc
        when {
            !host.hasLocationPermission() -> sb.append("Izin lokasi belum diberikan (tekan ◎ untuk meminta).")
            l == null -> sb.append("Mencari sinyal GPS…")
            else -> {
                sb.append("Posisi saya: ").append(Laporan.koordinatSalin(l.latitude, l.longitude))
                if (l.hasAccuracy()) {
                    sb.append(" (±").append(Laporan.angka(l.accuracy.toDouble(), 0)).append(" m)")
                    if (l.accuracy > MarkingText.ACC_OK) sb.append(" ⚠ kurang akurat")
                    else sb.append(" ✓")
                }
                sb.append("\nUTM: ").append(Coord.utmText(l.latitude, l.longitude))
                if (azimuth >= 0f) sb.append(" • Arah: ").append(azimuth.toInt()).append("° ").append(arah8(azimuth))
                val m = curMap
                if (m != null && m.id != "plain" && !m.contains(l.latitude, l.longitude)) sb.append("\n⚠ Posisi di luar peta ini")
            }
        }
        if (Tracker.active) {
            val p = Tracker.snapshot()
            sb.append(if (Tracker.paused) "\n⏸ Tracking DIJEDA: " else "\n● Tracking: ").append(p.size).append(" titik • ").append(Geo.meter(Geo.length(p)))
        }
        val s = selected
        if (canvas.mode == MapCanvasView.Mode.NORMAL) {
            if (s != null) sb.append("\nTitik terpilih").append(if (followMe) " (ikut posisi saya): " else ": ").append(Laporan.koordinatSalin(s.first, s.second)).append("  UTM ").append(Coord.utmText(s.first, s.second)).append(" (ketuk teks untuk salin)")
        } else {
            sb.append("\n").append(measureText())
        }
        tvInfo.text = sb.toString()
    }

    private fun copySelected() {
        val s = selected ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("koordinat", Laporan.koordinatSalin(s.first, s.second)))
        toast("Koordinat disalin")
    }

    // ---- MapCanvasView.Listener ----

    override fun onMapTap(lat: Double, lon: Double) {
        setFollow(false)
        selected = Pair(lat, lon)
        updateInfo()
    }

    override fun onMapLongPress(lat: Double, lon: Double) {
        setFollow(false)
        selected = Pair(lat, lon)
        updateInfo()
        editor(null, lat, lon, null)
    }

    override fun onMarkerTap(m: Marker) {
        if (m.id.startsWith("tm:")) { timView.rincian(m.id.removePrefix("tm:")); return }
        val ic = MarkerIcons.of(m.icon)
        val l = lastLoc
        val jarak = if (l != null) "\nJarak dari Anda: " + Geo.meter(Geo.dist(TPoint(l.latitude, l.longitude, 0), TPoint(m.lat, m.lon, 0))) else ""
        val pf = if (m.photo.isNotBlank()) MarkerStore.photoFile(context, m.id).takeIf { it.exists() } else null
        val dlgB = MaterialAlertDialogBuilder(context)
        if (pf != null) {
            val iv = ImageView(context)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(16), dp(8), dp(16), 0)
            iv.setImageBitmap(ImageUtil.thumb(pf, 900))
            dlgB.setView(iv)
        }
        dlgB
            .setTitle("${ic.emoji} ${m.title}")
            .setMessage(
                buildString {
                    append("Kategori: ${ic.label}\n")
                    if (m.blok.isNotBlank()) append("Blok: ${m.blok}\n")
                    val v = MarkingText.volumeText(m)
                    if (v.isNotBlank()) append("Volume: $v\n")
                    if (m.note.isNotBlank()) append("Keterangan: ${m.note}\n")
                    append("Tanggal: ${MarkingText.tanggal(m.createdAt)}\n")
                    append("Koordinat: ${Laporan.koordinatSalin(m.lat, m.lon)}")
                    if (m.acc >= 0.0) append("\nAkurasi GPS: ${MarkingText.accText(m)}${if (m.acc > MarkingText.ACC_OK) " ⚠" else ""}")
                    append(jarak)
                }
            )
            .setPositiveButton("Edit") { _, _ -> editor(m, m.lat, m.lon, null) }
            .setNegativeButton("Share") { _, _ -> shareTanda(m, pf) }
            .setNeutralButton("Navigasi") { _, _ -> navigasi(m.lat, m.lon) }
            .show()
    }

    /** Pilihan format berbagi satu tanda: WhatsApp (teks + foto), JSON, KML, KMZ + foto. */
    private fun shareTanda(m: Marker, pf: File?) {
        val labels = arrayOf(
            "WhatsApp (teks + foto)",
            "File JSON (bisa diimport kembali ke aplikasi)",
            "File KML (Google Earth, QGIS, ArcGIS)",
            "File KMZ + foto (Google Earth, QGIS, ArcGIS)",
        )
        MaterialAlertDialogBuilder(context)
            .setTitle("Share tanda: ${m.title}")
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> Share.textWithPhoto(context, MarkingText.pesan(m), pf)
                    1 -> try {
                        val f = File(exportDir(), "tanda_${m.title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(30).ifEmpty { "titik" }}_${stamp()}.json")
                        f.writeText(DataIO.exportAll(emptyList(), listOf(m), context))
                        Share.file(context, f, "application/json", "Bagikan tanda (JSON)")
                    } catch (e: Exception) {
                        toast("Gagal membagikan: ${e.message}")
                    }
                    else -> {
                        val format = if (i == 2) Gis.Format.KML else Gis.Format.KMZ
                        val app = context
                        Thread {
                            val f = try { Gis.export(app, format, listOf(m), emptyList(), exportDir()) } catch (e: Exception) { null }
                            post {
                                if (f != null) Share.file(app, f, format.mime, "Bagikan ${format.ext.uppercase()}")
                                else toast("Gagal membuat file ${format.ext.uppercase()}")
                            }
                        }.start()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onMeasureChanged() { updateInfo() }

    // ---- tombol ----

    /** Akurasi GPS (m) bila titik ini adalah posisi saya (selisih < 3 m); -1 bila dipilih manual di peta. */
    private fun accFor(lat: Double, lon: Double): Double {
        val l = lastLoc ?: return -1.0
        if (!l.hasAccuracy()) return -1.0
        val d = Geo.dist(TPoint(l.latitude, l.longitude, 0), TPoint(lat, lon, 0))
        return if (d < 3.0) l.accuracy.toDouble() else -1.0
    }

    private fun tandai() {
        val s = selected
        val l = lastLoc
        val target: Pair<Double, Double>? = s ?: l?.let { Pair(it.latitude, it.longitude) }
        if (target == null) {
            toast("Ketuk peta untuk memilih lokasi (atau tunggu GPS), lalu tekan Tandai. Tahan lama di peta untuk menandai langsung.")
            return
        }
        val acc = accFor(target.first, target.second)
        if (acc > MarkingText.ACC_OK) {
            MaterialAlertDialogBuilder(context).setTitle("⚠ Akurasi GPS kurang baik")
                .setMessage("Akurasi posisi Anda saat ini ±${Laporan.angka(acc, 0)} m (batas baik ±${Laporan.angka(MarkingText.ACC_OK, 0)} m).\n\n" +
                    "Tanda bisa meleset puluhan meter. Pindah ke area terbuka tanpa kanopi/bangunan, tunggu beberapa detik sampai angka akurasi mengecil, lalu tandai.")
                .setPositiveButton("Tunggu dulu", null)
                .setNegativeButton("Tandai tetap") { _, _ -> editor(null, target.first, target.second, null) }
                .show()
            return
        }
        editor(null, target.first, target.second, null)
    }

    private fun pilihUkur() {
        if (curMap == null) { toast("Peta belum siap"); return }
        MaterialAlertDialogBuilder(context).setTitle("Pengukuran")
            .setItems(arrayOf("📏 Ukur jarak (garis)", "⬛ Ukur luas area (poligon)")) { _, i ->
                canvas.clearMeasure()
                canvas.mode = if (i == 0) MapCanvasView.Mode.UKUR_JARAK else MapCanvasView.Mode.UKUR_LUAS
                barNormal.visibility = View.GONE
                barMeasure.visibility = View.VISIBLE
                toast(if (i == 0) "Ketuk titik-titik di peta untuk mengukur jarak" else "Ketuk sudut-sudut area (min. 3 titik) atau pakai \"+ Posisi Saya\" sambil berjalan di batas area")
                updateInfo()
            }.show()
    }

    private fun measureText(): String {
        val n = canvas.measurePoints.size
        return if (canvas.mode == MapCanvasView.Mode.UKUR_JARAK)
            "Ukur jarak: " + Geo.meter(canvas.measureLength()) + " • $n titik"
        else if (n < 3) "Ukur luas: tambahkan titik ($n/3 minimal)"
        else "Luas: " + Geo.luas(canvas.measureArea()) + " • Keliling: " + Geo.meter(canvas.measurePerimeter()) + " • $n titik"
    }

    private fun ukurPosisiSaya() {
        val l = lastLoc
        if (l == null) { toast("Posisi GPS belum didapat"); return }
        canvas.addMeasurePoint(l.latitude, l.longitude)
    }

    private fun selesaiUkur() {
        canvas.mode = MapCanvasView.Mode.NORMAL
        canvas.clearMeasure()
        barMeasure.visibility = View.GONE
        barNormal.visibility = View.VISIBLE
        updateInfo()
    }

    private fun simpanUkur() {
        val pts = canvas.measurePoints
        val luas = canvas.mode == MapCanvasView.Mode.UKUR_LUAS
        if (pts.size < (if (luas) 3 else 2)) { toast(if (luas) "Minimal 3 titik" else "Minimal 2 titik"); return }
        val lat = pts.map { it.first }.average()
        val lon = pts.map { it.second }.average()
        val preset = if (luas) {
            val ha = Math.round(canvas.measureArea() / 100.0) / 100.0
            Marker("", 0L, "pin", "Luas area", host.defaultBlok(),
                "Keliling ${Geo.meter(canvas.measurePerimeter())}; ${pts.size} titik", ha, "Ha", lat, lon)
        } else {
            val m = Math.round(canvas.measureLength() * 10.0) / 10.0
            Marker("", 0L, "road", "Panjang jarak", host.defaultBlok(), "${pts.size} titik", m, "m", lat, lon)
        }
        editor(null, lat, lon, preset) { selesaiUkur() }
    }

    private fun menuLainnya() {
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        items.add("🔎 Cari koordinat…"); acts.add { cariKoordinat() }
        val s = selected
        if (s != null) {
            items.add("🧭 Navigasi ke titik terpilih"); acts.add { navigasi(s.first, s.second) }
            items.add("📤 Share titik terpilih (WA)"); acts.add {
                Share.text(context, "Koordinat: ${Laporan.koordinatSalin(s.first, s.second)}\n${Laporan.mapsUrl(s.first, s.second)}")
            }
            items.add("Lepas titik terpilih"); acts.add { setFollow(false); selected = null; canvas.clearSelected(); updateInfo() }
        }
        items.add(if (canvas.showHistory) "Sembunyikan riwayat jalur" else "Tampilkan riwayat jalur")
        acts.add { canvas.showHistory = !canvas.showHistory }
        items.add(if (canvas.showLabels) "Sembunyikan judul tanda" else "Tampilkan judul tanda")
        acts.add { canvas.showLabels = !canvas.showLabels }
        val cs = onlineSrc
        if (cs != null) {
            items.add("🎯 Pusatkan peta online di sini"); acts.add {
                val r = selected ?: lastLoc?.let { Pair(it.latitude, it.longitude) }
                if (r != null) showOnline(cs, r.first, r.second) else toast("Pilih titik atau tunggu GPS")
            }
        }
        items.add("🗺 Pilih peta…"); acts.add { pilihPeta() }
        items.add("📥 Import peta / data…"); acts.add { host.importData() }
        MaterialAlertDialogBuilder(context).setTitle("Menu peta")
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun cariKoordinat() {
        val (til, et) = inputField(context, "Koordinat (desimal, DMS, atau UTM)", InputType.TYPE_CLASS_TEXT)
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(til)
        box.addView(TextView(context).apply {
            text = "Contoh:\n• -0.746073,117.415342\n• 0°44'45.8\"LU 117°24'55.2\"BT\n• 50N 512345 82345 (UTM)"
            textSize = 12f
        })
        MaterialAlertDialogBuilder(context).setTitle("Cari koordinat").setView(box)
            .setPositiveButton("Cari") { _, _ ->
                val r = Coord.parse(et.text.toString())
                if (r == null) toast("Format tidak dikenali. Gunakan desimal, DMS, atau UTM.")
                else { toast("Terbaca sebagai ${r.kind}"); goTo(r.lat, r.lon) }
            }.setNegativeButton("Batal", null).show()
    }

    private fun goTo(lat: Double, lon: Double, zoomMeters: Double = 150.0) {
        selectPane(0)
        val cur = curMap
        val src = onlineSrc
        if (cur != null && cur.id == "online" && src != null) {
            if (!cur.contains(lat, lon)) showOnline(src, lat, lon)
            selected = Pair(lat, lon)
            canvas.setSelected(lat, lon)
            canvas.centerOn(lat, lon, zoomFor(curMap ?: cur, zoomMeters))
            updateInfo()
            return
        }
        val target = if (cur != null && cur.contains(lat, lon)) cur else MapStore.pick(context, lat, lon)
        val act = {
            val m = curMap
            if (m != null) {
                selected = Pair(lat, lon)
                canvas.setSelected(lat, lon)
                canvas.centerOn(lat, lon, zoomFor(m, zoomMeters))
                updateInfo()
            }
        }
        when {
            target != null && target.id != cur?.id -> { pending = act; showMap(target) }
            target != null -> act()
            else -> { pending = act; showMap(MapCanvasView.plainMap(lat, lon)) }
        }
    }

    private fun navigasi(lat: Double, lon: Double) {
        val nav = Uri.parse("google.navigation:q=${Laporan.koordinat(lat)},${Laporan.koordinat(lon)}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, nav))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Laporan.mapsUrl(lat, lon))))
            } catch (e2: Exception) {
                toast("Tidak ada aplikasi peta untuk navigasi")
            }
        }
    }

    // =====================================================================
    // Editor tanda
    // =====================================================================

    private fun editor(existing: Marker?, lat: Double, lon: Double, preset: Marker?, onSaved: (() -> Unit)? = null, forceTim: Boolean = false) {
        val src = existing ?: preset
        var iconKey = src?.icon ?: "warn"
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), 0)

        val iconRow = LinearLayout(context)
        iconRow.orientation = HORIZONTAL
        val lbl = TextView(context)
        lbl.textSize = 12f
        lbl.setPadding(0, dp(2), 0, dp(6))
        val views = ArrayList<Pair<String, TextView>>()
        fun paint() {
            for ((k, v) in views) v.setBackgroundColor(if (k == iconKey) 0xFFC8E6C9.toInt() else 0x00000000)
            lbl.text = "Ikon: " + MarkerIcons.of(iconKey).label
        }
        for (ic in MarkerIcons.all) {
            val tv = TextView(context)
            tv.text = ic.emoji
            tv.textSize = 26f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(10), dp(6), dp(10), dp(6))
            tv.setOnClickListener { iconKey = ic.key; paint() }
            views.add(Pair(ic.key, tv))
            iconRow.addView(tv)
        }
        val hs = HorizontalScrollView(context)
        hs.addView(iconRow)
        col.addView(hs)
        col.addView(lbl)

        val (tilJ, etJ) = inputField(context, "Judul tanda", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, src?.title ?: "")
        val (tilB, etB) = inputField(context, "Blok", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            src?.blok?.takeIf { it.isNotBlank() } ?: host.defaultBlok())
        val (tilK, etK) = inputField(context, "Keterangan", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, src?.note ?: "")
        etK.minLines = 2
        val volTxt = if (src != null && src.volume != 0.0) Laporan.angka(src.volume, 3).replace(".", "") else ""
        val (tilV, etV) = inputField(context, "Volume", NUM_TYPE, volTxt)
        val (tilS, etS) = inputField(context, "Satuan (mis. Pokok, Ha, m, Unit)", InputType.TYPE_CLASS_TEXT, src?.unit ?: "")
        col.addView(tilJ); col.addView(tilB); col.addView(tilK)
        val vr = LinearLayout(context)
        vr.orientation = HORIZONTAL
        tilV.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
        tilS.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        vr.addView(tilV); vr.addView(tilS)
        col.addView(vr)

        val chips = LinearLayout(context)
        chips.orientation = HORIZONTAL
        for (u in listOf("Pokok", "Ha", "m", "m²", "Unit", "Kg", "Janjang", "Titik", "Batang")) {
            val c = TextView(context)
            c.text = u
            c.textSize = 12f
            c.setPadding(dp(12), dp(6), dp(12), dp(6))
            c.setBackgroundColor(0xFFE8F5E9.toInt())
            c.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            c.setOnClickListener { etS.setText(u) }
            chips.addView(c)
        }
        val hs2 = HorizontalScrollView(context)
        hs2.addView(chips)
        col.addView(hs2)

        // ---- foto ----
        var newRaw: File? = null
        var removePhoto = false
        val pv = ImageView(context)
        pv.adjustViewBounds = true
        pv.maxHeight = dp(200)
        pv.scaleType = ImageView.ScaleType.FIT_CENTER
        pv.visibility = View.GONE
        val btnHapusFoto = btn("✕ Hapus foto") { }
        fun showPrev() {
            val f: File? = newRaw ?: if (!removePhoto && existing != null && existing.photo.isNotBlank()) {
                MarkerStore.rawFile(context, existing.id).takeIf { it.exists() } ?: MarkerStore.photoFile(context, existing.id)
            } else null
            if (f != null && f.exists()) {
                pv.setImageBitmap(ImageUtil.thumb(f, 600))
                pv.visibility = View.VISIBLE
                btnHapusFoto.visibility = View.VISIBLE
            } else {
                pv.setImageDrawable(null)
                pv.visibility = View.GONE
                btnHapusFoto.visibility = View.GONE
            }
        }
        val photoRow = LinearLayout(context)
        photoRow.orientation = HORIZONTAL
        photoRow.setPadding(0, dp(10), 0, dp(4))
        photoRow.addView(btn("📷 Kamera") {
            host.pickPhoto(true) { f -> if (f != null) { newRaw = f; removePhoto = false; showPrev() } }
        })
        photoRow.addView(btn("🖼 Galeri") {
            host.pickPhoto(false) { f -> if (f != null) { newRaw = f; removePhoto = false; showPrev() } }
        })
        btnHapusFoto.setOnClickListener { newRaw = null; removePhoto = true; showPrev() }
        photoRow.addView(btnHapusFoto)
        col.addView(photoRow)
        col.addView(pv)
        col.addView(TextView(context).apply {
            text = "Foto otomatis diberi watermark (judul, blok, volume, koordinat, tanggal) saat disimpan."
            textSize = 11f
            setTextColor(0xFF777777.toInt())
        })
        showPrev()

        val accVal = existing?.acc ?: accFor(lat, lon)
        col.addView(TextView(context).apply {
            text = "Koordinat: ${Laporan.koordinatSalin(lat, lon)}" + when {
                accVal < 0.0 -> "\nPosisi dipilih manual di peta (akurasi GPS tidak tercatat)"
                accVal > MarkingText.ACC_OK -> "\n⚠ Akurasi GPS ±${Laporan.angka(accVal, 0)} m (kurang baik)"
                else -> "\nAkurasi GPS ±${Laporan.angka(accVal, 0)} m ✓"
            }
            textSize = 12f
            if (accVal > MarkingText.ACC_OK) setTextColor(0xFFE65100.toInt())
            setPadding(0, dp(10), 0, dp(8))
        })

        // ---- kirim ke tim ----
        var cbTim: android.widget.CheckBox? = null
        var etEst: EditText? = null
        var etAfd: EditText? = null
        if (existing == null) {
            val u = Tim.userCache(context)
            val cb = android.widget.CheckBox(context)
            cb.text = "Kirim ke Tim (anggota afdeling langsung menerima)"
            cb.isChecked = forceTim
            col.addView(cb)
            val estDef = prefs.getString("tm_est", null) ?: u?.estate?.firstOrNull { !it.equals("SEMUA", true) } ?: ""
            val afdDef = prefs.getString("tm_afd", null) ?: u?.afdeling?.firstOrNull { !it.equals("SEMUA", true) } ?: ""
            val (tilE, eE) = inputField(context, "Estate", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, estDef)
            val (tilA, eA) = inputField(context, "Afdeling", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, afdDef)
            val er = LinearLayout(context)
            er.orientation = HORIZONTAL
            tilE.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            tilA.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            er.addView(tilE); er.addView(tilA)
            er.visibility = if (cb.isChecked) View.VISIBLE else View.GONE
            cb.setOnCheckedChangeListener { _, on -> er.visibility = if (on) View.VISIBLE else View.GONE }
            col.addView(er)
            cbTim = cb; etEst = eE; etAfd = eA
        }
        paint()

        val sv = ScrollView(context)
        sv.addView(col)
        val dlg = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) "Tandai lokasi" else "Edit tanda")
            .setView(sv)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val title = etJ.text.toString().trim()
            if (title.isEmpty()) { tilJ.error = "Judul wajib diisi"; return@setOnClickListener }
            val kirimTim = cbTim?.isChecked == true
            val estTim = etEst?.text?.toString()?.trim()?.uppercase() ?: ""
            val afdTim = etAfd?.text?.toString()?.trim()?.uppercase() ?: ""
            if (kirimTim) {
                if (!Tim.aktif(context)) { toast("Isi NPK dan URL Web App di Pengaturan dulu."); return@setOnClickListener }
                if (estTim.isEmpty() || afdTim.isEmpty()) { toast("Estate dan Afdeling wajib diisi untuk kirim ke Tim."); return@setOnClickListener }
            }
            val id = existing?.id ?: UUID.randomUUID().toString()
            var m = Marker(
                id,
                existing?.createdAt ?: System.currentTimeMillis(),
                iconKey, title, etB.text.toString().trim().uppercase(), etK.text.toString().trim(),
                etV.num(), etS.text.toString().trim(), lat, lon,
                if (removePhoto) "" else (existing?.photo ?: ""),
                accVal,
            )
            if (removePhoto) MarkerStore.deletePhoto(context, id)
            val nr = newRaw
            if (nr != null) {
                try { nr.copyTo(MarkerStore.rawFile(context, id), overwrite = true); nr.delete(); m = m.copy(photo = "$id.jpg") } catch (e: Exception) { toast("Foto gagal disimpan") }
            }
            if (m.photo.isNotBlank()) {
                if (MarkerStore.rawFile(context, id).exists()) {
                    if (!MarkerStore.renderPhoto(context, m)) toast("Watermark foto gagal")
                }
                if (!MarkerStore.photoFile(context, id).exists()) m = m.copy(photo = "")
            }
            MarkerStore.save(context, m)
            if (kirimTim) {
                prefs.edit().putString("tm_est", estTim).putString("tm_afd", afdTim).apply()
                TimView.dariTanda(context, m, estTim, afdTim)
            }
            dlg.dismiss()
            toast(if (kirimTim) "Tersimpan dan dikirim ke Tim" else "Tanda tersimpan")
            reloadOverlays()
            refreshRekap()
            updateTimTab()
            if (kirimTim) timView.sinkron(true)
            onSaved?.invoke()
        }
    }

    private fun hapusMarker(m: Marker) {
        MaterialAlertDialogBuilder(context).setTitle("Hapus tanda?")
            .setMessage("\"${m.title}\" akan dihapus dari HP.")
            .setPositiveButton("Hapus") { _, _ -> MarkerStore.remove(context, m.id); reloadOverlays(); refreshRekap() }
            .setNegativeButton("Batal", null).show()
    }

    // =====================================================================
    // Pane 2: riwayat tracking
    // =====================================================================

    private fun buildHistoryPane() {
        historyPane.orientation = VERTICAL
        val top = LinearLayout(context)
        top.orientation = HORIZONTAL
        top.setPadding(dp(12), dp(10), dp(12), dp(2))
        top.addView(btn("Import") { host.importData() })
        top.addView(btn("Share Database") { shareDatabase() })
        top.addView(btn("Share File Peta") { shareFilePeta() })
        historyPane.addView(top)
        val top2 = LinearLayout(context)
        top2.orientation = HORIZONTAL
        top2.setPadding(dp(12), dp(2), dp(12), dp(2))
        top2.addView(btn("Export GIS (KML, SHP, GPX, GeoJSON)") { exportGis(MarkerStore.all(context), TrackStore.all(context), "semua tanda & tracking") })
        historyPane.addView(top2)
        historyPane.addView(TextView(context).apply {
            text = "Tracking, tanda, dan peta tersimpan di HP dan tidak ikut diupload. Share Database = cadangan tracking + tanda (file .json). Share File Peta = peta offline untuk HP lain. Import menerima keduanya."
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(16), dp(2), dp(16), dp(6))
        })
        historyList.orientation = VERTICAL
        historyList.setPadding(dp(16), dp(4), dp(16), dp(32))
        historyPane.addView(ScrollView(context).apply {
            addView(historyList)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
    }

    private fun exportDir() = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

    private fun shareDatabase() {
        val t = TrackStore.all(context)
        val m = MarkerStore.all(context)
        if (t.isEmpty() && m.isEmpty()) { toast("Belum ada data tracking / tanda"); return }
        try {
            val f = File(exportDir(), "ckp_database_peta_${stamp()}.json")
            f.writeText(DataIO.exportAll(t, m, context))
            Share.file(context, f, "application/json", "Bagikan database peta")
        } catch (e: Exception) {
            toast("Gagal membagikan: ${e.message}")
        }
    }

    private fun shareFilePeta() {
        val maps = MapStore.all(context)
        if (maps.isEmpty()) { toast("Belum ada peta offline"); return }
        fun kirim(m: MapInfo) {
            toast("Menyiapkan file peta…")
            val app = context
            Thread {
                val f = File(exportDir(), "peta_${m.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.ckpmap.zip")
                val ok = try { MapStore.exportZip(app, m.id, f) } catch (e: Exception) { false }
                post {
                    if (ok) Share.file(app, f, "application/zip", "Bagikan file peta")
                    else toast("Gagal menyiapkan file peta")
                }
            }.start()
        }
        if (maps.size == 1) kirim(maps[0])
        else MaterialAlertDialogBuilder(context).setTitle("Bagikan peta mana?")
            .setItems(maps.map { it.name }.toTypedArray()) { _, i -> kirim(maps[i]) }.show()
    }

    private fun judul(r: TrackRecord) = "Blok ${r.blok.ifBlank { "-" }}"

    private fun ringkas(r: TrackRecord): String {
        val tgl = SimpleDateFormat("dd MMM yyyy HH:mm", idn).format(Date(r.startedAt))
        val mnt = r.durationMs / 60000
        return "$tgl\n${listOf(r.est, r.afd).filter { it.isNotBlank() }.joinToString(" • ")}\n" +
            "Jarak ${Geo.km(r.distance)} • $mnt menit • ${r.points.size} titik"
    }

    fun refreshHistory() {
        historyList.removeAllViews()
        val items = TrackStore.all(context)
        if (items.isEmpty()) {
            historyList.addView(TextView(context).apply {
                text = "Belum ada riwayat tracking.\nTekan tombol merah \"Tracking\" di tab Peta atau \"Start Tracking\" di tab Laporan."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }
        for (r in items) historyList.addView(kartu(r))
    }

    private fun kartu(r: TrackRecord): MaterialCardView {
        val card = MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFE8F5E9.toInt())
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        col.addView(TextView(context).apply {
            text = judul(r); textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF1B5E20.toInt())
        })
        col.addView(TextView(context).apply { text = ringkas(r); textSize = 13f; setPadding(0, dp(4), 0, dp(6)) })
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFDDE8DD.toInt())
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
            tag = r.id
        }
        col.addView(iv)
        Thread {
            val bmp = TrackRender.render(context, MapStore.get(context, r.mapId), r.points, 640, 300)
            iv.post { if (iv.tag == r.id) iv.setImageBitmap(bmp) }
        }.start()
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        row.addView(btn("Lihat") { lihat(r) })
        row.addView(btn("Di Peta") { diPeta(r) })
        row.addView(btn("Share") { exportOne(r) })
        row.addView(btn("Hapus") { hapus(r) })
        col.addView(row)
        card.addView(col)
        return card
    }

    private fun diPeta(r: TrackRecord) {
        if (r.points.isEmpty()) return
        val mid = r.points[r.points.size / 2]
        val cur = curMap
        val target = MapStore.get(context, r.mapId) ?: MapStore.pick(context, mid.lat, mid.lon)
        selectPane(0)
        val act = { canvas.fitBounds(r.points) }
        val use = target ?: MapCanvasView.plainMap(mid.lat, mid.lon)
        if (cur == null || cur.id != use.id) { pending = act; showMap(use) } else act()
    }

    private fun lihat(r: TrackRecord) {
        val iv = ImageView(context).apply {
            adjustViewBounds = true
            setBackgroundColor(0xFFDDE8DD.toInt())
            minimumHeight = dp(260)
        }
        val luas = if (r.points.size >= 3) "\nPerkiraan luas jika jalur ditutup: " + Geo.luas(Geo.areaM2(r.points)) else ""
        val d = MaterialAlertDialogBuilder(context)
            .setTitle(judul(r))
            .setMessage(ringkas(r) + luas)
            .setView(iv)
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Share Gambar", null)
            .create()
        d.show()
        var shownBmp: Bitmap? = null
        Thread {
            val bmp = TrackRender.render(context, MapStore.get(context, r.mapId), r.points, 1280, 900,
                "Blok ${r.blok.ifBlank { "-" }} • ${Geo.km(r.distance)}")
            iv.post { shownBmp = bmp; iv.setImageBitmap(bmp) }
        }.start()
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val b = shownBmp ?: return@setOnClickListener
            try {
                val f = File(exportDir(), "tracking_${r.blok.ifBlank { "blok" }}_${r.startedAt}.jpg")
                FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                Share.file(context, f, "image/jpeg", "Bagikan gambar tracking")
            } catch (e: Exception) {
                toast("Gagal membagikan: ${e.message}")
            }
        }
    }

    private fun exportOne(r: TrackRecord) {
        try {
            val f = File(exportDir(), "tracking_${r.blok.ifBlank { "blok" }}_${r.startedAt}.json")
            f.writeText(DataIO.exportAll(listOf(r), emptyList()))
            Share.file(context, f, "application/json", "Bagikan data tracking")
        } catch (e: Exception) {
            toast("Gagal membagikan: ${e.message}")
        }
    }

    private fun hapus(r: TrackRecord) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Hapus tracking?")
            .setMessage("Tracking ${judul(r)} (${ringkas(r).lines().first()}) akan dihapus dari HP. Pastikan sudah dicadangkan jika masih diperlukan.")
            .setPositiveButton("Hapus") { _, _ -> TrackStore.remove(context, r.id); refreshHistory(); reloadOverlays() }
            .setNegativeButton("Batal", null)
            .show()
    }

    // =====================================================================
    // Pane 3: rekap tanda
    // =====================================================================

    private fun buildRekapPane() {
        rekapPane.orientation = VERTICAL
        val (til, et) = inputField(context, "Cari judul, blok, keterangan, satuan, kategori…", InputType.TYPE_CLASS_TEXT)
        etSearch = et
        til.setPadding(dp(12), dp(8), dp(12), 0)
        et.watch { refreshRekap() }
        rekapPane.addView(til)

        val filt = LinearLayout(context)
        filt.orientation = HORIZONTAL
        filt.setPadding(dp(12), 0, dp(12), 0)
        btnKategori = btn("Semua kategori") { pilihKategori() }
        btnUrut = btn("Urut: Terbaru") {
            if (!urutDekat && lastLoc == null) toast("Urut terdekat butuh posisi GPS")
            else { urutDekat = !urutDekat; refreshRekap() }
        }
        filt.addView(btnKategori)
        filt.addView(btnUrut)
        rekapPane.addView(filt)

        val acts = LinearLayout(context)
        acts.orientation = HORIZONTAL
        acts.setPadding(dp(12), dp(2), dp(12), 0)
        acts.addView(btn("Share WA") { shareRekapWa() })
        acts.addView(btn("Export CSV") { shareCsv() })
        acts.addView(btn("Share Database") { shareDatabase() })
        rekapPane.addView(acts)
        val acts2 = LinearLayout(context)
        acts2.orientation = HORIZONTAL
        acts2.setPadding(dp(12), 0, dp(12), 0)
        acts2.addView(btn("Export GIS (KML, SHP, GPX, GeoJSON)") { exportGis(rekapShown, emptyList(), "tanda yang tampil") })
        rekapPane.addView(acts2)

        tvRekapInfo.textSize = 13f
        tvRekapInfo.setTypeface(null, Typeface.BOLD)
        tvRekapInfo.setTextColor(0xFF1B5E20.toInt())
        tvRekapInfo.setPadding(dp(16), dp(8), dp(16), dp(4))
        rekapPane.addView(tvRekapInfo)

        rekapList.orientation = VERTICAL
        rekapList.setPadding(dp(16), dp(4), dp(16), dp(32))
        rekapPane.addView(ScrollView(context).apply {
            addView(rekapList)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
    }

    private fun pilihKategori() {
        val opts = listOf<MarkerIcon?>(null) + MarkerIcons.all
        MaterialAlertDialogBuilder(context).setTitle("Filter kategori")
            .setItems(opts.map { if (it == null) "Semua kategori" else "${it.emoji} ${it.label}" }.toTypedArray()) { _, i ->
                kategori = opts[i]?.key
                btnKategori.text = if (opts[i] == null) "Semua kategori" else "${opts[i]!!.emoji} ${opts[i]!!.label}"
                refreshRekap()
            }.show()
    }

    fun filtered(): List<Marker> {
        val q = etSearch.text.toString().trim().lowercase()
        val terms = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var list = MarkerStore.all(context).filter { m ->
            (kategori == null || m.icon == kategori) && run {
                val hay = (m.title + " " + m.blok + " " + m.note + " " + m.unit + " " + MarkerIcons.of(m.icon).label + " " +
                    MarkingText.tanggal(m.createdAt)).lowercase()
                terms.all { hay.contains(it) }
            }
        }
        val l = lastLoc
        if (urutDekat && l != null) {
            val me = TPoint(l.latitude, l.longitude, 0)
            list = list.sortedBy { Geo.dist(me, TPoint(it.lat, it.lon, 0)) }
        }
        return list
    }

    fun refreshRekap() {
        if (!::etSearch.isInitialized) return
        val list = filtered()
        rekapShown = list
        btnUrut.text = if (urutDekat) "Urut: Terdekat" else "Urut: Terbaru"
        val total = MarkerStore.all(context).size
        val t = MarkingText.totals(list)
        tvRekapInfo.text = "${list.size} dari $total tanda" + (if (t.isNotBlank()) " • Total: $t" else "")
        tabs.getTabAt(2)?.text = if (total > 0) "Rekap ($total)" else "Rekap"
        rekapList.removeAllViews()
        if (list.isEmpty()) {
            rekapList.addView(TextView(context).apply {
                text = if (total == 0) "Belum ada tanda.\nDi tab Peta: ketuk peta lalu tekan \"Tandai\", atau tahan lama di peta."
                else "Tidak ada tanda yang cocok dengan pencarian."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        for (m in list) rekapList.addView(kartuMarker(m))
    }

    private fun kartuMarker(m: Marker): MaterialCardView {
        val ic = MarkerIcons.of(m.icon)
        val card = MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFFFF8E1.toInt())
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        col.addView(TextView(context).apply {
            text = "${ic.emoji} ${m.title}"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF4E342E.toInt())
        })
        val l = lastLoc
        val line = listOf(
            ic.label,
            if (m.blok.isNotBlank()) "Blok ${m.blok}" else "",
            MarkingText.volumeText(m).let { if (it.isNotBlank()) "Volume $it" else "" },
        ).filter { it.isNotBlank() }.joinToString(" • ")
        col.addView(TextView(context).apply { text = line; textSize = 13f; setPadding(0, dp(3), 0, 0) })
        if (m.note.isNotBlank()) col.addView(TextView(context).apply { text = m.note; textSize = 13f; setPadding(0, dp(2), 0, 0) })
        val jarak = if (l != null) " • " + Geo.meter(Geo.dist(TPoint(l.latitude, l.longitude, 0), TPoint(m.lat, m.lon, 0))) + " dari Anda" else ""
        col.addView(TextView(context).apply {
            text = MarkingText.tanggal(m.createdAt) + " • " + Laporan.koordinatSalin(m.lat, m.lon) + jarak
            textSize = 11f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(3), 0, dp(4))
        })
        if (m.photo.isNotBlank()) {
            val pf = MarkerStore.photoFile(context, m.id)
            if (pf.exists()) col.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFEEEEEE.toInt())
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)).apply { bottomMargin = dp(6) }
                setImageBitmap(ImageUtil.thumb(pf, 640))
            })
        }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(btn("Di Peta") { goTo(m.lat, m.lon) })
        row.addView(btn("Edit") { editor(m, m.lat, m.lon, null) })
        row.addView(btn("Share") {
            Share.textWithPhoto(context, MarkingText.pesan(m), if (m.photo.isNotBlank()) MarkerStore.photoFile(context, m.id) else null)
        })
        row.addView(btn("Hapus") { hapusMarker(m) })
        col.addView(row)
        card.addView(col)
        return card
    }

    private fun shareRekapWa() {
        val list = rekapShown
        if (list.isEmpty()) { toast("Tidak ada tanda untuk dibagikan"); return }
        Share.text(context, MarkingText.rekap(list))
    }

    /** Export ke format GIS (QGIS / ArcGIS / Google Earth / Avenza). */
    private fun exportGis(markers: List<Marker>, tracks: List<TrackRecord>, label: String) {
        if (markers.isEmpty() && tracks.none { it.points.size >= 2 }) { toast("Belum ada data untuk diexport"); return }
        val fm = Gis.Format.values()
        MaterialAlertDialogBuilder(context)
            .setTitle("Export $label (${markers.size} tanda, ${tracks.count { it.points.size >= 2 }} tracking)")
            .setItems(fm.map { it.label }.toTypedArray()) { _, i ->
                val format = fm[i]
                val app = context
                Thread {
                    val f = try { Gis.export(app, format, markers, tracks, exportDir()) } catch (e: Exception) { null }
                    post {
                        if (f != null) Share.file(app, f, format.mime, "Bagikan ${format.ext.uppercase()}")
                        else toast("Gagal membuat file export")
                    }
                }.start()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun shareCsv() {
        val list = rekapShown
        if (list.isEmpty()) { toast("Tidak ada tanda untuk diexport"); return }
        try {
            val f = File(exportDir(), "rekap_marking_${stamp()}.csv")
            f.writeText(MarkingText.csv(list), Charsets.UTF_8)
            Share.file(context, f, "text/csv", "Bagikan rekap CSV")
        } catch (e: Exception) {
            toast("Gagal export: ${e.message}")
        }
    }
}
