package id.dsngroup.ckpreport

import java.util.Locale

object Laporan {
    /** 6 desimal, titik sebagai pemisah, nol di belakang dibuang (0.805990 -> 0.80599). */
    fun koordinat(v: Double): String {
        var s = String.format(Locale.US, "%.6f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    fun mapsUrl(lat: Double, lon: Double) =
        "https://www.google.com/maps/search/?api=1&query=${koordinat(lat)},${koordinat(lon)}"

    /** Teks untuk dibagikan ke grup WhatsApp (format koordinat mengikuti contoh chat). */
    fun pesan(
        estate: String, afdeling: String, blok: String, problem: String,
        lat: Double, lon: Double, fotoUrl: String, pelapor: String, waktu: String,
    ): String {
        val sb = StringBuilder()
        sb.append("🔥 *CKP SPOT REPORT* 🔥\n")
        sb.append("PT. Bima Palma Nugraha\n\n")
        sb.append("Waktu : $waktu\n")
        if (pelapor.isNotBlank()) sb.append("Pelapor : $pelapor\n")
        sb.append("Estate : $estate\n")
        sb.append("Afdeling : $afdeling\n")
        sb.append("Blok : $blok\n\n")
        sb.append("*Problem / Temuan :*\n$problem\n\n")
        sb.append("*Koordinat :*\n")
        sb.append("Latitude : ${koordinat(lat)};\n")
        sb.append("Longitude : ${koordinat(lon)};\n")
        sb.append("Google Maps :\n${mapsUrl(lat, lon)}")
        if (fotoUrl.isNotBlank()) sb.append("\n\nFoto : $fotoUrl")
        return sb.toString()
    }
}
