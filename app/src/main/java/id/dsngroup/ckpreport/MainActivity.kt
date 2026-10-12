package id.dsngroup.ckpreport

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etEst: TextInputEditText
    private lateinit var etAfd: TextInputEditText
    private lateinit var etBlok: TextInputEditText
    private lateinit var etProblem: TextInputEditText
    private lateinit var tvGps: TextView
    private lateinit var ivFoto: ImageView
    private lateinit var btnUpload: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var loc: LocationHelper
    private var photoReady = false

    private val photoDir by lazy { File(cacheDir, "photos").apply { mkdirs() } }
    private val cameraFile by lazy { File(photoDir, "camera.jpg") }
    private val uploadFile by lazy { File(photoDir, "upload.jpg") }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) usePhoto { FileInputStream(cameraFile) }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) usePhoto { contentResolver.openInputStream(uri) }
    }
    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startGps()
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
        titles.addView(TextView(this).apply {
            text = "PT. Bima Palma Nugraha"; textSize = 14f; setTextColor(0xFFE8F5E9.toInt())
        })
        header.addView(titles)
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Pengaturan"
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { showSettings() }
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

        // Problem
        box.addView(section("Problem / Temuan"))
        val (tPro, ePro) = field("Tuliskan temuan di lapangan",
            "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        ePro.minLines = 3
        ePro.gravity = Gravity.TOP
        etProblem = ePro
        box.addView(tPro)

        // Koordinat
        box.addView(section("Koordinat (diambil dengan GPS)"))
        tvGps = TextView(this).apply {
            text = "Mencari sinyal GPS..."
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFE8F5E9.toInt())
        }
        box.addView(tvGps)
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Ambil Ulang GPS"
            setOnClickListener { requestGps() }
        })

        // Foto
        box.addView(section("Foto / Dokumentasi"))
        ivFoto = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        box.addView(ivFoto)
        val rowFoto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowFoto.addView(MaterialButton(this).apply {
            text = "Foto Langsung"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) }
            setOnClickListener { openCamera() }
        })
        rowFoto.addView(MaterialButton(this).apply {
            text = "Pilih dari HP"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) }
            setOnClickListener { pickImage.launch("image/*") }
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

        root.addView(ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        setContentView(root)

        if (prefs.getString("url", "").isNullOrBlank()) showSettings()
        if (savedInstanceState == null && !hasLocationPermission()) requestGps()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission() && loc.best == null) startGps(false)
    }

    override fun onPause() {
        super.onPause()
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
        else permReq.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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

    private fun showLocation(l: android.location.Location) {
        tvGps.text = "Latitude : ${Laporan.koordinat(l.latitude)}\n" +
            "Longitude : ${Laporan.koordinat(l.longitude)}\n" +
            "Akurasi : ±${l.accuracy.toInt()} m"
    }

    // ---------- Foto ----------

    private fun openCamera() {
        try {
            cameraFile.delete()
            cameraFile.createNewFile()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", cameraFile)
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera tidak dapat dibuka: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun usePhoto(open: () -> InputStream?) {
        if (ImageUtil.prepare(open, uploadFile)) {
            photoReady = true
            ivFoto.setImageBitmap(BitmapFactory.decodeFile(uploadFile.path))
            ivFoto.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "Foto tidak dapat dibaca. Coba lagi.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Upload ----------

    private fun doUpload() {
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (url.isBlank()) { showSettings(); return }

        val est = etEst.text.toString().trim()
        val afd = etAfd.text.toString().trim()
        val blok = etBlok.text.toString().trim()
        val problem = etProblem.text.toString().trim()
        val l = loc.best
        val msg = when {
            est.isEmpty() -> "Est wajib diisi"
            afd.isEmpty() -> "Afd wajib diisi"
            blok.isEmpty() -> "Blok wajib diisi"
            problem.isEmpty() -> "Problem / Temuan wajib diisi"
            l == null -> "Koordinat GPS belum didapat. Tunggu sebentar atau tekan Ambil Ulang GPS."
            !photoReady -> "Foto wajib diambil / dipilih"
            else -> null
        }
        if (msg != null) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); return }
        l!!

        val pelapor = prefs.getString("pelapor", "") ?: ""
        val lat = l.latitude
        val lon = l.longitude
        val mapsUrl = Laporan.mapsUrl(lat, lon)
        val waktu = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("in", "ID")).format(Date())

        btnUpload.isEnabled = false
        btnUpload.text = "Mengupload..."
        progress.visibility = View.VISIBLE

        Thread {
            val b64 = Base64.encodeToString(uploadFile.readBytes(), Base64.NO_WRAP)
            val json = JSONObject()
                .put("token", token)
                .put("estate", est).put("afdeling", afd).put("blok", blok)
                .put("problem", problem)
                .put("lat", lat).put("lon", lon)
                .put("mapsUrl", mapsUrl)
                .put("pelapor", pelapor)
                .put("fotoBase64", b64)
            val r = Uploader.upload(url, json)
            runOnUiThread {
                btnUpload.isEnabled = true
                btnUpload.text = "Upload & Share"
                progress.visibility = View.GONE
                if (r.ok) {
                    prefs.edit().putString("est", est).putString("afd", afd).apply()
                    val teks = Laporan.pesan(est, afd, blok, problem, lat, lon, r.fotoUrl, pelapor, waktu)
                    showSuccess(teks)
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Upload gagal")
                        .setMessage(r.error + "\n\nData Anda belum hilang, silakan coba lagi.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showSuccess(teks: String) {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Berhasil terupload ✅")
            .setMessage("Data tercatat di spreadsheet dan foto tersimpan di Google Drive.\n\nBagikan ke grup WhatsApp?")
            .setCancelable(false)
            .setPositiveButton("Share ke WhatsApp", null)
            .setNegativeButton("Selesai", null)
            .create()
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { shareWhatsApp(teks) }
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            d.dismiss()
            resetForm()
        }
    }

    private fun shareWhatsApp(teks: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", uploadFile)
        fun build(pkg: String?): Intent {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "image/jpeg"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.putExtra(Intent.EXTRA_TEXT, teks)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) i.setPackage(pkg)
            return i
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { startActivity(build(pkg)); return } catch (e: ActivityNotFoundException) {}
        }
        startActivity(Intent.createChooser(build(null), "Bagikan laporan"))
    }

    private fun resetForm() {
        etBlok.setText("")
        etProblem.setText("")
        photoReady = false
        ivFoto.setImageDrawable(null)
        ivFoto.visibility = View.GONE
        requestGps()
    }

    // ---------- Pengaturan ----------

    private fun showSettings() {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val (tUrl, eUrl) = field("URL Web App (Apps Script)", prefs.getString("url", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val (tTok, eTok) = field("Kode rahasia", prefs.getString("token", "") ?: "", InputType.TYPE_CLASS_TEXT)
        val (tNama, eNama) = field("Nama pelapor (opsional)", prefs.getString("pelapor", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        f.addView(tUrl); f.addView(tTok); f.addView(tNama)

        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Pengaturan")
            .setView(f)
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
            prefs.edit()
                .putString("url", url)
                .putString("token", eTok.text.toString().trim())
                .putString("pelapor", eNama.text.toString().trim())
                .apply()
            d.dismiss()
        }
    }
}
