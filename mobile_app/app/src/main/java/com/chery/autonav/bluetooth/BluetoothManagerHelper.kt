package com.chery.autonav.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class BluetoothManagerHelper(
    private val context: Context,
    private val onCarBtStateChanged: (Boolean, String?) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isCarConnected = false
    private var connectedDeviceName: String? = null

    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action
            if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                connectedDeviceName = device?.name ?: "Unknown Bluetooth"
                isCarConnected = true
                onCarBtStateChanged(true, connectedDeviceName)
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
                isCarConnected = false
                connectedDeviceName = null
                onCarBtStateChanged(false, null)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            context.registerReceiver(btReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        checkInitialStatus()
    }

    fun stop() {
        try {
            context.unregisterReceiver(btReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkInitialStatus() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onCarBtStateChanged(false, null)
            return
        }
        // Default report
        onCarBtStateChanged(isCarConnected, connectedDeviceName)
    }

    fun isConnected(): Boolean = isCarConnected
    fun getDeviceName(): String? = connectedDeviceName
}
