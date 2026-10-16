package id.dsngroup.ckpreport

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ekspor tanda & tracking ke format GIS: KML/KMZ, GPX, GeoJSON, Shapefile (cocok untuk QGIS, ArcGIS, Google Earth, Avenza). */
object Gis {
    enum class Format(val label: String, val ext: String, val mime: String) {
        KML("KML (Google Earth, QGIS, ArcGIS)", "kml", "application/vnd.google-earth.kml+xml"),
        KMZ("KMZ + foto (Google Earth, QGIS, ArcGIS)", "kmz", "application/vnd.google-earth.kmz"),
        SHP("Shapefile .zip (ArcGIS, QGIS)", "zip", "application/zip"),
        GEOJSON("GeoJSON (QGIS, ArcGIS Online)", "geojson", "application/geo+json"),
        GPX("GPX (GPS, Avenza, QGIS)", "gpx", "application/gpx+xml"),
    }

    private fun d6(v: Double) = String.format(Locale.US, "%.7f", v)

    private fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun trackName(r: TrackRecord) =
        "Tracking ${if (r.blok.isNotBlank()) "Blok " + r.blok else ""} ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale("in", "ID")).format(Date(r.startedAt))}".replace("  ", " ").trim()

    // ---------------- KML ----------------

    /** @param photoRef nama file foto relatif di dalam KMZ untuk tanda ini (null = tanpa foto). */
    fun kml(markers: List<Marker>, tracks: List<TrackRecord>, photoRef: (Marker) -> String? = { null }): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>CKP Report</name>\n")
        sb.append("<Style id=\"trk\"><LineStyle><color>ff0000ff</color><width>3</width></LineStyle></Style>\n")
        sb.append("<Style id=\"tanda\"><IconStyle><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/pushpin/red-pushpin.png</href></Icon></IconStyle></Style>\n")
        if (markers.isNotEmpty()) {
            sb.append("<Folder><name>Tanda</name>\n")
            for (m in markers) {
                val ic = MarkerIcons.of(m.icon)
                val ph = photoRef(m)
                val html = StringBuilder()
                html.append("<table>")
                html.append("<tr><td><b>Kategori</b></td><td>${xml(ic.label)}</td></tr>")
                if (m.blok.isNotBlank()) html.append("<tr><td><b>Blok</b></td><td>${xml(m.blok)}</td></tr>")
                val v = MarkingText.volumeText(m)
                if (v.isNotBlank()) html.append("<tr><td><b>Volume</b></td><td>${xml(v)}</td></tr>")
                if (m.note.isNotBlank()) html.append("<tr><td><b>Keterangan</b></td><td>${xml(m.note)}</td></tr>")
                html.append("<tr><td><b>Tanggal</b></td><td>${xml(MarkingText.tanggal(m.createdAt))}</td></tr>")
                if (m.acc >= 0.0) html.append("<tr><td><b>Akurasi GPS</b></td><td>${xml(MarkingText.accText(m))}</td></tr>")
                html.append("</table>")
                if (ph != null) html.append("<br/><img src=\"${xml(ph)}\" width=\"400\"/>")
                sb.append("<Placemark><name>${xml(m.title)}</name>")
                sb.append("<description><![CDATA[${html.toString().replace("]]>", "]]&gt;")}]]></description>")
                sb.append("<TimeStamp><when>${iso(m.createdAt)}</when></TimeStamp><styleUrl>#tanda</styleUrl>")
                sb.append("<ExtendedData>")
                sb.append("<Data name=\"kategori\"><value>${xml(ic.label)}</value></Data>")
                sb.append("<Data name=\"blok\"><value>${xml(m.blok)}</value></Data>")
                sb.append("<Data name=\"keterangan\"><value>${xml(m.note)}</value></Data>")
                sb.append("<Data name=\"volume\"><value>${d6(m.volume)}</value></Data>")
                sb.append("<Data name=\"satuan\"><value>${xml(m.unit)}</value></Data>")
                if (m.acc >= 0.0) sb.append("<Data name=\"akurasi_m\"><value>${d6(m.acc)}</value></Data>")
                sb.append("</ExtendedData>")
                sb.append("<Point><coordinates>${d6(m.lon)},${d6(m.lat)},0</coordinates></Point></Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        if (tracks.isNotEmpty()) {
            sb.append("<Folder><name>Tracking</name>\n")
            for (r in tracks) {
                if (r.points.size < 2) continue
                sb.append("<Placemark><name>${xml(trackName(r))}</name>")
                sb.append("<description>${xml("Jarak ${Geo.km(r.distance)}; ${r.points.size} titik; Est ${r.est}; Afd ${r.afd}")}</description>")
                sb.append("<TimeSpan><begin>${iso(r.startedAt)}</begin><end>${iso(r.endedAt)}</end></TimeSpan><styleUrl>#trk</styleUrl>")
                sb.append("<LineString><tessellate>1</tessellate><coordinates>")
                sb.append(r.points.joinToString(" ") { "${d6(it.lon)},${d6(it.lat)},0" })
                sb.append("</coordinates></LineString></Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    // ---------------- GPX ----------------

    fun gpx(markers: List<Marker>, tracks: List<TrackRecord>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"CKP Report\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (m in markers) {
            val ic = MarkerIcons.of(m.icon)
            val desc = listOf(
                ic.label, if (m.blok.isNotBlank()) "Blok ${m.blok}" else "", MarkingText.volumeText(m), m.note,
                if (m.acc >= 0.0) "Akurasi ${MarkingText.accText(m)}" else "",
            ).filter { it.isNotBlank() }.joinToString("; ")
            sb.append("<wpt lat=\"${d6(m.lat)}\" lon=\"${d6(m.lon)}\"><time>${iso(m.createdAt)}</time>")
            sb.append("<name>${xml(m.title)}</name><desc>${xml(desc)}</desc><sym>${xml(ic.label)}</sym></wpt>\n")
        }
        for (r in tracks) {
            if (r.points.size < 2) continue
            sb.append("<trk><name>${xml(trackName(r))}</name><trkseg>\n")
            for (p in r.points) sb.append("<trkpt lat=\"${d6(p.lat)}\" lon=\"${d6(p.lon)}\"><time>${iso(if (p.t > 0) p.t else r.startedAt)}</time></trkpt>\n")
            sb.append("</trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    // ---------------- GeoJSON ----------------

    private fun js(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun geojson(markers: List<Marker>, tracks: List<TrackRecord>): String {
        val feats = ArrayList<String>()
        for (m in markers) {
            val ic = MarkerIcons.of(m.icon)
            feats.add(
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[${d6(m.lon)},${d6(m.lat)}]}," +
                    "\"properties\":{\"jenis\":\"tanda\",\"judul\":${js(m.title)},\"kategori\":${js(ic.label)},\"blok\":${js(m.blok)}," +
                    "\"keterangan\":${js(m.note)},\"volume\":${d6(m.volume)},\"satuan\":${js(m.unit)},\"tanggal\":${js(iso(m.createdAt))},\"akurasi_m\":${if (m.acc >= 0.0) d6(m.acc) else "null"}}}"
            )
        }
        for (r in tracks) {
            if (r.points.size < 2) continue
            feats.add(
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[" +
                    r.points.joinToString(",") { "[${d6(it.lon)},${d6(it.lat)}]" } + "]}," +
                    "\"properties\":{\"jenis\":\"tracking\",\"nama\":${js(trackName(r))},\"est\":${js(r.est)},\"afd\":${js(r.afd)},\"blok\":${js(r.blok)}," +
                    "\"mulai\":${js(iso(r.startedAt))},\"selesai\":${js(iso(r.endedAt))},\"jarak_m\":${d6(r.distance)}}}"
            )
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[\n" + feats.joinToString(",\n") + "\n]}\n"
    }

    // ---------------- Shapefile ----------------

    private const val PRJ = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
        "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"

    private class Field(val name: String, val type: Char, val len: Int, val dec: Int = 0)

    private fun fit(s: String, maxBytes: Int): ByteArray {
        var t = s
        var b = t.toByteArray(Charsets.UTF_8)
        while (b.size > maxBytes && t.isNotEmpty()) { t = t.dropLast(1); b = t.toByteArray(Charsets.UTF_8) }
        return b
    }

    private fun dbf(fields: List<Field>, rows: List<List<String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val recLen = 1 + fields.sumOf { it.len }
        val hdrLen = 32 + 32 * fields.size + 1
        val h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        val cal = java.util.Calendar.getInstance()
        h.put(3).put((cal.get(java.util.Calendar.YEAR) - 1900).toByte()).put((cal.get(java.util.Calendar.MONTH) + 1).toByte())
            .put(cal.get(java.util.Calendar.DAY_OF_MONTH).toByte())
        h.putInt(rows.size).putShort(hdrLen.toShort()).putShort(recLen.toShort())
        out.write(h.array())
        for (f in fields) {
            val fd = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            val nb = f.name.toByteArray(Charsets.US_ASCII)
            fd.put(nb, 0, minOf(10, nb.size))
            fd.position(11)
            fd.put(f.type.code.toByte())
            fd.position(16)
            fd.put(f.len.toByte()).put(f.dec.toByte())
            out.write(fd.array())
        }
        out.write(0x0D)
        for (r in rows) {
            out.write(' '.code)
            for ((i, f) in fields.withIndex()) {
                val raw = r[i].replace('\n', ' ').replace('\r', ' ')
                val bytes = fit(raw, f.len)
                if (f.type == 'N') {
                    repeat(f.len - bytes.size) { out.write(' '.code) }
                    out.write(bytes)
                } else {
                    out.write(bytes)
                    repeat(f.len - bytes.size) { out.write(' '.code) }
                }
            }
        }
        out.write(0x1A)
        return out.toByteArray()
    }

    private fun header(buf: ByteBuffer, fileWords: Int, type: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(9994)
        repeat(5) { buf.putInt(0) }
        buf.putInt(fileWords)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(1000).putInt(type)
        buf.putDouble(x0).putDouble(y0).putDouble(x1).putDouble(y1)
        buf.putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(0.0)
    }

    /** Hasil: (shp, shx). Tiap bentuk = daftar bagian, tiap bagian = daftar (lon,lat). Titik: 1 bagian 1 titik. */
    private fun shp(isPoint: Boolean, shapes: List<List<DoubleArray>>): Pair<ByteArray, ByteArray> {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (s in shapes) for (p in s) { minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0]); minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1]) }
        val recBytes = shapes.map { if (isPoint) 20 else 44 + 4 + 16 * it.size }   // isi rekaman (tanpa header 8 byte)
        val shpLen = 100 + recBytes.sumOf { it + 8 }
        val shxLen = 100 + 8 * shapes.size
        val a = ByteBuffer.allocate(shpLen)
        val x = ByteBuffer.allocate(shxLen)
        val type = if (isPoint) 1 else 3
        header(a, shpLen / 2, type, minX, minY, maxX, maxY)
        header(x, shxLen / 2, type, minX, minY, maxX, maxY)
        var offset = 100
        for ((i, s) in shapes.withIndex()) {
            val cl = recBytes[i]
            a.order(ByteOrder.BIG_ENDIAN); a.putInt(i + 1); a.putInt(cl / 2)
            x.order(ByteOrder.BIG_ENDIAN); x.putInt(offset / 2); x.putInt(cl / 2)
            a.order(ByteOrder.LITTLE_ENDIAN)
            a.putInt(type)
            if (isPoint) {
                a.putDouble(s[0][0]).putDouble(s[0][1])
            } else {
                var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE; var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
                for (p in s) { bx0 = minOf(bx0, p[0]); bx1 = maxOf(bx1, p[0]); by0 = minOf(by0, p[1]); by1 = maxOf(by1, p[1]) }
                a.putDouble(bx0).putDouble(by0).putDouble(bx1).putDouble(by1)
                a.putInt(1).putInt(s.size).putInt(0)
                for (p in s) { a.putDouble(p[0]); a.putDouble(p[1]) }
            }
            offset += cl + 8
        }
        return Pair(a.array(), x.array())
    }

    private fun put(z: ZipOutputStream, name: String, bytes: ByteArray) {
        z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
    }

    fun shapefileZip(markers: List<Marker>, tracks: List<TrackRecord>, out: File) {
        ZipOutputStream(FileOutputStream(out)).use { z ->
            if (markers.isNotEmpty()) {
                val (s, x) = shp(true, markers.map { listOf(doubleArrayOf(it.lon, it.lat)) })
                put(z, "ckp_tanda.shp", s); put(z, "ckp_tanda.shx", x)
                val f = listOf(
                    Field("JUDUL", 'C', 100), Field("KATEGORI", 'C', 30), Field("BLOK", 'C', 20), Field("KETERANGAN", 'C', 254),
                    Field("VOLUME", 'N', 18, 3), Field("SATUAN", 'C', 20), Field("TANGGAL", 'C', 20),
                    Field("LAT", 'N', 14, 7), Field("LON", 'N', 14, 7), Field("AKURASI_M", 'N', 8, 1),
                )
                put(z, "ckp_tanda.dbf", dbf(f, markers.map {
                    listOf(it.title, MarkerIcons.of(it.icon).label, it.blok, it.note, String.format(Locale.US, "%.3f", it.volume), it.unit,
                        iso(it.createdAt), d6(it.lat), d6(it.lon), if (it.acc >= 0.0) String.format(Locale.US, "%.1f", it.acc) else "")
                }))
                put(z, "ckp_tanda.prj", PRJ.toByteArray()); put(z, "ckp_tanda.cpg", "UTF-8".toByteArray())
            }
            val tr = tracks.filter { it.points.size >= 2 }
            if (tr.isNotEmpty()) {
                val (s, x) = shp(false, tr.map { r -> r.points.map { doubleArrayOf(it.lon, it.lat) } })
                put(z, "ckp_tracking.shp", s); put(z, "ckp_tracking.shx", x)
                val f = listOf(
                    Field("NAMA", 'C', 100), Field("EST", 'C', 30), Field("AFD", 'C', 30), Field("BLOK", 'C', 20),
                    Field("MULAI", 'C', 20), Field("SELESAI", 'C', 20), Field("JARAK_M", 'N', 14, 1), Field("TITIK", 'N', 8, 0),
                )
                put(z, "ckp_tracking.dbf", dbf(f, tr.map {
                    listOf(trackName(it), it.est, it.afd, it.blok, iso(it.startedAt), iso(it.endedAt),
                        String.format(Locale.US, "%.1f", it.distance), it.points.size.toString())
                }))
                put(z, "ckp_tracking.prj", PRJ.toByteArray()); put(z, "ckp_tracking.cpg", "UTF-8".toByteArray())
            }
        }
    }

    // ---------------- Ekspor ----------------

    /** Tulis file ekspor. @return file hasil atau null bila tidak ada data. */
    fun export(ctx: Context, format: Format, markers: List<Marker>, tracks: List<TrackRecord>, dir: File): File? {
        val tr = tracks.filter { it.points.size >= 2 }
        if (markers.isEmpty() && tr.isEmpty()) return null
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val f = File(dir, "ckp_peta_$stamp.${format.ext}")
        when (format) {
            Format.KML -> f.writeText(kml(markers, tr), Charsets.UTF_8)
            Format.GPX -> f.writeText(gpx(markers, tr), Charsets.UTF_8)
            Format.GEOJSON -> f.writeText(geojson(markers, tr), Charsets.UTF_8)
            Format.SHP -> shapefileZip(markers, tr, f)
            Format.KMZ -> {
                val withPhoto = markers.filter { it.photo.isNotBlank() && MarkerStore.photoFile(ctx, it.id).exists() }.map { it.id }.toSet()
                ZipOutputStream(FileOutputStream(f)).use { z ->
                    put(z, "doc.kml", kml(markers, tr) { if (it.id in withPhoto) "files/${it.id}.jpg" else null }.toByteArray(Charsets.UTF_8))
                    for (id in withPhoto) {
                        z.putNextEntry(ZipEntry("files/$id.jpg"))
                        MarkerStore.photoFile(ctx, id).inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
        }
        return f
    }
}
