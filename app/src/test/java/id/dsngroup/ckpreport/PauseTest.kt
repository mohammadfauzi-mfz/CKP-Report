package id.dsngroup.ckpreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTest {
    private fun pt(i: Int) = TPoint(0.70 + i * 0.0001, 117.4, 1000L + i)

    @Test fun jedaMemutusGarisDanJarak() {
        Tracker.start(null, "E", "A1", "H34")
        for (i in 0..4) Tracker.add(pt(i))
        Tracker.pause(null)
        assertTrue(Tracker.paused)
        Tracker.unpause(null)
        assertFalse(Tracker.paused)
        Tracker.add(TPoint(1.70, 117.4, 5000L))     // pindah ~110 km saat dijeda
        Tracker.add(TPoint(1.7002, 117.4, 5001L))
        val p = Tracker.snapshot()
        assertTrue(p[5].brk)
        assertEquals(2, Geo.segments(p).size)
        assertTrue(Geo.length(p) < 100.0)           // lompatan tidak ikut terhitung
        Tracker.stop(null)
    }

    @Test fun durasiTanpaJedaDanJsonBolakBalik() {
        val pts = listOf(pt(0), pt(1), TPoint(0.8, 117.4, 9L, true), TPoint(0.8001, 117.4, 10L))
        val r = TrackRecord("x", 0L, 10_000L, "", "", "", "", pts, 4_000L)
        assertEquals(6000L, r.durationMs)
        val b = TrackStore.fromJson(TrackStore.toJson(r))
        assertTrue(b.points[2].brk)
        assertEquals(4000L, b.pausedMs)
    }
}
