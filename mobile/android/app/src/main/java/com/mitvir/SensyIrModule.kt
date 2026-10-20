package com.mitvir

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.hardware.usb.*
import android.os.Build
import android.util.Base64
import com.facebook.react.bridge.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class SensyIrModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "SensyIrModule"
        const val VENDOR_ID = 0x2717
        const val PRODUCT_ID = 0x003d
        const val ACTION_USB_PERMISSION = "com.mitvir.USB_PERMISSION"
        const val DB_NAME = "lcndb.db"

        // Dongle Opcodes
        const val OP_GET_CONFIG = 1
        const val OP_SET_CONFIG = 2
        const val OP_START_BUFFER = 3
        const val OP_END_BUFFER = 4
        const val OP_RESET = 120
        const val OP_GET_VERSION = 127

        val KEY_NAMES = mapOf(
            0 to "0", 1 to "1", 2 to "2", 3 to "3", 4 to "4",
            5 to "5", 6 to "6", 7 to "7", 8 to "8", 9 to "9",
            10 to "VOL UP", 11 to "VOL DOWN", 12 to "VOL MUTE",
            13 to "CH UP", 14 to "CH DOWN", 15 to "POWER",
            16 to "SELECT", 17 to "BACK", 18 to "EXIT", 19 to "MENU",
            20 to "INFO", 21 to "UP", 22 to "DOWN", 23 to "LEFT",
            24 to "RIGHT", 25 to "OK", 26 to "RED", 27 to "GREEN",
            28 to "YELLOW", 29 to "BLUE", 30 to "PLAY", 31 to "REWIND",
            32 to "FAST FORWARD", 33 to "RECORD", 34 to "PAUSE", 35 to "STOP",
            36 to "SOURCE", 37 to "OPTIONS", 38 to "LANGUAGE", 39 to "GUIDE",
            40 to "PREVIOUS", 41 to "NEXT"
        )
    }

    private val usbManager: UsbManager = reactContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epCmd: UsbEndpoint? = null
    private var epResp: UsbEndpoint? = null
    private var epData: UsbEndpoint? = null
    private var database: SQLiteDatabase? = null

    override fun getName(): String = NAME

    init {
        initDatabase()
    }

    private fun initDatabase() {
        try {
            val dbFile = reactContext.getDatabasePath(DB_NAME)
            var needsCopy = !dbFile.exists()
            if (dbFile.exists()) {
                try {
                    val testDb = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                    val c = testDb.rawQuery("SELECT data_version FROM metadata LIMIT 1", null)
                    if (c.moveToFirst() && c.getInt(0) < 435) {
                        needsCopy = true
                    }
                    c.close()
                    testDb.close()
                } catch (e: Exception) {
                    needsCopy = true
                }
            }
            if (needsCopy) {
                database?.close()
                database = null
                dbFile.parentFile?.mkdirs()
                reactContext.assets.open(DB_NAME).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            database = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getDb(): SQLiteDatabase {
        if (database == null || !database!!.isOpen) {
            initDatabase()
        }
        return database ?: throw IllegalStateException("Database not available")
    }

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

    @ReactMethod
    fun searchProviders(query: String, limit: Int, promise: Promise) {
        Thread {
            try {
                val db = getDb()
                val search = "%$query%"
                val cursor = db.rawQuery("""
                    SELECT p.id, p.title, p.slug, p.type, p.group_id, p.brand_id,
                           b.title as brand_title, g.title as group_title
                    FROM providers p
                    LEFT JOIN provider_brands b ON p.brand_id = b.id
                    LEFT JOIN provider_groups g ON p.group_id = g.id
                    WHERE p.title LIKE ? OR b.title LIKE ? OR g.title LIKE ?
                    ORDER BY p.title ASC
                    LIMIT ?
                """.trimIndent(), arrayOf(search, search, search, limit.toString()))

                val array = Arguments.createArray()
                while (cursor.moveToNext()) {
                    val m = Arguments.createMap()
                    m.putInt("id", cursor.getInt(0))
                    m.putString("title", cursor.getString(1))
                    m.putString("slug", cursor.getString(2))
                    m.putString("type", cursor.getString(3))
                    m.putInt("groupId", cursor.getInt(4))
                    m.putInt("brandId", cursor.getInt(5))
                    m.putString("brandTitle", cursor.getString(6) ?: "")
                    m.putString("groupTitle", cursor.getString(7) ?: "")
                    array.pushMap(m)
                }
                cursor.close()
                promise.resolve(array)
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun getRemotesForProvider(providerId: Int, promise: Promise) {
        Thread {
            try {
                val db = getDb()
                // Check if provider has specific group_id
                var groupId = 0
                val pCursor = db.rawQuery("SELECT group_id FROM providers WHERE id = ?", arrayOf(providerId.toString()))
                if (pCursor.moveToFirst()) {
                    groupId = pCursor.getInt(0)
                }
                pCursor.close()

                val query = if (groupId > 0) {
                    """
                    SELECT DISTINCT r.id, r.protocol, r.length, r.gap
                    FROM provider_group_remotes pgr
                    JOIN remotes r ON pgr.remote_id = r.id
                    WHERE pgr.group_id = ?
                    ORDER BY r.id ASC
                    """.trimIndent()
                } else {
                    """
                    SELECT DISTINCT r.id, r.protocol, r.length, r.gap
                    FROM provider_remotes pr
                    JOIN remotes r ON pr.remote_id = r.id
                    WHERE pr.provider_id = ?
                    ORDER BY r.id ASC
                    """.trimIndent()
                }

                val param = if (groupId > 0) groupId.toString() else providerId.toString()
                val cursor = db.rawQuery(query, arrayOf(param))

                val array = Arguments.createArray()
                while (cursor.moveToNext()) {
                    val m = Arguments.createMap()
                    val rId = cursor.getInt(0)
                    m.putInt("id", rId)
                    m.putInt("protocol", cursor.getInt(1))
                    m.putInt("bitLength", cursor.getInt(2))
                    m.putInt("gap", cursor.getInt(3))

                    // Also fetch power key for quick testing preview
                    val kCursor = db.rawQuery("SELECT code FROM key_codes WHERE remote_id = ? AND \"key\" = 15 LIMIT 1", arrayOf(rId.toString()))
                    var powerHex = ""
                    if (kCursor.moveToFirst()) {
                        powerHex = kCursor.getString(0) ?: ""
                    }
                    kCursor.close()
                    m.putString("powerCode", powerHex)

                    array.pushMap(m)
                }
                cursor.close()
                promise.resolve(array)
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun getKeyCodes(remoteId: Int, promise: Promise) {
        Thread {
            try {
                val db = getDb()
                val cursor = db.rawQuery("""
                    SELECT "key", code FROM key_codes WHERE remote_id = ? ORDER BY "key" ASC
                """.trimIndent(), arrayOf(remoteId.toString()))

                val map = Arguments.createMap()
                val rawMap = mutableMapOf<String, String>()
                while (cursor.moveToNext()) {
                    val keyId = cursor.getInt(0)
                    val code = cursor.getString(1)
                    val keyName = KEY_NAMES[keyId] ?: "KEY_$keyId"
                    rawMap[keyName] = code
                    if (keyName.contains(" ")) {
                        rawMap[keyName.replace(" ", "_")] = code
                    }
                }
                cursor.close()

                // Sanity fix for corrupted database entries where CH UP was assigned DOWN code (e.g. Remote 184):
                val upCode = rawMap["UP"]
                val downCode = rawMap["DOWN"]
                val chUpCode = rawMap["CH UP"]
                val chDownCode = rawMap["CH DOWN"]

                if (upCode != null && (chUpCode == null || (downCode != null && chUpCode.equals(downCode, ignoreCase = true)))) {
                    rawMap["CH UP"] = upCode
                    rawMap["CH_UP"] = upCode
                    rawMap["CHANNEL_UP"] = upCode
                }
                if (downCode != null && (chDownCode == null || chDownCode.isEmpty())) {
                    rawMap["CH DOWN"] = downCode
                    rawMap["CH_DOWN"] = downCode
                    rawMap["CHANNEL_DOWN"] = downCode
                }

                for ((k, v) in rawMap) {
                    map.putString(k, v)
                }
                promise.resolve(map)
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message, e)
            }
        }.start()
    }

    private fun sendCommand(cmd: ByteArray): ByteArray {
        val conn = connection ?: throw IllegalStateException("Dongle not open")
        val outEp = epCmd ?: throw IllegalStateException("Command endpoint null")
        val inEp = epResp ?: throw IllegalStateException("Response endpoint null")

        conn.bulkTransfer(outEp, cmd, cmd.size, 1000)
        val resp = ByteArray(64)
        conn.bulkTransfer(inEp, resp, resp.size, 1000)
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
        // Sensy hardware carrier cycle counts (at 38kHz):
        // Header: 342 cycles mark (~9000µs), 171 cycles space (~4500µs)
        list.add(342)
        list.add(171)

        for (bit in bitLength - 1 downTo 0) {
            list.add(21) // Mark: 21 cycles (~553µs)
            val isSet = (numericVal and (1L shl bit)) != 0L
            list.add(if (isSet) 60 else 21) // Space: 60 cycles (~1579µs) for '1', 21 cycles (~553µs) for '0'
        }

        list.add(21) // Stop bit mark: 21 cycles

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
    fun getSavedTvIp(promise: Promise) {
        val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
        promise.resolve(sp.getString("tv_ip", "100.97.35.51"))
    }

    @ReactMethod
    fun saveTvIp(ip: String, promise: Promise) {
        val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
        sp.edit().putString("tv_ip", ip).apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun getAuthToken(promise: Promise) {
        val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
        promise.resolve(sp.getString("auth_token", null))
    }

    @ReactMethod
    fun saveAuthToken(token: String, promise: Promise) {
        val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
        sp.edit().putString("auth_token", token).apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun clearAuthToken(promise: Promise) {
        val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
        sp.edit().remove("auth_token").remove("active_session_key").apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun derivePinKey(pin: String, promise: Promise) {
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val salt = "CleanTV-Companion-Salt-v1".toByteArray(StandardCharsets.UTF_8)
            val spec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
            val tmp = factory.generateSecret(spec)
            val b64 = Base64.encodeToString(tmp.encoded, Base64.NO_WRAP)
            promise.resolve(b64)
        } catch (e: Exception) {
            promise.reject("CRYPTO_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun encryptPayload(keyB64: String, plaintextJson: String, promise: Promise) {
        try {
            val keyBytes = Base64.decode(keyB64, Base64.NO_WRAP)
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertext = cipher.doFinal(plaintextJson.toByteArray(StandardCharsets.UTF_8))
            val map = Arguments.createMap()
            map.putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            map.putString("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("CRYPTO_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun decryptPayload(keyB64: String, ivB64: String, cipherB64: String, promise: Promise) {
        try {
            val keyBytes = Base64.decode(keyB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(cipherB64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val decrypted = cipher.doFinal(ciphertext)
            promise.resolve(String(decrypted, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            promise.reject("CRYPTO_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun saveSessionKey(token: String, keyB64: String, promise: Promise) {
        val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
        sp.edit().putString("session_key_$token", keyB64).putString("active_session_key", keyB64).apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun getSessionKey(token: String?, promise: Promise) {
        val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
        val k = if (token != null) sp.getString("session_key_$token", null) ?: sp.getString("active_session_key", null)
                else sp.getString("active_session_key", null)
        promise.resolve(k)
    }

    @ReactMethod
    fun exportSettings(promise: Promise) {
        try {
            val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE)
            val root = JSONObject()
            root.put("version", 1)
            root.put("app", "CleanTV Companion")
            root.put("exported_at", System.currentTimeMillis())

            val settings = JSONObject()
            for ((key, value) in sp.all) {
                if (value != null) {
                    settings.put(key, value)
                }
            }
            root.put("companion_settings", settings)
            promise.resolve(root.toString(2))
        } catch (e: Exception) {
            promise.reject("EXPORT_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun importSettings(jsonStr: String, promise: Promise) {
        try {
            val root = JSONObject(jsonStr)
            val sp = reactContext.getSharedPreferences("tv_beaming", Context.MODE_PRIVATE).edit()

            if (root.has("companion_settings")) {
                val cs = root.getJSONObject("companion_settings")
                val it = cs.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = cs.get(k)
                    if (v is Boolean) sp.putBoolean(k, v)
                    else if (v is Int) sp.putInt(k, v)
                    else if (v is Long) sp.putLong(k, v)
                    else if (v is String) sp.putString(k, v)
                }
            } else if (root.has("stb_settings")) {
                sp.putString("tv_backup_cache", jsonStr)
            }
            sp.apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("IMPORT_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun getDatabaseVersion(promise: Promise) {
        try {
            val db = getDb()
            val c = db.rawQuery("SELECT data_version FROM metadata LIMIT 1", null)
            var v = 0
            if (c.moveToFirst()) v = c.getInt(0)
            c.close()
            promise.resolve(v)
        } catch (e: Exception) {
            promise.resolve(0)
        }
    }

    @ReactMethod
    fun syncDatabase(customUrl: String?, promise: Promise) {
        Thread {
            try {
                val cdnBase = "http://sensara-static-files.sensara.tv/lcndata"
                var targetUrl = customUrl?.trim()
                var latestVer = 435

                if (targetUrl.isNullOrEmpty()) {
                    var curVer = 0
                    try {
                        val db = getDb()
                        val c = db.rawQuery("SELECT data_version FROM metadata LIMIT 1", null)
                        if (c.moveToFirst()) curVer = c.getInt(0)
                        c.close()
                    } catch (ignored: Exception) {}

                    var low = curVer
                    var high = 550
                    latestVer = curVer
                    while (low <= high) {
                        val mid = (low + high) / 2
                        try {
                            val conn = java.net.URL("$cdnBase/lcndb-v$mid.db").openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "HEAD"
                            conn.connectTimeout = 4000
                            conn.readTimeout = 4000
                            if (conn.responseCode == 200) {
                                latestVer = mid
                                low = mid + 1
                            } else {
                                high = mid - 1
                            }
                            conn.disconnect()
                        } catch (e: Exception) {
                            high = mid - 1
                        }
                    }
                    targetUrl = "$cdnBase/lcndb-v$latestVer.db"
                }

                val dbFile = reactContext.getDatabasePath(DB_NAME)
                val tmpFile = java.io.File(dbFile.path + ".tmp")
                val conn = java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                if (conn.responseCode != 200) {
                    throw IllegalStateException("HTTP ${conn.responseCode} from $targetUrl")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                conn.disconnect()

                var ver = latestVer
                try {
                    val testDb = SQLiteDatabase.openDatabase(tmpFile.path, null, SQLiteDatabase.OPEN_READONLY)
                    val c = testDb.rawQuery("SELECT data_version FROM metadata LIMIT 1", null)
                    if (c.moveToFirst()) ver = c.getInt(0)
                    c.close()
                    testDb.close()
                } catch (ignored: Exception) {}

                synchronized(this) {
                    database?.close()
                    database = null
                    if (dbFile.exists()) dbFile.delete()
                    tmpFile.renameTo(dbFile)
                    initDatabase()
                }

                val res = Arguments.createMap()
                res.putBoolean("success", true)
                res.putInt("version", ver)
                res.putString("message", "Updated database to v$ver")
                promise.resolve(res)
            } catch (e: Exception) {
                promise.reject("SYNC_FAILED", e.message, e)
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
