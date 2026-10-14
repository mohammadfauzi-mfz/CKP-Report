package id.dsngroup.ckpreport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object Sync {
    enum class Outcome { OK, NETWORK, SERVER, SKIPPED }

    private val busy = HashSet<String>()

    fun hasNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Upload satu laporan. Aman dipanggil dari UI maupun Worker (tidak akan dobel). */
    fun uploadOne(ctx: Context, id: String): Outcome {
        val app = ctx.applicationContext
        synchronized(busy) { if (!busy.add(id)) return Outcome.SKIPPED }
        try {
            val r = ReportStore.get(app, id) ?: return Outcome.SKIPPED
            if (r.status == ST_DONE) return Outcome.OK

            val sp = app.getSharedPreferences("ckp", Context.MODE_PRIVATE)
            val url = sp.getString("url", "") ?: ""
            val token = sp.getString("token", "") ?: ""
            if (url.isBlank()) {
                ReportStore.update(app, id) { it.error = "URL Web App belum diatur di Pengaturan" }
                return Outcome.SERVER
            }

            val f = File(r.photoPath)
            val b64 = if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else ""
            val json = JSONObject()
                .put("token", token)
                .put("id", r.id)
                .put("waktu", r.waktuIso)
                .put("estate", r.est).put("afdeling", r.afd).put("blok", r.blok)
                .put("problem", r.problem)
                .put("lat", r.lat).put("lon", r.lon)
                .put("mapsUrl", Laporan.mapsUrl(r.lat, r.lon))
                .put("pelapor", r.pelapor)
                .put("fotoBase64", b64)
                .put("activity", r.activityText)
                .put("perbaikan", r.perbaikan)
                .put("lux", if (r.lux >= 0) r.lux else JSONObject.NULL)
            r.losses?.let { l ->
                json.put("losses", JSONObject().put("namaAncak", l.namaAncak).put("luas", l.luas)
                    .put("brondol", l.brondol).put("janjang", l.janjang).put("bjr", l.bjr)
                    .put("lossesJjg", l.lossesJjg).put("total", l.total).put("perHa", l.perHa))
            }
            r.grading?.let { g ->
                json.put("grading", JSONObject().put("noTph", g.noTph).put("namaPemanen", g.namaPemanen).put("kodePemanen", g.kodePemanen)
                    .put("ripe", g.ripe).put("mengkal", g.mengkal).put("mentah", g.mentah).put("overripe", g.overripe)
                    .put("total", g.total)
                    .put("pRipe", g.pct(g.ripe)).put("pMengkal", g.pct(g.mengkal))
                    .put("pMentah", g.pct(g.mentah)).put("pOverripe", g.pct(g.overripe))
                    .put("bjr", g.bjr).put("tonase", g.tonase).put("brondol", g.brondol).put("pBrondol", g.brondolPct)
                    .also { o -> if (g.hasCoord) o.put("lat", g.lat).put("lon", g.lon) })
            }
            r.kalibrasi?.let { k ->
                json.put("kalibrasi", JSONObject().put("noTph", k.noTph).put("namaPemanen", k.namaPemanen).put("kodePemanen", k.kodePemanen).put("janjang", k.janjang)
                    .put("tonaseKg", k.tonaseKg).put("bjr", k.bjr)
                    .also { o -> if (k.hasCoord) o.put("lat", k.lat).put("lon", k.lon) })
            }

            val res = Uploader.upload(url, json)
            return when {
                res.ok -> {
                    ReportStore.update(app, id) {
                        it.status = ST_DONE
                        it.fotoUrl = res.fotoUrl
                        it.error = ""
                        it.uploadedAt = System.currentTimeMillis()
                    }
                    Outcome.OK
                }
                res.network -> {
                    ReportStore.update(app, id) { it.error = "" }
                    Outcome.NETWORK
                }
                else -> {
                    ReportStore.update(app, id) { it.error = res.error }
                    Outcome.SERVER
                }
            }
        } finally {
            synchronized(busy) { busy.remove(id) }
        }
    }

    /** Jadwalkan upload otomatis begitu ada koneksi (jalan di latar belakang walau aplikasi ditutup). */
    fun schedule(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork("upload-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        var retry = false
        for (r in ReportStore.all(applicationContext).filter { it.status == ST_PENDING }.reversed()) {
            if (Sync.uploadOne(applicationContext, r.id) == Sync.Outcome.NETWORK) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }
}
