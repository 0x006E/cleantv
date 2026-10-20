package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Native TV Passthrough Player Activity.
 * Mirrors the TV hardware input (AV1 / Composite, HDMI) using TvView directly inside our app,
 * giving us 100% control over key events, OSD, and the translucent remote sidebar.
 */
public class TvPlayerActivity extends Activity {

    public static final String EXTRA_INPUT_ID = "extra_input_id";
    public static final String EXTRA_INITIAL_LCN = "extra_initial_lcn";

    private TvView tvView;
    private View osdBanner;
    private ImageView osdIcon;
    private TextView osdTitle;
    private TextView osdSubtitle;

    private String currentInputId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideOsdRunnable = this::hideOsd;

    private SensyIrManager irManager;
    private final Map<String, String> keyCodes = new HashMap<>();
    private int activeRemoteBitLength = 32;
    private int activeProviderId = 43;

    private boolean okKeyDownHandled = false;
    private boolean backDownReceived = false;
    private final Runnable longPressOkRunnable = () -> {
        okKeyDownHandled = true;
        openRemoteSidebar();
    };

    private void transmitKey(String keyName, String displayLabel) {
        String hex = keyCodes.get(keyName);
        if (hex == null && "OK".equals(keyName)) hex = keyCodes.get("SELECT");
        if (hex != null && !hex.isEmpty()) {
            showOsd(displayLabel, "IR " + keyName, 1200);
            irManager.transmitNecAsync(hex, activeRemoteBitLength, null);
        } else {
            showOsd(displayLabel, "No code", 1200);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_tv_player);

        tvView = findViewById(R.id.tv_view);
        osdBanner = findViewById(R.id.osd_banner);
        osdIcon = findViewById(R.id.osd_icon);
        osdTitle = findViewById(R.id.osd_title);
        osdSubtitle = findViewById(R.id.osd_subtitle);

        irManager = SensyIrManager.getInstance(this);
        loadIrSettings();

        // Resolve input ID
        String inputId = getIntent().getStringExtra(EXTRA_INPUT_ID);
        if (inputId == null || inputId.isEmpty()) {
            inputId = resolveDefaultAvInput();
        }
        currentInputId = inputId;

        setupTvView();
        tuneInput(currentInputId);

        String initialLcn = getIntent().getStringExtra(EXTRA_INITIAL_LCN);
        if (initialLcn == null && getIntent().hasExtra(EXTRA_INITIAL_LCN)) {
            int l = getIntent().getIntExtra(EXTRA_INITIAL_LCN, -1);
            if (l > 0) initialLcn = String.valueOf(l);
        }

        if (initialLcn != null && !initialLcn.isEmpty()) {
            showOsd("Tuned " + initialLcn, "Press OK or MENU for Remote Sidebar", 3500);
        } else {
            showOsd("Live TV", "Press OK or MENU for Remote Sidebar", 3500);
        }
    }

    private void setupTvView() {
        if (tvView == null) return;
        tvView.setFocusable(false);
        tvView.setFocusableInTouchMode(false);
        tvView.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String inputId) {
                super.onVideoAvailable(inputId);
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                super.onVideoUnavailable(inputId, reason);
            }
        });
    }

    private void tuneInput(String inputId) {
        if (tvView == null || inputId == null || inputId.isEmpty()) return;
        try {
            Uri passthroughUri = TvContract.buildChannelUriForPassthroughInput(inputId);
            tvView.tune(inputId, passthroughUri);

            String label = "Input: " + inputId;
            if (inputId.toLowerCase(Locale.ROOT).contains("av") || inputId.endsWith("/HW1") || inputId.endsWith("/HW3")) {
                label = "AV1 (Kerala Vision STB)";
                if (osdIcon != null) osdIcon.setImageResource(R.drawable.ic_av);
            } else if (inputId.toLowerCase(Locale.ROOT).contains("hdmi")) {
                label = "HDMI Input";
                if (osdIcon != null) osdIcon.setImageResource(R.drawable.ic_input);
            }
            if (osdTitle != null) osdTitle.setText(label);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to tune " + inputId, Toast.LENGTH_SHORT).show();
        }
    }

    private String resolveDefaultAvInput() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                String id = info.getId();
                CharSequence label = info.loadLabel(this);
                String labelStr = label != null ? label.toString().toUpperCase(Locale.ROOT) : "";
                if (labelStr.contains("AV") || id.toLowerCase(Locale.ROOT).contains("composite")
                        || id.endsWith("/HW1") || id.endsWith("/HW3")) {
                    return id;
                }
            }
        }
        return "com.droidlogic.tvinput/.services.AV1InputService/HW1";
    }

    private void loadIrSettings() {
        new Thread(() -> {
            SharedPreferences sp = getSharedPreferences("stb_settings", Context.MODE_PRIVATE);
            activeProviderId = sp.getInt("provider_id", 648);
            int remoteId = sp.getInt("remote_id", 43);
            activeRemoteBitLength = sp.getInt("bit_length", 32);

            LcnDatabaseHelper db = LcnDatabaseHelper.getInstance(this);
            Map<String, String> codes = db.getKeyCodes(remoteId);
            String customJson = sp.getString("custom_keys_json", null);
            if (customJson != null) {
                try {
                    JSONObject obj = new JSONObject(customJson);
                    JSONObject kObj = obj.optJSONObject("keys");
                    if (kObj != null) {
                        for (Iterator<String> it = kObj.keys(); it.hasNext(); ) {
                            String k = it.next();
                            codes.put(k.toUpperCase(Locale.ROOT), kObj.getString(k));
                        }
                    }
                } catch (Exception ignored) {}
            }
            handler.post(() -> {
                keyCodes.clear();
                keyCodes.putAll(codes);
            });
        }).start();
    }

    private void tuneStbChannel(int lcn) {
        showOsd("Tuning " + lcn, "Sending IR signal to STB...", 2500);
        irManager.tuneChannelAsync(String.valueOf(lcn), keyCodes, activeRemoteBitLength, null);
    }

    private void changeChannelDelta(int delta) {
        String label = delta > 0 ? "Channel Up" : "Channel Down";
        showOsd(label, delta > 0 ? "CH+" : "CH−", 1800);
        String hex = null;
        if (delta > 0) {
            for (String k : new String[]{"CH UP", "CH_UP", "CHANNEL_UP", "UP"}) {
                String val = keyCodes.get(k);
                if (val != null && !val.isEmpty()) {
                    if (!val.equalsIgnoreCase(keyCodes.get("DOWN"))) {
                        hex = val;
                        break;
                    }
                }
            }
            if (hex == null) hex = keyCodes.get("UP");
        } else {
            for (String k : new String[]{"CH DOWN", "CH_DOWN", "CHANNEL_DOWN", "DOWN"}) {
                String val = keyCodes.get(k);
                if (val != null && !val.isEmpty()) {
                    hex = val;
                    break;
                }
            }
            if (hex == null) hex = keyCodes.get("DOWN");
        }
        if (hex != null) {
            irManager.transmitNecAsync(hex, activeRemoteBitLength, null);
        }
    }

    public void openRemoteSidebar() {
        Intent intent = new Intent(this, RemoteSidebarActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void showOsd(String title, String subtitle, int durationMs) {
        if (osdBanner == null) return;
        handler.removeCallbacks(hideOsdRunnable);
        if (osdTitle != null) osdTitle.setText(title);
        if (osdSubtitle != null) osdSubtitle.setText(subtitle);

        osdBanner.setVisibility(View.VISIBLE);
        osdBanner.setAlpha(0f);
        osdBanner.animate().alpha(1f).setDuration(200).start();

        handler.postDelayed(hideOsdRunnable, durationMs);
    }

    private void hideOsd() {
        if (osdBanner == null || osdBanner.getVisibility() != View.VISIBLE) return;
        osdBanner.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            osdBanner.setVisibility(View.GONE);
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // MENU button -> directly toggle translucent Remote Sidebar
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_DOWN) {
                openRemoteSidebar();
            }
            return true;
        }

        // Center OK / Enter button: short press transmits IR OK, long press opens remote sidebar
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    okKeyDownHandled = false;
                    handler.postDelayed(longPressOkRunnable, 350);
                } else if (!okKeyDownHandled && event.getRepeatCount() > 2) {
                    handler.removeCallbacks(longPressOkRunnable);
                    okKeyDownHandled = true;
                    openRemoteSidebar();
                }
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressOkRunnable);
                if (!okKeyDownHandled) {
                    // Short tap on OK sends STB OK command
                    transmitKey("OK", "OK");
                }
                okKeyDownHandled = false;
                return true;
            }
        }

        // DPAD buttons: map directly to paired remote IR commands
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                transmitKey("UP", "▲ UP");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                transmitKey("DOWN", "▼ DOWN");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                transmitKey("LEFT", "◀ LEFT");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                transmitKey("RIGHT", "▶ RIGHT");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                changeChannelDelta(+1);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                changeChannelDelta(-1);
                return true;
            }
        }

        // Back key: require corresponding ACTION_DOWN to prevent orphaned ACTION_UP from closing player
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                backDownReceived = true;
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                if (backDownReceived) {
                    backDownReceived = false;
                    finish();
                    return true;
                }
                return true; // Consume orphaned ACTION_UP from dismissed sidebar
            }
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (tvView != null) {
            tvView.reset();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvView != null && currentInputId != null) {
            tuneInput(currentInputId);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (tvView != null) {
            tvView.reset();
        }
    }
}

