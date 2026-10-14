package id.dsngroup.ckpreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaporanTest {
    private fun rep(l: LossesData? = null, g: GradingData? = null, k: KalibrasiData? = null, lux: Double = -1.0) =
        Report(id = "x", createdAt = 0L, est = "BPN", afd = "AFD 1", blok = "A12", problem = "Buah mentah",
            lat = 0.746073, lon = 117.415342, pelapor = "Budi", photoPath = "",
            losses = l, grading = g, kalibrasi = k, lux = lux)

    @Test fun koordinatSalin() {
        assertEquals("0.746073,117.415342", Laporan.koordinatSalin(0.746073, 117.415342))
        assertEquals("0.80599", Laporan.koordinat(0.80599))
    }

    @Test fun rumusLossesSamaDenganExcel() {
        val l = LossesData("Ancak 1", 2.0, 12.0, 20.0, 0.8)
        assertEquals(16.0, l.lossesJjg, 1e-9)
        assertEquals(28.0, l.total, 1e-9)
        assertEquals(14.0, l.perHa, 1e-9)
    }

    @Test fun luasNolTidakError() {
        assertEquals(0.0, LossesData("a", 0.0, 1.0, 1.0, 1.0).perHa, 0.0)
    }

    @Test fun rumusKalibrasi() {
        assertEquals(18.6, KalibrasiData("T1", 10.0, 186.0).bjr, 1e-9)
    }

    @Test fun rumusGrading() {
        val g = GradingData("T1", 90.0, 5.0, 4.0, 3.0, 14.33, 40.0)
        assertEquals(102.0, g.total, 1e-9)
        assertEquals(90.0 / 102.0, g.pct(g.ripe), 1e-9)
        assertEquals(14.33 * 102.0 / 1000.0, g.tonase, 1e-9)
    }

    @Test fun pesanBerisiSemuaData() {
        val t = Laporan.pesan(rep(LossesData("Ancak 1", 2.0, 12.0, 20.0, 0.8), lux = 1234.0), "PT. Bima Palma Nugraha")
        assertTrue(t.startsWith("⚠️ *CKP SPOT REPORT* ⚠️"))
        listOf("PT. Bima Palma Nugraha", "AFD 1", "A12", "Buah mentah", "Budi", "0.746073,117.415342", "Losses", "Ancak 1")
            .forEach { assertTrue(it, t.contains(it)) }
        assertTrue(!t.contains("Grading TPH"))
    }

    @Test fun ikonLossesPerHa() {
        val buruk = Laporan.pesan(rep(LossesData("a", 2.0, 12.0, 20.0, 0.8)), "PT")   // 14 Kg/Ha
        assertTrue(buruk.contains("Losses/Ha : 14 Kg/Ha ⚠️"))
        val baik = Laporan.pesan(rep(LossesData("a", 10.0, 5.0, 0.0, 0.8)), "PT")      // 0,5 Kg/Ha
        assertTrue(baik.contains("Kg/Ha ✅"))
        val batas = Laporan.pesan(rep(LossesData("a", 10.0, 10.0, 0.0, 0.8)), "PT")    // tepat 1,00 -> baik
        assertTrue(batas.contains("Losses/Ha : 1 Kg/Ha ✅"))
    }

    @Test fun ikonBrondolPersen() {
        // tonase = 10*100/1000 = 1 ton; brondol 40 Kg = 4% (<5%) -> warning
        val g1 = GradingData("T1", 100.0, 0.0, 0.0, 0.0, 10.0, 40.0, 0.7, 117.4)
        assertTrue(Laporan.pesan(rep(g = g1), "PT").contains("(4%) ⚠️"))
        val g2 = GradingData("T1", 100.0, 0.0, 0.0, 0.0, 10.0, 50.0, 0.7, 117.4)   // tepat 5% -> check
        assertTrue(Laporan.pesan(rep(g = g2), "PT").contains("(5%) ✅"))
    }

    @Test fun koordinatSendiriDanPerbaikan() {
        val r = rep(g = GradingData("T1", 10.0, 0.0, 0.0, 0.0, 10.0, 1.0, 0.1, 117.2),
            k = KalibrasiData("T2", 10.0, 186.0, 0.3, 117.3)).copy(perbaikan = "Rawat piringan")
        val t = Laporan.pesan(r, "PT")
        assertTrue(t.contains("Koordinat : 0.100000,117.200000"))
        assertTrue(t.contains("Koordinat : 0.300000,117.300000"))
        assertTrue(t.contains("*Perbaikan :*\nRawat piringan"))
        assertTrue(!Laporan.pesan(rep(), "PT").contains("Perbaikan"))
    }
}
