package com.chery.autonav.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

data class VehicleLocation(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val bearingDeg: Float,
    val altitudeM: Float,
    val accuracyM: Float,
    val hasFix: Boolean
)

class LocationManagerHelper(private val context: Context, private val onLocationChanged: (VehicleLocation) -> Unit) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var isSimulating = false
    private var simProgress = 0.0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (isSimulating) return
            val speedKmh = loc.speed * 3.6f
            val vLoc = VehicleLocation(
                latitude = loc.latitude,
                longitude = loc.longitude,
                speedKmh = speedKmh,
                bearingDeg = loc.bearing,
                altitudeM = loc.altitude.toFloat(),
                accuracyM = loc.accuracy,
                hasFix = true
            )
            onLocationChanged(vLoc)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                200L, // 200 ms for 5Hz smooth updates
                0.5f,
                locationListener
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListening() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setSimulationMode(enabled: Boolean) {
        isSimulating = enabled
    }

    private fun routeLengthMeters(pts: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 1 until pts.size) {
            val (lat1, lon1) = pts[i - 1]
            val (lat2, lon2) = pts[i]
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            total += 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
        return total
    }

    fun tickSimulation(routePoints: List<Pair<Double, Double>>): VehicleLocation {
        if (routePoints.isEmpty()) {
            return VehicleLocation(30.0444, 31.2357, 0f, 0f, 20f, 3f, true)
        }

        // Advance by a realistic distance per tick (not a fixed fraction, which
        // made short demo routes crawl and long ones fly at 800 km/h). ~55 km/h
        // at the 10 Hz tick = ~1.5 m per tick, so tiles have time to load.
        val totalMeters = routeLengthMeters(routePoints).coerceAtLeast(1.0)
        simProgress = (simProgress + 1.5 / totalMeters) % 1.0
        val idxFloat = simProgress * (routePoints.size - 1)
        val idx = idxFloat.toInt()
        val nextIdx = minOf(idx + 1, routePoints.size - 1)
        val frac = (idxFloat - idx).toFloat()

        val p1 = routePoints[idx]
        val p2 = routePoints[nextIdx]

        val lat = p1.first + (p2.first - p1.first) * frac
        val lon = p1.second + (p2.second - p1.second) * frac

        // Bearing calculation
        val dLon = Math.toRadians(p2.second - p1.second)
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(p2.first))
        val x = Math.cos(Math.toRadians(p1.first)) * Math.sin(Math.toRadians(p2.first)) -
                Math.sin(Math.toRadians(p1.first)) * Math.cos(Math.toRadians(p2.first)) * Math.cos(dLon)
        val bearing = (Math.toDegrees(Math.atan2(y, x)).toFloat() + 360f) % 360f

        val simulatedSpeed = 65f + 15f * kotlin.math.sin(simProgress * 10).toFloat()

        val loc = VehicleLocation(
            latitude = lat,
            longitude = lon,
            speedKmh = simulatedSpeed,
            bearingDeg = bearing,
            altitudeM = 25f,
            accuracyM = 3.0f,
            hasFix = true
        )
        onLocationChanged(loc)
        return loc
    }
}
