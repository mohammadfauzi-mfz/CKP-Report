package id.dsngroup.ckpreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GisImportTest {
    private val ms = listOf(
        Marker("a1", 1_700_000_000_000L, "pohon", "Pohon Mati", "H34", "ket", 12.5, "pokok", 0.746073, 117.415342, "", 8.0),
        Marker("a2", 1_700_000_100_000L, "road", "Jalan rusak", "K34", "", 0.0, "m", 0.75, 117.42),
    )
    private val tr = listOf(
        TrackRecord("t1", 1_700_000_000_000L, 1_700_000_600_000L, "E", "A1", "H34", "",
            listOf(TPoint(0.7, 117.4, 1L), TPoint(0.701, 117.401, 2L), TPoint(0.702, 117.403, 3L)))
    )

    @Test fun kmlRoundTrip() {
        val r = GisImport.parse(Gis.kml(ms, tr).toByteArray(), "a.kml")
        assertNull(r.error); assertEquals(2, r.markers.size); assertEquals(1, r.tracks.size)
        val m = r.markers.first { it.title == "Pohon Mati" }
        assertEquals("H34", m.blok); assertEquals(12.5, m.volume, 1e-6); assertEquals(0.746073, m.lat, 1e-6)
    }

    @Test fun geojsonRoundTrip() {
        val r = GisImport.parse(Gis.geojson(ms, tr).toByteArray(), "a.geojson")
        assertNull(r.error); assertEquals(2, r.markers.size); assertEquals(3, r.tracks[0].points.size)
    }

    @Test fun gpxRoundTrip() {
        val r = GisImport.parse(Gis.gpx(ms, tr).toByteArray(), "a.gpx")
        assertNull(r.error); assertEquals(2, r.markers.size); assertEquals(1, r.tracks.size)
    }

    @Test fun csvDenganKoma() {
        val csv = "judul,blok,lat,lon\nA,H34,0.5,117.1\nB,K34,\"0,6\",\"117,2\"\n"
        val r = GisImport.parse(csv.toByteArray(), "x.csv")
        assertNull(r.error); assertEquals(2, r.markers.size)
    }

    @Test fun coordFormats() {
        val d = Coord.parse("0.746073, 117.415342"); assertNotNull(d); assertEquals(0.746073, d!!.lat, 1e-9)
        val s = Coord.parse("0°44'45.8\"LU 117°24'55.2\"BT"); assertNotNull(s); assertEquals(0.74606, s!!.lat, 1e-3)
        val u = Coord.toUtm(0.746073, 117.415342)
        val b = Coord.parse(u.toString()); assertNotNull(b)
        assertEquals(0.746073, b!!.lat, 1e-5); assertEquals(117.415342, b.lon, 1e-5)
        assertNull(Coord.parse("abc xyz"))
    }
}
