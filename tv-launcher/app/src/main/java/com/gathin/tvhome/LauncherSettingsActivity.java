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

        updateStbProviderText(stbProviderChoice, stbPrefs);
        stbProviderChoice.setOnClickListener(v -> showProviderPicker(stbProviderChoice, stbPrefs));

        pairRemoteChoice.setOnClickListener(v -> startPairingWizard(stbPrefs, stbProviderChoice));

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

        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
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
