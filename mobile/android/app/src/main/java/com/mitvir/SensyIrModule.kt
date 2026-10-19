package com.mitvir

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import com.facebook.react.bridge.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SensyIrModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "SensyIrModule"
        const val VENDOR_ID = 0x2717
        const val PRODUCT_ID = 0x003d
        const val ACTION_USB_PERMISSION = "com.mitvir.USB_PERMISSION"

        // Dongle Opcodes
        const val OP_GET_CONFIG = 1
        const val OP_SET_CONFIG = 2
        const val OP_START_BUFFER = 3
        const val OP_END_BUFFER = 4
        const val OP_RESET = 120
        const val OP_GET_VERSION = 127
    }

    private val usbManager: UsbManager = reactContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epCmd: UsbEndpoint? = null
    private var epResp: UsbEndpoint? = null
    private var epData: UsbEndpoint? = null

    override fun getName(): String = NAME

    private fun findDongle(): UsbDevice? {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID) {
                return device
            }
        }
        return null
    }

    private fun openDongle(): Boolean {
        val device = findDongle() ?: return false
        usbDevice = device

        if (!usbManager.hasPermission(device)) {
            return false
        }

        if (connection != null) {
            return true
        }

        val conn = usbManager.openDevice(device) ?: return false
        val intf = device.getInterface(0)
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            return false
        }

        connection = conn
        usbInterface = intf

        // Map endpoints
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            when (ep.endpointNumber) {
                2 -> if (ep.direction == UsbConstants.USB_DIR_OUT) epCmd = ep
                1 -> if (ep.direction == UsbConstants.USB_DIR_IN) epResp = ep
                4 -> if (ep.direction == UsbConstants.USB_DIR_OUT) epData = ep
            }
        }

        return epCmd != null && epResp != null && epData != null
    }

    private fun closeDongle() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        usbInterface = null
        epCmd = null
        epResp = null
        epData = null
    }

    @ReactMethod
    fun getStatus(promise: Promise) {
        val device = findDongle()
        val map = Arguments.createMap()
        if (device == null) {
            map.putBoolean("connected", false)
            map.putBoolean("hasPermission", false)
            map.putString("message", "Sensy IR dongle not attached")
        } else {
            val hasPerm = usbManager.hasPermission(device)
            map.putBoolean("connected", true)
            map.putBoolean("hasPermission", hasPerm)
            val info = Arguments.createMap()
            info.putInt("vendorId", device.vendorId)
            info.putInt("productId", device.productId)
            info.putString("deviceName", device.deviceName)
            map.putMap("deviceInfo", info)
            map.putString("message", if (hasPerm) "Dongle connected and ready" else "Permission required")
        }
        promise.resolve(map)
    }

    @ReactMethod
    fun requestPermission(promise: Promise) {
        val device = findDongle()
        if (device == null) {
            promise.reject("DEVICE_NOT_FOUND", "Sensy IR Dongle not connected")
            return
        }

        if (usbManager.hasPermission(device)) {
            openDongle()
            promise.resolve(true)
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(
            reactContext, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    reactContext.unregisterReceiver(this)
                    if (granted) {
                        openDongle()
                        promise.resolve(true)
                    } else {
                        promise.resolve(false)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reactContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            reactContext.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    private fun sendCommand(cmd: ByteArray): ByteArray {
        val conn = connection ?: throw IllegalStateException("Dongle not open")
        val outEp = epCmd ?: throw IllegalStateException("Command endpoint null")
        val inEp = epResp ?: throw IllegalStateException("Response endpoint null")

        conn.bulkTransfer(outEp, cmd, cmd.size, 1000)
        val resp = ByteArray(64)
        val read = conn.bulkTransfer(inEp, resp, resp.size, 1000)
        return resp
    }

    private fun getState(): Int {
        val resp = sendCommand(byteArrayOf(OP_GET_CONFIG.toByte()))
        return resp[3].toInt() and 0xFF
    }

    private fun waitForState(target: Int, timeoutMs: Long = 5000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (getState() == target) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun transmitRaw(frequency: Int, pattern: IntArray): Boolean {
        if (!openDongle()) {
            throw IllegalStateException("Unable to open USB IR dongle")
        }

        val byteLength = pattern.size * 4

        // 1. Reset
        sendCommand(byteArrayOf(OP_RESET.toByte()))
        waitForState(0, 1000)

        // 2. Set Config
        val cfgBuf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        cfgBuf.put(OP_SET_CONFIG.toByte())
        cfgBuf.putShort(frequency.toShort())
        cfgBuf.putShort(byteLength.toShort())
        sendCommand(cfgBuf.array())
        waitForState(1, 1000)

        // 3. Start Buffering
        sendCommand(byteArrayOf(OP_START_BUFFER.toByte()))
        waitForState(2, 1000)

        // 4. Stream data to bulk endpoint 4 in 64-byte chunks
        val dataBuf = ByteBuffer.allocate(byteLength).order(ByteOrder.LITTLE_ENDIAN)
        for (interval in pattern) {
            dataBuf.putInt(interval)
        }
        val rawBytes = dataBuf.array()

        val conn = connection!!
        val outData = epData!!
        var offset = 0
        while (offset < rawBytes.size) {
            val chunkLen = Math.min(64, rawBytes.size - offset)
            conn.bulkTransfer(outData, rawBytes, offset, chunkLen, 1000)
            offset += chunkLen
        }

        // 5. End Buffering / Emitting
        sendCommand(byteArrayOf(OP_END_BUFFER.toByte()))

        // 6. Wait for return to Idle
        return waitForState(0, 10000)
    }

    private fun encodeNec(codeStr: String, bitLength: Int): IntArray {
        val clean = codeStr.trim()
        val numericVal = if (clean.startsWith("0x", ignoreCase = true)) {
            java.lang.Long.parseUnsignedLong(clean.substring(2), 16)
        } else {
            java.lang.Long.parseUnsignedLong(clean, 10)
        }

        val list = mutableListOf<Int>()
        // Leader: 9000µs mark, 4500µs space
        list.add(9000)
        list.add(4500)

        // MSB first
        for (bit in bitLength - 1 downTo 0) {
            list.add(560)
            val isSet = (numericVal and (1L shl bit)) != 0L
            list.add(if (isSet) 1690 else 560)
        }

        // Stop bit + lead out gap
        list.add(560)
        list.add(40000)

        return list.toIntArray()
    }

    @ReactMethod
    fun transmitNec(hexCode: String, bitLength: Int, promise: Promise) {
        Thread {
            try {
                val pattern = encodeNec(hexCode, bitLength)
                val success = transmitRaw(38000, pattern)
                promise.resolve(success)
            } catch (e: Exception) {
                promise.reject("TRANSMIT_FAILED", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun resetDevice(promise: Promise) {
        Thread {
            try {
                if (openDongle()) {
                    sendCommand(byteArrayOf(OP_RESET.toByte()))
                    val ok = waitForState(0, 1000)
                    promise.resolve(ok)
                } else {
                    promise.resolve(false)
                }
            } catch (e: Exception) {
                promise.reject("RESET_FAILED", e.message, e)
            }
        }.start()
    }
}
