package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server running on port 8090 for LAN Wi-Fi Beaming.
 * Allows the mobile app to test candidate keys, blast IR, tune channels,
 * and save custom remote key overrides.
 */
public final class TvCommandServer {
    private static final String TAG = "TvCommandServer";
    public static final int DEFAULT_PORT = 8090;

    public static final String PREFS_COMPANION = "companion_auth_prefs";
    public static final String PREF_PAIRED_TOKENS = "paired_tokens";
    public static final String PREF_ACTIVE_PIN = "active_pairing_pin";
    public static final String PREF_PIN_EXPIRY = "active_pin_expiry";

    private static volatile TvCommandServer instance;
    private final Context context;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private boolean isRunning = false;

    public interface OnCustomRemoteUpdatedListener {
        void onCustomRemoteUpdated();
    }

    private OnCustomRemoteUpdatedListener listener;

    private TvCommandServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public static TvCommandServer getInstance(Context context) {
        if (instance == null) {
            synchronized (TvCommandServer.class) {
                if (instance == null) {
                    instance = new TvCommandServer(context);
                }
            }
        }
        return instance;
    }

    public void setListener(OnCustomRemoteUpdatedListener listener) {
        this.listener = listener;
    }

    // --- Pairing & Authentication Helpers ---

    public synchronized String getOrGeneratePairingPin() {
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        long expiry = sp.getLong(PREF_PIN_EXPIRY, 0);
        String pin = sp.getString(PREF_ACTIVE_PIN, null);
        if (pin != null && System.currentTimeMillis() < expiry) {
            return pin;
        }
        return generateNewPairingPin();
    }

    public synchronized String generateNewPairingPin() {
        int randomPin = 100000 + new java.util.Random().nextInt(900000);
        String pin = String.valueOf(randomPin);
        long expiry = System.currentTimeMillis() + (15 * 60 * 1000); // 15 mins validity
        context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_ACTIVE_PIN, pin)
                .putLong(PREF_PIN_EXPIRY, expiry)
                .apply();
        Log.i(TAG, "Generated new Companion Pairing PIN: " + pin);
        return pin;
    }

    public synchronized boolean validatePairingPin(String inputPin) {
        if (inputPin == null) return false;
        String clean = inputPin.replaceAll("\\s+", "").trim();
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        String active = sp.getString(PREF_ACTIVE_PIN, null);
        long expiry = sp.getLong(PREF_PIN_EXPIRY, 0);
        return active != null && active.equals(clean) && System.currentTimeMillis() <= expiry;
    }

    public synchronized String registerPairedDevice(String clientName, String clientId) {
        return registerPairedDevice(clientName, clientId, null);
    }

    public synchronized String registerPairedDevice(String clientName, String clientId, byte[] sessionKey) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        java.util.Set<String> tokens = new java.util.HashSet<>(sp.getStringSet(PREF_PAIRED_TOKENS, new java.util.HashSet<>()));
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        tokens.add(token);

        String desc = (clientName != null && !clientName.trim().isEmpty() ? clientName.trim() : "Mobile Companion")
                + " (Paired " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date()) + ")";

        SharedPreferences.Editor ed = sp.edit()
                .putStringSet(PREF_PAIRED_TOKENS, tokens)
                .putString("device_" + token, desc);

        if (sessionKey != null) {
            ed.putString("key_" + token, Base64.encodeToString(sessionKey, Base64.NO_WRAP));
        }

        ed.apply();
        Log.i(TAG, "Registered new paired device: " + desc + " [token=" + token + ", hasKey=" + (sessionKey != null) + "]");
        return token;
    }

    public synchronized byte[] getSessionKey(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        String b64 = sp.getString("key_" + token.trim(), null);
        if (b64 != null) {
            try {
                return Base64.decode(b64, Base64.NO_WRAP);
            } catch (Exception e) {
                Log.e(TAG, "Failed to decode session key for token: " + token, e);
            }
        }
        return null;
    }

    public synchronized boolean isAuthorized(String token) {
        if (token == null || token.trim().isEmpty()) return false;
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        java.util.Set<String> tokens = sp.getStringSet(PREF_PAIRED_TOKENS, java.util.Collections.emptySet());
        return tokens.contains(token.trim());
    }

    public synchronized void unpairDevice(String token) {
        if (token == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        java.util.Set<String> tokens = new java.util.HashSet<>(sp.getStringSet(PREF_PAIRED_TOKENS, new java.util.HashSet<>()));
        tokens.remove(token.trim());
        sp.edit().putStringSet(PREF_PAIRED_TOKENS, tokens).remove("device_" + token.trim()).apply();
    }

    public synchronized void unpairAllDevices() {
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        generateNewPairingPin();
        Log.i(TAG, "Unpaired all companion mobile devices");
    }

    public synchronized int getPairedDeviceCount() {
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        java.util.Set<String> tokens = sp.getStringSet(PREF_PAIRED_TOKENS, java.util.Collections.emptySet());
        return tokens.size();
    }

    private static String extractBearerToken(String authHeader) {
        if (authHeader == null) return null;
        String trimmed = authHeader.trim();
        if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    // --- Server Lifecycle & Dispatch ---

    public synchronized void start(int port) {
        if (isRunning) return;
        isRunning = true;
        // Ensure active PIN is ready
        getOrGeneratePairingPin();
        threadPool.execute(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.i(TAG, "TvCommandServer listening on port " + port);
                while (isRunning && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    threadPool.execute(() -> handleClient(client));
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error", e);
                }
            }
        });
    }

    public synchronized void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String fullUri = parts[1];

            // Read headers
            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String hKey = line.substring(0, colonIdx).trim().toLowerCase(java.util.Locale.ROOT);
                    String hVal = line.substring(colonIdx + 1).trim();
                    headers.put(hKey, hVal);
                    if ("content-length".equals(hKey)) {
                        try { contentLength = Integer.parseInt(hVal); } catch (Exception ignored) {}
                    }
                }
            }

            // Read body if any
            StringBuilder body = new StringBuilder();
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(buf, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                body.append(buf, 0, totalRead);
            }

            // Handle CORS Preflight
            if ("OPTIONS".equals(method)) {
                sendResponse(out, 204, "application/json", "");
                return;
            }

            String path = fullUri;
            String queryString = "";
            int qIdx = fullUri.indexOf('?');
            if (qIdx != -1) {
                path = fullUri.substring(0, qIdx);
                queryString = fullUri.substring(qIdx + 1);
            }
            Map<String, String> queryParams = parseQuery(queryString);

            // Extract auth token
            String authHeader = headers.get("authorization");
            if (authHeader == null) authHeader = headers.get("x-auth-token");
            if (authHeader == null) authHeader = queryParams.get("token");
            String clientToken = extractBearerToken(authHeader);

            // Parse body and handle AES-256-GCM decryption if payload is encrypted
            boolean isEncryptedRequest = false;
            byte[] activeSessionKey = null;
            JSONObject decryptedBodyJson = null;
            JSONObject rawBodyJson = null;

            if (body.length() > 0) {
                try {
                    rawBodyJson = new JSONObject(body.toString());
                    if (rawBodyJson.has("token") && clientToken == null) {
                        clientToken = rawBodyJson.getString("token");
                    }

                    if (rawBodyJson.has("iv") && rawBodyJson.has("data")) {
                        if ("/api/pair".equals(path)) {
                            // Initial pairing handshake encrypted with PBKDF2 key derived from active PIN
                            String activePin = getOrGeneratePairingPin();
                            byte[] pinKey = CryptoUtils.deriveKeyFromPin(activePin);
                            try {
                                String dec = CryptoUtils.decryptAesGcm(pinKey, rawBodyJson.getString("iv"), rawBodyJson.getString("data"));
                                decryptedBodyJson = new JSONObject(dec);
                                isEncryptedRequest = true;
                                activeSessionKey = pinKey;
                            } catch (Exception e) {
                                Log.w(TAG, "PIN key decryption failed - PIN might not match");
                            }
                        } else {
                            activeSessionKey = getSessionKey(clientToken);
                            if (activeSessionKey != null) {
                                String dec = CryptoUtils.decryptAesGcm(activeSessionKey, rawBodyJson.getString("iv"), rawBodyJson.getString("data"));
                                decryptedBodyJson = new JSONObject(dec);
                                isEncryptedRequest = true;

                                // Anti-replay check
                                if (decryptedBodyJson.has("timestamp")) {
                                    long ts = decryptedBodyJson.getLong("timestamp");
                                    if (Math.abs(System.currentTimeMillis() - ts) > 120_000) {
                                        JSONObject err = new JSONObject();
                                        err.put("success", false);
                                        err.put("error", "Replay detected or excessive clock skew (>120s)");
                                        sendResponse(out, 403, "application/json", err.toString());
                                        return;
                                    }
                                }
                            }
                        }
                    } else {
                        decryptedBodyJson = rawBodyJson;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing request body", e);
                }
            }

            JSONObject response = new JSONObject();
            SharedPreferences stbPrefs = context.getSharedPreferences("stb_settings", Context.MODE_PRIVATE);
            SensyIrManager ir = SensyIrManager.getInstance(context);

            if ("/api/status".equals(path) && "GET".equals(method)) {
                response.put("status", "online");
                response.put("tvName", "CleanTV (Mi TV 4A)");
                response.put("dongleConnected", ir.isConnected());
                response.put("providerTitle", stbPrefs.getString("provider_title", "Kerala Vision Digital"));
                response.put("providerId", stbPrefs.getInt("provider_id", 43));
                response.put("remoteId", stbPrefs.getInt("remote_id", 43));
                response.put("bitLength", stbPrefs.getInt("bit_length", 32));
                response.put("hasCustomRemote", stbPrefs.contains("custom_keys_json"));
                response.put("pairingRequired", true);
                response.put("isPaired", isAuthorized(clientToken));
                response.put("hasSessionKey", getSessionKey(clientToken) != null);
                response.put("pairedDevicesCount", getPairedDeviceCount());
                sendResponse(out, 200, "application/json", response.toString());

            } else if ("/api/pair".equals(path) && "POST".equals(method)) {
                String pin = queryParams.get("pin");
                String clientName = queryParams.get("clientName");
                String clientId = queryParams.get("clientId");
                if (decryptedBodyJson != null) {
                    if (decryptedBodyJson.has("pin")) pin = decryptedBodyJson.getString("pin");
                    if (decryptedBodyJson.has("clientName")) clientName = decryptedBodyJson.getString("clientName");
                    if (decryptedBodyJson.has("clientId")) clientId = decryptedBodyJson.getString("clientId");
                } else if (rawBodyJson != null) {
                    if (rawBodyJson.has("pin")) pin = rawBodyJson.getString("pin");
                    if (rawBodyJson.has("clientName")) clientName = rawBodyJson.getString("clientName");
                    if (rawBodyJson.has("clientId")) clientId = rawBodyJson.getString("clientId");
                }

                boolean pinValid = isEncryptedRequest || (pin != null && validatePairingPin(pin));

                if (pinValid) {
                    byte[] newSessionKey = CryptoUtils.generateSessionKey();
                    String newToken = registerPairedDevice(clientName, clientId, newSessionKey);
                    response.put("success", true);
                    response.put("token", newToken);
                    response.put("session_key", Base64.encodeToString(newSessionKey, Base64.NO_WRAP));
                    response.put("tvName", "CleanTV (Mi TV 4A)");
                    response.put("message", "Successfully paired companion mobile app with AES-256-GCM encryption!");

                    if (isEncryptedRequest && activeSessionKey != null) {
                        sendEncryptedResponse(out, activeSessionKey, response);
                    } else {
                        sendResponse(out, 200, "application/json", response.toString());
                    }
                } else {
                    response.put("success", false);
                    response.put("error", "Invalid pairing code. Check the 6-digit code in TV Settings -> Companion App.");
                    sendResponse(out, 401, "application/json", response.toString());
                }

            } else if ("/api/unpair".equals(path) && "POST".equals(method)) {
                String tokenToUnpair = clientToken;
                if (decryptedBodyJson != null && decryptedBodyJson.has("token")) {
                    tokenToUnpair = decryptedBodyJson.getString("token");
                }
                unpairDevice(tokenToUnpair);
                response.put("success", true);
                response.put("message", "Device unpaired successfully");
                if (isEncryptedRequest && activeSessionKey != null) {
                    sendEncryptedResponse(out, activeSessionKey, response);
                } else {
                    sendResponse(out, 200, "application/json", response.toString());
                }

            } else if ("/api/blast".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV. Enter TV pairing PIN in Settings.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String hex = queryParams.get("hex");
                int bits = 32;
                if (decryptedBodyJson != null) {
                    if (decryptedBodyJson.has("hex")) hex = decryptedBodyJson.getString("hex");
                    if (decryptedBodyJson.has("bits")) bits = decryptedBodyJson.getInt("bits");
                    if (decryptedBodyJson.has("bitLength")) bits = decryptedBodyJson.getInt("bitLength");
                }
                if (queryParams.containsKey("bits")) {
                    try { bits = Integer.parseInt(queryParams.get("bits")); } catch (Exception ignored) {}
                }

                if (hex != null && !hex.isEmpty()) {
                    final String codeHex = hex;
                    final int bitLength = bits;
                    ir.transmitNecAsync(codeHex, bitLength, () ->
                            Log.d(TAG, "Blasted IR code: " + codeHex)
                    );
                    response.put("success", true);
                    response.put("blasted", hex);
                    response.put("bitLength", bits);
                    if (isEncryptedRequest && activeSessionKey != null) {
                        sendEncryptedResponse(out, activeSessionKey, response);
                    } else {
                        sendResponse(out, 200, "application/json", response.toString());
                    }
                } else {
                    response.put("success", false);
                    response.put("error", "Missing hex parameter");
                    sendResponse(out, 400, "application/json", response.toString());
                }

            } else if ("/api/select-remote".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                int newRemoteId = 0;
                int newBitLength = 32;
                int newProviderId = 0;
                String newProviderTitle = null;

                if (decryptedBodyJson != null) {
                    if (decryptedBodyJson.has("remoteId")) newRemoteId = decryptedBodyJson.getInt("remoteId");
                    if (decryptedBodyJson.has("bitLength")) newBitLength = decryptedBodyJson.getInt("bitLength");
                    if (decryptedBodyJson.has("providerId")) newProviderId = decryptedBodyJson.getInt("providerId");
                    if (decryptedBodyJson.has("providerTitle")) newProviderTitle = decryptedBodyJson.getString("providerTitle");
                } else if (rawBodyJson != null) {
                    if (rawBodyJson.has("remoteId")) newRemoteId = rawBodyJson.getInt("remoteId");
                    if (rawBodyJson.has("bitLength")) newBitLength = rawBodyJson.getInt("bitLength");
                    if (rawBodyJson.has("providerId")) newProviderId = rawBodyJson.getInt("providerId");
                    if (rawBodyJson.has("providerTitle")) newProviderTitle = rawBodyJson.getString("providerTitle");
                }

                if (newRemoteId > 0) {
                    SharedPreferences.Editor ed = stbPrefs.edit();
                    ed.putInt("remote_id", newRemoteId);
                    ed.putInt("bit_length", newBitLength);
                    if (newProviderId > 0) ed.putInt("provider_id", newProviderId);
                    if (newProviderTitle != null) ed.putString("provider_title", newProviderTitle);
                    ed.apply();

                    MainActivity.reloadStbSettings();
                    TvLogger.i("TvCommandServer", "Selected remote updated: remoteId=" + newRemoteId + ", title=" + newProviderTitle);

                    response.put("success", true);
                    response.put("remoteId", newRemoteId);
                    response.put("providerTitle", newProviderTitle != null ? newProviderTitle : stbPrefs.getString("provider_title", ""));
                } else {
                    response.put("success", false);
                    response.put("error", "Missing remoteId parameter");
                }

                if (isEncryptedRequest && activeSessionKey != null) {
                    sendEncryptedResponse(out, activeSessionKey, response);
                } else {
                    sendResponse(out, 200, "application/json", response.toString());
                }

            } else if ("/api/custom-remote".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String keysJson = null;
                if (decryptedBodyJson != null) {
                    if (decryptedBodyJson.has("keys")) {
                        keysJson = decryptedBodyJson.get("keys").toString();
                    } else {
                        keysJson = decryptedBodyJson.toString();
                    }
                } else if (body.length() > 0) {
                    keysJson = body.toString();
                }

                if (keysJson != null && !keysJson.isEmpty()) {
                    stbPrefs.edit().putString("custom_keys_json", keysJson).apply();
                    if (listener != null) {
                        listener.onCustomRemoteUpdated();
                    }
                    response.put("success", true);
                    response.put("message", "Custom remote saved successfully");
                    if (isEncryptedRequest && activeSessionKey != null) {
                        sendEncryptedResponse(out, activeSessionKey, response);
                    } else {
                        sendResponse(out, 200, "application/json", response.toString());
                    }
                } else {
                    response.put("success", false);
                    response.put("error", "Empty body");
                    sendResponse(out, 400, "application/json", response.toString());
                }

            } else if ("/api/custom-remote".equals(path) && "GET".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String customJson = stbPrefs.getString("custom_keys_json", "{}");
                if (isEncryptedRequest && activeSessionKey != null) {
                    response.put("success", true);
                    response.put("custom_keys", new JSONObject(customJson));
                    sendEncryptedResponse(out, activeSessionKey, response);
                } else {
                    sendResponse(out, 200, "application/json", customJson);
                }

            } else if ("/api/tune".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String lcn = queryParams.get("lcn");
                if (decryptedBodyJson != null && decryptedBodyJson.has("lcn")) {
                    lcn = decryptedBodyJson.getString("lcn");
                }

                if (lcn == null || lcn.isEmpty()) {
                    response.put("success", false);
                    response.put("error", "Missing lcn parameter");
                    sendResponse(out, 400, "application/json", response.toString());
                    return;
                }

                tuneChannelAndSwitchAv(lcn, stbPrefs);
                response.put("success", true);
                response.put("tunedLcn", lcn);
                if (isEncryptedRequest && activeSessionKey != null) {
                    sendEncryptedResponse(out, activeSessionKey, response);
                } else {
                    sendResponse(out, 200, "application/json", response.toString());
                }

            } else if ("/api/settings/backup".equals(path) && ("GET".equals(method) || "POST".equals(method))) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String settingsJson = SettingsBackupHelper.exportSettingsJson(context);
                if (settingsJson != null) {
                    response.put("success", true);
                    response.put("settings", new JSONObject(settingsJson));
                } else {
                    response.put("success", false);
                    response.put("error", "Failed to generate settings backup");
                }

                if (isEncryptedRequest && activeSessionKey != null) {
                    sendEncryptedResponse(out, activeSessionKey, response);
                } else {
                    sendResponse(out, 200, "application/json", response.toString());
                }

            } else if ("/api/settings/restore".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String jsonToRestore = null;
                if (decryptedBodyJson != null) {
                    if (decryptedBodyJson.has("settings")) {
                        jsonToRestore = decryptedBodyJson.get("settings").toString();
                    } else {
                        jsonToRestore = decryptedBodyJson.toString();
                    }
                } else if (body.length() > 0) {
                    jsonToRestore = body.toString();
                }

                boolean ok = SettingsBackupHelper.importSettingsJson(context, jsonToRestore);
                response.put("success", ok);
                response.put("message", ok ? "Settings restored successfully" : "Failed to import settings");

                if (isEncryptedRequest && activeSessionKey != null) {
                    sendEncryptedResponse(out, activeSessionKey, response);
                } else {
                    sendResponse(out, ok ? 200 : 400, "application/json", response.toString());
                }

            } else {
                response.put("error", "Not Found");
                sendResponse(out, 404, "application/json", response.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling client request", e);
        }
    }

    private void sendEncryptedResponse(OutputStream out, byte[] key, JSONObject payload) throws Exception {
        try {
            JSONObject enc = CryptoUtils.encryptAesGcm(key, payload.toString());
            sendResponse(out, 200, "application/json", enc.toString());
        } catch (Exception e) {
            Log.e(TAG, "sendEncryptedResponse error", e);
            sendResponse(out, 500, "application/json", "{\"success\":false,\"error\":\"Encryption failure\"}");
        }
    }

    private void tuneChannelAndSwitchAv(String lcn, SharedPreferences stbPrefs) {
        int remoteId = stbPrefs.getInt("remote_id", 43);
        int bitLength = stbPrefs.getInt("bit_length", 32);
        LcnDatabaseHelper db = LcnDatabaseHelper.getInstance(context);
        SensyIrManager ir = SensyIrManager.getInstance(context);

        new Thread(() -> {
            Map<String, String> keys = db.getKeyCodes(remoteId);
            // Apply custom overrides if any
            String customJson = stbPrefs.getString("custom_keys_json", null);
            if (customJson != null) {
                try {
                    JSONObject obj = new JSONObject(customJson);
                    if (obj.has("keys")) {
                        JSONObject kObj = obj.getJSONObject("keys");
                        java.util.Iterator<String> it = kObj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            keys.put(k.toUpperCase(), kObj.getString(k));
                        }
                    }
                } catch (Exception ignored) {}
            }

            ir.tuneChannelAsync(lcn, keys, bitLength, () -> {
                try {
                    String avInputId = stbPrefs.getString("av_input_id", "com.xiaomi.mitv.tvinput/.TvInputService/HW3");
                    Uri uri = TvContract.buildChannelUriForPassthroughInput(avInputId);
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Could not open AV1 passthrough", e);
                }
            });
        }).start();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            } else {
                params.put(pair, "");
            }
        }
        return params;
    }

    private static void sendResponse(OutputStream out, int status, String contentType, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: " + contentType + "; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n"
                + "Access-Control-Allow-Headers: Content-Type, Accept, Authorization, X-Auth-Token\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        if (bytes.length > 0) {
            out.write(bytes);
        }
        out.flush();
    }
}
