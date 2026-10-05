package com.chery.autonav.server

import com.chery.autonav.protocol.ProtocolConstants
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class CarBridgeServer(
    val port: Int = 5555,
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: () -> Unit,
    private val onTouchEventReceived: (ProtocolConstants.TouchEvent) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private var acceptThread: Thread? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        acceptThread = Thread({
            try {
                serverSocket = ServerSocket(port)
                while (isRunning.get()) {
                    val socket = serverSocket!!.accept()
                    socket.tcpNoDelay = true
                    clientSockets.add(socket)
                    val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
                    onClientConnected(clientIp)

                    // Start client reader thread
                    Thread({ handleClientReader(socket) }, "CarClientReader-$clientIp").start()
                }
            } catch (e: Exception) {
                if (isRunning.get()) e.printStackTrace()
            }
        }, "CarBridgeServerAccept")
        acceptThread?.start()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}

        for (s in clientSockets) {
            try { s.close() } catch (e: Exception) {}
        }
        clientSockets.clear()
        onClientDisconnected()
    }

    fun hasClients(): Boolean = clientSockets.isNotEmpty()

    private fun handleClientReader(socket: Socket) {
        val inputStream = socket.getInputStream()
        val headerBuf = ByteArray(10)

        try {
            while (isRunning.get() && !socket.isClosed) {
                if (!readExact(inputStream, headerBuf, 10)) break

                val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                val magic = bb.int
                val opcode = bb.short.toInt() and 0xFFFF
                val payloadLen = bb.int

                if (magic != ProtocolConstants.CHERY_MAGIC) {
                    break // Corrupted stream
                }

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
            // Client closed
        } finally {
            clientSockets.remove(socket)
            try { socket.close() } catch (e: Exception) {}
            if (clientSockets.isEmpty()) {
                onClientDisconnected()
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
                val os: OutputStream = sock.getOutputStream()
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
