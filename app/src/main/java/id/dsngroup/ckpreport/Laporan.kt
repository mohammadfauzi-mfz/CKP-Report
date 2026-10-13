package id.dsngroup.ckpreport

import java.text.NumberFormat
import java.util.Locale

object Laporan {
    const val DEFAULT_PT = "PT. Bima Palma Nugraha"

    /** 6 desimal, titik sebagai pemisah, nol di belakang dibuang (0.805990 -> 0.80599). */
    fun koordinat(v: Double): String {
        var s = String.format(Locale.US, "%.6f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    /** Format mudah disalin ke CarryMap: 0.746073,117.415342 */
    fun koordinatSalin(lat: Double, lon: Double): String = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    fun mapsUrl(lat: Double, lon: Double) =
        "https://www.google.com/maps/search/?api=1&query=${koordinat(lat)},${koordinat(lon)}"

    /** Angka gaya Indonesia (koma desimal), maksimal maxF desimal. */
    fun angka(v: Double, maxF: Int = 2): String {
        if (v.isNaN() || v.isInfinite()) return "-"
        val nf = NumberFormat.getNumberInstance(Locale("in", "ID"))
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = maxF
        return nf.format(v)
    }

    fun persen(fraction: Double): String = angka(fraction * 100.0, 1) + "%"

    /** Teks untuk dibagikan ke grup WhatsApp. */
    fun pesan(r: Report, perusahaan: String): String {
        val sb = StringBuilder()
        sb.append("⚠️ *CKP SPOT REPORT* ⚠️\n")
        sb.append("${perusahaan.ifBlank { DEFAULT_PT }}\n\n")
        sb.append("Waktu : ${r.waktuText}\n")
        if (r.pelapor.isNotBlank()) sb.append("Pelapor : ${r.pelapor}\n")
        sb.append("Estate : ${r.est}\n")
        sb.append("Afdeling : ${r.afd}\n")
        sb.append("Blok : ${r.blok}\n")
        if (r.activityText.isNotBlank()) sb.append("Activity : ${r.activityText}\n")

        r.losses?.let { l ->
            sb.append("\n*Losses*\n")
            sb.append("Nama Ancak : ${l.namaAncak}\n")
            sb.append("Luas CKP : ${angka(l.luas)} Ha\n")
            sb.append("Brondol Tinggal : ${angka(l.brondol)} Kg\n")
            sb.append("Janjang Tinggal : ${angka(l.janjang)} JJG\n")
            sb.append("BJR : ${angka(l.bjr)} Kg\n")
            sb.append("Losses JJG : ${angka(l.lossesJjg)} Kg\n")
            sb.append("Total Losses : ${angka(l.total)} Kg\n")
            sb.append("Losses/Ha : ${angka(l.perHa)} Kg/Ha\n")
        }
        r.grading?.let { g ->
            sb.append("\n*Grading TPH*\n")
            sb.append("No TPH : ${g.noTph}\n")
            sb.append("Ripe : ${angka(g.ripe)} JJG (${persen(g.pct(g.ripe))})\n")
            sb.append("Mengkal : ${angka(g.mengkal)} JJG (${persen(g.pct(g.mengkal))})\n")
            sb.append("Mentah : ${angka(g.mentah)} JJG (${persen(g.pct(g.mentah))})\n")
            sb.append("Overripe : ${angka(g.overripe)} JJG (${persen(g.pct(g.overripe))})\n")
            sb.append("Total JJG : ${angka(g.total)}\n")
            sb.append("BJR : ${angka(g.bjr)} Kg\n")
            sb.append("Tonase : ${angka(g.tonase, 3)} Ton\n")
            sb.append("Brondol : ${angka(g.brondol)} Kg (${persen(g.brondolPct)})\n")
        }
        r.kalibrasi?.let { k ->
            sb.append("\n*Kalibrasi BJR*\n")
            sb.append("No. TPH : ${k.noTph}\n")
            sb.append("Jumlah Janjang : ${angka(k.janjang)} JJG\n")
            sb.append("Tonase : ${angka(k.tonaseKg)} Kg\n")
            sb.append("BJR : ${angka(k.bjr)} Kg\n")
        }

        sb.append("\n*Problem / Temuan :*\n${r.problem}\n")
        if (r.lux >= 0) sb.append("\nCahaya : ${angka(r.lux, 0)} Lux\n")

        sb.append("\n*Koordinat :*\n")
        sb.append("Latitude : ${koordinat(r.lat)};\n")
        sb.append("Longitude : ${koordinat(r.lon)};\n")
        sb.append("Google Maps :\n${mapsUrl(r.lat, r.lon)}\n\n")
        sb.append("Koordinat (salin ke CarryMap) :\n${koordinatSalin(r.lat, r.lon)}")
        if (r.fotoUrl.isNotBlank()) sb.append("\n\nFoto : ${r.fotoUrl}")
        return sb.toString()
    }
}
