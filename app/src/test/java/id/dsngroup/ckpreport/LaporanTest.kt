package id.dsngroup.ckpreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaporanTest {
    @Test fun koordinatSepertiContohWA() {
        assertEquals("0.80599", Laporan.koordinat(0.80599))
        assertEquals("117.3097", Laporan.koordinat(117.3097))
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=0.80599,117.3097",
            Laporan.mapsUrl(0.80599, 117.3097),
        )
    }

    @Test fun pesanBerisiSemuaData() {
        val t = Laporan.pesan("BPN", "AFD 1", "A12", "Buah mentah", 0.80599, 117.3097,
            "https://drive.google.com/x", "Budi", "12 Okt 2026 18:00")
        listOf("BPN", "AFD 1", "A12", "Buah mentah", "Latitude : 0.80599;", "Longitude : 117.3097;",
            "query=0.80599,117.3097", "https://drive.google.com/x", "Budi").forEach { assertTrue(it, t.contains(it)) }
    }

    @Test fun ikonWarningDanLinkPeta() {
        val url = "https://script.google.com/macros/s/AKfy/exec"
        val peta = Laporan.petaUrl(url, 0.80599, 117.3097)
        assertEquals("$url?lat=0.80599&lon=117.3097", peta)
        assertEquals("", Laporan.petaUrl("", 1.0, 2.0))
        val t = Laporan.pesan("BPN", "AFD 1", "A12", "x", 0.80599, 117.3097, "", "", "w", peta)
        assertTrue(t.startsWith("⚠️ *CKP SPOT REPORT* ⚠️"))
        assertTrue(!t.contains("🔥"))
        assertTrue(t.contains(peta))
    }
}
