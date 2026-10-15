package id.dsngroup.ckpreport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tanda / marking pada peta. */
data class Marker(
    val id: String,
    val createdAt: Long,
    val icon: String,       // kunci ikon (lihat MarkerIcons)
    val title: String,
    val blok: String,
    val note: String,
    val volume: Double,
    val unit: String,
    val lat: Double,
    val lon: Double,
)

data class MarkerIcon(val key: String, val emoji: String, val label: String)

object MarkerIcons {
    val all = listOf(
        MarkerIcon("warn", "⚠️", "Masalah"),
        MarkerIcon("pin", "📍", "Titik"),
        MarkerIcon("tree", "🌴", "Pokok"),
        MarkerIcon("road", "🚧", "Jalan"),
        MarkerIcon("water", "💧", "Air / Parit"),
        MarkerIcon("pest", "🐛", "Hama"),
        MarkerIcon("fire", "🔥", "Api"),
        MarkerIcon("tool", "🔧", "Perbaikan"),
        MarkerIcon("done", "✅", "Selesai"),
        MarkerIcon("house", "🏠", "Bangunan"),
    )

    fun of(key: String): MarkerIcon = all.firstOrNull { it.key == key } ?: all[1]
}

object MarkerStore {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "markers.json")

    fun toJson(m: Marker) = JSONObject()
        .put("id", m.id).put("t", m.createdAt).put("icon", m.icon).put("title", m.title)
        .put("blok", m.blok).put("note", m.note).put("vol", m.volume).put("unit", m.unit)
        .put("lat", m.lat).put("lon", m.lon)

    fun fromJson(o: JSONObject) = Marker(
        o.getString("id"), o.optLong("t"), o.optString("icon", "pin"), o.optString("title"),
        o.optString("blok"), o.optString("note"), o.optDouble("vol", 0.0), o.optString("unit"),
        o.getDouble("lat"), o.getDouble("lon"),
    )

    private fun read(ctx: Context): MutableList<Marker> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<Marker>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "markers.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(ctx))
    }

    fun all(ctx: Context): List<Marker> = synchronized(lock) { read(ctx).sortedByDescending { it.createdAt } }

    fun save(ctx: Context, m: Marker) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == m.id }
        list.add(m)
        write(ctx, list)
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == id }
        write(ctx, list)
    }

    /** @return jumlah tanda baru. */
    fun merge(ctx: Context, incoming: List<Marker>): Int = synchronized(lock) {
        val list = read(ctx)
        var added = 0
        for (m in incoming) if (list.none { it.id == m.id }) { list.add(m); added++ }
        write(ctx, list)
        added
    }
}

/** Ekspor / impor database (tracking + tanda) dalam satu file JSON. */
object DataIO {
    fun exportAll(tracks: List<TrackRecord>, markers: List<Marker>): String {
        val t = JSONArray()
        tracks.forEach { t.put(TrackStore.toJson(it)) }
        val m = JSONArray()
        markers.forEach { m.put(MarkerStore.toJson(it)) }
        return JSONObject().put("app", "ckpreport").put("type", "data").put("version", 2)
            .put("tracks", t).put("markers", m).toString()
    }

    /** @return (jumlah tracking baru, jumlah tanda baru), atau null jika file tidak valid. */
    fun importJson(ctx: Context, text: String): Pair<Int, Int>? {
        return try {
            val o = JSONObject(text)
            if (o.optString("app") != "ckpreport") return null
            val type = o.optString("type")
            if (type != "tracks" && type != "data") return null
            var nt = 0
            var nm = 0
            val ta = o.optJSONArray("tracks")
            if (ta != null) {
                val wrap = JSONObject().put("app", "ckpreport").put("type", "tracks").put("tracks", ta)
                nt = TrackStore.importJson(ctx, wrap.toString()).coerceAtLeast(0)
            }
            val ma = o.optJSONArray("markers")
            if (ma != null) {
                val list = ArrayList<Marker>()
                for (i in 0 until ma.length()) list.add(MarkerStore.fromJson(ma.getJSONObject(i)))
                nm = MarkerStore.merge(ctx, list)
            }
            Pair(nt, nm)
        } catch (e: Exception) {
            null
        }
    }
}

/** Teks untuk dibagikan (WhatsApp / CSV). */
object MarkingText {
    private val idn = Locale("in", "ID")

    fun tanggal(t: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm", idn).format(Date(t))

    fun volumeText(m: Marker): String =
        if (m.volume == 0.0 && m.unit.isBlank()) "" else (Laporan.angka(m.volume, 3) + " " + m.unit).trim()

    fun pesan(m: Marker): String = buildString {
        val ic = MarkerIcons.of(m.icon)
        append("${ic.emoji} *${m.title}*\n")
        append("Kategori : ${ic.label}\n")
        if (m.blok.isNotBlank()) append("Blok : ${m.blok}\n")
        val v = volumeText(m)
        if (v.isNotBlank()) append("Volume : $v\n")
        if (m.note.isNotBlank()) append("Keterangan : ${m.note}\n")
        append("Tanggal : ${tanggal(m.createdAt)}\n\n")
        append("Koordinat : ${Laporan.koordinatSalin(m.lat, m.lon)}\n")
        append("Google Maps : ${Laporan.mapsUrl(m.lat, m.lon)}")
    }

    /** Total volume per satuan, mis. "150 Pokok • 3,5 Ha". */
    fun totals(list: List<Marker>): String {
        val sums = LinkedHashMap<String, Double>()
        val names = HashMap<String, String>()
        for (m in list) {
            if (m.volume == 0.0) continue
            val k = m.unit.trim().lowercase()
            sums[k] = (sums[k] ?: 0.0) + m.volume
            if (!names.containsKey(k)) names[k] = m.unit.trim()
        }
        return sums.entries.joinToString(" • ") { (k, v) -> (Laporan.angka(v, 3) + " " + (names[k] ?: "")).trim() }
    }

    fun rekap(list: List<Marker>): String = buildString {
        append("*REKAP MARKING* (${list.size} tanda)\n")
        val t = totals(list)
        if (t.isNotBlank()) append("Total : $t\n")
        append("\n")
        list.forEachIndexed { i, m ->
            val ic = MarkerIcons.of(m.icon)
            append("${i + 1}. ${ic.emoji} *${m.title}*")
            val extra = listOf(
                if (m.blok.isNotBlank()) "Blok ${m.blok}" else "",
                volumeText(m),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            if (extra.isNotBlank()) append(" • $extra")
            append("\n")
            if (m.note.isNotBlank()) append("    ${m.note}\n")
            append("    ${Laporan.koordinatSalin(m.lat, m.lon)}\n")
        }
    }.trimEnd()

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun dec(v: Double) = v.toString().replace('.', ',')

    /** CSV (pemisah titik-koma, desimal koma) agar langsung terbuka rapi di Excel Indonesia. */
    fun csv(list: List<Marker>): String = buildString {
        append('﻿')
        append("Tanggal;Kategori;Judul;Blok;Keterangan;Volume;Satuan;Latitude;Longitude\n")
        for (m in list) {
            append(listOf(
                q(tanggal(m.createdAt)), q(MarkerIcons.of(m.icon).label), q(m.title), q(m.blok), q(m.note),
                dec(m.volume), q(m.unit), dec(m.lat), dec(m.lon),
            ).joinToString(";")).append('\n')
        }
    }
}
