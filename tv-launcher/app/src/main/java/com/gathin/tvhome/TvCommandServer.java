package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
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
        SharedPreferences sp = context.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE);
        java.util.Set<String> tokens = new java.util.HashSet<>(sp.getStringSet(PREF_PAIRED_TOKENS, new java.util.HashSet<>()));
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        tokens.add(token);

        String desc = (clientName != null && !clientName.trim().isEmpty() ? clientName.trim() : "Mobile Companion")
                + " (Paired " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date()) + ")";

        sp.edit()
                .putStringSet(PREF_PAIRED_TOKENS, tokens)
                .putString("device_" + token, desc)
                .apply();
        Log.i(TAG, "Registered new paired device: " + desc + " [token=" + token + "]");
        return token;
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
                response.put("pairedDevicesCount", getPairedDeviceCount());
                sendResponse(out, 200, "application/json", response.toString());

            } else if ("/api/pair".equals(path) && "POST".equals(method)) {
                String pin = queryParams.get("pin");
                String clientName = queryParams.get("clientName");
                String clientId = queryParams.get("clientId");
                if (body.length() > 0) {
                    try {
                        JSONObject bJson = new JSONObject(body.toString());
                        if (bJson.has("pin")) pin = bJson.getString("pin");
                        if (bJson.has("clientName")) clientName = bJson.getString("clientName");
                        if (bJson.has("clientId")) clientId = bJson.getString("clientId");
                    } catch (Exception ignored) {}
                }

                if (pin != null && validatePairingPin(pin)) {
                    String newToken = registerPairedDevice(clientName, clientId);
                    response.put("success", true);
                    response.put("token", newToken);
                    response.put("tvName", "CleanTV (Mi TV 4A)");
                    response.put("message", "Successfully paired companion mobile app!");
                    sendResponse(out, 200, "application/json", response.toString());
                } else {
                    response.put("success", false);
                    response.put("error", "Invalid pairing code. Check the 6-digit code in TV Settings -> Companion App.");
                    sendResponse(out, 401, "application/json", response.toString());
                }

            } else if ("/api/unpair".equals(path) && "POST".equals(method)) {
                String tokenToUnpair = clientToken;
                if (body.length() > 0) {
                    try {
                        JSONObject bJson = new JSONObject(body.toString());
                        if (bJson.has("token")) tokenToUnpair = bJson.getString("token");
                    } catch (Exception ignored) {}
                }
                unpairDevice(tokenToUnpair);
                response.put("success", true);
                response.put("message", "Device unpaired successfully");
                sendResponse(out, 200, "application/json", response.toString());

            } else if ("/api/blast".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV. Enter TV pairing PIN in Settings.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String hex = queryParams.get("hex");
                int bits = 32;
                if (body.length() > 0) {
                    try {
                        JSONObject bodyJson = new JSONObject(body.toString());
                        if (bodyJson.has("hex")) hex = bodyJson.getString("hex");
                        if (bodyJson.has("bits")) bits = bodyJson.getInt("bits");
                        if (bodyJson.has("bitLength")) bits = bodyJson.getInt("bitLength");
                    } catch (Exception ignored) {}
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
                    sendResponse(out, 200, "application/json", response.toString());
                } else {
                    response.put("success", false);
                    response.put("error", "Missing hex parameter");
                    sendResponse(out, 400, "application/json", response.toString());
                }

            } else if ("/api/custom-remote".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                if (body.length() > 0) {
                    stbPrefs.edit().putString("custom_keys_json", body.toString()).apply();
                    if (listener != null) {
                        listener.onCustomRemoteUpdated();
                    }
                    response.put("success", true);
                    response.put("message", "Custom remote saved successfully");
                    sendResponse(out, 200, "application/json", response.toString());
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
                sendResponse(out, 200, "application/json", customJson);

            } else if ("/api/tune".equals(path) && "POST".equals(method)) {
                if (!isAuthorized(clientToken)) {
                    response.put("success", false);
                    response.put("error", "Unauthorized. Device not paired with TV.");
                    sendResponse(out, 401, "application/json", response.toString());
                    return;
                }

                String lcn = queryParams.get("lcn");
                if (body.length() > 0) {
                    try {
                        JSONObject bJson = new JSONObject(body.toString());
                        if (bJson.has("lcn")) lcn = bJson.getString("lcn");
                    } catch (Exception ignored) {}
                }

                if (lcn != null && !lcn.isEmpty()) {
                    tuneChannelAndSwitchAv(lcn, stbPrefs);
                    response.put("success", true);
                    response.put("tuned", lcn);
                    sendResponse(out, 200, "application/json", response.toString());
                } else {
                    response.put("success", false);
                    response.put("error", "Missing lcn parameter");
                    sendResponse(out, 400, "application/json", response.toString());
                }

            } else {
                response.put("error", "Not Found");
                sendResponse(out, 404, "application/json", response.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling client request", e);
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
