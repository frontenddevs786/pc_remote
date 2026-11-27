package com.example.pcremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.UUID

/**
 * Minimal OBEX "Object Push" client (the protocol behind Windows' "Send or receive files via
 * Bluetooth"). It opens a short-lived classic Bluetooth link to an already paired PC, so the BLE
 * keyboard/mouse link is not touched and no new pairing is needed.
 */
@SuppressLint("MissingPermission")
class ObexPush(private val device: BluetoothDevice) {

    class PushException(message: String) : IOException(message)

    private class Resp(val code: Int, val payload: ByteArray)

    companion object {
        val OPP_UUID: UUID = UUID.fromString("00001105-0000-1000-8000-00805f9b34fb")

        private const val OP_CONNECT = 0x80
        private const val OP_DISCONNECT = 0x81
        private const val OP_PUT = 0x02
        private const val OP_PUT_FINAL = 0x82

        private const val RESP_CONTINUE = 0x90
        private const val RESP_OK = 0xA0

        private const val HDR_NAME = 0x01
        private const val HDR_LENGTH = 0xC3
        private const val HDR_BODY = 0x48
        private const val HDR_END_OF_BODY = 0x49
        private const val HDR_CONN_ID = 0xCB
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var maxPacket = 4096
    private var connectionId: ByteArray? = null

    /** Opens the link and the OBEX session. Throws IOException if the PC is not waiting for files. */
    fun open() {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val s: BluetoothSocket = try {
            device.createRfcommSocketToServiceRecord(OPP_UUID).also { it.connect() }
        } catch (e: IOException) {
            // Some PCs only accept the non-authenticated variant.
            device.createInsecureRfcommSocketToServiceRecord(OPP_UUID).also { it.connect() }
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
        obexConnect()
    }

    private fun obexConnect() {
        // version 1.0, flags 0, we accept packets up to 0x2000 bytes
        sendPacket(OP_CONNECT, byteArrayOf(0x10, 0x00, 0x20, 0x00))
        val r = readResponse()
        if (r.code != RESP_OK) throw PushException(describe(r.code))
        val p = r.payload
        if (p.size >= 4) {
            val server = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
            maxPacket = server.coerceIn(255, 8192)
        }
        // optional headers after the 4 fixed bytes: remember a Connection Id if the PC sent one
        var i = 4
        loop@ while (i < p.size) {
            val id = p[i].toInt() and 0xFF
            when (id and 0xC0) {
                0x00, 0x40 -> {
                    if (i + 3 > p.size) break@loop
                    val len = ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)
                    if (len < 3) break@loop
                    i += len
                }
                0x80 -> i += 2
                else -> {
                    if (id == HDR_CONN_ID && i + 5 <= p.size) {
                        connectionId = p.copyOfRange(i + 1, i + 5)
                    }
                    i += 5
                }
            }
        }
    }

    /** Sends one file. [onProgress] gets the number of bytes of this file the PC has acknowledged. */
    fun put(name: String, size: Long, data: InputStream, onProgress: (Long) -> Unit) {
        val src = PushbackInputStream(data, 1)
        val nameHdr = nameHeader(cleanName(name))
        val lenHdr = if (size >= 0) {
            byteArrayOf(
                HDR_LENGTH.toByte(),
                (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()
            )
        } else {
            ByteArray(0)
        }
        val connHdr = connectionId?.let { byteArrayOf(HDR_CONN_ID.toByte()) + it } ?: ByteArray(0)

        var first = true
        var sent = 0L
        while (true) {
            val overhead = 3 + connHdr.size + (if (first) nameHdr.size + lenHdr.size else 0) + 3
            val chunk = (maxPacket - overhead).coerceAtLeast(1)
            val buf = ByteArray(chunk)
            var n = 0
            while (n < chunk) {
                val r = src.read(buf, n, chunk - n)
                if (r < 0) break
                n += r
            }
            var last = n < chunk
            if (!last) {
                val b = src.read()
                if (b < 0) last = true else src.unread(b)
            }

            val out = ByteArrayOutputStream()
            out.write(connHdr)
            if (first) {
                out.write(nameHdr)
                out.write(lenHdr)
            }
            out.write(if (last) HDR_END_OF_BODY else HDR_BODY)
            out.write(((n + 3) shr 8) and 0xFF)
            out.write((n + 3) and 0xFF)
            out.write(buf, 0, n)
            sendPacket(if (last) OP_PUT_FINAL else OP_PUT, out.toByteArray())

            val resp = readResponse()
            sent += n
            onProgress(sent)
            first = false
            if (last) {
                if (resp.code != RESP_OK) throw PushException(describe(resp.code))
                return
            }
            if (resp.code != RESP_CONTINUE) throw PushException(describe(resp.code))
        }
    }

    /** Ends the session politely (best effort) and closes the link. */
    fun close() {
        try {
            val conn = connectionId?.let { byteArrayOf(HDR_CONN_ID.toByte()) + it } ?: ByteArray(0)
            if (output != null) {
                sendPacket(OP_DISCONNECT, conn)
                Thread.sleep(150)
            }
        } catch (e: Exception) {
            // ignore: closing anyway
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Aborts a running transfer from another thread. */
    fun cancel() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- low level ----------

    private fun sendPacket(op: Int, payload: ByteArray) {
        val o = output ?: throw IOException("Not connected")
        val len = 3 + payload.size
        o.write(op)
        o.write((len shr 8) and 0xFF)
        o.write(len and 0xFF)
        o.write(payload)
        o.flush()
    }

    private fun readResponse(): Resp {
        val head = ByteArray(3)
        readFully(head)
        val code = head[0].toInt() and 0xFF
        val len = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
        val payload = ByteArray((len - 3).coerceAtLeast(0))
        readFully(payload)
        return Resp(code, payload)
    }

    private fun readFully(buf: ByteArray) {
        val ins = input ?: throw IOException("Not connected")
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("The PC closed the connection")
            off += n
        }
    }

    private fun nameHeader(name: String): ByteArray {
        val text = (name + "\u0000").toByteArray(Charsets.UTF_16BE)
        val len = 3 + text.size
        return byteArrayOf(HDR_NAME.toByte(), (len shr 8).toByte(), len.toByte()) + text
    }

    private fun cleanName(name: String): String {
        val c = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (c.isEmpty()) "file" else c
    }

    private fun describe(code: Int): String {
        val why = when (code) {
            0xC0 -> "bad request"
            0xC1 -> "not authorized"
            0xC3 -> "forbidden"
            0xC4 -> "not found"
            0xC6 -> "not acceptable"
            0xCF -> "unsupported file type"
            0xD0 -> "PC internal error"
            0xD3 -> "service unavailable"
            else -> "declined"
        }
        return "The PC did not accept the file: $why (code 0x${Integer.toHexString(code).uppercase()})"
    }
}
