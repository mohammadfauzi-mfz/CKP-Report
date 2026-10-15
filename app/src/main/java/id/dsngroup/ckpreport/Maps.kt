package id.dsngroup.ckpreport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.Inflater
import kotlin.math.roundToInt

/**
 * Peta offline hasil import GeoPDF (mis. dari Global Mapper): gambar peta + 2 titik registrasi (halaman PDF <-> lon/lat).
 * Koordinat halaman PDF berasal dari kiri-bawah; piksel gambar dari kiri-atas.
 */
data class MapInfo(
    val id: String,
    val name: String,
    val w: Int,
    val h: Int,
    val pageW: Double,
    val pageH: Double,
    val x1: Double, val y1: Double, val lon1: Double, val lat1: Double,
    val x2: Double, val y2: Double, val lon2: Double, val lat2: Double,
) {
    /** Lat/lon -> posisi piksel pada gambar peta. */
    fun px(lat: Double, lon: Double): Pair<Double, Double> {
        val x = x1 + (lon - lon1) * (x2 - x1) / (lon2 - lon1)
        val y = y1 + (lat - lat1) * (y2 - y1) / (lat2 - lat1)
        return Pair(x / pageW * w, (pageH - y) / pageH * h)
    }

    /** Posisi piksel gambar -> lat/lon. */
    fun latLon(px: Double, py: Double): Pair<Double, Double> {
        val x = px / w * pageW
        val y = pageH - py / h * pageH
        val lon = lon1 + (x - x1) * (lon2 - lon1) / (x2 - x1)
        val lat = lat1 + (y - y1) * (lat2 - lat1) / (y2 - y1)
        return Pair(lat, lon)
    }

    /** Perkiraan meter per piksel gambar (arah horizontal). */
    val metersPerPixel: Double
        get() {
            val degPerPx = (lon2 - lon1) / (x2 - x1) * pageW / w
            return Math.abs(degPerPx) * 111320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        }

    fun contains(lat: Double, lon: Double): Boolean {
        val (a, b) = px(lat, lon)
        return a >= 0.0 && a <= w && b >= 0.0 && b <= h
    }
}

object MapStore {
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "maps").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "maps.json")
    fun imageFile(ctx: Context, id: String) = File(dir(ctx), "$id.jpg")

    fun toJson(m: MapInfo): JSONObject = JSONObject()
        .put("id", m.id).put("name", m.name).put("w", m.w).put("h", m.h).put("pw", m.pageW).put("ph", m.pageH)
        .put("x1", m.x1).put("y1", m.y1).put("lon1", m.lon1).put("lat1", m.lat1)
        .put("x2", m.x2).put("y2", m.y2).put("lon2", m.lon2).put("lat2", m.lat2)

    fun fromJson(o: JSONObject) = MapInfo(
        o.getString("id"), o.getString("name"), o.getInt("w"), o.getInt("h"),
        o.getDouble("pw"), o.getDouble("ph"),
        o.getDouble("x1"), o.getDouble("y1"), o.getDouble("lon1"), o.getDouble("lat1"),
        o.getDouble("x2"), o.getDouble("y2"), o.getDouble("lon2"), o.getDouble("lat2"),
    )

    fun all(ctx: Context): List<MapInfo> = synchronized(lock) {
        val f = index(ctx)
        if (!f.exists()) return emptyList()
        try {
            val a = JSONArray(f.readText())
            List(a.length()) { i -> fromJson(a.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<MapInfo>) {
        val a = JSONArray()
        for (m in list) a.put(toJson(m))
        index(ctx).writeText(a.toString())
    }

    /** Kemas peta (gambar + data koordinat) menjadi satu file zip untuk dibagikan. */
    fun exportZip(ctx: Context, id: String, out: File): Boolean {
        val m = get(ctx, id) ?: return false
        val img = imageFile(ctx, id)
        if (!img.exists()) return false
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("meta.json"))
            z.write(toJson(m).toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("map.jpg"))
            img.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        return true
    }

    /** Import paket peta (zip hasil Share Peta). @return pesan kesalahan, atau null jika berhasil. */
    fun importZip(ctx: Context, bytes: ByteArray): String? {
        var meta: String? = null
        var jpg: ByteArray? = null
        try {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        "meta.json" -> meta = String(z.readBytes(), Charsets.UTF_8)
                        "map.jpg" -> jpg = z.readBytes()
                    }
                }
            }
            val mj = meta
            val img = jpg
            if (mj == null || img == null) return "File ini bukan paket peta CKP yang valid."
            val old = fromJson(JSONObject(mj))
            val id = UUID.randomUUID().toString()
            imageFile(ctx, id).writeBytes(img)
            synchronized(lock) {
                val same = all(ctx).filter { it.name.equals(old.name, ignoreCase = true) }
                same.forEach { imageFile(ctx, it.id).delete() }
                save(ctx, all(ctx).filter { m -> same.none { it.id == m.id } } + old.copy(id = id))
            }
            return null
        } catch (e: Exception) {
            return "Gagal import paket peta: ${e.message}"
        }
    }

    fun get(ctx: Context, id: String): MapInfo? = all(ctx).firstOrNull { it.id == id }

    /** Peta yang memuat titik ini (jika ada). */
    fun pick(ctx: Context, lat: Double, lon: Double): MapInfo? = all(ctx).firstOrNull { it.contains(lat, lon) }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val list = all(ctx).filter { it.id != id }
        imageFile(ctx, id).delete()
        save(ctx, list)
    }

    /**
     * Import GeoPDF. Dipanggil dari thread latar belakang.
     * @return pesan kesalahan, atau null jika berhasil.
     */
    fun import(ctx: Context, uri: Uri, displayName: String): String? {
        val tmp = File(ctx.cacheDir, "import_map.pdf")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins -> FileOutputStream(tmp).use { ins.copyTo(it) } }
                ?: return "File tidak dapat dibuka"
            val bytes = tmp.readBytes()
            val geo = GeoPdf.parse(bytes)
                ?: return "File ini bukan GeoPDF (tidak ada data koordinat). Gunakan peta PDF yang berkoordinat (mis. ekspor dari Global Mapper / Avenza)."
            if (Math.abs(geo.lon1) > 180 || Math.abs(geo.lon2) > 180 || Math.abs(geo.lat1) > 90 || Math.abs(geo.lat2) > 90)
                return "Proyeksi peta belum didukung (harus koordinat lat/lon / GEODETIC)."
            if (geo.lon1 == geo.lon2 || geo.lat1 == geo.lat2 || geo.x1 == geo.x2 || geo.y1 == geo.y2)
                return "Data koordinat peta tidak valid."

            val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val id = UUID.randomUUID().toString()
            val out = imageFile(ctx, id)
            val outW = 2600
            val pageW = geo.pageW ?: renderer.openPage(0).let { p -> val w = p.width.toDouble(); p.close(); w }
            val pageH = geo.pageH ?: renderer.openPage(0).let { p -> val h = p.height.toDouble(); p.close(); h }
            val outH = (outW * pageH / pageW).roundToInt().coerceIn(200, 4000)
            try {
                val page = renderer.openPage(0)
                val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
            } finally {
                renderer.close()
                pfd.close()
            }
            val name = displayName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Peta" }
            synchronized(lock) {
                // Nama sama -> ganti
                val old = all(ctx).filter { it.name.equals(name, ignoreCase = true) }
                old.forEach { imageFile(ctx, it.id).delete() }
                val list = all(ctx).filter { m -> old.none { it.id == m.id } } + MapInfo(
                    id, name, outW, outH, pageW, pageH,
                    geo.x1, geo.y1, geo.lon1, geo.lat1, geo.x2, geo.y2, geo.lon2, geo.lat2,
                )
                save(ctx, list)
            }
            return null
        } catch (e: OutOfMemoryError) {
            return "Memori HP tidak cukup untuk memproses peta ini."
        } catch (e: Exception) {
            return "Gagal import peta: ${e.message}"
        } finally {
            tmp.delete()
        }
    }
}

/** Pembaca data georeferensi GeoPDF (LGIDict /Registration) tanpa library. */
object GeoPdf {
    class Geo(
        val x1: Double, val y1: Double, val lon1: Double, val lat1: Double,
        val x2: Double, val y2: Double, val lon2: Double, val lat2: Double,
        val pageW: Double?, val pageH: Double?,
    )

    private val num = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
    private val media = Regex("/MediaBox\\s*\\[\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s*\\]")

    fun parse(bytes: ByteArray): Geo? {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val texts = ArrayList<String>()
        texts.add(raw)
        // Objek PDF 1.5 sering terkompres (object stream): buka semua stream Flate.
        var pos = 0
        while (true) {
            val i = raw.indexOf("stream", pos)
            if (i < 0) break
            pos = i + 6
            if (i >= 3 && raw.startsWith("end", i - 3)) continue
            var s = pos
            if (s < raw.length && raw[s] == '\r') s++
            if (s < raw.length && raw[s] == '\n') s++
            val e = raw.indexOf("endstream", s)
            if (e < 0) break
            val out = inflate(bytes, s, e - s)
            if (out != null && out.isNotEmpty()) {
                val t = String(out, Charsets.ISO_8859_1)
                if (t.contains("/Registration") || t.contains("/MediaBox")) texts.add(t)
            }
            pos = e + 9
        }

        var reg: List<Double>? = null
        var mb: Pair<Double, Double>? = null
        for (t in texts) {
            if (reg == null) {
                val k = t.indexOf("/Registration")
                if (k >= 0) {
                    val n = num.findAll(t.substring(k + 13, minOf(t.length, k + 13 + 700))).take(8)
                        .mapNotNull { it.value.toDoubleOrNull() }.toList()
                    if (n.size == 8) reg = n
                }
            }
            if (mb == null) {
                media.find(t)?.let { m ->
                    val v = m.groupValues.drop(1).map { it.toDouble() }
                    mb = Pair(v[2] - v[0], v[3] - v[1])
                }
            }
            if (reg != null && mb != null) break
        }
        val r = reg ?: return null
        return Geo(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], mb?.first, mb?.second)
    }

    private fun inflate(b: ByteArray, off: Int, len: Int): ByteArray? {
        if (len <= 0) return null
        val inf = Inflater()
        return try {
            inf.setInput(b, off, len)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 40_000_000) break
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inf.end()
        }
    }
}
