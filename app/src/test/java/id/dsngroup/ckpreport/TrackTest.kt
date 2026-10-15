package id.dsngroup.ckpreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    // Nilai registrasi dari peta BPN (GeoPDF Global Mapper)
    private val map = MapInfo(
        "m", "BPN", 2600, 3694, 595.2756, 841.8898,
        0.0, 0.0, 117.312622138350, 0.613328255969,
        595.0, 841.0, 117.480586316923, 0.850736447868,
    )

    @Test fun pojokKiriBawahPeta() {
        val (x, y) = map.px(0.613328255969, 117.312622138350)
        assertEquals(0.0, x, 1e-6)
        assertEquals(3694.0, y, 1e-6)
    }

    @Test fun titikDiDalamPeta() {
        assertTrue(map.contains(0.746073, 117.415342))
        assertTrue(!map.contains(0.5, 117.4))
        val (x, y) = map.px(0.746073, 117.415342)
        assertTrue(x in 0.0..2600.0 && y in 0.0..3694.0)
    }

    @Test fun jarakDuaTitik() {
        // 0,001 derajat lintang ~ 111 m
        val d = Geo.dist(TPoint(0.0, 117.0, 0), TPoint(0.001, 117.0, 0))
        assertEquals(111.2, d, 1.0)
    }

    @Test fun trackerAbaikanGetaranKecil() {
        Tracker.start()
        Tracker.add(TPoint(0.746000, 117.415000, 1))
        Tracker.add(TPoint(0.746001, 117.415000, 2))   // ~0,1 m -> diabaikan
        Tracker.add(TPoint(0.746100, 117.415000, 3))   // ~11 m -> dicatat
        assertEquals(2, Tracker.stop().size)
    }
}
