package id.dsngroup.ckpreport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Import tanda & tracking dari format GIS umum: KML, KMZ, GPX, GeoJSON, CSV, dan Shapefile (.zip / .shp+.dbf).
 * Hasil import diberi id tetap (dari judul+koordinat) sehingga mengimport file yang sama dua kali tidak menggandakan data.
 */
object GisImport {
    class Result(val markers: List<Marker>, val tracks: List<TrackRecord>, val format: String, val error: String? = null)

    private class Acc(val now: Long) {
        val markers = ArrayList<Marker>()
        val tracks = ArrayList<TrackRecord>()
    }

    private fun fail(msg: String) = Result(emptyList(), emptyList(), "", msg)

    // ------------------------------------------------------------------ deteksi

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun zipEntries(bytes: ByteArray, withData: Boolean): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (!withData) { out[name] = ByteArray(0); continue }
                val data = z.readBytes()
                total += data.size
                if (total > 200_000_000L) break
                out[name] = data
            }
        }
        return out
    }

    /** Jenis data GIS pada file ini ("shp", "kml", "kmz", "gpx", "geojson", "csv") atau null bila bukan data GIS. */
    fun detect(bytes: ByteArray, name: String): String? {
        val low = name.lowercase()
        if (isZip(bytes)) {
            val names = try { zipEntries(bytes, false).keys.map { it.lowercase() } } catch (e: Exception) { return null }
            return when {
                names.any { it.endsWith(".shp") } -> "shp"
                names.any { it.endsWith(".kml") } -> "kmz"
                names.any { it.endsWith(".gpx") } -> "gpx"
                names.any { it.endsWith(".geojson") } -> "geojson"
                else -> null
            }
        }
        val head = String(bytes, 0, minOf(bytes.size, 4000), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (head.startsWith("<")) {
            val h = head.lowercase()
            return when {
                h.contains("<kml") -> "kml"
                h.contains("<gpx") -> "gpx"
                else -> null
            }
        }
        if (head.startsWith("{") || head.startsWith("[")) {
            if (head.contains("ckpreport")) return null
            return if (head.contains("Feature") || head.contains("coordinates") || head.contains("geometry")) "geojson" else null
        }
        if (low.endsWith(".csv") || low.endsWith(".txt") || low.endsWith(".tsv")) return "csv"
        return null
    }

    fun parse(bytes: ByteArray, name: String): Result {
        val now = System.currentTimeMillis()
        val kind = detect(bytes, name) ?: return fail("Format file tidak dikenali. Didukung: KML, KMZ, GPX, GeoJSON, CSV, Shapefile (.zip).")
        val acc = Acc(now)
        return try {
            when (kind) {
                "shp" -> parseShpZip(zipEntries(bytes, true), acc)
                "kmz" -> {
                    val z = zipEntries(bytes, true)
                    val k = z.entries.firstOrNull { it.key.lowercase().endsWith("doc.kml") } ?: z.entries.first { it.key.lowercase().endsWith(".kml") }
                    parseKml(k.value, acc)
                }
                "gpx" -> {
                    val b = if (isZip(bytes)) zipEntries(bytes, true).entries.first { it.key.lowercase().endsWith(".gpx") }.value else bytes
                    parseGpx(b, acc)
                }
                "kml" -> parseKml(bytes, acc)
                "geojson" -> {
                    val b = if (isZip(bytes)) zipEntries(bytes, true).entries.first { it.key.lowercase().endsWith(".geojson") }.value else bytes
                    parseGeoJson(String(b, Charsets.UTF_8).trimStart('﻿'), acc)
                }
                else -> parseCsv(decodeText(bytes), acc)
            }
            val label = when (kind) {
                "shp" -> "Shapefile"; "kmz" -> "KMZ"; "kml" -> "KML"; "gpx" -> "GPX"; "geojson" -> "GeoJSON"; else -> "CSV"
            }
            Result(acc.markers, acc.tracks, label)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "File tidak dapat dibaca.")
        } catch (e: Exception) {
            fail("File tidak dapat dibaca: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** Simpan hasil import (lewati yang sudah ada). @return (tanda baru, tracking baru). */
    fun apply(ctx: Context, r: Result): Pair<Int, Int> {
        val nm = MarkerStore.merge(ctx, r.markers)
        val have = TrackStore.all(ctx).map { it.id }.toHashSet()
        var nt = 0
        for (t in r.tracks) if (have.add(t.id)) { TrackStore.add(ctx, t); nt++ }
        return Pair(nm, nt)
    }

    // ------------------------------------------------------------------ umum

    private fun decodeText(b: ByteArray): String {
        val s = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, s, b.size - s)).toString()
        } catch (e: CharacterCodingException) {
            String(b, s, b.size - s, Charsets.ISO_8859_1)
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    private val TIME_FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy", "dd-MM-yyyy HH:mm", "dd-MM-yyyy", "dd MMM yyyy HH:mm", "dd MMM yyyy",
    )

    fun parseTime(s0: String): Long? {
        var s = s0.trim()
        if (s.isEmpty()) return null
        s = s.replace(Regex("(\\d{2}:\\d{2}:\\d{2})\\.\\d+"), "$1")
        val z = s.endsWith("Z") && s.contains('T')
        for (loc in listOf(Locale("in", "ID"), Locale.ENGLISH)) {
            for (f in TIME_FORMATS) {
                try {
                    val df = SimpleDateFormat(f, loc)
                    df.isLenient = false
                    if (z && !f.endsWith("XXX")) continue
                    val pp = java.text.ParsePosition(0)
                    val d = df.parse(s, pp)
                    if (d != null && pp.index == s.length) return d.time
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    private fun iconFrom(s: String?): String {
        val t = (s ?: "").trim().lowercase()
        if (t.isEmpty()) return "pin"
        return MarkerIcons.all.firstOrNull { it.key == t || it.label.lowercase() == t || it.emoji == t }?.key ?: "pin"
    }

    private fun pick(a: Map<String, String>, vararg keys: String): String? {
        for (k in keys) { val v = a[k]; if (v != null && v.isNotBlank()) return v.trim() }
        return null
    }

    private fun validLL(lat: Double, lon: Double) =
        !lat.isNaN() && !lon.isNaN() && Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0

    private fun stripHtml(s: String): String {
        if (s.isBlank()) return ""
        return s.replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim().take(500)
    }

    private fun addMarker(acc: Acc, attrs: Map<String, String>, title: String?, desc: String?, lat: Double, lon: Double, time: Long?) {
        if (!validLL(lat, lon)) return
        val t = pick(attrs, "judul", "title", "name", "nama", "label") ?: title?.trim().orEmpty()
        val titleF = t.ifBlank { "Titik impor" }
        val blok = pick(attrs, "blok", "block") ?: ""
        val note = pick(attrs, "keterangan", "ket", "note", "catatan", "deskripsi", "description", "desc", "remark") ?: (desc ?: "")
        val vol = pick(attrs, "volume", "vol", "jumlah", "qty")?.let { parseNum(it) } ?: 0.0
        val unit = pick(attrs, "satuan", "unit", "sat") ?: ""
        val icon = iconFrom(pick(attrs, "kategori", "icon", "type", "jenis"))
        var accM = pick(attrs, "akurasi_m", "akurasi", "acc", "accuracy")?.let { parseNum(it) } ?: -1.0
        if (accM.isNaN() || accM < 0.0) accM = -1.0
        val created = time ?: pick(attrs, "tanggal", "date", "waktu", "time", "datetime")?.let { parseTime(it) } ?: acc.now
        val id = "imp_" + md5("$titleF|$blok|${String.format(Locale.US, "%.6f,%.6f", lat, lon)}")
        acc.markers.add(Marker(id, created, icon, titleF, blok, note.take(500), if (vol.isNaN()) 0.0 else vol, unit, lat, lon, "", accM))
    }

    private fun addTrack(acc: Acc, attrs: Map<String, String>, name: String?, pts: List<TPoint>) {
        if (pts.size < 2) return
        val nm = (pick(attrs, "nama", "name", "judul", "title") ?: name ?: "").trim()
        val first = pts.first(); val last = pts.last()
        val id = "imp_" + md5("$nm|${String.format(Locale.US, "%.6f,%.6f|%.6f,%.6f|%d", first.lat, first.lon, last.lat, last.lon, pts.size)}")
        val hasT = pts.all { it.t > 0L }
        val list = if (hasT) pts else pts.mapIndexed { i, p -> TPoint(p.lat, p.lon, acc.now + i * 1000L) }
        val st = pick(attrs, "mulai", "start")?.let { parseTime(it) } ?: list.first().t
        val en = pick(attrs, "selesai", "end")?.let { parseTime(it) } ?: list.last().t
        acc.tracks.add(TrackRecord(id, st, maxOf(st, en), pick(attrs, "est") ?: "", pick(attrs, "afd") ?: "",
            pick(attrs, "blok", "block") ?: "", "", list))
    }

    // ------------------------------------------------------------------ XML (KML / GPX)

    private fun dom(bytes: ByteArray): Element {
        var i = 0
        while (i < bytes.size && bytes[i] != '<'.code.toByte()) i++
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) {}
        return try {
            f.newDocumentBuilder().parse(ByteArrayInputStream(bytes, i, bytes.size - i)).documentElement
        } catch (e: Exception) {
            throw IllegalArgumentException("Isi file XML tidak valid.")
        }
    }

    private fun local(n: Node): String = (n.nodeName ?: "").substringAfter(':')

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        var c: Node? = firstChild
        while (c != null) { if (c is Element) out.add(c); c = c.nextSibling }
        return out
    }

    private fun Element.kid(name: String): Element? = children().firstOrNull { local(it) == name }
    private fun Element.kids(name: String): List<Element> = children().filter { local(it) == name }
    private fun Element.txt(): String = (textContent ?: "").trim()

    private fun Element.desc(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) { for (k in e.children()) { if (local(k) == name) out.add(k); walk(k) } }
        walk(this)
        return out
    }

    private fun kmlCoords(text: String): List<TPoint> {
        val out = ArrayList<TPoint>()
        for (tok in text.trim().split(Regex("\\s+"))) {
            val p = tok.split(',')
            if (p.size < 2) continue
            val lon = p[0].toDoubleOrNull() ?: continue
            val lat = p[1].toDoubleOrNull() ?: continue
            if (validLL(lat, lon)) out.add(TPoint(lat, lon, 0L))
        }
        return out
    }

    private fun parseKml(bytes: ByteArray, acc: Acc) {
        val root = dom(bytes)
        for (pm in root.desc("Placemark")) {
            val name = pm.kid("name")?.txt() ?: ""
            val desc = pm.kid("description")?.txt() ?: ""
            val attrs = HashMap<String, String>()
            val ed = pm.kid("ExtendedData")
            if (ed != null) {
                for (d in ed.desc("Data")) {
                    val k = d.getAttribute("name").lowercase()
                    val v = d.kid("value")?.txt() ?: d.txt()
                    if (k.isNotEmpty() && v.isNotEmpty()) attrs[k] = v
                }
                for (d in ed.desc("SimpleData")) {
                    val k = d.getAttribute("name").lowercase()
                    if (k.isNotEmpty() && d.txt().isNotEmpty()) attrs[k] = d.txt()
                }
            }
            val ts = pm.kid("TimeStamp")?.kid("when")?.txt() ?: pm.kid("TimeSpan")?.kid("begin")?.txt()
            val time = ts?.let { parseTime(it) }
            val note = if (attrs.containsKey("keterangan")) "" else stripHtml(desc)
            for (p in pm.desc("Point")) {
                val c = kmlCoords(p.kid("coordinates")?.txt() ?: "").firstOrNull() ?: continue
                addMarker(acc, attrs, name, note, c.lat, c.lon, time)
            }
            for (l in pm.desc("LineString")) addTrack(acc, attrs, name, kmlCoords(l.kid("coordinates")?.txt() ?: ""))
            for (poly in pm.desc("Polygon")) {
                val ring = poly.kid("outerBoundaryIs")?.kid("LinearRing")?.kid("coordinates")?.txt() ?: continue
                addTrack(acc, attrs, name, kmlCoords(ring))
            }
            for (tr in pm.desc("Track")) {
                val whens = tr.kids("when").map { parseTime(it.txt()) ?: 0L }
                val pts = ArrayList<TPoint>()
                tr.kids("coord").forEachIndexed { i, c ->
                    val p = c.txt().split(Regex("\\s+"))
                    val lon = p.getOrNull(0)?.toDoubleOrNull()
                    val lat = p.getOrNull(1)?.toDoubleOrNull()
                    if (lon != null && lat != null && validLL(lat, lon)) pts.add(TPoint(lat, lon, whens.getOrElse(i) { 0L }))
                }
                addTrack(acc, attrs, name, pts)
            }
        }
        if (acc.markers.isEmpty() && acc.tracks.isEmpty()) throw IllegalArgumentException("KML tidak berisi titik atau garis.")
    }

    private fun parseGpx(bytes: ByteArray, acc: Acc) {
        val root = dom(bytes)
        for (w in root.kids("wpt")) {
            val lat = w.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = w.getAttribute("lon").toDoubleOrNull() ?: continue
            val desc = w.kid("desc")?.txt() ?: w.kid("cmt")?.txt() ?: ""
            addMarker(acc, emptyMap(), w.kid("name")?.txt() ?: "", desc, lat, lon, w.kid("time")?.txt()?.let { parseTime(it) })
        }
        fun pts(e: Element, tag: String): List<TPoint> {
            val out = ArrayList<TPoint>()
            for (p in e.desc(tag)) {
                val lat = p.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = p.getAttribute("lon").toDoubleOrNull() ?: continue
                if (validLL(lat, lon)) out.add(TPoint(lat, lon, p.kid("time")?.txt()?.let { parseTime(it) } ?: 0L))
            }
            return out
        }
        for (t in root.kids("trk")) addTrack(acc, emptyMap(), t.kid("name")?.txt() ?: "", pts(t, "trkpt"))
        for (r in root.kids("rte")) addTrack(acc, emptyMap(), r.kid("name")?.txt() ?: "", pts(r, "rtept"))
        if (acc.markers.isEmpty() && acc.tracks.isEmpty()) throw IllegalArgumentException("GPX tidak berisi waypoint atau track.")
    }

    // ------------------------------------------------------------------ GeoJSON

    private fun jsonPts(a: JSONArray?): List<TPoint> {
        val out = ArrayList<TPoint>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            if (p.length() < 2) continue
            val lon = p.optDouble(0, Double.NaN); val lat = p.optDouble(1, Double.NaN)
            if (validLL(lat, lon)) out.add(TPoint(lat, lon, 0L))
        }
        return out
    }

    private fun geoms(g: JSONObject?, attrs: Map<String, String>, acc: Acc) {
        if (g == null) return
        when (g.optString("type")) {
            "Point" -> { val p = jsonPts(JSONArray().put(g.optJSONArray("coordinates"))).firstOrNull(); if (p != null) addMarker(acc, attrs, null, null, p.lat, p.lon, null) }
            "MultiPoint" -> for (p in jsonPts(g.optJSONArray("coordinates"))) addMarker(acc, attrs, null, null, p.lat, p.lon, null)
            "LineString" -> addTrack(acc, attrs, null, jsonPts(g.optJSONArray("coordinates")))
            "MultiLineString" -> { val a = g.optJSONArray("coordinates"); if (a != null) for (i in 0 until a.length()) addTrack(acc, attrs, null, jsonPts(a.optJSONArray(i))) }
            "Polygon" -> { val a = g.optJSONArray("coordinates"); if (a != null && a.length() > 0) addTrack(acc, attrs, null, jsonPts(a.optJSONArray(0))) }
            "MultiPolygon" -> {
                val a = g.optJSONArray("coordinates")
                if (a != null) for (i in 0 until a.length()) { val poly = a.optJSONArray(i); if (poly != null && poly.length() > 0) addTrack(acc, attrs, null, jsonPts(poly.optJSONArray(0))) }
            }
            "GeometryCollection" -> { val a = g.optJSONArray("geometries"); if (a != null) for (i in 0 until a.length()) geoms(a.optJSONObject(i), attrs, acc) }
        }
    }

    private fun props(o: JSONObject?): Map<String, String> {
        val m = HashMap<String, String>()
        if (o == null) return m
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (o.isNull(k)) continue
            val v = o.opt(k)
            if (v is JSONObject || v is JSONArray) continue
            m[k.lowercase()] = v.toString()
        }
        return m
    }

    private fun parseGeoJson(text: String, acc: Acc) {
        val root = try {
            if (text.trimStart().startsWith("[")) JSONObject().put("type", "FeatureCollection").put("features", JSONArray(text)) else JSONObject(text)
        } catch (e: Exception) { throw IllegalArgumentException("Isi file GeoJSON tidak valid.") }
        val crs = root.optJSONObject("crs")?.optJSONObject("properties")?.optString("name") ?: ""
        if (crs.isNotEmpty() && !crs.contains("4326") && !crs.uppercase().contains("CRS84")) {
            throw IllegalArgumentException("GeoJSON memakai CRS $crs. Ubah ke WGS84 (EPSG:4326) dulu, mis. lewat QGIS → Export → Save Features As.")
        }
        fun feature(f: JSONObject) {
            val g = f.optJSONObject("geometry")
            if (g != null) geoms(g, props(f.optJSONObject("properties")), acc)
        }
        when (root.optString("type")) {
            "FeatureCollection" -> { val a = root.optJSONArray("features"); if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { feature(it) } }
            "Feature" -> feature(root)
            else -> geoms(root, emptyMap(), acc)
        }
        if (acc.markers.isEmpty() && acc.tracks.isEmpty()) throw IllegalArgumentException("GeoJSON tidak berisi titik atau garis.")
    }

    // ------------------------------------------------------------------ CSV

    private fun csvRows(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else q = false }
                else sb.append(c)
            } else when {
                c == '"' -> q = true
                c == delim -> { row.add(sb.toString()); sb.setLength(0) }
                c == '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList() }
                c == '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun parseCsv(text: String, acc: Acc) {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: throw IllegalArgumentException("File CSV kosong.")
        val delim = listOf(';', '\t', ',').maxByOrNull { d -> firstLine.count { it == d } }?.takeIf { d -> firstLine.contains(d) } ?: ','
        val rows = csvRows(text, delim)
        if (rows.isEmpty()) throw IllegalArgumentException("File CSV kosong.")
        val head = rows[0].map { it.trim().lowercase().replace(" ", "").replace("_", "") }
        fun col(vararg n: String) = head.indexOfFirst { it in n }
        var iLat = col("latitude", "lat", "lintang", "y")
        var iLon = col("longitude", "lon", "lng", "long", "bujur", "x")
        val iKoor = col("koordinat", "coordinate", "coordinates", "posisi", "position", "latlon", "latlng")
        val iE = col("easting", "utmx", "utme", "timur")
        val iN = col("northing", "utmy", "utmn", "utara")
        val iZ = col("zona", "zone", "utmzone")
        var data = rows.drop(1)
        var names = rows[0].map { it.trim().lowercase() }
        val noHeader = iLat < 0 && iLon < 0 && iKoor < 0 && iE < 0 &&
            rows[0].size >= 2 && parseNum(rows[0][0]) != null && parseNum(rows[0][1]) != null
        if (noHeader) { iLat = 0; iLon = 1; data = rows; names = List(rows[0].size) { if (it == 2) "judul" else "k$it" } }
        if (iLat < 0 && iLon < 0 && iKoor < 0 && iE < 0) {
            throw IllegalArgumentException("Kolom koordinat tidak ditemukan. Beri judul kolom Latitude dan Longitude (atau Koordinat).")
        }
        var skipped = 0
        for (r in data) {
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            var lat: Double? = null
            var lon: Double? = null
            if (iLat >= 0 && iLon >= 0) { lat = parseNum(cell(iLat)); lon = parseNum(cell(iLon)) }
            if ((lat == null || lon == null) && iKoor >= 0) Coord.parse(cell(iKoor))?.let { lat = it.lat; lon = it.lon }
            if ((lat == null || lon == null) && iE >= 0 && iN >= 0 && iZ >= 0) {
                Coord.parse("${cell(iZ)} ${cell(iE)} ${cell(iN)}")?.let { lat = it.lat; lon = it.lon }
            }
            var la = lat; var lo = lon
            if (la != null && lo != null && Math.abs(la) > 90.0 && Math.abs(lo) <= 90.0) { val t = la; la = lo; lo = t }
            if (la == null || lo == null || !validLL(la, lo)) { skipped++; continue }
            val attrs = HashMap<String, String>()
            names.forEachIndexed { i, n -> if (n.isNotEmpty() && cell(i).isNotEmpty()) attrs[n.replace(" ", "").replace("_", "").let { k -> if (k == "akurasim") "akurasi_m" else k }] = cell(i) }
            addMarker(acc, attrs, null, null, la, lo, null)
        }
        if (acc.markers.isEmpty()) throw IllegalArgumentException("Tidak ada baris dengan koordinat valid (desimal derajat, DMS, atau UTM).")
    }

    // ------------------------------------------------------------------ Shapefile

    private class Dbf(val rows: List<Map<String, String>?>)

    private fun parseDbf(b: ByteArray, cpg: String): Dbf {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.getInt(4)
        val headLen = bb.getShort(8).toInt() and 0xFFFF
        val recLen = bb.getShort(10).toInt() and 0xFFFF
        data class F(val name: String, val len: Int)
        val fields = ArrayList<F>()
        var p = 32
        while (p + 32 <= headLen && b[p] != 0x0D.toByte()) {
            var e = p
            while (e < p + 11 && b[e] != 0.toByte()) e++
            fields.add(F(String(b, p, e - p, Charsets.ISO_8859_1).lowercase(), b[p + 16].toInt() and 0xFF))
            p += 32
        }
        val utf = cpg.uppercase().contains("UTF") || cpg.isBlank()
        val rows = ArrayList<Map<String, String>?>()
        for (i in 0 until n) {
            val o = headLen + i * recLen
            if (o + recLen > b.size) break
            if (b[o] == '*'.code.toByte()) { rows.add(null); continue }
            val m = HashMap<String, String>()
            var q = o + 1
            for (f in fields) {
                val raw = b.copyOfRange(q, minOf(q + f.len, b.size))
                val s = (if (utf) decodeText(raw) else String(raw, Charsets.ISO_8859_1)).trim().trim('\u0000')
                if (s.isNotEmpty()) m[f.name] = s
                q += f.len
            }
            rows.add(m)
        }
        return Dbf(rows)
    }

    /** Proyeksi dari .prj: null = geografis (lat/lon). Pair(zona, selatan) = UTM. Melempar bila tidak didukung. */
    private fun prjUtm(prj: String): Pair<Int, Boolean>? {
        val t = prj.trim()
        if (t.isEmpty() || t.uppercase().startsWith("GEOGCS") || !t.uppercase().contains("PROJCS")) return null
        Regex("UTM[_ ]zone[_ ]?(\\d{1,2})\\s*([NS])", RegexOption.IGNORE_CASE).find(t)?.let {
            return Pair(it.groupValues[1].toInt(), it.groupValues[2].uppercase() == "S")
        }
        val cm = Regex("central_meridian\"?\\s*,\\s*(-?[\\d.]+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        val fn = Regex("false_northing\"?\\s*,\\s*(-?[\\d.]+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        val k0 = Regex("scale_factor\"?\\s*,\\s*(-?[\\d.]+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        if (cm != null && fn != null && k0 != null && Math.abs(k0 - 0.9996) < 1e-6) {
            val z = Math.round((cm + 183.0) / 6.0).toInt()
            if (z in 1..60) return Pair(z, fn > 1_000_000.0)
        }
        val nm = Regex("PROJCS\\[\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "?"
        throw IllegalArgumentException("Proyeksi shapefile \"$nm\" belum didukung. Di QGIS: klik kanan layer → Export → Save Features As → CRS EPSG:4326 (WGS 84), lalu import lagi.")
    }

    private fun parseShpZip(z: Map<String, ByteArray>, acc: Acc) {
        val bases = z.keys.filter { it.lowercase().endsWith(".shp") }.map { it.substring(0, it.length - 4) }
        if (bases.isEmpty()) throw IllegalArgumentException("Tidak ada file .shp di dalam zip.")
        fun find(base: String, ext: String): ByteArray? = z.entries.firstOrNull { it.key.lowercase() == base.lowercase() + ext }?.value
        for (base in bases) {
            val shp = find(base, ".shp") ?: continue
            val dbf = find(base, ".dbf")?.let { parseDbf(it, find(base, ".cpg")?.toString(Charsets.UTF_8) ?: "") }
            val utm = prjUtm(find(base, ".prj")?.toString(Charsets.ISO_8859_1) ?: "")
            readShp(shp, dbf, utm, acc)
        }
        if (acc.markers.isEmpty() && acc.tracks.isEmpty()) throw IllegalArgumentException("Shapefile tidak berisi titik atau garis (hanya tipe Point, MultiPoint, Polyline, Polygon).")
    }

    private fun readShp(b: ByteArray, dbf: Dbf?, utm: Pair<Int, Boolean>?, acc: Acc) {
        val be = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (b.size < 100 || be.getInt(0) != 9994) throw IllegalArgumentException("File .shp tidak valid.")
        fun conv(x: Double, y: Double): TPoint? {
            if (utm != null) {
                val (la, lo) = GeoPdf.utmToLatLon(x, y, utm.first, utm.second)
                return if (validLL(la, lo)) TPoint(la, lo, 0L) else null
            }
            if (!validLL(y, x)) throw IllegalArgumentException("Koordinat shapefile bukan lat/lon derajat dan tidak ada info proyeksi (.prj). Ubah ke WGS84 (EPSG:4326) di QGIS lalu import lagi.")
            return TPoint(y, x, 0L)
        }
        var pos = 100
        var idx = 0
        while (pos + 12 <= b.size) {
            val words = be.getInt(pos + 4)
            val start = pos + 8
            val end = start + words * 2
            if (words < 2 || end > b.size) break
            val type = le.getInt(start)
            val attrs: Map<String, String>? = if (dbf == null) emptyMap() else dbf.rows.getOrNull(idx)
            idx++
            pos = end
            if (attrs == null) continue                     // record dihapus
            when (type) {
                1, 11, 21 -> conv(le.getDouble(start + 4), le.getDouble(start + 12))?.let { addMarker(acc, attrs, null, null, it.lat, it.lon, null) }
                8, 18, 28 -> {
                    val np = le.getInt(start + 36)
                    for (i in 0 until np) conv(le.getDouble(start + 40 + i * 16), le.getDouble(start + 48 + i * 16))?.let { addMarker(acc, attrs, null, null, it.lat, it.lon, null) }
                }
                3, 13, 23, 5, 15, 25 -> {
                    val nparts = le.getInt(start + 36)
                    val np = le.getInt(start + 40)
                    val partsAt = start + 44
                    val ptsAt = partsAt + nparts * 4
                    if (nparts < 0 || np < 0 || ptsAt + np * 16 > end) continue
                    for (k in 0 until nparts) {
                        val s0 = le.getInt(partsAt + k * 4)
                        val e0 = if (k + 1 < nparts) le.getInt(partsAt + (k + 1) * 4) else np
                        val pts = ArrayList<TPoint>()
                        for (i in s0 until e0) conv(le.getDouble(ptsAt + i * 16), le.getDouble(ptsAt + i * 16 + 8))?.let { pts.add(it) }
                        val nm = attrs.let { pick(it, "nama", "name", "judul", "title") ?: "" }
                        addTrack(acc, attrs, if (nparts > 1) "$nm (${k + 1})" else nm, pts)
                    }
                }
            }
        }
    }
}
