package id.dsngroup.ckpreport

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val ST_PENDING = "PENDING"
const val ST_DONE = "DONE"

data class Report(
    val id: String,
    val createdAt: Long,
    val est: String,
    val afd: String,
    val blok: String,
    val problem: String,
    val lat: Double,
    val lon: Double,
    val pelapor: String,
    val photoPath: String,
    val losses: LossesData? = null,
    val grading: GradingData? = null,
    val kalibrasi: KalibrasiData? = null,
    val lux: Double = -1.0,
    val perbaikan: String = "",
    var status: String = ST_PENDING,
    var fotoUrl: String = "",
    var error: String = "",
    var uploadedAt: Long = 0L,
) {
    val waktuText: String
        get() = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("in", "ID")).format(Date(createdAt))
    val activityText: String
        get() = listOfNotNull(
            if (losses != null) "Losses" else null,
            if (grading != null) "Grading TPH" else null,
            if (kalibrasi != null) "Kalibrasi BJR" else null,
        ).joinToString(", ")
    val waktuIso: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(createdAt))
}

/** Laporan tersimpan di HP (file JSON + foto), aman walau aplikasi ditutup / HP restart. */
object ReportStore {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var onChange: (() -> Unit)? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "reports.json")
    fun photoDir(ctx: Context) = File(ctx.filesDir, "reports").apply { mkdirs() }

    private fun fire() {
        main.post { onChange?.invoke() }
    }

    private fun read(ctx: Context): MutableList<Report> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<Report>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "reports.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(ctx))
    }

    fun all(ctx: Context): List<Report> = synchronized(lock) { read(ctx).sortedByDescending { it.createdAt } }

    fun get(ctx: Context, id: String): Report? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    fun pendingCount(ctx: Context): Int = synchronized(lock) { read(ctx).count { it.status == ST_PENDING } }

    fun add(ctx: Context, r: Report) {
        synchronized(lock) {
            val list = read(ctx)
            // Bersihkan laporan yang sudah terupload lebih dari 14 hari
            val cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
            val old = list.filter { it.status == ST_DONE && it.uploadedAt in 1 until cutoff }
            old.forEach { File(it.photoPath).delete() }
            list.removeAll(old.toSet())
            list.add(r)
            write(ctx, list)
        }
        fire()
    }

    fun update(ctx: Context, id: String, block: (Report) -> Unit) {
        synchronized(lock) {
            val list = read(ctx)
            val r = list.firstOrNull { it.id == id } ?: return
            block(r)
            write(ctx, list)
        }
        fire()
    }

    fun remove(ctx: Context, id: String) {
        synchronized(lock) {
            val list = read(ctx)
            val r = list.firstOrNull { it.id == id } ?: return
            File(r.photoPath).delete()
            list.remove(r)
            write(ctx, list)
        }
        fire()
    }

    /** Gabungkan laporan dari cadangan (id yang sudah ada dilewati). @return jumlah baru. */
    fun mergeFromJson(ctx: Context, text: String, srcPhotos: File): Int {
        var added = 0
        synchronized(lock) {
            val a = try { JSONArray(text) } catch (e: Exception) { return 0 }
            val list = read(ctx)
            for (i in 0 until a.length()) {
                val r = try { fromJson(a.getJSONObject(i)) } catch (e: Exception) { continue }
                if (list.any { it.id == r.id }) continue
                val name = File(r.photoPath).name
                val dest = File(photoDir(ctx), name)
                val src = File(srcPhotos, name)
                if (src.exists() && !dest.exists()) src.copyTo(dest)
                list.add(r.copy(photoPath = dest.path))
                added++
            }
            write(ctx, list)
        }
        fire()
        return added
    }

    private fun toJson(r: Report) = JSONObject()
        .put("id", r.id).put("t", r.createdAt)
        .put("est", r.est).put("afd", r.afd).put("blok", r.blok).put("problem", r.problem)
        .put("lat", r.lat).put("lon", r.lon).put("pelapor", r.pelapor).put("photo", r.photoPath)
        .put("lux", r.lux).put("perbaikan", r.perbaikan)
        .put("losses", r.losses?.let { JSONObject().put("n", it.namaAncak).put("l", it.luas).put("b", it.brondol).put("j", it.janjang).put("bjr", it.bjr).put("bl", it.blok) })
        .put("grading", r.grading?.let { JSONObject().put("t", it.noTph).put("r", it.ripe).put("m", it.mengkal).put("mt", it.mentah).put("o", it.overripe).put("bjr", it.bjr).put("b", it.brondol).put("pn", it.namaPemanen).put("pk", it.kodePemanen).put("bl", it.blok).also { o -> if (it.hasCoord) o.put("lat", it.lat).put("lon", it.lon) } })
        .put("kal", r.kalibrasi?.let { JSONObject().put("t", it.noTph).put("j", it.janjang).put("k", it.tonaseKg).put("pn", it.namaPemanen).put("pk", it.kodePemanen).put("bl", it.blok).also { o -> if (it.hasCoord) o.put("lat", it.lat).put("lon", it.lon) } })
        .put("status", r.status).put("fotoUrl", r.fotoUrl).put("error", r.error).put("upAt", r.uploadedAt)

    private fun fromJson(o: JSONObject) = Report(
        id = o.getString("id"), createdAt = o.getLong("t"),
        est = o.getString("est"), afd = o.getString("afd"), blok = o.getString("blok"),
        problem = o.getString("problem"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
        pelapor = o.optString("pelapor", ""), photoPath = o.getString("photo"),
        losses = o.optJSONObject("losses")?.let { LossesData(it.getString("n"), it.getDouble("l"), it.getDouble("b"), it.getDouble("j"), it.getDouble("bjr"), it.optString("bl", "")) },
        grading = o.optJSONObject("grading")?.let {
            GradingData(it.getString("t"), it.getDouble("r"), it.getDouble("m"), it.getDouble("mt"), it.getDouble("o"), it.getDouble("bjr"), it.getDouble("b"),
                it.optDouble("lat", Double.NaN), it.optDouble("lon", Double.NaN),
                it.optString("pn", ""), it.optString("pk", ""), it.optString("bl", ""))
        },
        kalibrasi = o.optJSONObject("kal")?.let { KalibrasiData(it.getString("t"), it.getDouble("j"), it.getDouble("k"), it.optDouble("lat", Double.NaN), it.optDouble("lon", Double.NaN), it.optString("pn", ""), it.optString("pk", ""), it.optString("bl", "")) },
        lux = o.optDouble("lux", -1.0), perbaikan = o.optString("perbaikan", ""),
        status = o.optString("status", ST_PENDING), fotoUrl = o.optString("fotoUrl", ""),
        error = o.optString("error", ""), uploadedAt = o.optLong("upAt", 0L),
    )
}
