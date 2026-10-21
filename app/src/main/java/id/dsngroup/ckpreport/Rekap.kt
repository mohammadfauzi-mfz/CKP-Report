package id.dsngroup.ckpreport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Menarik laporan yang sudah terupload (dari Spreadsheet via Apps Script) dan menjadikannya file Excel dengan foto. */
object Rekap {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    class Filter(
        val from: String = "",      // yyyy-MM-dd
        val to: String = "",
        val estate: String = "",
        val afdeling: String = "",
        val blok: String = "",
        val pelapor: String = "",
    )

    class Data(val headers: List<String>, val rows: List<List<Any?>>, val total: Int, val terpotong: Boolean)

    class Hasil(val file: File, val laporan: Int, val foto: Int, val terpotong: Boolean, val gagalFoto: Int)

    class Gagal(msg: String) : Exception(msg)

    private fun get(url: String, params: Map<String, String>, readMs: Int): JSONObject {
        val q = params.entries.filter { it.value.isNotEmpty() }
            .joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val full = url + (if (url.contains("?")) "&" else "?") + q
        var conn: HttpURLConnection? = null
        try {
            val c = URL(full).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "GET"
            c.connectTimeout = 20000
            c.readTimeout = readMs
            c.instanceFollowRedirects = true
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val js: JSONObject? = try { JSONObject(text) } catch (e: Exception) { null }
            if (js == null) throw Gagal("Respon tidak dikenali (kode $code). Pastikan script sudah diperbarui dan di-deploy sebagai versi baru.")
            if (!js.optBoolean("ok")) throw Gagal(js.optString("error", "Gagal mengambil data"))
            return js
        } catch (e: Gagal) {
            throw e
        } catch (e: Exception) {
            throw Gagal("Tidak ada koneksi / sinyal lemah")
        } finally {
            conn?.disconnect()
        }
    }

    fun ambilData(url: String, token: String, f: Filter): Data {
        val js = get(url, mapOf(
            "action" to "list", "token" to token, "from" to f.from, "to" to f.to,
            "estate" to f.estate, "afdeling" to f.afdeling, "blok" to f.blok, "pelapor" to f.pelapor,
        ), 120000)
        val hs = js.optJSONArray("headers") ?: JSONArray()
        val headers = List(hs.length()) { hs.optString(it) }
        val ra = js.optJSONArray("rows") ?: JSONArray()
        val rows = ArrayList<List<Any?>>(ra.length())
        for (i in 0 until ra.length()) {
            val r = ra.optJSONArray(i) ?: continue
            rows.add(List(r.length()) { k ->
                val v = r.opt(k)
                if (v == null || v == JSONObject.NULL) null else v
            })
        }
        return Data(headers, rows, js.optInt("total", rows.size), js.optBoolean("terpotong"))
    }

    fun ambilFoto(url: String, token: String, id: String): ByteArray? {
        val js = get(url, mapOf("action" to "foto", "token" to token, "id" to id), 90000)
        val b64 = js.optString("b64", "")
        if (b64.isEmpty()) return null
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    /** Perkecil foto: sisi terpanjang 440 px disimpan, ditampilkan separuhnya (tajam saat diperbesar). */
    fun thumb(bytes: ByteArray): XlsxFoto? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 440) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val sc = 440f / maxOf(bmp.width, bmp.height)
        val b2 = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        b2.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val w = b2.width; val h = b2.height
        if (b2 !== bmp) b2.recycle()
        bmp.recycle()
        return XlsxFoto(out.toByteArray(), (w / 2).coerceAtLeast(1), (h / 2).coerceAtLeast(1))
    }

    fun folder(ctx: Context) = File(ctx.filesDir, "rekap").apply { mkdirs() }

    fun terakhir(ctx: Context): File? =
        folder(ctx).listFiles { f -> f.name.endsWith(".xlsx") }?.maxByOrNull { it.lastModified() }

    fun namaFile(): String = "CKP_Rekap_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".xlsx"

    /**
     * Ambil data + foto lalu tulis file Excel. Panggil dari thread latar.
     * @param progres teks status; @param batal true = hentikan
     */
    fun buat(
        ctx: Context, url: String, token: String, f: Filter, sertakanFoto: Boolean,
        progres: (String) -> Unit, batal: () -> Boolean,
    ): Hasil? {
        progres("Mengambil data laporan…")
        val d = ambilData(url, token, f)
        if (batal()) return null
        if (d.rows.isEmpty()) throw Gagal("Tidak ada laporan yang cocok dengan filter ini.")

        val iLink = d.headers.indexOf("Link Foto")
        val iId = d.headers.indexOf("ID Laporan")
        val foto = arrayOfNulls<XlsxFoto>(d.rows.size)
        var gagal = 0
        if (sertakanFoto && iLink >= 0 && iId >= 0) {
            val perlu = d.rows.indices.filter {
                d.rows[it].getOrNull(iLink)?.toString().orEmpty().isNotBlank() && d.rows[it].getOrNull(iId)?.toString().orEmpty().isNotBlank()
            }
            val selesai = AtomicInteger(0)
            val gagalN = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(3)
            for (idx in perlu) pool.execute {
                if (!batal()) {
                    try {
                        var bytes: ByteArray? = null
                        for (coba in 1..2) {            // satu kali coba ulang bila sinyal putus-putus
                            try { bytes = ambilFoto(url, token, d.rows[idx][iId].toString()); break } catch (e: Gagal) { if (coba == 2) throw e }
                        }
                        val t = if (bytes != null) thumb(bytes) else null
                        if (t != null) foto[idx] = t else gagalN.incrementAndGet()
                    } catch (e: Throwable) {
                        gagalN.incrementAndGet()
                    }
                }
                progres("Mengunduh foto ${selesai.incrementAndGet()} / ${perlu.size}…")
            }
            pool.shutdown()
            while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) { if (batal()) { pool.shutdownNow(); break } }
            if (batal()) return null
            gagal = gagalN.get()
        }

        progres("Membuat file Excel…")
        val persen = d.headers.indices.filter { d.headers[it].endsWith("(%)") }.toSet()
        folder(ctx).listFiles()?.forEach { it.delete() }          // hanya simpan rekap terbaru
        val out = File(folder(ctx), namaFile())
        FileOutputStream(out).use {
            Xlsx.tulis(it, "CKP", d.headers, d.rows, persen, if (sertakanFoto) "Foto" else null, foto.toList())
        }
        return Hasil(out, d.rows.size, foto.count { it != null }, d.terpotong, gagal)
    }
}
