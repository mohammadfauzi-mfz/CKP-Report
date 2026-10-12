package id.dsngroup.ckpreport

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Uploader {
    /** network=true bila gagal karena koneksi/sinyal (layak dicoba ulang otomatis). */
    data class Result(val ok: Boolean, val fotoUrl: String, val error: String, val network: Boolean = false)

    /** Kirim ke Google Apps Script Web App. Panggil dari thread latar belakang. */
    fun upload(url: String, payload: JSONObject): Result {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val js = try { JSONObject(text) } catch (e: Exception) { null }
            when {
                js == null -> Result(false, "",
                    "Respon tidak dikenali (kode $code). Pastikan URL benar dan akses Web App diatur ke \"Siapa saja\".")
                js.optBoolean("ok") -> Result(true, js.optString("fotoUrl", ""), "")
                else -> Result(false, "", js.optString("error", "Gagal menyimpan"))
            }
        } catch (e: Exception) {
            Result(false, "", "Tidak ada koneksi / sinyal lemah", network = true)
        } finally {
            conn?.disconnect()
        }
    }
}
