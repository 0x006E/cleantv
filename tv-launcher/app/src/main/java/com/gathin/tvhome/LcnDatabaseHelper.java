package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native SQLite helper managing universal LCN database (3,755 providers, channels, and IR keymaps).
 */
public class LcnDatabaseHelper {

    private static final String TAG = "LcnDbHelper";
    private static final String DB_NAME = "lcndb.db";
    private static LcnDatabaseHelper sInstance;

    private final Context context;
    private SQLiteDatabase database;

    public static synchronized LcnDatabaseHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new LcnDatabaseHelper(context.getApplicationContext());
        }
        return sInstance;
    }

    private LcnDatabaseHelper(Context context) {
        this.context = context;
        ensureDatabaseCopied();
    }

    public static final int BUNDLED_DB_VERSION = 435;

    private synchronized void ensureDatabaseCopied() {
        try {
            File dbFile = context.getDatabasePath(DB_NAME);
            boolean needsCopy = !dbFile.exists();

            if (dbFile.exists()) {
                try (SQLiteDatabase existing = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                    try (Cursor c = existing.rawQuery("SELECT data_version FROM metadata LIMIT 1", null)) {
                        if (c.moveToFirst() && c.getInt(0) < BUNDLED_DB_VERSION) {
                            Log.i(TAG, "Upgrading on-disk DB (v" + c.getInt(0) + ") to bundled v" + BUNDLED_DB_VERSION);
                            needsCopy = true;
                        }
                    }
                } catch (Exception e) {
                    needsCopy = true;
                }
            }

            if (needsCopy) {
                if (database != null && database.isOpen()) {
                    database.close();
                    database = null;
                }
                File dir = dbFile.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                Log.i(TAG, "Extracting " + DB_NAME + " (v" + BUNDLED_DB_VERSION + ") from assets to " + dbFile.getAbsolutePath());
                InputStream assetStream;
                try {
                    assetStream = new java.util.zip.GZIPInputStream(context.getAssets().open(DB_NAME + ".gz"));
                } catch (Exception notGzip) {
                    assetStream = context.getAssets().open(DB_NAME);
                }
                try (InputStream is = assetStream;
                     OutputStream os = new FileOutputStream(dbFile)) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                    os.flush();
                }
                Log.i(TAG, "Database copy complete: " + dbFile.length() + " bytes");
            }
            if (database == null || !database.isOpen()) {
                database = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy/open " + DB_NAME, e);
        }
    }

    private synchronized SQLiteDatabase getDb() {
        if (database == null || !database.isOpen()) {
            ensureDatabaseCopied();
        }
        return database;
    }

    public static final String SENSARA_CDN_BASE = "http://sensara-static-files.sensara.tv/lcndata";

    public interface SyncListener {
        void onProgress(String status);
        void onSuccess(int newVersion, String message);
        void onError(String error);
    }

    public int getDatabaseVersion() {
        SQLiteDatabase db = getDb();
        if (db == null) return 0;
        try (Cursor c = db.rawQuery("SELECT data_version FROM metadata LIMIT 1", null)) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {}
        return 0;
    }

    public void syncDatabaseFromInternet(String customUrl, SyncListener listener) {
        new Thread(() -> {
            try {
                if (listener != null) listener.onProgress("Connecting to update server...");
                String downloadUrl;
                int targetVersion = 0;

                if (customUrl != null && !customUrl.trim().isEmpty()) {
                    downloadUrl = customUrl.trim();
                } else {
                    int currentVersion = getDatabaseVersion();
                    if (listener != null) listener.onProgress("Probing CDN for updates (current: v" + currentVersion + ")...");
                    int latestVersion = currentVersion;
                    int low = currentVersion;
                    int high = 550;
                    while (low <= high) {
                        int mid = (low + high) / 2;
                        try {
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(SENSARA_CDN_BASE + "/lcndb-v" + mid + ".db").openConnection();
                            conn.setRequestMethod("HEAD");
                            conn.setConnectTimeout(4000);
                            conn.setReadTimeout(4000);
                            int code = conn.getResponseCode();
                            conn.disconnect();
                            if (code == 200) {
                                latestVersion = mid;
                                low = mid + 1;
                            } else {
                                high = mid - 1;
                            }
                        } catch (Exception e) {
                            high = mid - 1;
                        }
                    }

                    if (latestVersion <= currentVersion && currentVersion > 0) {
                        if (listener != null) listener.onSuccess(currentVersion, "Database is already at latest version (v" + currentVersion + ").");
                        return;
                    }
                    targetVersion = latestVersion;
                    downloadUrl = SENSARA_CDN_BASE + "/lcndb-v" + latestVersion + ".db";
                }

                if (listener != null) listener.onProgress("Downloading database update...");
                File dbFile = context.getDatabasePath(DB_NAME);
                File tmpFile = new File(dbFile.getAbsolutePath() + ".tmp");

                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(downloadUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                if (conn.getResponseCode() != 200) {
                    throw new Exception("HTTP " + conn.getResponseCode() + " from " + downloadUrl);
                }

                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                conn.disconnect();

                if (listener != null) listener.onProgress("Verifying downloaded database...");
                int ver = 0;
                try (SQLiteDatabase testDb = SQLiteDatabase.openDatabase(tmpFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                    try (Cursor c = testDb.rawQuery("SELECT data_version FROM metadata LIMIT 1", null)) {
                        if (c.moveToFirst()) ver = c.getInt(0);
                    }
                }

                synchronized (this) {
                    if (database != null && database.isOpen()) {
                        database.close();
                        database = null;
                    }
                    if (dbFile.exists()) {
                        dbFile.delete();
                    }
                    if (!tmpFile.renameTo(dbFile)) {
                        throw new Exception("Failed to rename temporary database file");
                    }
                    ensureDatabaseCopied();
                }

                int finalVer = ver > 0 ? ver : targetVersion;
                if (listener != null) {
                    listener.onSuccess(finalVer, "Successfully updated database to v" + finalVer + "!");
                }
            } catch (Exception e) {
                Log.e(TAG, "Database sync failed", e);
                if (listener != null) {
                    listener.onError("Sync failed: " + e.getMessage());
                }
            }
        }).start();
    }

    // --- Data Models ---

    public static class Provider {
        public final int id;
        public final String title;
        public final String type;
        public final int groupId;
        public final String brandTitle;

        public Provider(int id, String title, String type, int groupId, String brandTitle) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.groupId = groupId;
            this.brandTitle = brandTitle;
        }
    }

    public static class RemoteModel {
        public final int id;
        public final int protocol;
        public final int bitLength;
        public final int gap;
        public final String powerCode;

        public RemoteModel(int id, int protocol, int bitLength, int gap, String powerCode) {
            this.id = id;
            this.protocol = protocol;
            this.bitLength = bitLength;
            this.gap = gap;
            this.powerCode = powerCode;
        }
    }

    public static class Channel {
        public final String lcn;
        public final String name;
        public final String genre;
        public final String language;
        public final int channelId;
        public final String imageUrl;
        public boolean isFavorite = false;
        public String customLcn = null;

        public Channel(String lcn, String name, String genre, String language, int channelId, String imageUrl) {
            this.lcn = lcn;
            this.name = name;
            this.genre = genre;
            this.language = language;
            this.channelId = channelId;
            this.imageUrl = imageUrl;
        }

        public Channel(String lcn, String name, String genre, String language) {
            this(lcn, name, genre, language, 0, "");
        }

        public String getDisplayLcn() {
            return (customLcn != null && !customLcn.trim().isEmpty()) ? customLcn.trim() : lcn;
        }
    }

    // --- Queries ---

    public List<Provider> searchProviders(String query, int limit) {
        List<Provider> list = new ArrayList<>();
        SQLiteDatabase db = getDb();
        if (db == null) return list;

        String wildcard = "%" + query.trim() + "%";
        String sql = "SELECT p.id, p.title, p.type, p.group_id, b.title as brand_title " +
                "FROM providers p " +
                "LEFT JOIN provider_brands b ON p.brand_id = b.id " +
                "LEFT JOIN provider_groups g ON p.group_id = g.id " +
                "WHERE p.title LIKE ? OR b.title LIKE ? OR g.title LIKE ? " +
                "ORDER BY p.title ASC LIMIT ?";

        try (Cursor cursor = db.rawQuery(sql, new String[]{wildcard, wildcard, wildcard, String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                list.add(new Provider(
                        cursor.getInt(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.getString(4) != null ? cursor.getString(4) : ""
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "searchProviders error", e);
        }
        return list;
    }

    public Provider getProviderById(int providerId) {
        SQLiteDatabase db = getDb();
        if (db == null) return null;
        String sql = "SELECT p.id, p.title, p.type, p.group_id, b.title as brand_title " +
                "FROM providers p " +
                "LEFT JOIN provider_brands b ON p.brand_id = b.id " +
                "WHERE p.id = ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(providerId)})) {
            if (cursor.moveToFirst()) {
                return new Provider(
                        cursor.getInt(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.getString(4) != null ? cursor.getString(4) : ""
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "getProviderById error", e);
        }
        return null;
    }

    public List<RemoteModel> getRemotesForProvider(int providerId) {
        List<RemoteModel> list = new ArrayList<>();
        SQLiteDatabase db = getDb();
        if (db == null) return list;

        int groupId = 0;
        try (Cursor c = db.rawQuery("SELECT group_id FROM providers WHERE id = ?", new String[]{String.valueOf(providerId)})) {
            if (c.moveToFirst()) {
                groupId = c.getInt(0);
            }
        }

        String sql;
        String param;
        if (groupId > 0) {
            sql = "SELECT DISTINCT r.id, r.protocol, r.length, r.gap " +
                    "FROM provider_group_remotes pgr " +
                    "JOIN remotes r ON pgr.remote_id = r.id " +
                    "WHERE pgr.group_id = ? ORDER BY r.id ASC";
            param = String.valueOf(groupId);
        } else {
            sql = "SELECT DISTINCT r.id, r.protocol, r.length, r.gap " +
                    "FROM provider_remotes pr " +
                    "JOIN remotes r ON pr.remote_id = r.id " +
                    "WHERE pr.provider_id = ? ORDER BY r.id ASC";
            param = String.valueOf(providerId);
        }

        try (Cursor cursor = db.rawQuery(sql, new String[]{param})) {
            while (cursor.moveToNext()) {
                int rId = cursor.getInt(0);
                int proto = cursor.getInt(1);
                int len = cursor.getInt(2);
                int gap = cursor.getInt(3);

                String powerCode = "";
                try (Cursor kc = db.rawQuery("SELECT code FROM key_codes WHERE remote_id = ? AND \"key\" = 15 LIMIT 1",
                        new String[]{String.valueOf(rId)})) {
                    if (kc.moveToFirst()) {
                        powerCode = kc.getString(0);
                    }
                }
                list.add(new RemoteModel(rId, proto, len, gap, powerCode != null ? powerCode : ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "getRemotesForProvider error", e);
        }
        return list;
    }

    public Map<String, String> getKeyCodes(int remoteId) {
        Map<String, String> map = new HashMap<>();
        SQLiteDatabase db = getDb();
        if (db == null) return map;

        String sql = "SELECT \"key\", code FROM key_codes WHERE remote_id = ? ORDER BY \"key\" ASC";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(remoteId)})) {
            while (c.moveToNext()) {
                int keyId = c.getInt(0);
                String code = c.getString(1);
                String keyName = mapKeyIdToName(keyId);
                map.put(keyName, code);
                if (keyName.contains(" ")) {
                    map.put(keyName.replace(" ", "_"), code);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getKeyCodes error", e);
        }

        // Sanity fix for database entries where CH UP was erroneously assigned the DOWN code (e.g. Remote 184):
        String upCode = map.get("UP");
        String downCode = map.get("DOWN");
        String chUpCode = map.get("CH UP");
        String chDownCode = map.get("CH DOWN");

        if (upCode != null && (chUpCode == null || (downCode != null && chUpCode.equalsIgnoreCase(downCode)))) {
            map.put("CH UP", upCode);
            map.put("CH_UP", upCode);
            map.put("CHANNEL_UP", upCode);
        }
        if (downCode != null && (chDownCode == null || chDownCode.isEmpty())) {
            map.put("CH DOWN", downCode);
            map.put("CH_DOWN", downCode);
            map.put("CHANNEL_DOWN", downCode);
        }

        return map;
    }

    private android.content.SharedPreferences getUserPrefs() {
        return context.getSharedPreferences("user_channel_overrides", Context.MODE_PRIVATE);
    }

    public boolean isFavorite(int providerId, int channelId, String lcn) {
        android.content.SharedPreferences sp = getUserPrefs();
        if (channelId > 0 && sp.contains("fav_" + providerId + "_" + channelId)) {
            return sp.getBoolean("fav_" + providerId + "_" + channelId, false);
        }
        return sp.getBoolean("fav_" + providerId + "_lcn_" + lcn, false);
    }

    public void setFavorite(int providerId, int channelId, String lcn, boolean isFav) {
        android.content.SharedPreferences.Editor ed = getUserPrefs().edit();
        if (channelId > 0) {
            ed.putBoolean("fav_" + providerId + "_" + channelId, isFav);
        }
        ed.putBoolean("fav_" + providerId + "_lcn_" + lcn, isFav);
        ed.apply();
    }

    public boolean toggleFavorite(int providerId, Channel ch) {
        boolean newState = !isFavorite(providerId, ch.channelId, ch.lcn);
        ch.isFavorite = newState;
        setFavorite(providerId, ch.channelId, ch.lcn, newState);
        return newState;
    }

    public String getCustomLcn(int providerId, int channelId, String defaultLcn) {
        android.content.SharedPreferences sp = getUserPrefs();
        if (channelId > 0 && sp.contains("lcn_" + providerId + "_" + channelId)) {
            return sp.getString("lcn_" + providerId + "_" + channelId, defaultLcn);
        }
        return defaultLcn;
    }

    public void setCustomLcn(int providerId, int channelId, String newLcn) {
        if (channelId <= 0) return;
        android.content.SharedPreferences.Editor ed = getUserPrefs().edit();
        if (newLcn == null || newLcn.trim().isEmpty()) {
            ed.remove("lcn_" + providerId + "_" + channelId);
        } else {
            ed.putString("lcn_" + providerId + "_" + channelId, newLcn.trim());
        }
        ed.apply();
    }

    public List<Channel> getChannelsForProvider(int providerId, int limit) {
        List<Channel> list = new ArrayList<>();
        SQLiteDatabase db = getDb();
        if (db == null) return list;

        String sql = "SELECT pc.key as lcn, c.name, c.genre, c.language, c.id, c.image " +
                "FROM provider_channels pc " +
                "JOIN channels c ON pc.channel_id = c.id " +
                "WHERE pc.provider_id = ? " +
                "ORDER BY CAST(pc.key AS INTEGER) ASC LIMIT ?";

        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(providerId), String.valueOf(limit)})) {
            while (c.moveToNext()) {
                String origLcn = c.getString(0);
                String name = c.getString(1);
                String genre = c.getString(2) != null ? c.getString(2) : "General";
                String language = c.getString(3) != null ? c.getString(3) : "";
                int channelId = c.getInt(4);
                String image = c.getString(5) != null ? c.getString(5) : "";

                Channel ch = new Channel(origLcn, name, genre, language, channelId, image);
                ch.customLcn = getCustomLcn(providerId, channelId, origLcn);
                ch.isFavorite = isFavorite(providerId, channelId, origLcn);
                list.add(ch);
            }
        } catch (Exception e) {
            Log.e(TAG, "getChannelsForProvider error", e);
        }

        // Sort:
        // 1. Favourites foremost!
        // 2. High usage count
        // 3. Display LCN numeric
        android.content.SharedPreferences stbPrefs = context.getSharedPreferences("stb_settings", Context.MODE_PRIVATE);
        java.util.Collections.sort(list, (a, b) -> {
            if (a.isFavorite != b.isFavorite) {
                return a.isFavorite ? -1 : 1; // Favourites come first
            }
            int useA = stbPrefs.getInt("ch_use_" + providerId + "_" + a.getDisplayLcn(), 0);
            int useB = stbPrefs.getInt("ch_use_" + providerId + "_" + b.getDisplayLcn(), 0);
            if (useA != useB) {
                return Integer.compare(useB, useA); // Higher usage comes next
            }
            try {
                return Integer.compare(Integer.parseInt(a.getDisplayLcn()), Integer.parseInt(b.getDisplayLcn()));
            } catch (Exception e) {
                return a.getDisplayLcn().compareTo(b.getDisplayLcn());
            }
        });

        return list;
    }

    public List<Channel> filterChannels(List<Channel> source, String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Channel> filtered = new ArrayList<>();
        for (Channel ch : source) {
            if (ch.name.toLowerCase(java.util.Locale.ROOT).contains(q) ||
                ch.getDisplayLcn().toLowerCase(java.util.Locale.ROOT).contains(q) ||
                (ch.genre != null && ch.genre.toLowerCase(java.util.Locale.ROOT).contains(q))) {
                filtered.add(ch);
            }
        }
        return filtered;
    }

    private static String mapKeyIdToName(int keyId) {
        switch (keyId) {
            case 0: return "0";
            case 1: return "1";
            case 2: return "2";
            case 3: return "3";
            case 4: return "4";
            case 5: return "5";
            case 6: return "6";
            case 7: return "7";
            case 8: return "8";
            case 9: return "9";
            case 10: return "VOL UP";
            case 11: return "VOL DOWN";
            case 12: return "VOL MUTE";
            case 13: return "CH UP";
            case 14: return "CH DOWN";
            case 15: return "POWER";
            case 16: return "SELECT";
            case 17: return "BACK";
            case 18: return "EXIT";
            case 19: return "MENU";
            case 20: return "INFO";
            case 21: return "UP";
            case 22: return "DOWN";
            case 23: return "LEFT";
            case 24: return "RIGHT";
            case 25: return "OK";
            case 39: return "GUIDE";
            default: return "KEY_" + keyId;
        }
    }
}

