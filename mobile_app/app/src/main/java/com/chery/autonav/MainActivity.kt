package com.chery.autonav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chery.autonav.map.TileManager
import com.chery.autonav.map.TileSources
import com.chery.autonav.service.NavForegroundService
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var osmMapView: com.chery.autonav.map.OsmMapView
    private lateinit var searchCard: com.google.android.material.card.MaterialCardView
    private lateinit var turnCard: com.google.android.material.card.MaterialCardView
    private lateinit var ivTurnArrow: ImageView
    private lateinit var tvTurnDistance: TextView
    private lateinit var tvTurnStreet: TextView
    private lateinit var etDestination: EditText
    private lateinit var searchProgress: ProgressBar
    private lateinit var btnClearSearch: ImageView
    private lateinit var tvCarStatus: TextView
    private lateinit var tvAttribution: TextView
    private lateinit var tvRouteLabel: TextView
    private lateinit var tvRouteStatus: TextView
    private lateinit var tvTileSyncStatus: TextView
    private lateinit var btnClearRoute: Button
    private lateinit var btnRecenter: com.google.android.material.card.MaterialCardView
    private lateinit var fabCompass: FloatingActionButton
    private lateinit var fabService: FloatingActionButton
    private lateinit var fabLayers: FloatingActionButton
    private lateinit var fabMyLocation: FloatingActionButton

    private var previewTileManager: TileManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private val statusChecker = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        osmMapView = findViewById(R.id.osmMapView)
        searchCard = findViewById(R.id.searchCard)
        turnCard = findViewById(R.id.turnCard)
        ivTurnArrow = findViewById(R.id.ivTurnArrow)
        tvTurnDistance = findViewById(R.id.tvTurnDistance)
        tvTurnStreet = findViewById(R.id.tvTurnStreet)
        etDestination = findViewById(R.id.etDestination)
        searchProgress = findViewById(R.id.searchProgress)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        tvCarStatus = findViewById(R.id.tvCarStatus)
        tvAttribution = findViewById(R.id.tvAttribution)
        tvRouteLabel = findViewById(R.id.tvRouteLabel)
        tvRouteStatus = findViewById(R.id.tvRouteStatus)
        tvTileSyncStatus = findViewById(R.id.tvTileSyncStatus)
        btnClearRoute = findViewById(R.id.btnClearRoute)
        btnRecenter = findViewById(R.id.btnRecenter)
        fabCompass = findViewById(R.id.fabCompass)
        fabService = findViewById(R.id.fabService)
        fabLayers = findViewById(R.id.fabLayers)
        fabMyLocation = findViewById(R.id.fabMyLocation)

        osmMapView.onFollowStateChanged = { following ->
            btnRecenter.visibility = if (following) android.view.View.GONE else android.view.View.VISIBLE
        }
        osmMapView.onCompassRotationChanged = { rot ->
            fabCompass.rotation = rot
        }
        btnRecenter.setOnClickListener {
            osmMapView.recenter()
        }
        fabCompass.setOnClickListener {
            val courseUp = osmMapView.toggleCourseUp()
            android.widget.Toast.makeText(
                this,
                if (courseUp) "وضع الملاحة (مع اتجاه السيارة)" else "وضع الشمال لأعلى",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        migrateToMapboxDefault()
        requestRequiredPermissions()

        // Search on keyboard "search" action.
        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        btnClearSearch.setOnClickListener {
            etDestination.text.clear()
            btnClearSearch.visibility = ImageView.GONE
        }
        etDestination.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                btnClearSearch.visibility = if ((s?.length ?: 0) > 0) ImageView.VISIBLE else ImageView.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        fabService.setOnClickListener {
            if (NavForegroundService.isRunning) stopNavService() else startNavService()
        }
        fabLayers.setOnClickListener { showStyleDialog() }
        fabMyLocation.setOnClickListener {
            osmMapView.recenter()
            val mgr = NavForegroundService.currentTileManager ?: getPreviewManager()
            val loc = NavForegroundService.latestLocation
            val lat = loc?.latitude ?: 30.0444
            val lon = loc?.longitude ?: 31.2357
            mgr.updateVehiclePosition(lat, lon)
        }

        btnClearRoute.setOnClickListener {
            NavForegroundService.instance?.resetToDemoRoute()
            tvRouteStatus.text = "اكتب وجهة في البحث فوق للانطلاق"
            etDestination.text.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusChecker)
    }

    override fun onPause() {
        handler.removeCallbacks(statusChecker)
        super.onPause()
    }

    // One-time switch of the default style to Mapbox navigation-night when a
    // token is present (older installs defaulted to Thunderforest).
    private fun migrateToMapboxDefault() {
        val prefs = getSharedPreferences(TileSources.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("migrated_mapbox_v2", false)) {
            if (TileSources.hasMapbox()) {
                TileSources.setSelectedId(this, "mapbox_nav_night")
            }
            prefs.edit().putBoolean("migrated_mapbox_v2", true).apply()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etDestination.windowToken, 0)
        etDestination.clearFocus()
    }

    private fun doSearch() {
        val query = etDestination.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()

        // Auto-start the bridge service so there is a route owner + car sync.
        if (NavForegroundService.instance == null) {
            startNavService()
            searchProgress.visibility = ProgressBar.VISIBLE
            tvRouteStatus.text = "بشغّل الخدمة..."
            handler.postDelayed({ runSearch(query) }, 900)
        } else {
            runSearch(query)
        }
    }

    private fun runSearch(query: String) {
        val svc = NavForegroundService.instance
        if (svc == null) {
            searchProgress.visibility = ProgressBar.GONE
            Toast.makeText(this, "تعذر تشغيل الخدمة", Toast.LENGTH_SHORT).show()
            return
        }
        val origin = NavForegroundService.latestLocation
        val oLat = origin?.latitude ?: 30.0444
        val oLon = origin?.longitude ?: 31.2357
        searchProgress.visibility = ProgressBar.VISIBLE
        tvRouteStatus.text = "بحسب المسار..."

        val direct = parseLatLon(query)
        if (direct != null) {
            requestRoute(svc, oLat, oLon, direct.first, direct.second, query)
        } else {
            com.chery.autonav.navigation.OsrmRouter.search(query, oLat, oLon) { found ->
                runOnUiThread {
                    if (found == null) {
                        searchProgress.visibility = ProgressBar.GONE
                        tvRouteStatus.text = "مش لاقي المكان ده، جرّب اسم أوضح أو إحداثيات"
                    } else {
                        requestRoute(svc, oLat, oLon, found.lat, found.lon, found.displayName.take(60))
                    }
                }
            }
        }
    }

    private fun requestRoute(
        svc: NavForegroundService,
        oLat: Double,
        oLon: Double,
        dLat: Double,
        dLon: Double,
        destName: String
    ) {
        com.chery.autonav.navigation.OsrmRouter.fetchRoute(oLat, oLon, dLat, dLon, destName) { result ->
            runOnUiThread {
                searchProgress.visibility = ProgressBar.GONE
                if (result == null) {
                    tvRouteStatus.text = "تعذر حساب المسار — اتأكد من النت وجرّب تاني"
                    return@runOnUiThread
                }
                svc.setCustomRoute(result)
                osmMapView.setRoute(result.points)
                val km = String.format("%.1f", result.totalDistanceM / 1000.0)
                val mins = result.totalDurationSec / 60
                tvRouteLabel.text = result.destName
                tvRouteStatus.text = "$km كم · حوالي $mins دقيقة · ظاهر على شاشة العربية"
            }
        }
    }

    private fun parseLatLon(query: String): Pair<Double, Double>? {
        val parts = query.split(",")
        if (parts.size != 2) return null
        return try {
            val lat = parts[0].trim().toDouble()
            val lon = parts[1].trim().toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) Pair(lat, lon) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun showStyleDialog() {
        val sources = TileSources.ALL
        val names = sources.map { it.displayName }.toTypedArray()
        val currentId = TileSources.getSelectedId(this)
        var checked = sources.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val simOn = NavForegroundService.instance?.isSimulationMode() ?: true

        AlertDialog.Builder(this)
            .setTitle("ستايل الخريطة")
            .setSingleChoiceItems(names, checked) { _, which -> checked = which }
            .setPositiveButton("تطبيق") { _, _ ->
                val picked = sources[checked]
                TileSources.setSelectedId(this, picked.id)
                val mgr = NavForegroundService.currentTileManager ?: getPreviewManager()
                mgr.setStyle(picked.id)
                val loc = NavForegroundService.latestLocation
                mgr.updateVehiclePosition(loc?.latitude ?: 30.0444, loc?.longitude ?: 31.2357)
                osmMapView.tileManager = mgr
                osmMapView.invalidate()
                tvAttribution.text = picked.attribution
            }
            .setNeutralButton(if (simOn) "إيقاف المحاكاة" else "تشغيل المحاكاة") { _, _ ->
                NavForegroundService.instance?.setSimulationMode(!simOn)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun getPreviewManager(): TileManager {
        var mgr = previewTileManager
        if (mgr == null) {
            mgr = TileManager(this) { }
            previewTileManager = mgr
        }
        return mgr
    }

    private fun updateUI() {
        val connectedBt = NavForegroundService.connectedBtDevice
        if (connectedBt != null) {
            tvCarStatus.text = "متصل بالعربية ($connectedBt)"
            tvCarStatus.setTextColor(0xFF00E676.toInt())
        } else {
            tvCarStatus.text = "بانتظار اتصال البلوتوث..."
            tvCarStatus.setTextColor(0xFFFFC65C.toInt())
        }

        // FAB service state colour.
        fabService.imageTintList = android.content.res.ColorStateList.valueOf(
            if (NavForegroundService.isRunning) 0xFF00E676.toInt() else 0xFFFF6B6B.toInt()
        )

        val tileMgr = NavForegroundService.currentTileManager ?: getPreviewManager()
        osmMapView.tileManager = tileMgr
        val synced = tileMgr.getSyncedCount()
        val pending = tileMgr.getPendingCount()
        tvTileSyncStatus.text = "كاش الشاشة: $synced بلاطة" + (if (pending > 0) " ($pending قيد الإرسال)" else "")
        tvAttribution.text = tileMgr.getAttribution()

        val loc = NavForegroundService.latestLocation
        if (loc != null) {
            osmMapView.updateVehicle(loc.latitude, loc.longitude, loc.bearingDeg)
            if (NavForegroundService.currentTileManager == null) {
                tileMgr.updateVehiclePosition(loc.latitude, loc.longitude)
            }
        } else if (NavForegroundService.currentTileManager == null) {
            tileMgr.updateVehiclePosition(30.0444, 31.2357)
            osmMapView.updateVehicle(30.0444, 31.2357, 0f)
        }
        val route = NavForegroundService.currentRoute
        if (route.isNotEmpty()) osmMapView.setRoute(route)

        // Keep the bottom label in sync with the active route when idle.
        if (tvRouteLabel.text.isNullOrBlank()) {
            tvRouteLabel.text = NavForegroundService.routeLabel
        }

        // Turn-by-turn card replaces the search bar while navigating a real route.
        val rm = NavForegroundService.currentRouteManager
        val navigating = rm?.isCustom() == true && loc != null
        if (navigating) {
            val step = rm!!.getCurrentStep()
            val dist = rm.getDistanceToNextTurn(loc!!.latitude, loc.longitude)
            ivTurnArrow.setImageResource(iconForManeuver(step.maneuver))
            tvTurnDistance.text = "بعد " + formatDistance(dist)
            tvTurnStreet.text = step.streetName
            if (turnCard.visibility != android.view.View.VISIBLE) {
                turnCard.visibility = android.view.View.VISIBLE
                searchCard.visibility = android.view.View.GONE
            }
        } else {
            if (turnCard.visibility != android.view.View.GONE) {
                turnCard.visibility = android.view.View.GONE
                searchCard.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun iconForManeuver(maneuver: Int): Int = when (maneuver) {
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_LEFT -> R.drawable.ic_nav_left
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_SLIGHT_LEFT -> R.drawable.ic_nav_slight_left
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_SHARP_LEFT -> R.drawable.ic_nav_sharp_left
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_RIGHT -> R.drawable.ic_nav_right
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_SLIGHT_RIGHT -> R.drawable.ic_nav_slight_right
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_SHARP_RIGHT -> R.drawable.ic_nav_sharp_right
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_UTURN -> R.drawable.ic_nav_uturn
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_ROUNDABOUT -> R.drawable.ic_nav_roundabout
        com.chery.autonav.protocol.ProtocolConstants.MANEUVER_DESTINATION -> R.drawable.ic_nav_destination
        else -> R.drawable.ic_nav_straight
    }

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            String.format("%.1f كم", meters / 1000.0)
        } else {
            "${(meters / 10) * 10} م"
        }
    }

    private fun startNavService() {
        val intent = Intent(this, NavForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopNavService() {
        stopService(Intent(this, NavForegroundService::class.java))
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }
}
