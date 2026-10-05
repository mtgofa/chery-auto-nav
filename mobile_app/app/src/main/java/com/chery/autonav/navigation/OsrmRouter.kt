package com.chery.autonav.navigation

import com.chery.autonav.protocol.ProtocolConstants
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free OSM-based routing (no API key needed for personal use):
 * - Route: OSRM public demo server (driving profile, GeoJSON geometry + steps).
 * - Search: Nominatim (address -> lat/lon).
 *
 * Be gentle with these free servers: one request per user action only.
 */
object OsrmRouter {

    data class RouteResult(
        val points: List<Pair<Double, Double>>, // (lat, lon)
        val steps: List<RouteStep>,
        val totalDistanceM: Int,
        val totalDurationSec: Int,
        val destName: String
    )

    data class SearchResult(
        val lat: Double,
        val lon: Double,
        val displayName: String
    )

    private const val UA = "CheryAutoNav/1.0 (Android Vehicle Bridge)"

    fun fetchRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        destName: String,
        callback: (RouteResult?) -> Unit
    ) {
        Thread({
            try {
                val urlStr = "https://router.project-osrm.org/route/v1/driving/" +
                        "$originLon,$originLat;$destLon,$destLat" +
                        "?overview=full&geometries=geojson&steps=true"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    callback(null)
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                callback(parseRoute(body, destName))
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }, "OsrmRouteThread").start()
    }

    fun search(
        query: String,
        nearLat: Double,
        nearLon: Double,
        callback: (SearchResult?) -> Unit
    ) {
        Thread({
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                // viewbox biases results near the car without hard filtering.
                val d = 1.0
                val urlStr = "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=1" +
                        "&viewbox=${nearLon - d},${nearLat + d},${nearLon + d},${nearLat - d}&bounded=0" +
                        "&accept-language=en,ar"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    callback(null)
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = JSONArray(body)
                if (arr.length() == 0) {
                    callback(null)
                    return@Thread
                }
                val o = arr.getJSONObject(0)
                callback(
                    SearchResult(
                        lat = o.getString("lat").toDouble(),
                        lon = o.getString("lon").toDouble(),
                        displayName = o.optString("display_name", query)
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }, "NominatimThread").start()
    }

    private fun parseRoute(body: String, destName: String): RouteResult? {
        try {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return null
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val r = routes.getJSONObject(0)

            val distanceM = r.optDouble("distance", 0.0).toInt()
            val durationSec = r.optDouble("duration", 0.0).toInt()

            // Full geometry as (lat, lon) pairs, decimated to keep memory sane.
            val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
            val raw = ArrayList<Pair<Double, Double>>(coords.length())
            for (i in 0 until coords.length()) {
                val c: JSONArray = coords.getJSONArray(i)
                raw.add(Pair(c.getDouble(1), c.getDouble(0)))
            }
            val points = decimate(raw, 600)

            // Turn steps from legs.
            val outSteps = ArrayList<RouteStep>()
            val legs = r.optJSONArray("legs")
            if (legs != null) {
                for (li in 0 until legs.length()) {
                    val leg = legs.getJSONObject(li)
                    val steps = leg.optJSONArray("steps") ?: continue
                    for (si in 0 until steps.length()) {
                        val s = steps.getJSONObject(si)
                        val man = s.optJSONObject("maneuver")
                        val type = man?.optString("type", "") ?: ""
                        val modifier = man?.optString("modifier", null)
                        val locArr = man?.optJSONArray("location")
                        val sLat = locArr?.optDouble(1) ?: 0.0
                        val sLon = locArr?.optDouble(0) ?: 0.0
                        val name = s.optString("name", "").trim()
                        val maneuver = mapManeuver(type, modifier)
                        val instr = buildInstruction(type, modifier, name, destName, maneuver)
                        val street = when {
                            name.isNotEmpty() -> name
                            maneuver == ProtocolConstants.MANEUVER_DESTINATION -> destName.take(60)
                            else -> "Unnamed road"
                        }
                        outSteps.add(RouteStep(sLat, sLon, maneuver, street, instr))
                    }
                }
            }
            if (outSteps.isEmpty()) return null
            return RouteResult(points, outSteps, distanceM, durationSec, destName)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun decimate(
        pts: List<Pair<Double, Double>>,
        max: Int
    ): List<Pair<Double, Double>> {
        if (pts.size <= max) return pts
        val step = (pts.size - 1).toDouble() / (max - 1)
        val out = ArrayList<Pair<Double, Double>>(max)
        for (i in 0 until max) {
            out.add(pts[(i * step).toInt()])
        }
        return out
    }

    private fun mapManeuver(type: String, modifier: String?): Int {
        if (type == "arrive") return ProtocolConstants.MANEUVER_DESTINATION
        if (type == "roundabout" || type == "rotary" || type == "roundabout turn") {
            return ProtocolConstants.MANEUVER_ROUNDABOUT
        }
        if (type == "depart" || type == "continue" || type == "new name" || type == "notification") {
            return byModifier(modifier, ProtocolConstants.MANEUVER_STRAIGHT)
        }
        // turn / merge / on ramp / off ramp / fork / end of road
        return byModifier(modifier, ProtocolConstants.MANEUVER_STRAIGHT)
    }

    private fun byModifier(modifier: String?, fallback: Int): Int {
        return when (modifier) {
            "uturn" -> ProtocolConstants.MANEUVER_UTURN
            "sharp right" -> ProtocolConstants.MANEUVER_SHARP_RIGHT
            "right" -> ProtocolConstants.MANEUVER_RIGHT
            "slight right" -> ProtocolConstants.MANEUVER_SLIGHT_RIGHT
            "straight" -> ProtocolConstants.MANEUVER_STRAIGHT
            "slight left" -> ProtocolConstants.MANEUVER_SLIGHT_LEFT
            "left" -> ProtocolConstants.MANEUVER_LEFT
            "sharp left" -> ProtocolConstants.MANEUVER_SHARP_LEFT
            else -> fallback
        }
    }

    private fun buildInstruction(
        type: String,
        modifier: String?,
        name: String,
        destName: String,
        maneuver: Int
    ): String {
        val onRoad = if (name.isNotEmpty()) " onto $name" else ""
        return when (maneuver) {
            ProtocolConstants.MANEUVER_DESTINATION -> "Arrived at $destName".take(120)
            ProtocolConstants.MANEUVER_UTURN -> "Make a U-turn$onRoad"
            ProtocolConstants.MANEUVER_ROUNDABOUT -> "At the roundabout$onRoad"
            ProtocolConstants.MANEUVER_STRAIGHT -> "Continue straight$onRoad"
            else -> {
                val dir = when (modifier) {
                    "slight left", "slight right",
                    "sharp left", "sharp right" -> modifier.replaceFirstChar { it.uppercase() }
                    "left" -> "Left"
                    "right" -> "Right"
                    else -> if (type == "depart") "Head out" else "Turn"
                }
                "$dir$onRoad"
            }
        }.take(120)
    }
}
