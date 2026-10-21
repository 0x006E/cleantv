package dev.ntsv.cleantv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateManager {

    private static final String TAG = "CleanTV.Update";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/0x006E/cleantv/releases/latest";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static void checkForUpdates(final Activity activity, final boolean userInitiated) {
        if (activity == null || activity.isFinishing()) return;

        if (userInitiated) {
            Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT).show();
        }

        EXECUTOR.execute(() -> {
            try {
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "CleanTV-Launcher-Updater");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("GitHub API error: HTTP " + responseCode);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                JSONObject release = new JSONObject(sb.toString());
                String tagName = release.optString("tag_name", "");
                String releaseName = release.optString("name", tagName);
                String body = release.optString("body", "");
                JSONArray assets = release.optJSONArray("assets");

                String downloadUrl = null;
                String assetFileName = null;
                long assetSize = 0;

                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.endsWith(".apk") && (name.contains("launcher") || !name.contains("companion"))) {
                            downloadUrl = asset.optString("browser_download_url", "");
                            assetFileName = name;
                            assetSize = asset.optLong("size", 0);
                            break;
                        }
                    }
                }

                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    throw new IOException("No launcher APK asset found in latest release.");
                }

                String currentVersion = getCurrentVersion(activity);
                int cmp = compareVersions(tagName, currentVersion);

                final String finalDownloadUrl = downloadUrl;
                final String finalAssetName = assetFileName;
                final long finalAssetSize = assetSize;
                final String finalReleaseName = releaseName;
                final String finalBody = body;

                MAIN_HANDLER.post(() -> {
                    if (activity.isFinishing()) return;

                    if (cmp > 0) {
                        // Newer version available
                        showUpdateAvailableDialog(activity, tagName, finalReleaseName, finalBody,
                                finalDownloadUrl, finalAssetName, finalAssetSize);
                    } else {
                        // Up to date
                        if (userInitiated) {
                            showUpToDateDialog(activity, currentVersion, tagName,
                                    finalDownloadUrl, finalAssetName, finalAssetSize);
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
                if (userInitiated) {
                    MAIN_HANDLER.post(() -> {
                        if (!activity.isFinishing()) {
                            Toast.makeText(activity, "Update check failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private static void showUpdateAvailableDialog(Activity activity, String tagName, String releaseName,
                                                 String body, String downloadUrl, String fileName, long size) {
        StringBuilder msg = new StringBuilder();
        msg.append("A new version of CleanTV is available (").append(tagName).append(").\n\n");
        if (body != null && !body.trim().isEmpty()) {
            msg.append(body.trim()).append("\n\n");
        }
        if (size > 0) {
            msg.append("Download size: ").append(String.format("%.1f MB", size / (1024.0 * 1024.0)));
        }

        new AlertDialog.Builder(activity)
                .setTitle("Update Available: " + releaseName)
                .setMessage(msg.toString().trim())
                .setPositiveButton("Update Now", (dialog, which) -> {
                    startDownload(activity, downloadUrl, fileName);
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private static void showUpToDateDialog(Activity activity, String currentVersion, String latestTag,
                                           String downloadUrl, String fileName, long size) {
        new AlertDialog.Builder(activity)
                .setTitle("CleanTV is Up to Date")
                .setMessage("Installed version: v" + currentVersion + "\nLatest version: " + latestTag)
                .setPositiveButton("OK", null)
                .setNeutralButton("Reinstall", (dialog, which) -> {
                    startDownload(activity, downloadUrl, fileName);
                })
                .show();
    }

    private static void startDownload(Activity activity, String downloadUrl, String fileName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                try {
                    Intent grantIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(grantIntent);
                    Toast.makeText(activity, "Allow installing unknown apps, then update again", Toast.LENGTH_LONG).show();
                    return;
                } catch (ActivityNotFoundException ignored) {}
            }
        }

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView tvStatus = new TextView(activity);
        tvStatus.setText("Connecting...");
        tvStatus.setTextSize(16);
        tvStatus.setTextColor(0xFFFFFFFF);

        ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * activity.getResources().getDisplayMetrics().density);
        progressBar.setLayoutParams(lp);

        layout.addView(tvStatus);
        layout.addView(progressBar);

        final boolean[] cancelled = {false};

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("Downloading Update")
                .setView(layout)
                .setNegativeButton("Cancel", (dialog, which) -> cancelled[0] = true)
                .setCancelable(false)
                .show();

        EXECUTOR.execute(() -> {
            File destFile = new File(activity.getExternalCacheDir(), "cleantv-update.apk");
            try {
                HttpURLConnection conn = openConnectionWithRedirects(downloadUrl);
                long totalLength = conn.getContentLengthLong();

                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(destFile);

                byte[] buffer = new byte[8192];
                long totalBytesRead = 0;
                int bytesRead;
                long lastUiUpdate = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    if (cancelled[0]) {
                        in.close();
                        out.close();
                        destFile.delete();
                        return;
                    }
                    out.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    long now = System.currentTimeMillis();
                    if (now - lastUiUpdate > 100 || totalBytesRead == totalLength) {
                        lastUiUpdate = now;
                        final int progress = totalLength > 0 ? (int) ((totalBytesRead * 100) / totalLength) : 0;
                        final long currentBytes = totalBytesRead;
                        final long maxBytes = totalLength;

                        MAIN_HANDLER.post(() -> {
                            if (progressDialog.isShowing()) {
                                progressBar.setProgress(progress);
                                if (maxBytes > 0) {
                                    tvStatus.setText(String.format("Downloading... %d%% (%.1f / %.1f MB)",
                                            progress,
                                            currentBytes / (1024.0 * 1024.0),
                                            maxBytes / (1024.0 * 1024.0)));
                                } else {
                                    tvStatus.setText(String.format("Downloading... %.1f MB",
                                            currentBytes / (1024.0 * 1024.0)));
                                }
                            }
                        });
                    }
                }

                out.flush();
                out.close();
                in.close();
                conn.disconnect();

                MAIN_HANDLER.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    installApk(activity, destFile);
                });

            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                MAIN_HANDLER.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    if (!cancelled[0]) {
                        Toast.makeText(activity, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private static HttpURLConnection openConnectionWithRedirects(String downloadUrl) throws IOException {
        String currentUrl = downloadUrl;
        HttpURLConnection conn = null;
        for (int i = 0; i < 6; i++) {
            URL url = new URL(currentUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "CleanTV-Launcher-Updater");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null && !loc.isEmpty()) {
                    currentUrl = loc;
                    continue;
                }
            }
            if (code == HttpURLConnection.HTTP_OK) {
                return conn;
            }
            throw new IOException("HTTP error: " + code + " " + conn.getResponseMessage());
        }
        throw new IOException("Too many HTTP redirects");
    }

    private static void installApk(Activity activity, File apkFile) {
        if (!apkFile.exists() || apkFile.length() == 0) {
            Toast.makeText(activity, "Update package is empty or missing", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch installer", e);
            Toast.makeText(activity, "Failed to launch installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static String getCurrentVersion(Activity activity) {
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "0.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0";
        }
    }

    public static int compareVersions(String v1, String v2) {
        if (v1 == null) v1 = "0";
        if (v2 == null) v2 = "0";
        v1 = v1.trim().replaceFirst("^[vV]", "");
        v2 = v2.trim().replaceFirst("^[vV]", "");

        String[] parts1 = v1.split("[.-]");
        String[] parts2 = v2.split("[.-]");
        int len = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < len; i++) {
            int num1 = 0;
            if (i < parts1.length) {
                try {
                    num1 = Integer.parseInt(parts1[i].replaceAll("\\D+", ""));
                } catch (Exception ignored) {}
            }
            int num2 = 0;
            if (i < parts2.length) {
                try {
                    num2 = Integer.parseInt(parts2[i].replaceAll("\\D+", ""));
                } catch (Exception ignored) {}
            }
            if (num1 < num2) return -1;
            if (num1 > num2) return 1;
        }
        return 0;
    }
}
