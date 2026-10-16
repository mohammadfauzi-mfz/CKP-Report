package id.dsngroup.ckpreport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Cadangan lengkap seluruh data aplikasi dalam satu file zip:
 * pengaturan, laporan (+foto), draf, riwayat tracking, tanda (+foto), dan peta offline.
 * Dipakai sebelum menghapus / memindahkan aplikasi ke HP lain.
 */
object Backup {
    const val MARKER = "backup.json"

    /** @param includePdf sertakan salinan PDF peta (besar, hanya perlu untuk zoom tajam). */
    fun create(ctx: Context, includePdf: Boolean, out: File): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(out).buffered()).use { z ->
                z.putNextEntry(ZipEntry(MARKER))
                z.write(JSONObject().put("app", "ckpreport").put("type", "backup").put("version", 1)
                    .put("created", System.currentTimeMillis()).put("pdf", includePdf).toString().toByteArray(Charsets.UTF_8))
                z.closeEntry()

                val prefs = ctx.getSharedPreferences("ckp", Context.MODE_PRIVATE)
                val pj = JSONObject()
                for ((k, v) in prefs.all) {
                    val o = JSONObject()
                    when (v) {
                        is String -> o.put("t", "s").put("v", v)
                        is Boolean -> o.put("t", "b").put("v", v)
                        is Int -> o.put("t", "i").put("v", v)
                        is Long -> o.put("t", "l").put("v", v)
                        is Float -> o.put("t", "f").put("v", v.toDouble())
                        else -> continue
                    }
                    pj.put(k, o)
                }
                z.putNextEntry(ZipEntry("prefs.json"))
                z.write(pj.toString().toByteArray(Charsets.UTF_8))
                z.closeEntry()

                val root = ctx.filesDir
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (rel.endsWith(".tmp")) return@forEach
                    if (!includePdf && rel.startsWith("maps/") && rel.endsWith(".pdf")) return@forEach
                    z.putNextEntry(ZipEntry("files/$rel"))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            out.delete()
            false
        }
    }

    /** Apakah aliran ini zip cadangan CKP (entri pertama = backup.json)? Aliran ditutup oleh pemanggil. */
    fun isBackup(input: InputStream): Boolean = try {
        ZipInputStream(input).nextEntry?.name == MARKER
    } catch (e: Exception) {
        false
    }

    /** Pulihkan (gabungkan) data dari zip cadangan. Data yang sudah ada tidak ditimpa, kecuali pengaturan. @return ringkasan / pesan galat. */
    fun restore(ctx: Context, input: InputStream): String {
        val tmp = File(ctx.cacheDir, "restore_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            val base = tmp.canonicalPath
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val dest = File(tmp, e.name)
                    if (!dest.canonicalPath.startsWith(base)) continue      // lindungi dari path berbahaya
                    if (e.isDirectory) { dest.mkdirs(); continue }
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { z.copyTo(it) }
                }
            }
            if (!File(tmp, MARKER).exists()) return "Ini bukan file backup CKP Report."

            val files = File(tmp, "files")
            val sb = ArrayList<String>()

            // pengaturan
            val pf = File(tmp, "prefs.json")
            if (pf.exists()) {
                val pj = JSONObject(pf.readText())
                val ed = ctx.getSharedPreferences("ckp", Context.MODE_PRIVATE).edit()
                for (k in pj.keys()) {
                    val o = pj.getJSONObject(k)
                    when (o.optString("t")) {
                        "s" -> ed.putString(k, o.optString("v"))
                        "b" -> ed.putBoolean(k, o.optBoolean("v"))
                        "i" -> ed.putInt(k, o.optInt("v"))
                        "l" -> ed.putLong(k, o.optLong("v"))
                        "f" -> ed.putFloat(k, o.optDouble("v").toFloat())
                    }
                }
                ed.apply()
                sb.add("pengaturan")
            }

            val nm = File(files, "maps")
            val nMap = if (File(nm, "maps.json").exists()) MapStore.mergeFromDir(ctx, nm) else 0

            val rj = File(files, "reports.json")
            val nRep = if (rj.exists()) ReportStore.mergeFromJson(ctx, rj.readText(), File(files, "reports")) else 0

            val dj = File(files, "drafts.json")
            val nDraft = if (dj.exists()) DraftStore.mergeFromJson(ctx, dj.readText(), File(files, "drafts")) else 0

            val tj = File(files, "tracks.json")
            val nTrack = if (tj.exists()) {
                val wrap = JSONObject().put("app", "ckpreport").put("type", "tracks").put("tracks", JSONArray(tj.readText()))
                TrackStore.importJson(ctx, wrap.toString()).coerceAtLeast(0)
            } else 0

            val mj = File(files, "markers.json")
            var nMark = 0
            if (mj.exists()) {
                val a = JSONArray(mj.readText())
                val existing = MarkerStore.all(ctx).map { it.id }.toSet()
                val list = ArrayList<Marker>()
                for (i in 0 until a.length()) {
                    val m = MarkerStore.fromJson(a.getJSONObject(i))
                    if (m.id !in existing) {
                        for (n in listOf("${m.id}.jpg", "${m.id}_raw.jpg")) {
                            val src = File(files, "markphotos/$n")
                            if (src.exists()) {
                                val dst = if (n.endsWith("_raw.jpg")) MarkerStore.rawFile(ctx, m.id) else MarkerStore.photoFile(ctx, m.id)
                                if (!dst.exists()) src.copyTo(dst)
                            }
                        }
                    }
                    list.add(m)
                }
                nMark = MarkerStore.merge(ctx, list)
            }
            return "Pemulihan selesai: $nRep laporan, $nDraft draf, $nTrack tracking, $nMark tanda, $nMap peta" +
                (if (sb.isNotEmpty()) ", pengaturan dipulihkan." else ".")
        } catch (e: Exception) {
            return "Pemulihan gagal: ${e.message}"
        } finally {
            tmp.deleteRecursively()
        }
    }
}
