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
) {
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
) {
    val bjr: Double get() = if (janjang > 0.0) tonaseKg / janjang else 0.0 // =D30/D29
}
