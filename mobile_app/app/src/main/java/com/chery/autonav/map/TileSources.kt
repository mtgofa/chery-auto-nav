package com.chery.autonav.map

import android.content.Context
import com.chery.autonav.BuildConfig

/**
 * Central registry for map tile styles.
 * - OSM + CARTO: free, no key needed.
 * - Thunderforest: needs API key (stored in SharedPreferences, ships with a default).
 *
 * Changing style changes the tile URL AND the local file prefix,
 * so the phone preview + car sync automatically refetch the new look.
 */
object TileSources {

    const val PREFS_NAME = "chery_tile_cache"
    const val KEY_SOURCE = "tile_source_id"
    const val KEY_TF_API = "thunderforest_api_key"

    // Default key shipped with the app (user-provided, personal use).
    // Can be overridden at runtime via setThunderforestKey().
    const val DEFAULT_TF_KEY = "bfaee8b03af74ff5a251d7fcf6c1ce98"

    data class Source(
        val id: String,
        val displayName: String,
        val attribution: String,
        val needsTfKey: Boolean = false,
        val tfStyle: String? = null,
        val urlTemplate: ((z: Int, x: Long, y: Long, tfKey: String) -> String)? = null
    )

    // Mapbox public token, injected from local.properties at build time.
    val MAPBOX_TOKEN: String = BuildConfig.MAPBOX_TOKEN
    fun hasMapbox(): Boolean = MAPBOX_TOKEN.startsWith("pk.")

    private fun tfUrl(style: String): (Int, Long, Long, String) -> String = { z, x, y, key ->
        "https://tile.thunderforest.com/$style/$z/$x/$y.png?apikey=$key"
    }

    // Mapbox Static Tiles API (raster), 256px tiles to match our renderer.
    // navigation-night-v1 / navigation-day-v1 are Mapbox's turn-by-turn styles.
    private fun mapboxUrl(styleId: String): (Int, Long, Long, String) -> String = { z, x, y, _ ->
        "https://api.mapbox.com/styles/v1/mapbox/$styleId/tiles/256/$z/$x/$y?access_token=$MAPBOX_TOKEN"
    }

    val ALL: List<Source> = listOf(
        Source(
            id = "mapbox_nav_night",
            displayName = "Mapbox Navigation (night)",
            attribution = "© Mapbox © OpenStreetMap",
            urlTemplate = mapboxUrl("navigation-night-v1")
        ),
        Source(
            id = "mapbox_nav_day",
            displayName = "Mapbox Navigation (day)",
            attribution = "© Mapbox © OpenStreetMap",
            urlTemplate = mapboxUrl("navigation-day-v1")
        ),
        Source(
            id = "mapbox_streets",
            displayName = "Mapbox Streets",
            attribution = "© Mapbox © OpenStreetMap",
            urlTemplate = mapboxUrl("streets-v12")
        ),
        Source(
            id = "tf_transport",
            displayName = "Thunderforest Transport (car-like)",
            attribution = "© Thunderforest © OpenStreetMap",
            needsTfKey = true,
            tfStyle = "transport",
            urlTemplate = tfUrl("transport")
        ),
        Source(
            id = "tf_transport_dark",
            displayName = "Thunderforest Dark (night)",
            attribution = "© Thunderforest © OpenStreetMap",
            needsTfKey = true,
            tfStyle = "transport-dark",
            urlTemplate = tfUrl("transport-dark")
        ),
        Source(
            id = "tf_landscape",
            displayName = "Thunderforest Landscape",
            attribution = "© Thunderforest © OpenStreetMap",
            needsTfKey = true,
            tfStyle = "landscape",
            urlTemplate = tfUrl("landscape")
        ),
        Source(
            id = "tf_outdoors",
            displayName = "Thunderforest Outdoors",
            attribution = "© Thunderforest © OpenStreetMap",
            needsTfKey = true,
            tfStyle = "outdoors",
            urlTemplate = tfUrl("outdoors")
        ),
        Source(
            id = "tf_neighbourhood",
            displayName = "Thunderforest Neighbourhood",
            attribution = "© Thunderforest © OpenStreetMap",
            needsTfKey = true,
            tfStyle = "neighbourhood",
            urlTemplate = tfUrl("neighbourhood")
        ),
        Source(
            id = "osm_standard",
            displayName = "OSM Standard (fallback)",
            attribution = "© OpenStreetMap contributors",
            urlTemplate = { z, x, y, _ -> "https://tile.openstreetmap.org/$z/$x/$y.png" }
        )
    )

    fun getSource(id: String): Source = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun getSelectedId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Default to Mapbox's navigation-night style when a token is present,
        // otherwise fall back to the keyless Thunderforest style.
        val fallback = if (hasMapbox()) "mapbox_nav_night" else "tf_transport"
        val id = prefs.getString(KEY_SOURCE, fallback) ?: fallback
        // If a previous run saved a Mapbox style but the token is now gone, don't
        // get stuck on blank tiles.
        if (id.startsWith("mapbox_") && !hasMapbox()) return "tf_transport"
        return id
    }

    fun setSelectedId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SOURCE, id).apply()
    }

    fun getThunderforestKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TF_API, null)
        if (!saved.isNullOrBlank()) return saved
        // First run: persist the shipped default so it is editable later.
        prefs.edit().putString(KEY_TF_API, DEFAULT_TF_KEY).apply()
        return DEFAULT_TF_KEY
    }

    fun setThunderforestKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TF_API, key.trim()).apply()
    }

    fun buildUrl(context: Context, zoom: Int, x: Long, y: Long): Pair<Source, String> {
        val source = getSource(getSelectedId(context))
        val key = getThunderforestKey(context)
        val url = source.urlTemplate?.invoke(zoom, x, y, key)
            ?: "https://tile.openstreetmap.org/$zoom/$x/$y.png"
        return source to url
    }
}
