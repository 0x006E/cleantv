package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Unified settings export and import utility for CleanTV Launcher and Companion Mobile App.
 * Handles backup & restore of STB remote configurations, channel favorites, custom LCNs, and AV input preferences.
 */
public final class SettingsBackupHelper {
    private static final String TAG = "SettingsBackupHelper";
    public static final String BACKUP_FILENAME = "cleantv_settings_backup.json";

    private SettingsBackupHelper() {}

    /**
     * Exports all user preferences to a JSON string.
     */
    public static String exportSettingsJson(Context context) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("app", "CleanTV");
            root.put("exported_at", System.currentTimeMillis());

            // 1. STB Settings
            SharedPreferences stbPrefs = context.getSharedPreferences("stb_settings", Context.MODE_PRIVATE);
            JSONObject stbJson = new JSONObject();
            for (Map.Entry<String, ?> entry : stbPrefs.getAll().entrySet()) {
                if (entry.getValue() != null) {
                    stbJson.put(entry.getKey(), entry.getValue());
                }
            }
            root.put("stb_settings", stbJson);

            // 2. User Channel Preferences (Favorites, Custom LCNs)
            SharedPreferences channelPrefs = context.getSharedPreferences("user_channel_prefs", Context.MODE_PRIVATE);
            JSONObject channelJson = new JSONObject();
            for (Map.Entry<String, ?> entry : channelPrefs.getAll().entrySet()) {
                if (entry.getValue() != null) {
                    channelJson.put(entry.getKey(), entry.getValue());
                }
            }
            root.put("channel_prefs", channelJson);

            return root.toString(2);
        } catch (Exception e) {
            Log.e(TAG, "Failed to export settings JSON", e);
            return null;
        }
    }

    /**
     * Imports and applies settings from a JSON string.
     */
    public static boolean importSettingsJson(Context context, String jsonStr) {
        try {
            if (jsonStr == null || jsonStr.trim().isEmpty()) return false;
            JSONObject root = new JSONObject(jsonStr);

            // 1. Restore STB Settings
            if (root.has("stb_settings")) {
                JSONObject stbJson = root.getJSONObject("stb_settings");
                SharedPreferences.Editor stbEd = context.getSharedPreferences("stb_settings", Context.MODE_PRIVATE).edit();
                for (java.util.Iterator<String> it = stbJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    Object val = stbJson.get(key);
                    if (val instanceof Boolean) stbEd.putBoolean(key, (Boolean) val);
                    else if (val instanceof Integer) stbEd.putInt(key, (Integer) val);
                    else if (val instanceof Long) stbEd.putLong(key, (Long) val);
                    else if (val instanceof Double) stbEd.putFloat(key, ((Double) val).floatValue());
                    else if (val instanceof String) stbEd.putString(key, (String) val);
                }
                stbEd.apply();
            }

            // 2. Restore Channel Preferences
            if (root.has("channel_prefs")) {
                JSONObject channelJson = root.getJSONObject("channel_prefs");
                SharedPreferences.Editor chEd = context.getSharedPreferences("user_channel_prefs", Context.MODE_PRIVATE).edit();
                for (java.util.Iterator<String> it = channelJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    Object val = channelJson.get(key);
                    if (val instanceof Boolean) chEd.putBoolean(key, (Boolean) val);
                    else if (val instanceof Integer) chEd.putInt(key, (Integer) val);
                    else if (val instanceof Long) chEd.putLong(key, (Long) val);
                    else if (val instanceof String) chEd.putString(key, (String) val);
                }
                chEd.apply();
            }

            Log.i(TAG, "Settings imported and applied successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to import settings JSON", e);
            return false;
        }
    }

    public static File getBackupFile(Context context) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
            return new File(downloadDir, BACKUP_FILENAME);
        }
        return new File(context.getExternalFilesDir(null), BACKUP_FILENAME);
    }

    /**
     * Exports settings to standard backup file on storage.
     */
    public static File exportToFile(Context context) {
        String json = exportSettingsJson(context);
        if (json == null) return null;
        try {
            File file = getBackupFile(context);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Saved settings backup to: " + file.getAbsolutePath());
            return file;
        } catch (Exception e) {
            Log.e(TAG, "Error writing settings backup file", e);
            return null;
        }
    }

    /**
     * Imports settings from standard backup file on storage.
     */
    public static boolean importFromFile(Context context) {
        try {
            File file = getBackupFile(context);
            if (!file.exists()) {
                Log.w(TAG, "Backup file not found at " + file.getAbsolutePath());
                return false;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return importSettingsJson(context, sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error reading settings backup file", e);
            return false;
        }
    }
}

