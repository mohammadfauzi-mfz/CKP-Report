package id.dsngroup.ckpreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkingTest {
    private fun mk(t: String, v: Double, u: String) =
        Marker("1", 0L, "tree", t, "A01", "catatan", v, u, 0.746073, 117.415342)

    @Test fun totalPerSatuan() {
        val s = MarkingText.totals(listOf(mk("a", 100.0, "Pokok"), mk("b", 50.0, "pokok"), mk("c", 2.5, "Ha")))
        assertEquals("150 Pokok • 2,5 Ha", s)
    }

    @Test fun pesanBerisiKoordinatDanBlok() {
        val p = MarkingText.pesan(mk("Pokok mati", 3.0, "Pokok"))
        assertTrue(p.contains("Blok : A01"))
        assertTrue(p.contains("0.746073,117.415342"))
        assertTrue(p.contains("Volume : 3 Pokok"))
    }

    @Test fun luasPersegi100m() {
        val k = 111320.0
        val dLat = 100.0 / 110574.0
        val dLon = 100.0 / k
        val p = listOf(TPoint(0.0, 0.0, 0), TPoint(0.0, dLon, 0), TPoint(dLat, dLon, 0), TPoint(dLat, 0.0, 0))
        assertEquals(10000.0, Geo.areaM2(p), 50.0)
    }
}
