package id.dsngroup.ckpreport

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** Mengambil lokasi dari GPS HP (tanpa Google Play Services). */
class LocationHelper(ctx: Context, private val onUpdate: (Location) -> Unit) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var best: Location? = null
        private set

    /** @return false jika GPS/lokasi HP belum aktif. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        stop()
        best = null
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
                    any = true
                }
            } catch (e: Exception) {
            }
        }
        return any
    }

    fun stop() {
        try { lm.removeUpdates(this) } catch (e: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        val b = best
        if (b == null || location.accuracy <= b.accuracy) {
            best = location
            onUpdate(location)
        }
        // Cukup akurat dari GPS -> hemat baterai
        if (location.provider == LocationManager.GPS_PROVIDER && location.accuracy <= 15f) stop()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
