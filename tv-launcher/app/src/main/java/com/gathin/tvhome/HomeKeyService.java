package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Global accessibility service:
 * 1. Routes KEYCODE_ALL_APPS to Home on Xiaomi remotes.
 * 2. Intercepts long-press on KEYCODE_DPAD_CENTER / ENTER from ANY input (including AV1/HDMI)
 *    and launches the translucent RemoteSidebarActivity over the running input.
 * 3. Routes KEYCODE_MENU to the translucent sidebar.
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isCenterLongPressed = false;

    private final Runnable centerLongPressRunnable = () -> {
        isCenterLongPressed = true;
        openSidebar();
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new android.accessibilityservice.AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);

        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        settingsKeycodes = getResources().getIntArray(R.array.settings_key_keycodes);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private void openSidebar() {
        android.util.Log.i("HomeKeyService", "Opening RemoteSidebarActivity");
        Intent intent = new Intent(this, RemoteSidebarActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        android.util.Log.d("HomeKeyService", "Key: action=" + event.getAction() + " code=" + keyCode + " repeat=" + event.getRepeatCount());

        // Stock launcher handled remote Settings button
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }

        // Long-press round center button to open translucent sidebar ONLY in live input (AV1 / HDMI)
        if (!MainActivity.isForeground && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    isCenterLongPressed = false;
                    handler.removeCallbacks(centerLongPressRunnable);
                    handler.postDelayed(centerLongPressRunnable, 400);
                } else if (event.getRepeatCount() >= 2 && !isCenterLongPressed) {
                    isCenterLongPressed = true;
                    handler.removeCallbacks(centerLongPressRunnable);
                    openSidebar();
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(centerLongPressRunnable);
                if (isCenterLongPressed) {
                    isCenterLongPressed = false;
                    return true;
                }
            }
            return false;
        }

        // Dedicated remote buttons that open sidebar in live input: MENU, WINDOW, GUIDE, INFO
        if (!MainActivity.isForeground && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_WINDOW || keyCode == KeyEvent.KEYCODE_GUIDE)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openSidebar();
                return true;
            }
        }

        if (keyCode != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(centerLongPressRunnable);
    }
}
