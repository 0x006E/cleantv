package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dedicated STB Provider Search & Remote Pairing Activity.
 * Live-searches 7,000+ providers with top Indian operators by default,
 * and includes a comprehensive interactive IR pairing wizard.
 */
public class StbSetupActivity extends Activity {

    public static final String EXTRA_START_PAIRING = "extra_start_pairing";

    private View btnModeProvider;
    private View btnModePair;
    private View panelProvider;
    private View panelPair;

    private EditText searchInput;
    private RecyclerView providersRecycler;
    private ProvidersAdapter providersAdapter;

    private TextView wizardProviderName;
    private TextView wizardRemoteCandidate;
    private TextView wizardFeedback;
    private View btnTestPower;
    private View btnTestVolUp;
    private View btnTestChUp;
    private View btnNextRemote;
    private View btnPairConfirm;

    private LcnDatabaseHelper dbHelper;
    private SensyIrManager irManager;
    private SharedPreferences stbPrefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<LcnDatabaseHelper.RemoteModel> candidateRemotes = new ArrayList<>();
    private int currentCandidateIndex = 0;
    private int activeProviderId = -1;
    private String activeProviderTitle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Avoid virtual keyboard auto-popping on launch on Android TV
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setContentView(R.layout.activity_stb_setup);

        dbHelper = LcnDatabaseHelper.getInstance(this);
        irManager = SensyIrManager.getInstance(this);
        stbPrefs = getSharedPreferences("stb_settings", Context.MODE_PRIVATE);

        activeProviderId = stbPrefs.getInt("provider_id", -1);
        activeProviderTitle = stbPrefs.getString("provider_title", "");

        btnModeProvider = findViewById(R.id.btn_mode_provider);
        btnModePair = findViewById(R.id.btn_mode_pair);
        panelProvider = findViewById(R.id.panel_provider);
        panelPair = findViewById(R.id.panel_pair);

        searchInput = findViewById(R.id.search_provider_input);
        providersRecycler = findViewById(R.id.providers_recycler);

        wizardProviderName = findViewById(R.id.wizard_provider_name);
        wizardRemoteCandidate = findViewById(R.id.wizard_remote_candidate);
        wizardFeedback = findViewById(R.id.wizard_feedback);
        btnTestPower = findViewById(R.id.btn_test_power);
        btnTestVolUp = findViewById(R.id.btn_test_vol_up);
        btnTestChUp = findViewById(R.id.btn_test_ch_up);
        btnNextRemote = findViewById(R.id.btn_next_remote);
        btnPairConfirm = findViewById(R.id.btn_pair_confirm);

        // Tab buttons
        btnModeProvider.setOnClickListener(v -> switchMode(false));
        btnModePair.setOnClickListener(v -> switchMode(true));

        btnModeProvider.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    searchInput.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    btnModePair.requestFocus();
                    return true;
                }
            }
            return false;
        });

        btnModePair.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (panelPair.getVisibility() == View.VISIBLE) {
                        btnTestPower.requestFocus();
                    } else {
                        searchInput.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    btnModeProvider.requestFocus();
                    return true;
                }
            }
            return false;
        });

        // Setup RecyclerView
        providersRecycler.setLayoutManager(new LinearLayoutManager(this));
        providersAdapter = new ProvidersAdapter(this::onProviderSelected, () -> searchInput.requestFocus());
        providersRecycler.setAdapter(providersAdapter);

        // Search text watcher
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                performSearch(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // D-Pad navigation for search box
        searchInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (providersRecycler.getChildCount() > 0) {
                        providersRecycler.getChildAt(0).requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    btnModeProvider.requestFocus();
                    return true;
                }
            }
            return false;
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                if (providersRecycler.getChildCount() > 0) {
                    providersRecycler.getChildAt(0).requestFocus();
                    return true;
                }
            }
            return false;
        });

        setupWizardActions();

        // Check if intent wants direct pairing
        boolean startPairing = getIntent().getBooleanExtra(EXTRA_START_PAIRING, false);
        if (startPairing && activeProviderId > 0) {
            loadCandidateRemotes(activeProviderId, true);
        } else {
            switchMode(false);
            performSearch("");
        }
    }

    private void switchMode(boolean pairMode) {
        btnModeProvider.setSelected(!pairMode);
        btnModePair.setSelected(pairMode);

        panelProvider.setVisibility(pairMode ? View.GONE : View.VISIBLE);
        panelPair.setVisibility(pairMode ? View.VISIBLE : View.GONE);

        if (pairMode) {
            loadCandidateRemotes(activeProviderId, false);
            if (btnTestPower != null) btnTestPower.requestFocus();
        } else {
            if (searchInput != null) searchInput.requestFocus();
        }
    }

    private void performSearch(String query) {
        new Thread(() -> {
            List<LcnDatabaseHelper.Provider> results;
            if (query.trim().isEmpty()) {
                results = getTopIndianProviders();
            } else {
                results = dbHelper.searchProviders(query, 60);
            }
            handler.post(() -> providersAdapter.setProviders(results, query.trim().isEmpty()));
        }).start();
    }

    private List<LcnDatabaseHelper.Provider> getTopIndianProviders() {
        List<LcnDatabaseHelper.Provider> top = new ArrayList<>();
        int[] topIds = {648, 42, 37, 41, 39, 643, 178};
        for (int id : topIds) {
            LcnDatabaseHelper.Provider p = dbHelper.getProviderById(id);
            if (p != null) {
                top.add(p);
            }
        }
        if (top.isEmpty()) {
            top.add(new LcnDatabaseHelper.Provider(648, "Kerala Vision Digital Network", "CABLE", 43, "Kerala Vision"));
            top.add(new LcnDatabaseHelper.Provider(42, "Tata Play (Tata Sky)", "DTH", 14, "Tata Play"));
            top.add(new LcnDatabaseHelper.Provider(37, "Airtel Digital TV", "DTH", 17, "Airtel"));
            top.add(new LcnDatabaseHelper.Provider(41, "Sun Direct DTH", "DTH", 18, "Sun Direct"));
            top.add(new LcnDatabaseHelper.Provider(39, "Dish TV DTH", "DTH", 16, "Dish TV"));
            top.add(new LcnDatabaseHelper.Provider(643, "Asianet Satellite Digital", "CABLE", 6, "Asianet"));
            top.add(new LcnDatabaseHelper.Provider(178, "Den Digital", "CABLE", 2, "Den"));
        }
        return top;
    }

    private void onProviderSelected(LcnDatabaseHelper.Provider p) {
        activeProviderId = p.id;
        activeProviderTitle = p.title;

        stbPrefs.edit()
                .putInt("provider_id", p.id)
                .putString("provider_title", p.title)
                .apply();
        getSharedPreferences("stb_settings", Context.MODE_PRIVATE).edit()
                .putInt("provider_id", p.id)
                .putString("provider_title", p.title)
                .apply();

        Toast.makeText(this, "Selected " + p.title, Toast.LENGTH_SHORT).show();
        loadCandidateRemotes(p.id, true);
    }

    private void loadCandidateRemotes(int providerId, boolean autoSwitch) {
        new Thread(() -> {
            List<LcnDatabaseHelper.RemoteModel> list = dbHelper.getRemotesForProvider(providerId);
            handler.post(() -> {
                candidateRemotes.clear();
                if (list != null && !list.isEmpty()) {
                    candidateRemotes.addAll(list);
                }
                currentCandidateIndex = 0;
                updateWizardUi();
                if (autoSwitch) {
                    switchMode(true);
                }
            });
        }).start();
    }

    private void updateWizardUi() {
        wizardProviderName.setText(activeProviderTitle);
        if (candidateRemotes.isEmpty()) {
            wizardRemoteCandidate.setText("No remote models found for this provider");
            wizardFeedback.setText("Using default standard profile");
            btnPairConfirm.setEnabled(true);
            return;
        }

        LcnDatabaseHelper.RemoteModel current = candidateRemotes.get(currentCandidateIndex);
        int total = candidateRemotes.size();
        wizardRemoteCandidate.setText("Candidate Model " + (currentCandidateIndex + 1) + " of " + total
                + " (Remote #" + current.id + ", " + current.bitLength + "-bit NEC)");
        wizardFeedback.setText("Ready to test. Press 'Test Power' to blast IR.");
    }

    private void setupWizardActions() {
        btnTestPower.setOnClickListener(v -> testKey("POWER", "POWER"));
        btnTestVolUp.setOnClickListener(v -> testKey("VOL UP", "VOL+"));
        btnTestChUp.setOnClickListener(v -> testKey("CH UP", "CH+"));

        btnNextRemote.setOnClickListener(v -> {
            if (candidateRemotes.isEmpty()) return;
            currentCandidateIndex = (currentCandidateIndex + 1) % candidateRemotes.size();
            updateWizardUi();
        });

        btnPairConfirm.setOnClickListener(v -> {
            if (candidateRemotes.isEmpty()) {
                finish();
                return;
            }
            LcnDatabaseHelper.RemoteModel current = candidateRemotes.get(currentCandidateIndex);
            stbPrefs.edit()
                    .putInt("remote_id", current.id)
                    .putInt("bit_length", current.bitLength)
                    .apply();
            getSharedPreferences("stb_settings", Context.MODE_PRIVATE).edit()
                    .putInt("remote_id", current.id)
                    .putInt("bit_length", current.bitLength)
                    .apply();

            setResult(RESULT_OK);
            Toast.makeText(this, "Successfully paired with Remote #" + current.id, Toast.LENGTH_LONG).show();
            finish();
        });

        // Set D-pad focus connections in wizard
        btnTestPower.setNextFocusRightId(R.id.btn_test_vol_up);
        btnTestPower.setNextFocusUpId(R.id.btn_mode_pair);

        btnTestVolUp.setNextFocusLeftId(R.id.btn_test_power);
        btnTestVolUp.setNextFocusRightId(R.id.btn_test_ch_up);
        btnTestVolUp.setNextFocusUpId(R.id.btn_mode_pair);

        btnTestChUp.setNextFocusLeftId(R.id.btn_test_vol_up);
        btnTestChUp.setNextFocusRightId(R.id.btn_next_remote);
        btnTestChUp.setNextFocusUpId(R.id.btn_mode_pair);

        btnNextRemote.setNextFocusLeftId(R.id.btn_test_ch_up);
        btnNextRemote.setNextFocusRightId(R.id.btn_pair_confirm);
        btnNextRemote.setNextFocusUpId(R.id.btn_mode_pair);

        btnPairConfirm.setNextFocusLeftId(R.id.btn_next_remote);
        btnPairConfirm.setNextFocusUpId(R.id.btn_mode_pair);
    }

    private void testKey(String keyName, String display) {
        if (candidateRemotes.isEmpty()) return;
        LcnDatabaseHelper.RemoteModel current = candidateRemotes.get(currentCandidateIndex);
        wizardFeedback.setText("Sending " + display + "...");

        new Thread(() -> {
            Map<String, String> keys = dbHelper.getKeyCodes(current.id);
            String hex = keys.get(keyName);
            handler.post(() -> {
                if (hex != null && !hex.isEmpty()) {
                    irManager.transmitNecAsync(hex, current.bitLength, () -> {
                        wizardFeedback.setText("Signal sent: " + display + " [" + hex + "]");
                    });
                } else {
                    wizardFeedback.setText("Key " + display + " not available in this candidate");
                }
            });
        }).start();
    }

    // RecyclerView Adapter
    private static class ProvidersAdapter extends RecyclerView.Adapter<ProvidersAdapter.ViewHolder> {

        interface Listener {
            void onProviderClick(LcnDatabaseHelper.Provider p);
        }

        interface FocusUpListener {
            void onFocusUpFromTop();
        }

        private final List<LcnDatabaseHelper.Provider> items = new ArrayList<>();
        private final Listener listener;
        private final FocusUpListener focusUpListener;
        private boolean isTopList = false;

        ProvidersAdapter(Listener listener, FocusUpListener focusUpListener) {
            this.listener = listener;
            this.focusUpListener = focusUpListener;
        }

        void setProviders(List<LcnDatabaseHelper.Provider> list, boolean isTop) {
            items.clear();
            if (list != null) items.addAll(list);
            this.isTopList = isTop;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider_row, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LcnDatabaseHelper.Provider p = items.get(position);
            holder.title.setText(p.title);
            holder.type.setText(p.type != null ? p.type : "CABLE / DTH");
            holder.badge.setVisibility(isTopList ? View.VISIBLE : View.GONE);

            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.02f : 1.0f).scaleY(hasFocus ? 1.02f : 1.0f).setDuration(120).start();
                holder.title.setTextColor(hasFocus ? 0xFFFFFFFF : 0xE6FFFFFF);
            });

            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && holder.getAdapterPosition() == 0) {
                        if (focusUpListener != null) {
                            focusUpListener.onFocusUpFromTop();
                            return true;
                        }
                    }
                }
                return false;
            });

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onProviderClick(p);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView type;
            final TextView badge;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.provider_row_title);
                type = itemView.findViewById(R.id.provider_row_type);
                badge = itemView.findViewById(R.id.provider_row_badge);
            }
        }
    }
}
