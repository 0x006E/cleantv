package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));

        // STB & Live Channels Settings
        SharedPreferences stbPrefs = getSharedPreferences("stb_settings", MODE_PRIVATE);
        TextView stbProviderChoice = findViewById(R.id.stbProviderChoice);
        TextView pairRemoteChoice = findViewById(R.id.pairRemoteChoice);
        TextView testIrChoice = findViewById(R.id.testIrChoice);
        TextView avInputChoice = findViewById(R.id.avInputChoice);
        TextView resetChannelsChoice = findViewById(R.id.resetChannelsChoice);
        TextView companionPairingChoice = findViewById(R.id.companionPairingChoice);
        TextView forgetCompanionDevicesChoice = findViewById(R.id.forgetCompanionDevicesChoice);

        updateStbProviderText(stbProviderChoice, stbPrefs);
        stbProviderChoice.setOnClickListener(v -> startActivity(new Intent(this, StbSetupActivity.class)));

        pairRemoteChoice.setOnClickListener(v -> {
            Intent intent = new Intent(this, StbSetupActivity.class);
            intent.putExtra(StbSetupActivity.EXTRA_START_PAIRING, true);
            startActivity(intent);
        });

        testIrChoice.setOnClickListener(v -> {
            int rId = stbPrefs.getInt("remote_id", 43);
            int bitLen = stbPrefs.getInt("bit_length", 32);
            LcnDatabaseHelper db = LcnDatabaseHelper.getInstance(this);
            SensyIrManager ir = SensyIrManager.getInstance(this);
            new Thread(() -> {
                java.util.Map<String, String> keys = db.getKeyCodes(rId);
                String powerHex = keys.get("POWER");
                if (powerHex != null && !powerHex.isEmpty()) {
                    ir.transmitNecAsync(powerHex, bitLen, () ->
                            Toast.makeText(this, "Transmitted POWER [" + powerHex + "]", Toast.LENGTH_SHORT).show()
                    );
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "No power code found", Toast.LENGTH_SHORT).show());
                }
            }).start();
        });

        updateAvInputText(avInputChoice, stbPrefs);
        avInputChoice.setOnClickListener(v -> showAvInputPicker(avInputChoice, stbPrefs));

        resetChannelsChoice.setOnClickListener(v -> showResetChannelsDialog());

        companionPairingChoice.setOnClickListener(v -> showCompanionPairingDialog());

        updateForgetDevicesText(forgetCompanionDevicesChoice);
        forgetCompanionDevicesChoice.setOnClickListener(v -> showForgetDevicesDialog(forgetCompanionDevicesChoice));

        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();

        if (getIntent().getBooleanExtra("extra_start_pairing", false)) {
            startPairingWizard(stbPrefs, stbProviderChoice);
        }
    }

    private void showProviderPicker(TextView view, SharedPreferences stbPrefs) {
        LcnDatabaseHelper db = LcnDatabaseHelper.getInstance(this);
        new Thread(() -> {
            java.util.List<LcnDatabaseHelper.Provider> list = db.searchProviders("", 40);
            runOnUiThread(() -> {
                String[] titles = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    titles[i] = list.get(i).title + " (" + list.get(i).type + ")";
                }
                new AlertDialog.Builder(this)
                        .setTitle("Select STB Provider")
                        .setItems(titles, (d, which) -> {
                            LcnDatabaseHelper.Provider p = list.get(which);
                            new Thread(() -> {
                                java.util.List<LcnDatabaseHelper.RemoteModel> remotes = db.getRemotesForProvider(p.id);
                                int rId = remotes.isEmpty() ? p.id : remotes.get(0).id;
                                int bitLen = remotes.isEmpty() ? 32 : remotes.get(0).bitLength;
                                stbPrefs.edit()
                                        .putInt("provider_id", p.id)
                                        .putString("provider_title", p.title)
                                        .putInt("remote_id", rId)
                                        .putInt("bit_length", bitLen)
                                        .apply();
                                runOnUiThread(() -> {
                                    updateStbProviderText(view, stbPrefs);
                                    setResult(RESULT_OK);
                                });
                            }).start();
                        })
                        .show();
            });
        }).start();
    }

    private void startPairingWizard(SharedPreferences stbPrefs, TextView providerView) {
        int providerId = stbPrefs.getInt("provider_id", 43);
        LcnDatabaseHelper db = LcnDatabaseHelper.getInstance(this);
        new Thread(() -> {
            java.util.List<LcnDatabaseHelper.RemoteModel> remotes = db.getRemotesForProvider(providerId);
            if (remotes.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "No remote profiles found for this provider", Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> showWizardStep(remotes, 0, stbPrefs, providerView));
        }).start();
    }

    private void showWizardStep(java.util.List<LcnDatabaseHelper.RemoteModel> remotes, int index,
                                SharedPreferences stbPrefs, TextView providerView) {
        if (index < 0 || index >= remotes.size()) return;
        LcnDatabaseHelper.RemoteModel current = remotes.get(index);
        int total = remotes.size();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pair STB Remote (" + (index + 1) + " of " + total + ")")
                .setMessage("Candidate Model: Remote #" + current.id + " (Bits: " + current.bitLength + ")\n\n"
                        + "1. Click 'TEST POWER' below.\n"
                        + "2. Check if your Set-Top Box powers ON/OFF.\n"
                        + "3. If it worked, select 'PAIR THIS REMOTE'. Otherwise select 'NEXT MODEL'.")
                .setPositiveButton("PAIR THIS REMOTE", (d, w) -> {
                    stbPrefs.edit()
                            .putInt("remote_id", current.id)
                            .putInt("bit_length", current.bitLength)
                            .apply();
                    updateStbProviderText(providerView, stbPrefs);
                    setResult(RESULT_OK);
                    Toast.makeText(this, "Successfully paired with Remote #" + current.id + "!", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(index < total - 1 ? "NEXT MODEL" : "RESTART", (d, w) -> {
                    int next = (index + 1) % total;
                    showWizardStep(remotes, next, stbPrefs, providerView);
                })
                .setNeutralButton("TEST POWER", null)
                .create();

        dialog.setOnShowListener(dInterface -> {
            // Keep dialog open when clicking TEST POWER
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                LcnDatabaseHelper db = LcnDatabaseHelper.getInstance(this);
                SensyIrManager ir = SensyIrManager.getInstance(this);
                new Thread(() -> {
                    java.util.Map<String, String> keys = db.getKeyCodes(current.id);
                    String powerHex = keys.get("POWER");
                    if (powerHex != null && !powerHex.isEmpty()) {
                        ir.transmitNecAsync(powerHex, current.bitLength, () ->
                                Toast.makeText(this, "Blasted POWER for Remote #" + current.id, Toast.LENGTH_SHORT).show()
                        );
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "No POWER key for Remote #" + current.id, Toast.LENGTH_SHORT).show());
                    }
                }).start();
            });
        });

        dialog.show();
    }

    private void updateStbProviderText(TextView view, SharedPreferences stbPrefs) {
        String title = stbPrefs.getString("provider_title", "Kerala Vision Digital");
        int rId = stbPrefs.getInt("remote_id", 43);
        view.setText("Provider: " + title + " (Remote #" + rId + ")");
    }

    private void updateAvInputText(TextView view, SharedPreferences stbPrefs) {
        String inputId = stbPrefs.getString("av_input_id", "com.xiaomi.mitv.tvinput/.TvInputService/HW3");
        String friendly = "AV1 (HW3)";
        if (inputId.contains("HW1")) friendly = "HDMI 1 (HW1)";
        else if (inputId.contains("HW2")) friendly = "HDMI 2 (HW2)";
        else if (inputId.contains("HW4")) friendly = "HDMI 3 (HW4)";
        view.setText("Input Source: " + friendly);
    }

    private void showAvInputPicker(TextView view, SharedPreferences stbPrefs) {
        final String[] names = {"AV1 Composite (HW3 - Default)", "HDMI 1 (HW1)", "HDMI 2 (HW2)", "HDMI 3 (HW4)"};
        final String[] ids = {
                "com.xiaomi.mitv.tvinput/.TvInputService/HW3",
                "com.xiaomi.mitv.tvinput/.TvInputService/HW1",
                "com.xiaomi.mitv.tvinput/.TvInputService/HW2",
                "com.xiaomi.mitv.tvinput/.TvInputService/HW4"
        };
        new AlertDialog.Builder(this)
                .setTitle("Select Live TV Input Source")
                .setItems(names, (d, which) -> {
                    stbPrefs.edit().putString("av_input_id", ids[which]).apply();
                    updateAvInputText(view, stbPrefs);
                    Toast.makeText(this, "Set TV input to " + names[which], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showResetChannelsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Channel Customizations")
                .setMessage("Reset all custom channel numbers, usage sorting, and favourites back to default?")
                .setPositiveButton("Reset", (d, w) -> {
                    getSharedPreferences("user_channel_overrides", MODE_PRIVATE).edit().clear().apply();
                    SharedPreferences stbPrefs = getSharedPreferences("stb_settings", MODE_PRIVATE);
                    SharedPreferences.Editor ed = stbPrefs.edit();
                    for (String k : stbPrefs.getAll().keySet()) {
                        if (k.startsWith("ch_use_")) ed.remove(k);
                    }
                    ed.apply();
                    Toast.makeText(this, "All channels reset to defaults", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCompanionPairingDialog() {
        TvCommandServer server = TvCommandServer.getInstance(this);
        String pin = server.getOrGeneratePairingPin();
        String ip = getDeviceIp();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pair Mobile Companion App")
                .setMessage("1. Open Companion App on your mobile phone.\n"
                        + "2. Tap Settings / Pair with TV.\n"
                        + "3. Enter this 6-digit Pairing Code:\n\n"
                        + "      [   " + pin + "   ]\n\n"
                        + "TV Address: " + ip + ":" + TvCommandServer.DEFAULT_PORT + "\n"
                        + "(Expires in 15 minutes)")
                .setPositiveButton("Done", null)
                .setNeutralButton("Generate New PIN", null)
                .create();

        dialog.setOnShowListener(dInterface -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String newPin = server.generateNewPairingPin();
                dialog.setMessage("1. Open Companion App on your mobile phone.\n"
                        + "2. Tap Settings / Pair with TV.\n"
                        + "3. Enter this 6-digit Pairing Code:\n\n"
                        + "      [   " + newPin + "   ]\n\n"
                        + "TV Address: " + ip + ":" + TvCommandServer.DEFAULT_PORT + "\n"
                        + "(Expires in 15 minutes)");
                Toast.makeText(this, "Generated new PIN: " + newPin, Toast.LENGTH_SHORT).show();
            });
        });
        dialog.show();
    }

    private void updateForgetDevicesText(TextView view) {
        int count = TvCommandServer.getInstance(this).getPairedDeviceCount();
        view.setText("Forget Paired Devices (" + count + " paired)");
    }

    private void showForgetDevicesDialog(TextView view) {
        int count = TvCommandServer.getInstance(this).getPairedDeviceCount();
        new AlertDialog.Builder(this)
                .setTitle("Forget Paired Companion Devices")
                .setMessage("Unpair all " + count + " companion mobile app(s)? They will no longer be able to blast IR or tune TV until re-paired.")
                .setPositiveButton("Unpair All", (d, w) -> {
                    TvCommandServer.getInstance(this).unpairAllDevices();
                    updateForgetDevicesText(view);
                    Toast.makeText(this, "All mobile devices forgotten", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getDeviceIp() {
        try {
            java.util.List<java.net.NetworkInterface> interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces());
            for (java.net.NetworkInterface intf : interfaces) {
                java.util.List<java.net.InetAddress> addrs = java.util.Collections.list(intf.getInetAddresses());
                for (java.net.InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "100.97.35.51";
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
