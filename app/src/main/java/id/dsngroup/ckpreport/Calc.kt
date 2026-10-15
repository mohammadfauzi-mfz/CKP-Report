package id.dsngroup.ckpreport

/** Rumus identik dengan file Excel "tambahan.xlsx". */

/** Losses */
data class LossesData(
    val namaAncak: String,
    val luas: Double,      // Luas CKP (Ha)
    val brondol: Double,   // Brondol Tinggal (Kg)
    val janjang: Double,   // Janjang Tinggal (JJG)
    val bjr: Double,       // BJR (Kg)
) {
    val lossesJjg: Double get() = bjr * janjang                      // =D10*D9
    val total: Double get() = brondol + lossesJjg                    // =SUM(D8,D11)
    val perHa: Double get() = if (luas != 0.0) total / luas else 0.0 // =IFERROR(D12/D7,0)

    /** Ada ikon penilaian hanya jika luas terisi. */
    val dinilai: Boolean get() = luas > 0.0
    /** Losses/Ha <= 1,00 Kg/Ha = baik (dibandingkan setelah dibulatkan 2 desimal seperti yang tampil). */
    val baik: Boolean get() = Math.round(perHa * 100.0) / 100.0 <= 1.0
}

/** Grading TPH */
data class GradingData(
    val noTph: String,
    val ripe: Double,
    val mengkal: Double,
    val mentah: Double,
    val overripe: Double,
    val bjr: Double,
    val brondol: Double,   // Brondol (Kg)
    val lat: Double = Double.NaN,   // koordinat TPH ini (NaN = belum ada)
    val lon: Double = Double.NaN,
    val namaPemanen: String = "",
    val kodePemanen: String = "",
) {
    val hasCoord: Boolean get() = !lat.isNaN() && !lon.isNaN()
    val dinilai: Boolean get() = tonase > 0.0
    /** Brondol >= 5,00% = baik (dibandingkan setelah dibulatkan 2 desimal seperti yang tampil). */
    val baik: Boolean get() = Math.round(brondolPct * 10000.0) / 100.0 >= 5.0
    /** Ripe >= 92,00% = baik (dibandingkan setelah dibulatkan 2 desimal seperti yang tampil). */
    val ripeBaik: Boolean get() = Math.round(pct(ripe) * 10000.0) / 100.0 >= 92.0
    val total: Double get() = ripe + mengkal + mentah + overripe          // =SUM(D18:D21)
    fun pct(v: Double): Double = if (total > 0.0) v / total else 0.0       // =D18/$D$22
    val tonase: Double get() = bjr * total / 1000.0                       // =D23*D22/1000 (Ton)
    val brondolPct: Double get() = if (tonase > 0.0) brondol / (tonase * 1000.0) else 0.0 // =D25/(D24*1000)
}

/** Kalibrasi BJR */
data class KalibrasiData(
    val noTph: String,
    val janjang: Double,   // Jumlah Janjang (JJG)
    val tonaseKg: Double,  // Tonase (Kg)
    val lat: Double = Double.NaN,   // koordinat TPH ini (NaN = belum ada)
    val lon: Double = Double.NaN,
    val namaPemanen: String = "",
    val kodePemanen: String = "",
) {
    val hasCoord: Boolean get() = !lat.isNaN() && !lon.isNaN()
    val bjr: Double get() = if (janjang > 0.0) tonaseKg / janjang else 0.0 // =D30/D29
}
