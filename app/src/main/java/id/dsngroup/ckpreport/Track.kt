package id.dsngroup.ckpreport

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TPoint(val lat: Double, val lon: Double, val t: Long)

data class TrackRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val est: String,
    val afd: String,
    val blok: String,
    val mapId: String,
    val points: List<TPoint>,
) {
    val distance: Double get() = Geo.length(points)
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
}

object Geo {
    fun dist(a: TPoint, b: TPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    fun length(p: List<TPoint>): Double {
        var d = 0.0
        for (i in 1 until p.size) d += dist(p[i - 1], p[i])
        return d
    }

    /** Luas poligon (jalur ditutup dari titik akhir ke awal), meter persegi, perkiraan datar. */
    fun areaM2(p: List<TPoint>): Double {
        if (p.size < 3) return 0.0
        val k = cos(Math.toRadians(p[0].lat))
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val ax = (a.lon - p[0].lon) * 111320.0 * k
            val ay = (a.lat - p[0].lat) * 110574.0
            val bx = (b.lon - p[0].lon) * 111320.0 * k
            val by = (b.lat - p[0].lat) * 110574.0
            sum += ax * by - bx * ay
        }
        return Math.abs(sum) / 2.0
    }

    fun luas(m2: Double): String =
        if (m2 >= 10000.0) Laporan.angka(m2 / 10000.0, 2) + " Ha (" + Laporan.angka(m2, 0) + " m²)"
        else Laporan.angka(m2, 1) + " m²"

    fun meter(m: Double): String = if (m >= 1000.0) Laporan.angka(m / 1000.0, 2) + " km" else Laporan.angka(m, 1) + " m"

    fun km(m: Double): String = Laporan.angka(m / 1000.0, 2) + " km"
}

/** Keadaan tracking yang sedang berjalan (dipakai bersama oleh Service dan layar). */
object Tracker {
    @Volatile var active = false
    @Volatile var startedAt = 0L
    @Volatile var gpsError: String? = null
    @Volatile var lastFixAt = 0L
    @Volatile var est = ""
    @Volatile var afd = ""
    @Volatile var blok = ""
    private val pts = ArrayList<TPoint>()
    private var lastSave = 0L

    private fun file(ctx: Context) = File(ctx.filesDir, "active_track.json")

    fun start(ctx: Context? = null, est: String = "", afd: String = "", blok: String = "") {
        synchronized(pts) { pts.clear() }
        startedAt = System.currentTimeMillis()
        gpsError = null
        lastFixAt = 0L
        this.est = est; this.afd = afd; this.blok = blok
        active = true
        if (ctx != null) save(ctx, true)
    }

    /** Hentikan dan kembalikan titik-titik yang terkumpul. */
    fun stop(ctx: Context? = null): List<TPoint> {
        active = false
        if (ctx != null) discard(ctx)
        return snapshot()
    }

    fun snapshot(): List<TPoint> = synchronized(pts) { pts.toList() }

    fun add(p: TPoint) {
        synchronized(pts) {
            val last = pts.lastOrNull()
            if (last == null || Geo.dist(last, p) >= 3.0) pts.add(p)   // abaikan getaran GPS < 3 m
        }
        lastFixAt = System.currentTimeMillis()
    }

    /** Simpan keadaan tracking ke file agar bisa dipulihkan bila aplikasi/service terbunuh sistem. */
    fun save(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 15000L) return
        lastSave = now
        try {
            val a = JSONArray()
            for (p in snapshot()) a.put(JSONArray().put(p.lat).put(p.lon).put(p.t))
            val o = JSONObject().put("start", startedAt).put("est", est).put("afd", afd).put("blok", blok).put("pts", a)
            val tmp = File(ctx.filesDir, "active_track.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file(ctx))
        } catch (e: Exception) {
            CrashLog.error(ctx, "Tracker.save", e)
        }
    }

    fun discard(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) {}
    }

    /** Ada tracking yang terputus (aplikasi/service terbunuh) dan belum diselesaikan? */
    fun pendingRecovery(ctx: Context): TrackRecord? {
        if (active) return null
        return readSaved(ctx)
    }

    private fun readSaved(ctx: Context): TrackRecord? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val a = o.optJSONArray("pts") ?: JSONArray()
            val list = ArrayList<TPoint>()
            for (i in 0 until a.length()) {
                val p = a.optJSONArray(i) ?: continue
                list.add(TPoint(p.getDouble(0), p.getDouble(1), p.optLong(2)))
            }
            if (list.isEmpty()) { f.delete(); return null }
            val st = o.optLong("start")
            TrackRecord("recovered", st, list.last().t.coerceAtLeast(st), o.optString("est"), o.optString("afd"), o.optString("blok"), "", list)
        } catch (e: Exception) {
            null
        }
    }

    /** Lanjutkan tracking dari file simpanan (titik lama dipertahankan). */
    fun resume(ctx: Context): Boolean {
        val r = readSaved(ctx) ?: return false
        synchronized(pts) { pts.clear(); pts.addAll(r.points) }
        startedAt = r.startedAt
        est = r.est; afd = r.afd; blok = r.blok
        gpsError = null
        lastFixAt = 0L
        active = true
        return true
    }
}

/** Service latar depan: tracking tetap berjalan walau layar mati / aplikasi dibuka-tutup. */
class TrackingService : Service(), LocationListener {
    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null
    private var nm: NotificationManager? = null
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: service dihidupkan ulang oleh sistem setelah terbunuh -> pulihkan dari file
        if (!Tracker.active) {
            if (Tracker.resume(this)) CrashLog.event(this, "Tracking dipulihkan otomatis setelah service terbunuh (${Tracker.snapshot().size} titik)")
            else { stopSelf(); return START_NOT_STICKY }
        }
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            CrashLog.error(this, "startForeground", e)
            Tracker.gpsError = "Sistem menolak tracking di latar belakang: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWake()
        startUpdates()
        CrashLog.event(this, "Service tracking berjalan (intent=${if (intent == null) "restart sistem" else "start"})")
        return START_STICKY
    }

    private fun acquireWake() {
        try {
            if (wake?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ckpreport:tracking").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            CrashLog.error(this, "wakelock", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CrashLog.event(this, "Aplikasi digeser dari daftar terbaru (service tetap jalan: ${Tracker.active})")
        super.onTaskRemoved(rootIntent)
    }

    override fun onLowMemory() {
        CrashLog.event(this, "Sistem melaporkan memori rendah")
        super.onLowMemory()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tracking CKP", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pts = Tracker.snapshot()
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Tracking CKP aktif")
            .setContentText("${pts.size} titik • ${Geo.km(Geo.length(pts))}. Buka aplikasi lalu tekan Stop Tracking untuk selesai.")
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Tracker.gpsError = "GPS HP belum aktif. Aktifkan lokasi lalu mulai lagi."
                return
            }
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            Tracker.gpsError = "GPS tidak dapat dipakai: ${e.message}"
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!Tracker.active) return
        if (location.hasAccuracy() && location.accuracy > 35f) return   // abaikan titik yang kurang akurat
        Tracker.add(TPoint(location.latitude, location.longitude, System.currentTimeMillis()))
        Tracker.save(this)
        val now = System.currentTimeMillis()
        if (now - lastNotif > 30000L) {
            lastNotif = now
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) } catch (e: Exception) {}
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) CrashLog.event(this, "GPS HP dimatikan saat tracking")
    }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        CrashLog.event(this, "Service tracking berhenti (tracking aktif=${Tracker.active})")
        if (Tracker.active) Tracker.save(this, true)   // dihentikan sistem: simpan agar bisa dipulihkan
        try { lm?.removeUpdates(this) } catch (e: Exception) {}
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 77
    }
}

/** Riwayat tracking di HP (tidak diupload; bisa dibagikan / diimport sebagai cadangan). */
object TrackStore {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "tracks.json")

    fun toJson(r: TrackRecord): JSONObject {
        val pts = JSONArray()
        for (p in r.points) pts.put(JSONArray().put(p.lat).put(p.lon).put(p.t))
        return JSONObject()
            .put("id", r.id).put("start", r.startedAt).put("end", r.endedAt)
            .put("est", r.est).put("afd", r.afd).put("blok", r.blok).put("map", r.mapId)
            .put("pts", pts)
    }

    fun fromJson(o: JSONObject): TrackRecord {
        val a = o.optJSONArray("pts") ?: JSONArray()
        val pts = ArrayList<TPoint>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            pts.add(TPoint(p.getDouble(0), p.getDouble(1), p.optLong(2)))
        }
        return TrackRecord(
            o.getString("id"), o.optLong("start"), o.optLong("end"),
            o.optString("est"), o.optString("afd"), o.optString("blok"), o.optString("map"), pts,
        )
    }

    private fun read(ctx: Context): MutableList<TrackRecord> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<TrackRecord>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "tracks.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(ctx))
    }

    fun all(ctx: Context): List<TrackRecord> = synchronized(lock) { read(ctx).sortedByDescending { it.startedAt } }

    fun get(ctx: Context, id: String): TrackRecord? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    fun add(ctx: Context, r: TrackRecord) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == r.id }
        list.add(r)
        write(ctx, list)
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == id }
        write(ctx, list)
    }

    fun exportJson(list: List<TrackRecord>): String {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        return JSONObject().put("app", "ckpreport").put("type", "tracks").put("version", 1).put("tracks", a).toString()
    }

    /** @return jumlah track baru yang ditambahkan, atau -1 jika file tidak valid. */
    fun importJson(ctx: Context, text: String): Int = synchronized(lock) {
        try {
            val o = JSONObject(text)
            if (o.optString("app") != "ckpreport" || o.optString("type") != "tracks") return -1
            val a = o.getJSONArray("tracks")
            val list = read(ctx)
            var added = 0
            for (i in 0 until a.length()) {
                val r = fromJson(a.getJSONObject(i))
                if (list.none { it.id == r.id }) { list.add(r); added++ }
            }
            write(ctx, list)
            added
        } catch (e: Exception) {
            -1
        }
    }
}
