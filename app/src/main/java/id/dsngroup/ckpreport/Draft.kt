package id.dsngroup.ckpreport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Draf laporan: isian form + foto asli, disimpan di HP untuk dikoreksi dulu sebelum dikirim. */
data class Draft(
    val id: String,
    val savedAt: Long,
    val est: String,
    val afd: String,
    val blok: String,
    val problem: String,
    val perbaikan: String,
    val lat: Double,            // NaN = belum ada koordinat
    val lon: Double,
    val losses: LossesData?,
    val grading: GradingData?,
    val kalibrasi: KalibrasiData?,
    val lux: Double,
    val trackId: String,        // "" = tanpa tracking
    val attachTrack: Boolean,
    val photos: List<String>,   // nama file di folder draf
) {
    val hasCoord: Boolean get() = !lat.isNaN() && !lon.isNaN()
}

object DraftStore {
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "drafts.json")
    fun dir(ctx: Context, id: String) = File(File(ctx.filesDir, "drafts"), id).apply { mkdirs() }

    private fun dbl(o: JSONObject, k: String) = o.optDouble(k, Double.NaN)

    private fun toJson(d: Draft): JSONObject {
        val o = JSONObject()
            .put("id", d.id).put("t", d.savedAt)
            .put("est", d.est).put("afd", d.afd).put("blok", d.blok)
            .put("problem", d.problem).put("perbaikan", d.perbaikan)
            .put("lux", d.lux).put("track", d.trackId).put("attach", d.attachTrack)
            .put("photos", JSONArray(d.photos))
        if (d.hasCoord) o.put("lat", d.lat).put("lon", d.lon)
        d.losses?.let { o.put("losses", JSONObject().put("n", it.namaAncak).put("l", it.luas).put("b", it.brondol).put("j", it.janjang).put("bjr", it.bjr)) }
        d.grading?.let {
            val g = JSONObject().put("t", it.noTph).put("r", it.ripe).put("m", it.mengkal).put("mt", it.mentah)
                .put("o", it.overripe).put("bjr", it.bjr).put("b", it.brondol)
                .put("pn", it.namaPemanen).put("pk", it.kodePemanen)
            if (it.hasCoord) g.put("lat", it.lat).put("lon", it.lon)
            o.put("grading", g)
        }
        d.kalibrasi?.let {
            val k = JSONObject().put("t", it.noTph).put("j", it.janjang).put("k", it.tonaseKg)
                .put("pn", it.namaPemanen).put("pk", it.kodePemanen)
            if (it.hasCoord) k.put("lat", it.lat).put("lon", it.lon)
            o.put("kal", k)
        }
        return o
    }

    private fun fromJson(o: JSONObject): Draft {
        val pa = o.optJSONArray("photos") ?: JSONArray()
        return Draft(
            id = o.getString("id"), savedAt = o.optLong("t"),
            est = o.optString("est"), afd = o.optString("afd"), blok = o.optString("blok"),
            problem = o.optString("problem"), perbaikan = o.optString("perbaikan"),
            lat = dbl(o, "lat"), lon = dbl(o, "lon"),
            losses = o.optJSONObject("losses")?.let { LossesData(it.optString("n"), it.optDouble("l"), it.optDouble("b"), it.optDouble("j"), it.optDouble("bjr")) },
            grading = o.optJSONObject("grading")?.let {
                GradingData(it.optString("t"), it.optDouble("r"), it.optDouble("m"), it.optDouble("mt"), it.optDouble("o"),
                    it.optDouble("bjr"), it.optDouble("b"), dbl(it, "lat"), dbl(it, "lon"),
                    it.optString("pn"), it.optString("pk"))
            },
            kalibrasi = o.optJSONObject("kal")?.let {
                KalibrasiData(it.optString("t"), it.optDouble("j"), it.optDouble("k"), dbl(it, "lat"), dbl(it, "lon"),
                    it.optString("pn"), it.optString("pk"))
            },
            lux = o.optDouble("lux", -1.0),
            trackId = o.optString("track"), attachTrack = o.optBoolean("attach"),
            photos = List(pa.length()) { pa.getString(it) },
        )
    }

    private fun read(ctx: Context): MutableList<Draft> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<Draft>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "drafts.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(ctx))
    }

    fun all(ctx: Context): List<Draft> = synchronized(lock) { read(ctx).sortedByDescending { it.savedAt } }

    fun get(ctx: Context, id: String): Draft? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    /** Gabungkan draf dari cadangan (id yang sudah ada dilewati). @return jumlah baru. */
    fun mergeFromJson(ctx: Context, text: String, srcDrafts: File): Int = synchronized(lock) {
        val a = try { JSONArray(text) } catch (e: Exception) { return 0 }
        val list = read(ctx)
        var added = 0
        for (i in 0 until a.length()) {
            val d = try { fromJson(a.getJSONObject(i)) } catch (e: Exception) { continue }
            if (list.any { it.id == d.id }) continue
            val src = File(srcDrafts, d.id)
            if (src.exists()) src.copyRecursively(dir(ctx, d.id), overwrite = true)
            list.add(d)
            added++
        }
        write(ctx, list)
        added
    }

    /** Simpan (atau timpa draf dengan id yang sama). */
    fun save(ctx: Context, d: Draft) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == d.id }
        list.add(d)
        write(ctx, list)
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == id }
        write(ctx, list)
        File(File(ctx.filesDir, "drafts"), id).deleteRecursively()
    }
}
