package com.chery.autonav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chery.autonav.service.NavForegroundService
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: Switch
    private lateinit var tvIpAddress: TextView
    private lateinit var tvCarStatus: TextView
    private lateinit var btnResetRoute: Button

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

        switchService = findViewById(R.id.switchService)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvCarStatus = findViewById(R.id.tvCarStatus)
        btnResetRoute = findViewById(R.id.btnResetRoute)

        requestRequiredPermissions()

        switchService.isChecked = NavForegroundService.isRunning
        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startNavService()
            } else {
                stopNavService()
            }
        }

        btnResetRoute.setOnClickListener {
            // Restart service to reset demo route
            if (NavForegroundService.isRunning) {
                stopNavService()
                handler.postDelayed({ startNavService() }, 500)
            }
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

    private fun updateUI() {
        val ip = getLocalIpAddress() ?: "192.168.43.1"
        tvIpAddress.text = "Bluetooth SPP Active | WiFi IP: $ip:5555"

        val connectedBt = NavForegroundService.connectedBtDevice
        val connectedIp = NavForegroundService.connectedClientIp

        if (connectedBt != null) {
            tvCarStatus.text = "Car Screen: BLUETOOTH CONNECTED ($connectedBt)"
            tvCarStatus.setTextColor(0xFF00E676.toInt()) // Green
        } else if (connectedIp != null) {
            tvCarStatus.text = "Car Screen: WIFI CONNECTED ($connectedIp)"
            tvCarStatus.setTextColor(0xFF00E676.toInt()) // Green
        } else {
            tvCarStatus.text = "Car Screen: Waiting for Bluetooth pairing / connection..."
            tvCarStatus.setTextColor(0xFFFFA726.toInt()) // Orange
        }

        switchService.isChecked = NavForegroundService.isRunning
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
        val intent = Intent(this, NavForegroundService::class.java)
        stopService(intent)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
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
