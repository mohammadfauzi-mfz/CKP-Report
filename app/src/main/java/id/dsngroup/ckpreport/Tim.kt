package id.dsngroup.ckpreport

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Satu temuan bersama (disinkronkan lewat Spreadsheet). */
data class Temuan(
    val id: String,
    val waktu: Long,
    val est: String,
    val afd: String,
    val blok: String,
    val judul: String,
    val icon: String,
    val ket: String,
    val volume: Double,
    val satuan: String,
    val lat: Double,
    val lon: Double,
    val acc: Double,
    val pNpk: String,
    val pNama: String,
    val status: String = Tim.OUTSTANDING,
    val tuntasAt: Long = 0L,
    val sNpk: String = "",
    val sNama: String = "",
    val catatan: String = "",
    val fotoT: Boolean = false,       // ada foto temuan (di server atau lokal)
    val fotoP: Boolean = false,       // ada foto perbaikan
    val upd: Long = 0L,               // waktu ubah di server (ms)
    val sync: String = "",            // "" = sinkron; N = belum terkirim (baru); S = status belum terkirim; D = hapus belum terkirim
    val err: String = "",             // alasan ditolak server (bila ada)
) {
    val tuntas: Boolean get() = status == Tim.TUNTAS
    val pending: Boolean get() = sync.isNotEmpty()
}

/** Pengguna yang login lewat NPK (hasil verifikasi server). */
data class TimUser(val npk: String, val nama: String, val peran: String, val luas: Boolean, val estate: List<String>, val afdeling: List<String>) {
    val supervisi: Boolean get() = luas || peran.equals("Supervisi", true)
    val admin: Boolean get() = peran.equals("Admin", true)
}

object Tim {
    const val OUTSTANDING = "OUTSTANDING"
    const val TUNTAS = "TUNTAS"

    class Gagal(val kode: String, msg: String) : Exception(msg) {
        val jaringan: Boolean get() = kode == "NET"
    }

    // ---------- pengaturan ----------

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ckp", Context.MODE_PRIVATE)
    fun url(ctx: Context) = sp(ctx).getString("url", "") ?: ""
    fun token(ctx: Context) = sp(ctx).getString("token", "") ?: ""
    fun npk(ctx: Context) = (sp(ctx).getString("npk", "") ?: "").trim()
    fun aktif(ctx: Context) = npk(ctx).isNotEmpty() && url(ctx).isNotBlank()

    fun userCache(ctx: Context): TimUser? {
        val s = sp(ctx).getString("tm_user", null) ?: return null
        return try { userFrom(JSONObject(s)) } catch (e: Exception) { null }
    }

    private fun userFrom(o: JSONObject): TimUser {
        fun list(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.optString(it) }
        return TimUser(o.optString("npk"), o.optString("nama"), o.optString("peran"), o.optBoolean("luas"),
            list(o.optJSONArray("estate")), list(o.optJSONArray("afdeling")))
    }

    private fun userJson(u: TimUser) = JSONObject().put("npk", u.npk).put("nama", u.nama).put("peran", u.peran).put("luas", u.luas)
        .put("estate", JSONArray(u.estate)).put("afdeling", JSONArray(u.afdeling))

    fun simpanUser(ctx: Context, u: TimUser) {
        sp(ctx).edit().putString("tm_user", userJson(u).toString()).apply()
    }

    fun lupaUser(ctx: Context) {
        sp(ctx).edit().remove("tm_user").remove("tm_since").apply()
    }

    // ---------- HTTP ----------

    private fun baca(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val js = try { JSONObject(text) } catch (e: Exception) { null }
            ?: throw Gagal("RESP", "Respon tidak dikenali (kode $code). Pastikan script sudah diperbarui dan di-deploy sebagai versi baru.")
        if (!js.optBoolean("ok")) throw Gagal(js.optString("code", "ERR"), js.optString("error", "Gagal"))
        return js
    }

    fun get(ctx: Context, aksi: String, extra: Map<String, String> = emptyMap(), readMs: Int = 60000): JSONObject {
        val p = LinkedHashMap<String, String>()
        p["action"] = aksi; p["token"] = token(ctx); p["npk"] = npk(ctx)
        p.putAll(extra)
        val q = p.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val u = url(ctx)
        var conn: HttpURLConnection? = null
        try {
            val c = URL(u + (if (u.contains("?")) "&" else "?") + q).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 20000; c.readTimeout = readMs; c.instanceFollowRedirects = true
            return baca(c)
        } catch (e: Gagal) { throw e } catch (e: Exception) { throw Gagal("NET", "Tidak ada koneksi / sinyal lemah") } finally { conn?.disconnect() }
    }

    fun post(ctx: Context, aksi: String, data: JSONObject): JSONObject {
        data.put("action", aksi).put("token", token(ctx)).put("npk", npk(ctx))
        var conn: HttpURLConnection? = null
        try {
            val c = URL(url(ctx)).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 20000; c.readTimeout = 90000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            return baca(c)
        } catch (e: Gagal) { throw e } catch (e: Exception) { throw Gagal("NET", "Tidak ada koneksi / sinyal lemah") } finally { conn?.disconnect() }
    }

    // ---------- kelola pengguna (Admin) ----------

    class Pengguna(val npk: String, val nama: String, val peran: String, val estate: String, val afdeling: String)

    fun daftarPengguna(ctx: Context): List<Pengguna> {
        val a = get(ctx, "tm_users", readMs = 30000).optJSONArray("rows") ?: return emptyList()
        val out = ArrayList<Pengguna>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Pengguna(o.optString("npk"), o.optString("nama"), o.optString("peran"), o.optString("estate"), o.optString("afdeling")))
        }
        return out
    }

    fun simpanPengguna(ctx: Context, p: Pengguna) {
        post(ctx, "tm_user_set", JSONObject().put("target", p.npk).put("nama", p.nama).put("peran", p.peran)
            .put("estate", p.estate).put("afdeling", p.afdeling))
    }

    fun hapusPengguna(ctx: Context, npk: String) {
        post(ctx, "tm_user_del", JSONObject().put("target", npk))
    }

    /** Verifikasi NPK ke server dan simpan profilnya. */
    fun verifikasi(ctx: Context): TimUser {
        val js = get(ctx, "tm_me", readMs = 30000)
        val u = userFrom(js.getJSONObject("user"))
        simpanUser(ctx, u)
        return u
    }

    // ---------- konversi ----------

    fun fromServer(o: JSONObject) = Temuan(
        id = o.getString("id"), waktu = o.optLong("waktu"), est = o.optString("est"), afd = o.optString("afd"),
        blok = o.optString("blok"), judul = o.optString("judul"), icon = o.optString("icon", "warn"),
        ket = o.optString("ket"), volume = o.optDouble("volume", 0.0), satuan = o.optString("satuan"),
        lat = o.optDouble("lat"), lon = o.optDouble("lon"), acc = o.optDouble("acc", -1.0),
        pNpk = o.optString("pNpk"), pNama = o.optString("pNama"),
        status = o.optString("status", OUTSTANDING).uppercase(), tuntasAt = o.optLong("tuntas"),
        sNpk = o.optString("sNpk"), sNama = o.optString("sNama"), catatan = o.optString("catatan"),
        fotoT = o.optBoolean("fotoT"), fotoP = o.optBoolean("fotoP"), upd = o.optLong("upd"),
    )

    fun toJson(t: Temuan) = JSONObject()
        .put("id", t.id).put("waktu", t.waktu).put("est", t.est).put("afd", t.afd).put("blok", t.blok)
        .put("judul", t.judul).put("icon", t.icon).put("ket", t.ket).put("volume", t.volume).put("satuan", t.satuan)
        .put("lat", t.lat).put("lon", t.lon).put("acc", t.acc).put("pNpk", t.pNpk).put("pNama", t.pNama)
        .put("status", t.status).put("tuntas", t.tuntasAt).put("sNpk", t.sNpk).put("sNama", t.sNama)
        .put("catatan", t.catatan).put("fotoT", t.fotoT).put("fotoP", t.fotoP).put("upd", t.upd)
        .put("sync", t.sync).put("err", t.err)

    fun fromJson(o: JSONObject) = fromServer(o).copy(sync = o.optString("sync"), err = o.optString("err"))

    /**
     * Gabungkan hasil dari server ke daftar lokal.
     * Temuan dengan perubahan yang belum terkirim (sync != "") tidak ditimpa. Temuan yang dihapus di server dibuang.
     */
    fun gabung(lokal: List<Temuan>, server: List<Temuan>, hapusServer: Set<String>): List<Temuan> {
        val map = LinkedHashMap<String, Temuan>()
        lokal.forEach { map[it.id] = it }
        for (s in server) {
            val l = map[s.id]
            if (l != null && l.pending) continue
            map[s.id] = s
        }
        for (id in hapusServer) {
            val l = map[id]
            if (l != null && !l.pending) map.remove(id)
        }
        return map.values.toList()
    }
}

/** Penyimpanan lokal temuan + foto (cache). */
object TemuanStore {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "temuan.json")
    fun fotoDir(ctx: Context) = File(ctx.filesDir, "timfoto").apply { mkdirs() }
    fun fotoT(ctx: Context, id: String) = File(fotoDir(ctx), "${id}_t.jpg")
    fun fotoP(ctx: Context, id: String) = File(fotoDir(ctx), "${id}_p.jpg")

    private fun read(ctx: Context): MutableList<Temuan> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { Tim.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) { mutableListOf() }
    }

    private fun write(ctx: Context, list: List<Temuan>) {
        val a = JSONArray()
        list.forEach { a.put(Tim.toJson(it)) }
        val tmp = File(ctx.filesDir, "temuan.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(ctx))
    }

    fun all(ctx: Context): List<Temuan> = synchronized(lock) { read(ctx).sortedByDescending { it.waktu } }

    fun get(ctx: Context, id: String): Temuan? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    fun ids(ctx: Context): Set<String> = synchronized(lock) { read(ctx).map { it.id }.toSet() }

    fun pendingCount(ctx: Context): Int = synchronized(lock) { read(ctx).count { it.pending && it.err.isEmpty() } }

    fun put(ctx: Context, t: Temuan) = synchronized(lock) {
        val list = read(ctx)
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list.add(t)
        write(ctx, list)
    }

    /** Ubah satu temuan bila masih ada. */
    fun ubah(ctx: Context, id: String, f: (Temuan) -> Temuan) = synchronized(lock) {
        val list = read(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) { list[i] = f(list[i]); write(ctx, list) }
    }

    fun hapusLokal(ctx: Context, id: String) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == id }
        write(ctx, list)
        try { fotoT(ctx, id).delete(); fotoP(ctx, id).delete() } catch (e: Exception) {}
    }

    /** Gabungkan hasil tarik dari server (atomik terhadap perubahan lokal yang terjadi bersamaan). */
    fun terapkan(ctx: Context, server: List<Temuan>, hapusServer: Set<String>) = synchronized(lock) {
        val gabung = Tim.gabung(read(ctx), server, hapusServer)
        write(ctx, gabung)
        for (id in hapusServer) if (gabung.none { it.id == id }) {
            try { fotoT(ctx, id).delete(); fotoP(ctx, id).delete() } catch (e: Exception) {}
        }
    }

    /** Titik peta dari temuan yang tampil (hijau = tuntas, merah = outstanding). */
    fun sebagaiMarker(t: Temuan) = Marker(
        "tm:" + t.id, t.waktu, if (t.tuntas) "done" else "warn", t.judul, t.blok, t.ket, t.volume, t.satuan, t.lat, t.lon, "", t.acc,
    )
}

/** Sinkronisasi: kirim antrean lalu tarik perubahan terbaru. Aman dipanggil dari thread latar. */
object TimSync {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    class Hasil(val terkirim: Int, val baru: Int, val gagal: Int, val pesan: String?)

    fun sedangJalan() = busy.get()

    fun jalankan(ctx0: Context): Hasil {
        val ctx = ctx0.applicationContext
        if (!Tim.aktif(ctx)) return Hasil(0, 0, 0, "Isi NPK dan URL Web App di Pengaturan dulu.")
        if (!busy.compareAndSet(false, true)) return Hasil(0, 0, 0, null)
        try {
            var terkirim = 0
            var gagal = 0
            var pesan: String? = null
            // 1) kirim antrean (urut dari yang tertua)
            for (t in TemuanStore.all(ctx).filter { it.pending && it.err.isEmpty() }.sortedBy { it.waktu }) {
                try {
                    kirim(ctx, t)
                    terkirim++
                } catch (e: Tim.Gagal) {
                    if (e.jaringan) { pesan = e.message; break }              // sinyal hilang: coba lagi nanti
                    if (e.kode == "NPK" || e.kode == "AUTH") { pesan = e.message; break }
                    gagal++
                    TemuanStore.ubah(ctx, t.id) { it.copy(err = e.message ?: "Ditolak server") }
                }
            }
            // 2) tarik perubahan
            var baru = 0
            if (pesan == null) {
                try {
                    val since = ctx.getSharedPreferences("ckp", Context.MODE_PRIVATE).getLong("tm_since", 0L)
                    val js = Tim.get(ctx, "tm_list", mapOf("since" to since.toString()))
                    js.optJSONObject("user")?.let { u ->
                        Tim.simpanUser(ctx, TimUser(u.optString("npk"), u.optString("nama"), u.optString("peran"), u.optBoolean("luas"),
                            List(u.optJSONArray("estate")?.length() ?: 0) { u.optJSONArray("estate")!!.optString(it) },
                            List(u.optJSONArray("afdeling")?.length() ?: 0) { u.optJSONArray("afdeling")!!.optString(it) }))
                    }
                    val rows = js.optJSONArray("rows") ?: JSONArray()
                    val ada = TemuanStore.ids(ctx)
                    val server = ArrayList<Temuan>()
                    val hapus = HashSet<String>()
                    for (i in 0 until rows.length()) {
                        val o = rows.getJSONObject(i)
                        if (o.optBoolean("del")) hapus.add(o.getString("id")) else server.add(Tim.fromServer(o))
                    }
                    baru = server.count { it.id !in ada }
                    TemuanStore.terapkan(ctx, server, hapus)
                    val now = js.optLong("now", System.currentTimeMillis())
                    ctx.getSharedPreferences("ckp", Context.MODE_PRIVATE).edit()
                        .putLong("tm_since", (now - 5000L).coerceAtLeast(0L))      // tumpang tindih 5 dtk agar tidak ada yang terlewat
                        .putLong("tm_last", System.currentTimeMillis()).apply()
                } catch (e: Tim.Gagal) {
                    pesan = e.message
                }
            }
            return Hasil(terkirim, baru, gagal, pesan)
        } finally {
            busy.set(false)
        }
    }

    private fun b64(f: File): String = if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else ""

    private fun kirim(ctx: Context, t: Temuan) {
        val js: JSONObject = when (t.sync) {
            "N" -> {
                val r = Tim.post(ctx, "tm_create", JSONObject()
                    .put("id", t.id).put("waktu", t.waktu).put("est", t.est).put("afd", t.afd).put("blok", t.blok)
                    .put("judul", t.judul).put("icon", t.icon).put("ket", t.ket).put("volume", t.volume).put("satuan", t.satuan)
                    .put("lat", t.lat).put("lon", t.lon).put("acc", t.acc)
                    .put("fotoBase64", b64(TemuanStore.fotoT(ctx, t.id))))
                if (t.status != Tim.OUTSTANDING) {
                    // dituntaskan sebelum sempat terkirim: temuan sudah ada di server, lanjut kirim statusnya
                    TemuanStore.ubah(ctx, t.id) { it.copy(sync = "S", fotoT = it.fotoT || TemuanStore.fotoT(ctx, t.id).exists()) }
                    TemuanStore.get(ctx, t.id)?.let { kirim(ctx, it) }
                    return
                }
                r
            }
            "S" -> Tim.post(ctx, "tm_status", JSONObject()
                .put("id", t.id).put("status", t.status).put("catatan", t.catatan).put("waktuTuntas", t.tuntasAt)
                .put("fotoBase64", if (t.tuntas) b64(TemuanStore.fotoP(ctx, t.id)) else ""))
            "D" -> {
                try { Tim.post(ctx, "tm_hapus", JSONObject().put("id", t.id)) } catch (e: Tim.Gagal) {
                    if (e.kode != "NOTFOUND") throw e
                }
                TemuanStore.hapusLokal(ctx, t.id)
                return
            }
            else -> return
        }
        val server = Tim.fromServer(js.getJSONObject("row"))
        val adaT = server.fotoT || TemuanStore.fotoT(ctx, t.id).exists()
        val adaP = server.fotoP || TemuanStore.fotoP(ctx, t.id).exists()
        // simpan hasil server; bila pengguna mengubah lagi saat mengirim, jangan hilangkan perubahan itu
        TemuanStore.ubah(ctx, t.id) { cur ->
            when {
                cur.sync == t.sync && cur.status == t.status -> server.copy(fotoT = adaT, fotoP = adaP)
                // dituntaskan sebelum sempat terkirim: temuan sudah ada di server, status menyusul sebagai kiriman berikutnya
                cur.sync == "N" -> cur.copy(sync = if (cur.status != Tim.OUTSTANDING) "S" else "", upd = server.upd, fotoT = adaT)
                else -> cur
            }
        }
    }

    /** Unduh foto temuan (t) / perbaikan (p) dari server ke cache. */
    fun unduhFoto(ctx: Context, id: String, jenis: String): File? {
        val tujuan = if (jenis == "p") TemuanStore.fotoP(ctx, id) else TemuanStore.fotoT(ctx, id)
        if (tujuan.exists()) return tujuan
        return try {
            val js = Tim.get(ctx, "tm_foto", mapOf("id" to id, "jenis" to jenis), readMs = 90000)
            val s = js.optString("b64")
            if (s.isEmpty()) null else { tujuan.writeBytes(Base64.decode(s, Base64.DEFAULT)); tujuan }
        } catch (e: Exception) { null }
    }
}
