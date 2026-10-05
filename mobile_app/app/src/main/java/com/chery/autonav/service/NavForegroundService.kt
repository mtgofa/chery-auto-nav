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
import com.chery.autonav.navigation.RouteManager
import com.chery.autonav.protocol.ProtocolConstants
import com.chery.autonav.server.CarBridgeServer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NavForegroundService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var server: CarBridgeServer
    private lateinit var btServer: BluetoothServerHelper
    private lateinit var locHelper: LocationManagerHelper
    private lateinit var btHelper: BluetoothManagerHelper
    private lateinit var mapRenderer: MapRendererHelper
    private lateinit var routeManager: RouteManager

    private var scheduler: ScheduledExecutorService? = null
    private var lastLocation = VehicleLocation(30.0444, 31.2357, 0f, 0f, 20f, 3f, true)
    private var isSimulating = true
    private var frameSeq = 0

    companion object {
        const val CHANNEL_ID = "CheryNavServiceChannel"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
        var connectedClientIp: String? = null
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

        startForeground(NOTIFICATION_ID, createNotification("Chery AutoNav Bluetooth/WiFi Bridge Active"))

        routeManager = RouteManager()
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
            }
        )
        btServer.start()

        // 2. Wi-Fi TCP Server (Port 5555)
        server = CarBridgeServer(
            port = 5555,
            onClientConnected = { ip ->
                connectedClientIp = ip
            },
            onClientDisconnected = {
                connectedClientIp = null
            },
            onTouchEventReceived = { touch ->
                handleTouch(touch)
            }
        )
        server.start()

        // 3. Location Manager
        locHelper = LocationManagerHelper(this) { loc ->
            lastLocation = loc
            routeManager.updateProgress(loc.latitude, loc.longitude, loc.speedKmh)
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

    private fun startStreamingLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor()

        // 10 Hz Telemetry & Route Loop (Instantaneous speedometer & heading)
        scheduler?.scheduleAtFixedRate({
            try {
                if (isSimulating) {
                    lastLocation = locHelper.tickSimulation(routeManager.getRoutePoints())
                    routeManager.updateProgress(lastLocation.latitude, lastLocation.longitude, lastLocation.speedKmh)
                }

                val hasClients = server.hasClients() || btServer.isConnected()
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
                    server.broadcastPacket(telemPkt)
                    btServer.broadcastPacket(telemPkt)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 0, 100, TimeUnit.MILLISECONDS)

        // 2 Hz Turn Guidance, Vector Polyline & Heartbeat Loop
        scheduler?.scheduleAtFixedRate({
            try {
                val hasClients = server.hasClients() || btServer.isConnected()
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
                    server.broadcastPacket(instrPkt)
                    btServer.broadcastPacket(instrPkt)

                    // 2. Send Vector Route Polyline (Ideal for Bluetooth SPP)
                    val polyPkt = ProtocolConstants.createRoutePolylinePacket(
                        carLat = lastLocation.latitude,
                        carLon = lastLocation.longitude,
                        points = routeManager.getRoutePoints()
                    )
                    server.broadcastPacket(polyPkt)
                    btServer.broadcastPacket(polyPkt)

                    // 3. Heartbeat
                    val batteryMgr = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batLevel = batteryMgr?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
                    val hbPkt = ProtocolConstants.createHeartbeatPacket(
                        batteryLevel = batLevel,
                        isCharging = false,
                        wifiSignal = 4,
                        btConnected = btServer.isConnected() || btHelper.isConnected()
                    )
                    server.broadcastPacket(hbPkt)
                    btServer.broadcastPacket(hbPkt)

                    // 4. Send Map frame if connected via Wi-Fi
                    if (server.hasClients()) {
                        frameSeq++
                        val mapBytes = mapRenderer.renderMapFrame(
                            carLat = lastLocation.latitude,
                            carLon = lastLocation.longitude,
                            headingDeg = lastLocation.bearingDeg,
                            routePoints = routeManager.getRoutePoints()
                        )
                        val mapPkt = ProtocolConstants.createMapImagePacket(
                            frameSeq = frameSeq,
                            width = 580,
                            height = 480,
                            carX = 290,
                            carY = 312,
                            heading = lastLocation.bearingDeg,
                            imageData = mapBytes,
                            format = ProtocolConstants.IMG_FMT_RGB565
                        )
                        server.broadcastPacket(mapPkt)
                    }
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
            .setContentTitle("Chery AutoNav Bluetooth/WiFi Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scheduler?.shutdownNow()
        btServer.stop()
        server.stop()
        locHelper.stopListening()
        btHelper.stop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
