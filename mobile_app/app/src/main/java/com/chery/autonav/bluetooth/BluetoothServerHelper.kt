package com.chery.autonav.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.chery.autonav.protocol.ProtocolConstants
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothServerHelper(
    private val onCarConnected: (String) -> Unit,
    private val onCarDisconnected: () -> Unit,
    private val onTouchEventReceived: (ProtocolConstants.TouchEvent) -> Unit
) {
    // Standard Bluetooth Serial Port Profile (SPP) UUID
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val clientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private var acceptThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        if (isRunning.get()) return
        isRunning.set(true)

        acceptThread = Thread({
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("CheryNavBridge", SPP_UUID)
                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    clientSockets.add(socket)
                    val devName = socket.remoteDevice?.name ?: "Chery Radio"
                    onCarConnected(devName)

                    Thread({ handleClientReader(socket) }, "CarBtReader-$devName").start()
                }
            } catch (e: Exception) {
                if (isRunning.get()) e.printStackTrace()
            }
        }, "CheryBtAcceptThread")
        acceptThread?.start()
    }

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (e: Exception) {}
        for (s in clientSockets) {
            try { s.close() } catch (e: Exception) {}
        }
        clientSockets.clear()
        onCarDisconnected()
    }

    fun isConnected(): Boolean = clientSockets.isNotEmpty()

    private fun handleClientReader(socket: BluetoothSocket) {
        val inputStream: InputStream = socket.inputStream
        val headerBuf = ByteArray(10)

        try {
            while (isRunning.get() && socket.isConnected) {
                if (!readExact(inputStream, headerBuf, 10)) break

                val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                val magic = bb.int
                val opcode = bb.short.toInt() and 0xFFFF
                val payloadLen = bb.int

                if (magic != ProtocolConstants.CHERY_MAGIC) break

                val payload = ByteArray(payloadLen)
                if (payloadLen > 0) {
                    if (!readExact(inputStream, payload, payloadLen)) break
                }

                if (opcode == ProtocolConstants.PKT_TYPE_TOUCH_EVENT) {
                    val ev = ProtocolConstants.parseTouchEvent(payload)
                    if (ev != null) {
                        onTouchEventReceived(ev)
                    }
                }
            }
        } catch (e: Exception) {
            // Disconnect
        } finally {
            clientSockets.remove(socket)
            try { socket.close() } catch (e: Exception) {}
            if (clientSockets.isEmpty()) {
                onCarDisconnected()
            }
        }
    }

    private fun readExact(isStream: InputStream, buf: ByteArray, len: Int): Boolean {
        var total = 0
        while (total < len && isRunning.get()) {
            val r = isStream.read(buf, total, len - total)
            if (r <= 0) return false
            total += r
        }
        return total == len
    }

    fun broadcastPacket(packet: ByteArray) {
        if (clientSockets.isEmpty()) return
        for (sock in clientSockets) {
            try {
                val os: OutputStream = sock.outputStream
                synchronized(os) {
                    os.write(packet)
                    os.flush()
                }
            } catch (e: Exception) {
                clientSockets.remove(sock)
                try { sock.close() } catch (ex: Exception) {}
            }
        }
    }
}
