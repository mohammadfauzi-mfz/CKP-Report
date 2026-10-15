package id.dsngroup.ckpreport

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etEst: TextInputEditText
    private lateinit var etAfd: TextInputEditText
    private lateinit var etBlok: TextInputEditText
    private lateinit var etProblem: TextInputEditText
    private lateinit var etPerbaikan: TextInputEditText
    private lateinit var tvGps: TextView
    private lateinit var tvPt: TextView
    private lateinit var activities: ActivityForm
    private lateinit var lux: LuxCard
    private lateinit var thumbs: LinearLayout
    private lateinit var tvFotoInfo: TextView
    private lateinit var btnUpload: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var loc: LocationHelper
    private val photos = mutableListOf<File>()
    private lateinit var queue: QueueView
    private lateinit var tabs: TabLayout
    private lateinit var formScroll: ScrollView
    private lateinit var peta: PetaView
    private lateinit var btnTrack: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var trackBox: LinearLayout
    private lateinit var tvTrackInfo: TextView
    private lateinit var ivTrack: ImageView
    private lateinit var cbTrack: MaterialCheckBox
    private var lastTrack: TrackRecord? = null
    private var rendering = false
    private var coordOverride: Pair<Double, Double>? = null     // koordinat dari draf (sampai "Ambil Ulang GPS")
    private var editingDraftId: String? = null
    private var tvMapLabel: TextView? = null
    private val trackHandler = Handler(Looper.getMainLooper())

    private val photoDir by lazy { File(cacheDir, "photos").apply { mkdirs() } }
    private val cameraFile by lazy { File(photoDir, "camera.jpg") }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) addPhoto { FileInputStream(cameraFile) }
    }
    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        for (uri in uris) {
            if (photos.size >= ImageUtil.MAX_PHOTOS) {
                Toast.makeText(this, "Maksimal ${ImageUtil.MAX_PHOTOS} foto", Toast.LENGTH_SHORT).show()
                break
            }
            addPhoto { contentResolver.openInputStream(uri) }
        }
    }
    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startGps()
        if (::tabs.isInitialized && tabs.selectedTabPosition == 2) peta.onShown()
    }
    private val importMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doImportMap(uri)
    }
    private val importTracks = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doImportTracks(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ckp", Context.MODE_PRIVATE)
        loc = LocationHelper(this) { showLocation(it) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2E7D32.toInt())
            setPadding(dp(16), dp(14), dp(8), dp(12))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(this).apply {
            text = "CKP SPOT REPORT"; textSize = 20f
            setTypeface(null, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        tvPt = TextView(this).apply {
            text = prefs.getString("pt", Laporan.DEFAULT_PT); textSize = 14f; setTextColor(0xFFE8F5E9.toInt())
        }
        titles.addView(tvPt)
        header.addView(titles)
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Pengaturan"
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { showSettings() }
        })
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_info)
            contentDescription = "Informasi"
            setBackgroundColor(0x00000000)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, AboutActivity::class.java)) }
        })
        root.addView(header)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        // Est / Afd / Blok
        val (tEst, eEst) = field("Est", prefs.getString("est", "") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val (tAfd, eAfd) = field("Afd", prefs.getString("afd", "") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val (tBlok, eBlok) = field("Blok", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        etEst = eEst; etAfd = eAfd; etBlok = eBlok
        box.addView(tEst); box.addView(tAfd); box.addView(tBlok)

        // Tracking peta CKP
        btnTrack = MaterialButton(this).apply {
            text = "Start Tracking"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFFD32F2F.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                .apply { topMargin = dp(4); bottomMargin = dp(4) }
            setOnClickListener { toggleTracking() }
        }
        box.addView(btnTrack)
        trackBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tvTrackInfo = TextView(this).apply { textSize = 13f; setPadding(0, dp(2), 0, dp(4)) }
        trackBox.addView(tvTrackInfo)
        ivTrack = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(0xFFDDE8DD.toInt())
            minimumHeight = dp(160)
        }
        trackBox.addView(ivTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cbTrack = MaterialCheckBox(this).apply {
            text = "Lampirkan tracking pada foto (kolase)"
            textSize = 14f
            isChecked = true
            visibility = View.GONE
        }
        trackBox.addView(cbTrack)
        box.addView(trackBox)

        // Activity (Losses / Grading TPH / Kalibrasi BJR)
        activities = ActivityForm(this) { loc.best }
        box.addView(activities)

        // Problem
        box.addView(section("Problem / Temuan"))
        val (tPro, ePro) = field("Tuliskan temuan di lapangan",
            "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        ePro.minLines = 3
        ePro.gravity = Gravity.TOP
        etProblem = ePro
        box.addView(tPro)

        // Perbaikan (opsional)
        box.addView(section("Perbaikan :"))
        val (tPer, ePer) = field("Tuliskan perbaikan yang dilakukan atau direncanakan",
            "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        ePer.minLines = 3
        ePer.gravity = Gravity.TOP
        etPerbaikan = ePer
        box.addView(tPer)

        // Lux Meter
        box.addView(section("Pengukur Cahaya"))
        lux = LuxCard(this, prefs)
        box.addView(lux)

        // Koordinat
        box.addView(section("Koordinat (diambil dengan GPS)"))
        tvGps = TextView(this).apply {
            text = "Mencari sinyal GPS..."
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFE8F5E9.toInt())
            setOnClickListener { copyCoordinate() }
        }
        box.addView(tvGps)
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Ambil Ulang GPS"
            setOnClickListener { coordOverride = null; requestGps() }
        })

        // Foto
        box.addView(section("Foto / Dokumentasi"))
        tvFotoInfo = TextView(this).apply {
            text = "Maks. ${ImageUtil.MAX_PHOTOS} foto. Jika lebih dari 1, otomatis dibuat kolase."
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        box.addView(tvFotoInfo)
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(HorizontalScrollView(this).apply {
            addView(thumbs)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
        val rowFoto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowFoto.addView(MaterialButton(this).apply {
            text = "Foto Langsung"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) }
            setOnClickListener { openCamera() }
        })
        rowFoto.addView(MaterialButton(this).apply {
            text = "Pilih dari HP"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) }
            setOnClickListener {
                if (photos.size >= ImageUtil.MAX_PHOTOS) toast("Maksimal ${ImageUtil.MAX_PHOTOS} foto")
                else pickImages.launch("image/*")
            }
        })
        box.addView(rowFoto)

        // Upload
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        box.addView(progress)
        btnUpload = MaterialButton(this).apply {
            text = "Upload & Share"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(24) }
            setOnClickListener { doUpload() }
        }
        box.addView(btnUpload)
        btnSave = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Hanya Simpan (koreksi dulu, kirim nanti)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { saveDraft() }
        }
        box.addView(btnSave)

        formScroll = ScrollView(this).apply { addView(box) }
        queue = QueueView(this) { loadDraft(it) }
        queue.visibility = View.GONE
        peta = PetaView(this, object : PetaHost {
            override fun importData() { importTracks.launch(arrayOf("*/*")) }
            override fun toggleTracking() { toggleTrackingFromPeta() }
            override fun hasLocationPermission() = this@MainActivity.hasLocationPermission()
            override fun requestLocationPermission() { requestGps() }
            override fun defaultBlok() = etBlok.text.toString().trim()
        })
        peta.visibility = View.GONE

        tabs = TabLayout(this)
        tabs.addTab(tabs.newTab().setText("Laporan"))
        tabs.addTab(tabs.newTab().setText("Antrean"))
        tabs.addTab(tabs.newTab().setText("Peta"))
        root.addView(tabs)

        val frame = FrameLayout(this)
        frame.addView(formScroll)
        frame.addView(queue)
        frame.addView(peta)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                formScroll.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                queue.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                peta.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
                if (tab.position == 1) queue.refresh()
                if (tab.position == 2) peta.onShown() else peta.onHidden()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        setContentView(root)

        if (Tracker.active) {
            trackBox.visibility = View.VISIBLE
            updateTrackUi()
            trackHandler.post(trackTick)
        }
        if (prefs.getString("url", "").isNullOrBlank()) showSettings()
        if (savedInstanceState == null && !hasLocationPermission()) requestGps()
    }

    override fun onStart() {
        super.onStart()
        ReportStore.onChange = { queue.refresh(); updateBadge() }
        queue.refresh()
        updateBadge()
    }

    override fun onStop() {
        super.onStop()
        ReportStore.onChange = null
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission() && loc.best == null) startGps(false)
        if (ReportStore.pendingCount(this) > 0) Sync.schedule(this)
    }

    private fun updateBadge() {
        val n = ReportStore.pendingCount(this)
        tabs.getTabAt(1)?.text = if (n > 0) "Antrean ($n)" else "Antrean"
    }

    override fun onDestroy() {
        trackHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        lux.stop()
        loc.stop()
    }

    // ---------- UI helpers ----------

    private fun field(hint: String, value: String, type: Int): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        til.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        val et = TextInputEditText(til.context)
        et.inputType = type
        et.setText(value)
        til.addView(et)
        return Pair(til, et)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    // ---------- GPS ----------

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestGps() {
        if (hasLocationPermission()) startGps(true)
        else {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permReq.launch(perms.toTypedArray())
        }
    }

    private fun startGps(openSettings: Boolean = false) {
        if (!hasLocationPermission()) {
            tvGps.text = "Izin lokasi belum diberikan. Tekan \"Ambil Ulang GPS\" dan pilih Izinkan."
            return
        }
        tvGps.text = "Mencari sinyal GPS..."
        if (!loc.start()) {
            tvGps.text = "Lokasi/GPS HP belum aktif. Aktifkan lokasi, lalu tekan \"Ambil Ulang GPS\"."
            if (openSettings) {
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) {}
            }
        }
    }

    private fun currentLatLon(): Pair<Double, Double>? =
        coordOverride ?: loc.best?.let { Pair(it.latitude, it.longitude) }

    private fun showLocation(l: android.location.Location) {
        if (coordOverride != null) return
        tvGps.text = "Latitude : ${Laporan.koordinat(l.latitude)}\n" +
            "Longitude : ${Laporan.koordinat(l.longitude)}\n" +
            "Akurasi : ±${l.accuracy.toInt()} m\n" +
            "Ketuk kotak ini untuk menyalin: ${Laporan.koordinatSalin(l.latitude, l.longitude)}"
    }

    private fun copyCoordinate() {
        val ll = currentLatLon()
        if (ll == null) { toast("Koordinat belum didapat"); return }
        val t = Laporan.koordinatSalin(ll.first, ll.second)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Koordinat", t))
        toast("Koordinat disalin: $t")
    }

    // ---------- Foto ----------

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun openCamera() {
        if (photos.size >= ImageUtil.MAX_PHOTOS) { toast("Maksimal ${ImageUtil.MAX_PHOTOS} foto"); return }
        try {
            cameraFile.delete()
            cameraFile.createNewFile()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", cameraFile)
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera tidak dapat dibuka: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addPhoto(open: () -> InputStream?) {
        val f = File(photoDir, "p_${System.currentTimeMillis()}_${photos.size}.jpg")
        if (ImageUtil.prepare(open, f)) {
            photos.add(f)
            renderThumbs()
        } else {
            toast("Foto tidak dapat dibaca. Coba lagi.")
        }
    }

    private fun renderThumbs() {
        thumbs.removeAllViews()
        for (f in photos.toList()) {
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { rightMargin = dp(8) }
            }
            cell.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(ImageUtil.thumb(f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(TextView(this).apply {
                text = "✕"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC000000.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    photos.remove(f)
                    f.delete()
                    renderThumbs()
                }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            thumbs.addView(cell)
        }
        tvFotoInfo.text = when {
            photos.isEmpty() -> "Maks. ${ImageUtil.MAX_PHOTOS} foto. Jika lebih dari 1, otomatis dibuat kolase."
            photos.size == 1 -> "1 foto"
            else -> "${photos.size} foto - akan dijadikan kolase saat Upload"
        }
    }

    // ---------- Upload ----------

    private fun doUpload() {
        val est = etEst.text.toString().trim()
        val afd = etAfd.text.toString().trim()
        val blok = etBlok.text.toString().trim()
        val problem = etProblem.text.toString().trim()
        val ll = currentLatLon()
        val msg = when {
            Tracker.active -> "Tracking masih berjalan. Tekan Stop Tracking dulu."
            est.isEmpty() -> "Est wajib diisi"
            afd.isEmpty() -> "Afd wajib diisi"
            blok.isEmpty() -> "Blok wajib diisi"
            problem.isEmpty() -> "Problem / Temuan wajib diisi"
            ll == null -> "Koordinat GPS belum didapat. Tunggu sebentar atau tekan Ambil Ulang GPS."
            photos.isEmpty() -> "Foto wajib diambil / dipilih"
            else -> activities.validate()
        }
        if (msg != null) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); return }
        ll!!

        val snapshot = photos.toList()
        val trackRec = if (cbTrack.isChecked && cbTrack.visibility == View.VISIBLE) lastTrack else null
        val draftId = editingDraftId
        val pelapor = prefs.getString("pelapor", "") ?: ""
        val lat = ll.first
        val lon = ll.second
        val losses = activities.losses()
        val grading = activities.grading()
        val kalibrasi = activities.kalibrasi()
        val luxVal = lux.saved
        val perbaikan = etPerbaikan.text.toString().trim()
        prefs.edit().putString("est", est).putString("afd", afd).apply()

        btnUpload.isEnabled = false
        btnUpload.text = "Mengupload..."
        progress.visibility = View.VISIBLE
        val app = applicationContext
        Thread {
            // 1) Buat kolase (jika >1 foto) dan simpan dulu ke HP supaya tidak hilang walau tidak ada sinyal
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val dest = File(ReportStore.photoDir(app), "$id.jpg")
            // Tangkapan area tracking (jalur merah di atas peta offline) menjadi baris terakhir kolase
            var capture: File? = null
            if (trackRec != null) {
                try {
                    val bmp = TrackRender.render(app, MapStore.get(app, trackRec.mapId), trackRec.points, 1280, 600,
                        "Tracking Blok $blok • ${Geo.km(trackRec.distance)}")
                    val cf = File(app.cacheDir, "track_capture_$id.jpg")
                    java.io.FileOutputStream(cf).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    bmp.recycle()
                    capture = cf
                } catch (e: Exception) {
                } catch (e: OutOfMemoryError) {
                }
            }
            val okCollage = ImageUtil.collage(snapshot, dest, extra = capture)
            capture?.delete()
            if (!okCollage) {
                runOnUiThread {
                    btnUpload.isEnabled = true
                    btnUpload.text = "Upload & Share"
                    progress.visibility = View.GONE
                    toast("Gagal memproses foto. Coba lagi.")
                }
                return@Thread
            }
            // Watermark: Blok | koordinat | Lux | tanggal & jam (satu baris di tepi bawah foto)
            val luxTeks = if (luxVal >= 0) "${Laporan.angka(luxVal, 0)} Lux" else "Lux -"
            val waktuTeks = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("in", "ID")).format(java.util.Date(now))
            ImageUtil.watermark(dest, "Blok $blok  |  ${Laporan.koordinatSalin(lat, lon)}  |  $luxTeks  |  $waktuTeks")
            ReportStore.add(app, Report(
                id = id, createdAt = now,
                est = est, afd = afd, blok = blok, problem = problem,
                lat = lat, lon = lon, pelapor = pelapor, photoPath = dest.path,
                losses = losses, grading = grading, kalibrasi = kalibrasi, lux = luxVal, perbaikan = perbaikan,
            ))

            if (draftId != null) DraftStore.remove(app, draftId)   // draf sudah menjadi laporan

            // 2) Coba upload sekarang; jika tidak ada sinyal, masuk antrean
            val outcome = if (Sync.hasNetwork(app)) Sync.uploadOne(app, id) else Sync.Outcome.NETWORK
            if (outcome != Sync.Outcome.OK) Sync.schedule(app)
            runOnUiThread {
                btnUpload.isEnabled = true
                btnUpload.text = "Upload & Share"
                progress.visibility = View.GONE
                editingDraftId = null
                queue.refresh()
                showResult(id, outcome)
            }
        }.start()
    }

    private fun showResult(id: String, outcome: Sync.Outcome) {
        val r = ReportStore.get(this, id)
        val (title, message) = when (outcome) {
            Sync.Outcome.OK -> Pair(
                "Berhasil terupload ✅",
                "Data tercatat di spreadsheet dan foto tersimpan di Google Drive.\n\nBagikan ke grup WhatsApp?"
            )
            Sync.Outcome.NETWORK -> Pair(
                "Tersimpan di Antrean 📥",
                "Sinyal belum tersedia. Laporan sudah aman tersimpan di HP dan akan terupload otomatis saat ada sinyal.\n\nAnda tetap bisa membagikannya ke WhatsApp sekarang."
            )
            else -> Pair(
                "Tersimpan di Antrean, upload gagal",
                (r?.error ?: "Upload gagal") + "\n\nLaporan aman di HP. Periksa Pengaturan lalu buka tab Antrean untuk mengupload ulang.\n\nAnda tetap bisa membagikannya ke WhatsApp sekarang."
            )
        }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Share ke WhatsApp", null)
            .setNegativeButton("Selesai", null)
            .create()
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            ReportStore.get(this, id)?.let { Share.whatsApp(this, it) }
        }
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            d.dismiss()
            resetForm()
        }
    }

    private fun resetForm() {
        etBlok.setText("")
        etProblem.setText("")
        etPerbaikan.setText("")
        activities.reset()
        lux.reset()
        photos.forEach { it.delete() }
        photos.clear()
        renderThumbs()
        lastTrack = null
        editingDraftId = null
        coordOverride = null
        if (!Tracker.active) trackBox.visibility = View.GONE
        updateTrackUi()
        requestGps()
    }

    // ---------- Tracking ----------

    private val trackTick = object : Runnable {
        override fun run() {
            if (!Tracker.active) return
            val err = Tracker.gpsError
            if (err != null) {
                toast(err)
                cancelTracking()
                return
            }
            val pts = Tracker.snapshot()
            tvTrackInfo.text = "● Tracking aktif • ${pts.size} titik • ${Geo.km(Geo.length(pts))}"
            renderPreview(pts, null)
            trackHandler.postDelayed(this, 2000)
        }
    }

    private fun toggleTracking() {
        if (Tracker.active) stopTracking() else startTracking()
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            toast("Izinkan akses lokasi dulu, lalu tekan Start Tracking lagi.")
            requestGps()
            return
        }
        Tracker.start()
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        } catch (e: Exception) {
            Tracker.stop()
            toast("Tracking tidak dapat dimulai: ${e.message}")
            return
        }
        lastTrack = null
        cbTrack.visibility = View.GONE
        trackBox.visibility = View.VISIBLE
        tvTrackInfo.text = "● Tracking aktif • menunggu sinyal GPS..."
        ivTrack.setImageDrawable(null)
        updateTrackUi()
        if (MapStore.all(this).isEmpty()) toast("Peta offline belum diimport (Pengaturan). Jalur tetap direkam di latar polos.")
        trackHandler.removeCallbacks(trackTick)
        trackHandler.post(trackTick)
    }

    private fun cancelTracking() {
        Tracker.stop()
        stopService(Intent(this, TrackingService::class.java))
        trackHandler.removeCallbacks(trackTick)
        if (lastTrack == null) trackBox.visibility = View.GONE
        updateTrackUi()
    }

    private fun stopTracking() {
        val pts = Tracker.stop()
        stopService(Intent(this, TrackingService::class.java))
        trackHandler.removeCallbacks(trackTick)
        if (pts.size < 2) {
            toast("Titik tracking terlalu sedikit (belum ada pergerakan). Tracking dibatalkan.")
            trackBox.visibility = View.GONE
            lastTrack = null
            updateTrackUi()
            return
        }
        val mid = pts[pts.size / 2]
        val mapId = MapStore.pick(this, mid.lat, mid.lon)?.id ?: ""
        val rec = TrackRecord(
            UUID.randomUUID().toString(), Tracker.startedAt, System.currentTimeMillis(),
            etEst.text.toString().trim(), etAfd.text.toString().trim(), etBlok.text.toString().trim(), mapId, pts,
        )
        TrackStore.add(this, rec)
        lastTrack = rec
        cbTrack.isChecked = true
        updateTrackUi()
        renderPreview(pts, mapId)
        peta.refresh()
        toast(
            if (mapId.isEmpty()) "Tracking tersimpan di tab Peta (lokasi di luar peta offline / peta belum diimport)"
            else "Tracking tersimpan di tab Peta"
        )
    }

    private fun updateTrackUi() {
        val active = Tracker.active
        btnTrack.text = if (active) "Stop Tracking" else "Start Tracking"
        btnTrack.backgroundTintList = ColorStateList.valueOf(if (active) 0xFF7F0000.toInt() else 0xFFD32F2F.toInt())
        val rec = lastTrack
        cbTrack.visibility = if (!active && rec != null) View.VISIBLE else View.GONE
        if (!active && rec != null) {
            tvTrackInfo.text = "Tracking selesai • ${rec.points.size} titik • ${Geo.km(rec.distance)} • tersimpan di tab Peta"
        }
    }

    private fun renderPreview(pts: List<TPoint>, mapId: String?) {
        if (rendering || pts.isEmpty()) return
        rendering = true
        val app = applicationContext
        Thread {
            val bmp = try {
                val map = if (mapId != null) MapStore.get(app, mapId)
                else pts.last().let { MapStore.pick(app, it.lat, it.lon) }
                TrackRender.render(app, map, pts, 900, 560)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                rendering = false
                if (bmp != null) ivTrack.setImageBitmap(bmp)
            }
        }.start()
    }

    // ---------- Draf ("Hanya Simpan") ----------

    private fun saveDraft() {
        if (Tracker.active) { toast("Tracking masih berjalan. Tekan Stop Tracking dulu."); return }
        val id = editingDraftId ?: UUID.randomUUID().toString()
        val dir = DraftStore.dir(this, id)
        dir.listFiles()?.forEach { it.delete() }
        val names = ArrayList<String>()
        photos.forEachIndexed { i, f ->
            val n = "p$i.jpg"
            try { f.copyTo(File(dir, n), overwrite = true); names.add(n) } catch (e: Exception) {}
        }
        val est = etEst.text.toString().trim()
        val afd = etAfd.text.toString().trim()
        val ll = currentLatLon()
        val rec = lastTrack
        DraftStore.save(this, Draft(
            id = id, savedAt = System.currentTimeMillis(),
            est = est, afd = afd, blok = etBlok.text.toString().trim(),
            problem = etProblem.text.toString().trim(), perbaikan = etPerbaikan.text.toString().trim(),
            lat = ll?.first ?: Double.NaN, lon = ll?.second ?: Double.NaN,
            losses = activities.losses(), grading = activities.grading(), kalibrasi = activities.kalibrasi(),
            lux = lux.saved, trackId = rec?.id ?: "",
            attachTrack = rec != null && cbTrack.isChecked, photos = names,
        ))
        prefs.edit().putString("est", est).putString("afd", afd).apply()
        resetForm()
        queue.refresh()
        toast("Draf tersimpan. Buka tab Antrean > Edit & Kirim untuk mengoreksi lalu mengirim.")
    }

    private fun loadDraft(d: Draft) {
        if (Tracker.active) { toast("Tracking masih berjalan. Tekan Stop Tracking dulu."); return }
        photos.forEach { it.delete() }
        photos.clear()
        val dir = DraftStore.dir(this, d.id)
        for (n in d.photos) {
            val src = File(dir, n)
            if (!src.exists()) continue
            val f = File(photoDir, "d_${System.currentTimeMillis()}_${photos.size}.jpg")
            try { src.copyTo(f, overwrite = true); photos.add(f) } catch (e: Exception) {}
        }
        renderThumbs()
        etEst.setText(d.est)
        etAfd.setText(d.afd)
        etBlok.setText(d.blok)
        etProblem.setText(d.problem)
        etPerbaikan.setText(d.perbaikan)
        activities.load(d.losses, d.grading, d.kalibrasi)
        lux.reset()
        if (d.lux >= 0) lux.restore(d.lux)
        coordOverride = if (d.hasCoord) Pair(d.lat, d.lon) else null
        coordOverride?.let {
            tvGps.text = "Latitude : ${Laporan.koordinat(it.first)}\nLongitude : ${Laporan.koordinat(it.second)}\n" +
                "(koordinat dari draf; tekan Ambil Ulang GPS untuk memperbarui)\n" +
                "Ketuk kotak ini untuk menyalin: ${Laporan.koordinatSalin(it.first, it.second)}"
        }
        val rec = if (d.trackId.isNotBlank()) TrackStore.get(this, d.trackId) else null
        lastTrack = rec
        if (rec != null) {
            trackBox.visibility = View.VISIBLE
            cbTrack.isChecked = d.attachTrack
            renderPreview(rec.points, rec.mapId)
        } else {
            trackBox.visibility = View.GONE
        }
        editingDraftId = d.id
        updateTrackUi()
        tabs.getTabAt(0)?.select()
        toast("Draf dimuat. Koreksi lalu tekan Upload & Share.")
    }

    // ---------- Peta offline & data tracking ----------

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "Peta"
            }
        } catch (e: Exception) {}
        return "Peta"
    }

    private fun doImportMap(uri: Uri) {
        val name = displayName(uri)
        val app = applicationContext
        toast("Mengimport peta... (bisa sampai 30 detik)")
        Thread {
            val err = MapStore.import(app, uri, name)
            runOnUiThread {
                tvMapLabel?.text = mapLabel()
                if (err == null) toast("Peta berhasil diimport: ${name.removeSuffix(".pdf")}")
                else MaterialAlertDialogBuilder(this).setTitle("Import peta gagal").setMessage(err)
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun doImportTracks(uri: Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null }
        if (bytes == null) { toast("File tidak dapat dibuka."); return }
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            // paket peta offline (hasil "Share File Peta")
            val app = applicationContext
            toast("Mengimport file peta...")
            Thread {
                val err = MapStore.importZip(app, bytes)
                runOnUiThread {
                    tvMapLabel?.text = mapLabel()
                    if (err == null) toast("Peta berhasil diimport.") else toast(err)
                    peta.refresh()
                }
            }.start()
            return
        }
        val r = DataIO.importJson(this, bytes.toString(Charsets.UTF_8))
        toast(when {
            r == null -> "File bukan data peta CKP yang valid."
            r.first == 0 && r.second == 0 -> "Tidak ada data baru (semua sudah ada)."
            else -> "Import: ${r.first} tracking, ${r.second} tanda baru."
        })
        peta.refresh()
    }

    /** Tombol tracking di tab Peta: sama dengan tab Laporan, tetapi menanyakan Blok bila masih kosong. */
    private fun toggleTrackingFromPeta() {
        if (Tracker.active || etBlok.text.toString().isNotBlank()) { toggleTracking(); return }
        val (til, et) = inputField(this, "Blok yang dicek (boleh dikosongkan)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(til)
        MaterialAlertDialogBuilder(this).setTitle("Mulai tracking").setView(box)
            .setPositiveButton("Mulai") { _, _ ->
                etBlok.setText(et.text.toString().trim().uppercase())
                toggleTracking()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun mapLabel(): String {
        val maps = MapStore.all(this)
        return if (maps.isEmpty()) "Belum ada peta offline. Tekan Import Peta lalu pilih file GeoPDF."
        else "Peta offline: " + maps.joinToString(", ") { it.name }
    }

    private fun confirmDeleteMap() {
        val maps = MapStore.all(this)
        if (maps.isEmpty()) { toast("Belum ada peta untuk dihapus"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus peta mana?")
            .setItems(maps.map { it.name }.toTypedArray()) { _, which ->
                MapStore.remove(this, maps[which].id)
                tvMapLabel?.text = mapLabel()
                toast("Peta dihapus")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- Pengaturan ----------

    private fun showSettings() {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val (tPt, ePt) = field("Nama PT (tampil di header & laporan WA)", prefs.getString("pt", Laporan.DEFAULT_PT) ?: Laporan.DEFAULT_PT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val (tUrl, eUrl) = field("URL Web App (Apps Script)", prefs.getString("url", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val (tTok, eTok) = field("Kode rahasia", prefs.getString("token", "") ?: "", InputType.TYPE_CLASS_TEXT)
        val (tNama, eNama) = field("Nama pelapor (opsional)", prefs.getString("pelapor", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        f.addView(tPt); f.addView(tUrl); f.addView(tTok); f.addView(tNama)

        f.addView(section("Peta Offline (untuk Tracking)"))
        val lblMap = TextView(this).apply { text = mapLabel(); textSize = 13f; setPadding(0, 0, 0, dp(6)) }
        tvMapLabel = lblMap
        f.addView(lblMap)
        val rowMap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowMap.addView(MaterialButton(this).apply {
            text = "Import Peta (GeoPDF)"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) }
            setOnClickListener { importMap.launch(arrayOf("application/pdf")) }
        })
        rowMap.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Hapus Peta"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) }
            setOnClickListener { confirmDeleteMap() }
        })
        f.addView(rowMap)

        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Pengaturan")
            .setView(ScrollView(this).apply { addView(f) })
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val url = eUrl.text.toString().trim()
            if (!url.startsWith("https://script.google.com/")) {
                tUrl.error = "Harus diawali https://script.google.com/"
                return@setOnClickListener
            }
            val pt = ePt.text.toString().trim().ifEmpty { Laporan.DEFAULT_PT }
            tvPt.text = pt
            prefs.edit()
                .putString("pt", pt)
                .putString("url", url)
                .putString("token", eTok.text.toString().trim())
                .putString("pelapor", eNama.text.toString().trim())
                .apply()
            d.dismiss()
        }
    }
}
