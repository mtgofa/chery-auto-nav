package com.chery.autonav.navigation

import com.chery.autonav.protocol.ProtocolConstants
import kotlin.math.*

data class RouteStep(
    val lat: Double,
    val lon: Double,
    val maneuver: Int,
    val streetName: String,
    val instruction: String
)

class RouteManager {

    private val routeSteps = mutableListOf<RouteStep>()
    private val routePolyline = mutableListOf<Pair<Double, Double>>()

    private var currentStepIdx = 0
    private var totalDistanceM = 0
    private var remainingDistanceM = 0
    private var remainingTimeSec = 0
    private var isCustomRoute = false

    init {
        loadDemoRoute()
    }

    /**
     * Replace the demo route with a real route (e.g. from OSRM).
     * Resets progress so guidance + car polyline start from step 0.
     */
    fun setCustomRoute(
        points: List<Pair<Double, Double>>,
        steps: List<RouteStep>,
        totalDistM: Int,
        totalTimeSec: Int
    ) {
        routeSteps.clear()
        routeSteps.addAll(steps)
        routePolyline.clear()
        routePolyline.addAll(points)
        currentStepIdx = 0
        totalDistanceM = totalDistM
        remainingDistanceM = totalDistM
        remainingTimeSec = totalTimeSec
        isCustomRoute = true
    }

    fun isCustom(): Boolean = isCustomRoute

    fun loadDemoRoute() {
        routeSteps.clear()
        routePolyline.clear()
        isCustomRoute = false

        // Demo route in Cairo (Salah Salem to Ring Road / Airport)
        val baseLat = 30.0444
        val baseLon = 31.2357

        routeSteps.add(RouteStep(baseLat, baseLon, ProtocolConstants.MANEUVER_STRAIGHT, "Tahrir Square", "Head north on Tahrir St"))
        routeSteps.add(RouteStep(baseLat + 0.008, baseLon + 0.005, ProtocolConstants.MANEUVER_RIGHT, "Ramses St", "Turn right onto Ramses St"))
        routeSteps.add(RouteStep(baseLat + 0.020, baseLon + 0.015, ProtocolConstants.MANEUVER_SLIGHT_LEFT, "Salah Salem St", "Take the ramp onto Salah Salem St"))
        routeSteps.add(RouteStep(baseLat + 0.045, baseLon + 0.035, ProtocolConstants.MANEUVER_ROUNDABOUT, "Abbassia Square", "At the roundabout, take 2nd exit"))
        routeSteps.add(RouteStep(baseLat + 0.080, baseLon + 0.060, ProtocolConstants.MANEUVER_DESTINATION, "Cairo Airport Road", "You have arrived at your destination"))

        // Generate dense polyline
        for (i in 0 until routeSteps.size - 1) {
            val s1 = routeSteps[i]
            val s2 = routeSteps[i + 1]
            val count = 20
            for (step in 0..count) {
                val frac = step.toDouble() / count
                val lat = s1.lat + (s2.lat - s1.lat) * frac
                val lon = s1.lon + (s2.lon - s1.lon) * frac
                routePolyline.add(Pair(lat, lon))
            }
        }

        totalDistanceM = 18500
        remainingDistanceM = totalDistanceM
        remainingTimeSec = 22 * 60
    }

    fun getRoutePoints(): List<Pair<Double, Double>> = routePolyline

    /**
     * Window of the route around the car for the head unit.
     * The car packet holds max 80 points, so for long real routes we send
     * the upcoming segment (from the nearest point forward) instead of
     * just the first 80 points of the whole trip.
     */
    fun getUpcomingPoints(carLat: Double, carLon: Double, max: Int = 80): List<Pair<Double, Double>> {
        if (routePolyline.size <= max) return routePolyline
        var bestIdx = 0
        var bestD = Double.MAX_VALUE
        for (i in routePolyline.indices) {
            val p = routePolyline[i]
            val dLat = p.first - carLat
            val dLon = p.second - carLon
            val d = dLat * dLat + dLon * dLon
            if (d < bestD) {
                bestD = d
                bestIdx = i
            }
        }
        val remaining = routePolyline.size - bestIdx
        if (remaining <= max) {
            // Not enough points ahead: pad with the tail behind the car.
            val from = maxOf(0, routePolyline.size - max)
            return routePolyline.subList(from, routePolyline.size)
        }
        // Decimate the upcoming segment if it is much longer than max.
        val segment = routePolyline.subList(bestIdx, routePolyline.size)
        if (segment.size <= max) return segment.toList()
        val step = segment.size.toDouble() / max
        val out = ArrayList<Pair<Double, Double>>(max)
        for (i in 0 until max) {
            out.add(segment[(i * step).toInt()])
        }
        return out
    }

    fun getCurrentStep(): RouteStep {
        return if (routeSteps.isNotEmpty()) routeSteps[currentStepIdx]
        else RouteStep(30.0444, 31.2357, ProtocolConstants.MANEUVER_STRAIGHT, "Main Street", "Drive straight")
    }

    fun getDistanceToNextTurn(currentLat: Double, currentLon: Double): Int {
        if (currentStepIdx >= routeSteps.size) return 0
        val target = routeSteps[minOf(currentStepIdx + 1, routeSteps.size - 1)]
        return distanceInMeters(currentLat, currentLon, target.lat, target.lon)
    }

    fun getRemainingDistance(): Int = remainingDistanceM
    fun getRemainingTime(): Int = remainingTimeSec

    fun updateProgress(currentLat: Double, currentLon: Double, currentSpeedKmh: Float) {
        if (currentStepIdx < routeSteps.size - 1) {
            val nextStep = routeSteps[currentStepIdx + 1]
            val dist = distanceInMeters(currentLat, currentLon, nextStep.lat, nextStep.lon)
            if (dist < 40) {
                currentStepIdx++
            }
        }

        // Decay remaining distance and time
        val speedMs = maxOf(currentSpeedKmh / 3.6f, 5.0f)
        remainingDistanceM = maxOf(0, remainingDistanceM - (speedMs * 1.0f).toInt())
        remainingTimeSec = if (speedMs > 1.0f) (remainingDistanceM / speedMs).toInt() else remainingTimeSec
    }

    private fun distanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).toInt()
    }
}
