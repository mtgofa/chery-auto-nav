package com.chery.autonav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.chery.autonav.bluetooth.BluetoothManagerHelper
import com.chery.autonav.bluetooth.BluetoothServerHelper
import com.chery.autonav.location.LocationManagerHelper
import com.chery.autonav.location.VehicleLocation
import com.chery.autonav.map.MapRendererHelper
import com.chery.autonav.map.TileManager
import com.chery.autonav.navigation.RouteManager
import com.chery.autonav.protocol.ProtocolConstants
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NavForegroundService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var btServer: BluetoothServerHelper
    private lateinit var locHelper: LocationManagerHelper
    private lateinit var btHelper: BluetoothManagerHelper
    private lateinit var mapRenderer: MapRendererHelper
    private lateinit var routeManager: RouteManager
    private lateinit var tileManager: TileManager

    private var scheduler: ScheduledExecutorService? = null
    private var lastLocation = VehicleLocation(30.0444, 31.2357, 0f, 0f, 20f, 3f, true)
    private var isSimulating = true
    private var frameSeq = 0

    companion object {
        const val CHANNEL_ID = "CheryNavServiceChannel"
        const val NOTIFICATION_ID = 1001

        var instance: NavForegroundService? = null
            private set
        var currentTileManager: TileManager? = null
            private set
        var currentRouteManager: com.chery.autonav.navigation.RouteManager? = null
            private set
        var routeLabel: String = "Demo: Tahrir → Airport"
            private set
        var latestLocation: VehicleLocation? = null
            private set
        var currentRoute: List<Pair<Double, Double>> = emptyList()
            private set

        var isRunning = false
            private set
        var connectedBtDevice: String? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CheryNav::WakeLock")
        wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 hours

        startForeground(NOTIFICATION_ID, createNotification("CheryNav Bluetooth Bridge Active"))

        instance = this
        routeManager = RouteManager()
        currentRouteManager = routeManager
        currentRoute = routeManager.getRoutePoints()
        mapRenderer = MapRendererHelper(580, 480)

        // 1. Bluetooth SPP RFCOMM Server
        btServer = BluetoothServerHelper(
            onCarConnected = { devName ->
                connectedBtDevice = devName
            },
            onCarDisconnected = {
                connectedBtDevice = null
            },
            onTouchEventReceived = { touch ->
                handleTouch(touch)
            },
            onTileAckReceived = { ack ->
                tileManager.onTileAckReceived(ack.zoom, ack.tileX, ack.tileY, ack.status)
            }
        )
        btServer.start()

        // 2. Tile Manager (OSM Tiles + Bluetooth Sync + Local Caching)
        tileManager = TileManager(this) { pkt ->
            btServer.broadcastPacket(pkt)
        }
        currentTileManager = tileManager
        tileManager.preCacheRouteTiles(currentRoute)

        // 4. Location Manager
        locHelper = LocationManagerHelper(this) { loc ->
            lastLocation = loc
            latestLocation = loc
            routeManager.updateProgress(loc.latitude, loc.longitude, loc.speedKmh)
            tileManager.updateVehiclePosition(loc.latitude, loc.longitude)
        }
        locHelper.setSimulationMode(isSimulating)
        locHelper.startListening()

        // 4. Bluetooth state watcher
        btHelper = BluetoothManagerHelper(this) { isConnected, name -> }
        btHelper.start()

        startStreamingLoop()
    }

    private fun handleTouch(touch: ProtocolConstants.TouchEvent) {
        when (touch.action) {
            3 -> mapRenderer.zoomIn()   // Zoom In
            4 -> mapRenderer.zoomOut()  // Zoom Out
            5 -> { /* Recenter */ }
        }
    }

    /** Apply a real route fetched on the phone (OSRM) and push it to the car. */
    fun setCustomRoute(result: com.chery.autonav.navigation.OsrmRouter.RouteResult) {
        routeManager.setCustomRoute(result.points, result.steps, result.totalDistanceM, result.totalDurationSec)
        currentRoute = routeManager.getRoutePoints()
        routeLabel = result.destName.take(40) + " (" + String.format("%.1f", result.totalDistanceM / 1000.0) + " km)"
        tileManager.preCacheRouteTiles(currentRoute)
    }

    fun resetToDemoRoute() {
        routeManager.loadDemoRoute()
        currentRoute = routeManager.getRoutePoints()
        routeLabel = "Demo: Tahrir → Airport"
        tileManager.preCacheRouteTiles(currentRoute)
    }

    fun setSimulationMode(enabled: Boolean) {
        isSimulating = enabled
        if (::locHelper.isInitialized) {
            locHelper.setSimulationMode(enabled)
        }
    }

    fun isSimulationMode(): Boolean = isSimulating

    private fun startStreamingLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor()

        // 10 Hz Telemetry & Route Loop (Instantaneous speedometer & heading)
        scheduler?.scheduleAtFixedRate({
            try {
                if (isSimulating) {
                    lastLocation = locHelper.tickSimulation(routeManager.getRoutePoints())
                    latestLocation = lastLocation
                    routeManager.updateProgress(lastLocation.latitude, lastLocation.longitude, lastLocation.speedKmh)
                    tileManager.updateVehiclePosition(lastLocation.latitude, lastLocation.longitude)
                }

                val hasClients = btServer.isConnected()
                if (hasClients) {
                    val telemPkt = ProtocolConstants.createTelemetryPacket(
                        lat = lastLocation.latitude,
                        lon = lastLocation.longitude,
                        speedKmh = lastLocation.speedKmh,
                        bearingDeg = lastLocation.bearingDeg,
                        altitudeM = lastLocation.altitudeM,
                        accuracyM = lastLocation.accuracyM,
                        hasFix = lastLocation.hasFix
                    )
                    btServer.broadcastPacket(telemPkt)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 0, 100, TimeUnit.MILLISECONDS)

        // 2 Hz Turn Guidance, Vector Polyline & Heartbeat Loop
        scheduler?.scheduleAtFixedRate({
            try {
                val hasClients = btServer.isConnected()
                if (hasClients) {
                    // 1. Send Turn-by-Turn Instruction
                    val step = routeManager.getCurrentStep()
                    val distTurn = routeManager.getDistanceToNextTurn(lastLocation.latitude, lastLocation.longitude)
                    val remDist = routeManager.getRemainingDistance()
                    val remTime = routeManager.getRemainingTime()

                    val instrPkt = ProtocolConstants.createInstructionPacket(
                        maneuver = step.maneuver,
                        distToTurnM = distTurn,
                        remDistM = remDist,
                        remTimeSec = remTime,
                        streetName = step.streetName,
                        instruction = step.instruction
                    )
                    btServer.broadcastPacket(instrPkt)

                    // 2. Send Vector Route Polyline (upcoming window so long
                    //    real routes render around the car, not just the start)
                    val polyPkt = ProtocolConstants.createRoutePolylinePacket(
                        carLat = lastLocation.latitude,
                        carLon = lastLocation.longitude,
                        points = routeManager.getUpcomingPoints(
                            lastLocation.latitude,
                            lastLocation.longitude
                        )
                    )
                    btServer.broadcastPacket(polyPkt)

                    // 3. Heartbeat
                    val batteryMgr = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batLevel = batteryMgr?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
                    val hbPkt = ProtocolConstants.createHeartbeatPacket(
                        batteryLevel = batLevel,
                        isCharging = false,
                        wifiSignal = 0,
                        btConnected = btServer.isConnected() || btHelper.isConnected()
                    )
                    btServer.broadcastPacket(hbPkt)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun createNotification(contentText: String): Notification {
        val channelId = CHANNEL_ID
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chery Auto Navigation Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("CheryNav Bluetooth Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        currentRouteManager = null
        scheduler?.shutdownNow()
        btServer.stop()
        locHelper.stopListening()
        btHelper.stop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
