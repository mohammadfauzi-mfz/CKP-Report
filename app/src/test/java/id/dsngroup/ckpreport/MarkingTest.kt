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

class GeoTest {
    @Test fun utmZona50() {
        val (lat, lon) = GeoPdf.utmToLatLon(500000.0, 0.0, 50, false)
        assertEquals(0.0, lat, 1e-6)
        assertEquals(117.0, lon, 1e-6)
    }

    @Test fun affineMengembalikanTitikKontrol() {
        val rows = listOf(
            doubleArrayOf(0.0, 0.0, -1.0, 117.0), doubleArrayOf(0.0, 842.0, -0.9, 117.0),
            doubleArrayOf(595.0, 842.0, -0.9, 117.1), doubleArrayOf(595.0, 0.0, -1.0, 117.1),
        )
        val f = Affine.fit(rows)!!
        val m = MapInfo("a", "a", 1000, 1415, 595.0, 842.0, 0.0, 0.0, 117.0, -1.0, 595.0, 842.0, 117.1, -0.9, f)
        val (lat, lon) = m.latLon(500.0, 707.5)
        assertEquals(-0.95, lat, 1e-6)
        assertEquals(117.05, lon, 1e-6)
        val (x, y) = m.px(-0.95, 117.05)
        assertEquals(500.0, x, 1e-3)
        assertEquals(707.5, y, 1e-3)
    }
}
