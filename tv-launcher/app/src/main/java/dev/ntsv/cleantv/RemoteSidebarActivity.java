package dev.ntsv.cleantv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translucent popout sidebar activity for Android TV.
 * Slides over whatever is on screen (Live TV, AV1, HDMI, Apps), allowing full remote control,
 * channel browsing, and interactive candidate remote pairing while watching Live TV.
 */
public class RemoteSidebarActivity extends Activity {

    public static final String EXTRA_INITIAL_TAB = "extra_initial_tab";

    private LcnDatabaseHelper dbHelper;
    private SensyIrManager irManager;
    private SharedPreferences stbPrefs;

    private int activeProviderId = 648;
    private String activeProviderTitle = "Kerala Vision Digital Network";
    private int activeRemoteId = 43;
    private int activeRemoteBitLength = 32;

    private final Map<String, String> keyCodes = new HashMap<>();
    private final List<LcnDatabaseHelper.Channel> allChannels = new ArrayList<>();
    private final List<LcnDatabaseHelper.Channel> channels = new ArrayList<>();
    private final List<LcnDatabaseHelper.RemoteModel> candidateRemotes = new ArrayList<>();
    private int currentCandidateIndex = 0;
    private final android.util.SparseIntArray logoCache = new android.util.SparseIntArray();

    private SidebarChannelsAdapter channelsAdapter;
    private RecyclerView channelsList;
    private android.widget.EditText searchInput;
    private TextView statusText;

    private View tabChannels;
    private View tabRemote;
    private View tabPairing;
    private View channelsContainer;
    private View remoteContainer;
    private View pairingContainer;

    private TextView sidebarPairingProvider;
    private TextView sidebarPairingModel;
    private TextView sidebarPairingFeedback;
    private View btnSidebarTestPower;
    private View btnSidebarTestVolUp;
    private View btnSidebarTestChUp;
    private View btnSidebarTestChDown;
    private View btnSidebarPrevModel;
    private View btnSidebarNextModel;
    private View btnSidebarPairConfirm;
    private View btnSidebarChangeProvider;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_remote_sidebar);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.END);
        }

        dbHelper = LcnDatabaseHelper.getInstance(this);
        irManager = SensyIrManager.getInstance(this);
        stbPrefs = getSharedPreferences("stb_settings", Context.MODE_PRIVATE);

        activeProviderId = stbPrefs.getInt("provider_id", -1);
        activeProviderTitle = stbPrefs.getString("provider_title", "");
        activeRemoteId = stbPrefs.getInt("remote_id", -1);
        activeRemoteBitLength = stbPrefs.getInt("bit_length", 32);

        TextView title = findViewById(R.id.remote_title);
        TextView subtitle = findViewById(R.id.remote_subtitle);
        statusText = findViewById(R.id.remote_status_text);

        if (title != null) title.setText(!activeProviderTitle.isEmpty() ? activeProviderTitle : "Set-Top Box");
        if (subtitle != null) subtitle.setText(activeProviderId > 0 ? "AV1 Set-Top Box · Sorted by Most Used" : "No provider configured. Use Pairing tab to set up.");

        tabChannels = findViewById(R.id.tab_channels);
        tabRemote = findViewById(R.id.tab_remote);
        tabPairing = findViewById(R.id.tab_pairing);
        channelsContainer = findViewById(R.id.channels_container);
        remoteContainer = findViewById(R.id.remote_container);
        pairingContainer = findViewById(R.id.pairing_container);

        // Tab Switching on click and on D-Pad focus change
        if (tabChannels != null) {
            tabChannels.setOnClickListener(v -> switchTab(0));
            tabChannels.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) switchTab(0);
            });
        }
        if (tabRemote != null) {
            tabRemote.setOnClickListener(v -> switchTab(1));
            tabRemote.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) switchTab(1);
            });
        }
        if (tabPairing != null) {
            tabPairing.setOnClickListener(v -> switchTab(2));
            tabPairing.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) switchTab(2);
            });
        }

        // Channels Recycler & Search
        searchInput = findViewById(R.id.sidebar_channel_search);
        if (searchInput != null) {
            searchInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterChannels(s != null ? s.toString() : "");
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        channelsList = findViewById(R.id.sidebar_channels_list);
        if (channelsList != null) {
            channelsList.setLayoutManager(new LinearLayoutManager(this));
            channelsAdapter = new SidebarChannelsAdapter(channels, this::onChannelClicked, this::onChannelLongClicked);
            channelsList.setAdapter(channelsAdapter);
        }

        // Bind button actions for Remote tab
        bindKey(R.id.btn_power, "POWER");
        bindKey(R.id.btn_mute, "VOL MUTE");
        bindKey(R.id.btn_vol_up, "VOL UP");
        bindKey(R.id.btn_vol_down, "VOL DOWN");
        bindKey(R.id.btn_ch_up, "CH UP");
        bindKey(R.id.btn_ch_down, "CH DOWN");
        bindKey(R.id.btn_menu, "MENU");
        bindKey(R.id.btn_back, "BACK");
        bindKey(R.id.btn_exit, "EXIT");
        bindKey(R.id.btn_recall, "RECALL");
        bindKey(R.id.btn_info, "INFO");
        bindKey(R.id.btn_ok, "OK");

        bindKey(R.id.btn_num_0, "0");
        bindKey(R.id.btn_num_1, "1");
        bindKey(R.id.btn_num_2, "2");
        bindKey(R.id.btn_num_3, "3");
        bindKey(R.id.btn_num_4, "4");
        bindKey(R.id.btn_num_5, "5");
        bindKey(R.id.btn_num_6, "6");
        bindKey(R.id.btn_num_7, "7");
        bindKey(R.id.btn_num_8, "8");
        bindKey(R.id.btn_num_9, "9");

        View closeBtn = findViewById(R.id.btn_close_remote);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> finish());
        }

        // Setup Pairing Tab UI
        sidebarPairingProvider = findViewById(R.id.sidebar_pairing_provider);
        sidebarPairingModel = findViewById(R.id.sidebar_pairing_model);
        sidebarPairingFeedback = findViewById(R.id.sidebar_pairing_feedback);
        btnSidebarTestPower = findViewById(R.id.btn_sidebar_test_power);
        btnSidebarTestVolUp = findViewById(R.id.btn_sidebar_test_volup);
        btnSidebarTestChUp = findViewById(R.id.btn_sidebar_test_chup);
        btnSidebarTestChDown = findViewById(R.id.btn_sidebar_test_chdown);
        btnSidebarPrevModel = findViewById(R.id.btn_sidebar_prev_model);
        btnSidebarNextModel = findViewById(R.id.btn_sidebar_next_model);
        btnSidebarPairConfirm = findViewById(R.id.btn_sidebar_pair_confirm);
        btnSidebarChangeProvider = findViewById(R.id.btn_sidebar_change_provider);

        setupPairingControls();

        // Tap backdrop to dismiss (pointer/touch only - NEVER focusable on TV D-pad)
        View backdrop = findViewById(R.id.sidebar_backdrop);
        if (backdrop != null) {
            backdrop.setOnClickListener(v -> finish());
            backdrop.setFocusable(false);
            backdrop.setFocusableInTouchMode(false);
        }

        int initialTab = getIntent().getIntExtra(EXTRA_INITIAL_TAB, 0);
        switchTab(initialTab);
        loadChannelsAndCodes();

        // Ensure active tab receives crisp initial focus
        View initialFocus = initialTab == 2 ? tabPairing : (initialTab == 1 ? tabRemote : tabChannels);
        if (initialFocus != null) {
            initialFocus.requestFocus();
        }
        handler.postDelayed(() -> {
            if (initialFocus != null && !initialFocus.hasFocus()) {
                initialFocus.requestFocus();
            }
        }, 120);
    }

    private void switchTab(int tabIndex) {
        if (tabChannels != null) tabChannels.setSelected(tabIndex == 0);
        if (tabRemote != null) tabRemote.setSelected(tabIndex == 1);
        if (tabPairing != null) tabPairing.setSelected(tabIndex == 2);

        if (channelsContainer != null) channelsContainer.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        if (remoteContainer != null) remoteContainer.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        if (pairingContainer != null) pairingContainer.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);

        if (tabIndex == 2) {
            loadCandidateRemotes();
        }
    }

    private void setupPairingControls() {
        if (btnSidebarTestChUp != null) {
            btnSidebarTestChUp.setOnClickListener(v -> testCandidateKey("CH UP", "CH+"));
        }
        if (btnSidebarTestChDown != null) {
            btnSidebarTestChDown.setOnClickListener(v -> testCandidateKey("CH DOWN", "CH−"));
        }
        if (btnSidebarTestPower != null) {
            btnSidebarTestPower.setOnClickListener(v -> testCandidateKey("POWER", "POWER"));
        }
        if (btnSidebarTestVolUp != null) {
            btnSidebarTestVolUp.setOnClickListener(v -> testCandidateKey("VOL UP", "VOL+"));
        }
        if (btnSidebarPrevModel != null) {
            btnSidebarPrevModel.setOnClickListener(v -> {
                if (candidateRemotes.isEmpty()) return;
                currentCandidateIndex = (currentCandidateIndex - 1 + candidateRemotes.size()) % candidateRemotes.size();
                updatePairingUi();
            });
        }
        if (btnSidebarNextModel != null) {
            btnSidebarNextModel.setOnClickListener(v -> {
                if (candidateRemotes.isEmpty()) return;
                currentCandidateIndex = (currentCandidateIndex + 1) % candidateRemotes.size();
                updatePairingUi();
            });
        }
        if (btnSidebarPairConfirm != null) {
            btnSidebarPairConfirm.setOnClickListener(v -> {
                if (candidateRemotes.isEmpty()) return;
                LcnDatabaseHelper.RemoteModel current = candidateRemotes.get(currentCandidateIndex);
                activeRemoteId = current.id;
                activeRemoteBitLength = current.bitLength;
                stbPrefs.edit()
                        .putInt("remote_id", current.id)
                        .putInt("bit_length", current.bitLength)
                        .apply();
                getSharedPreferences("stb_remote_prefs", Context.MODE_PRIVATE).edit()
                        .putInt("remote_id", current.id)
                        .putInt("bit_length", current.bitLength)
                        .apply();

                Toast.makeText(this, "Paired Remote #" + current.id + " for Live TV!", Toast.LENGTH_SHORT).show();
                loadChannelsAndCodes();
                switchTab(1);
            });
        }
        if (btnSidebarChangeProvider != null) {
            btnSidebarChangeProvider.setOnClickListener(v -> {
                Intent i = new Intent(this, StbSetupActivity.class);
                i.putExtra(StbSetupActivity.EXTRA_START_PAIRING, false);
                startActivity(i);
            });
        }
    }

    private void loadCandidateRemotes() {
        new Thread(() -> {
            List<LcnDatabaseHelper.RemoteModel> list = dbHelper.getRemotesForProvider(activeProviderId);
            handler.post(() -> {
                candidateRemotes.clear();
                if (list != null && !list.isEmpty()) {
                    candidateRemotes.addAll(list);
                    currentCandidateIndex = 0;
                    for (int i = 0; i < candidateRemotes.size(); i++) {
                        if (candidateRemotes.get(i).id == activeRemoteId) {
                            currentCandidateIndex = i;
                            break;
                        }
                    }
                }
                updatePairingUi();
            });
        }).start();
    }

    private void updatePairingUi() {
        if (sidebarPairingProvider != null) sidebarPairingProvider.setText(activeProviderTitle);
        if (candidateRemotes.isEmpty()) {
            if (sidebarPairingModel != null) sidebarPairingModel.setText("No remote profiles found for provider");
            if (sidebarPairingFeedback != null) sidebarPairingFeedback.setText("Using standard default remote");
            return;
        }
        LcnDatabaseHelper.RemoteModel current = candidateRemotes.get(currentCandidateIndex);
        int total = candidateRemotes.size();
        if (sidebarPairingModel != null) {
            sidebarPairingModel.setText("Candidate Model " + (currentCandidateIndex + 1) + " of " + total
                    + " (Remote #" + current.id + ", " + current.bitLength + "-bit NEC)");
        }
        if (sidebarPairingFeedback != null) {
            sidebarPairingFeedback.setText("Watch Live TV on left while testing buttons");
        }
    }

    private void testCandidateKey(String keyName, String display) {
        if (candidateRemotes.isEmpty()) return;
        LcnDatabaseHelper.RemoteModel current = candidateRemotes.get(currentCandidateIndex);
        if (sidebarPairingFeedback != null) {
            sidebarPairingFeedback.setText("Sending " + display + "...");
        }

        new Thread(() -> {
            Map<String, String> keys = dbHelper.getKeyCodes(current.id);
            String hex = keys.get(keyName);
            handler.post(() -> {
                if (hex != null && !hex.isEmpty()) {
                    irManager.transmitNecAsync(hex, current.bitLength, () -> {
                        if (sidebarPairingFeedback != null) {
                            sidebarPairingFeedback.setText("Blasted: " + display + " [" + hex + "]");
                        }
                    });
                } else {
                    if (sidebarPairingFeedback != null) {
                        sidebarPairingFeedback.setText("Key " + display + " not on model #" + current.id);
                    }
                }
            });
        }).start();
    }

    private void loadChannelsAndCodes() {
        new Thread(() -> {
            Map<String, String> codes = dbHelper.getKeyCodes(activeRemoteId);
            String customJson = stbPrefs.getString("custom_keys_json", null);
            if (customJson != null) {
                try {
                    JSONObject obj = new JSONObject(customJson);
                    if (obj.has("keys")) {
                        JSONObject kObj = obj.getJSONObject("keys");
                        Iterator<String> it = kObj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            codes.put(k.toUpperCase(Locale.ROOT), kObj.getString(k));
                        }
                    }
                } catch (Exception ignored) {}
            }

            List<LcnDatabaseHelper.Channel> list = dbHelper.getChannelsForProvider(activeProviderId, 500);

            handler.post(() -> {
                keyCodes.clear();
                keyCodes.putAll(codes);
                allChannels.clear();
                allChannels.addAll(list);
                String query = (searchInput != null && searchInput.getText() != null)
                        ? searchInput.getText().toString() : "";
                filterChannels(query);
                if (statusText != null) {
                    statusText.setText(allChannels.size() + " channels · Remote #" + activeRemoteId);
                }
            });
        }).start();
    }

    private void filterChannels(String query) {
        channels.clear();
        channels.addAll(dbHelper.filterChannels(allChannels, query));
        if (channelsAdapter != null) {
            channelsAdapter.notifyDataSetChanged();
        }
    }

    private void bindKey(int viewId, String keyName) {
        View v = findViewById(viewId);
        if (v == null) return;
        v.setOnClickListener(view -> {
            String hex = keyCodes.get(keyName);
            if (hex != null && !hex.isEmpty()) {
                if (statusText != null) statusText.setText("Blasting " + keyName + " (" + hex + ")...");
                irManager.transmitNecAsync(hex, activeRemoteBitLength, () -> {
                    if (statusText != null) {
                        statusText.setText("Sent: " + keyName);
                    }
                });
            } else {
                if (statusText != null) statusText.setText("No code for " + keyName);
            }
        });
    }

    private void onChannelClicked(LcnDatabaseHelper.Channel ch) {
        String effectiveLcn = ch.getDisplayLcn();
        String prefKey = "ch_use_" + activeProviderId + "_" + effectiveLcn;
        int current = stbPrefs.getInt(prefKey, 0);
        stbPrefs.edit().putInt(prefKey, current + 1).apply();

        if (statusText != null) statusText.setText("Tuning LCN " + effectiveLcn + " " + ch.name + "...");
        irManager.tuneChannelAsync(effectiveLcn, keyCodes, activeRemoteBitLength, () -> {
            if (statusText != null) statusText.setText("Tuned: " + effectiveLcn + " " + ch.name);
            handler.postDelayed(this::finish, 600);
        });
    }

    private void onChannelLongClicked(LcnDatabaseHelper.Channel ch) {
        String favOption = ch.isFavorite ? "Remove from Favourites" : "Mark as Favourite";
        String[] options = new String[]{favOption, "Edit Channel Number (LCN)", "Tune Now"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(ch.name + " (LCN " + ch.getDisplayLcn() + ")")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        boolean isFav = dbHelper.toggleFavorite(activeProviderId, ch);
                        Toast.makeText(this, isFav ? "Added to favourites" : "Removed from favourites", Toast.LENGTH_SHORT).show();
                        loadChannelsAndCodes();
                    } else if (which == 1) {
                        showEditLcnDialog(ch);
                    } else if (which == 2) {
                        onChannelClicked(ch);
                    }
                })
                .show();
    }

    private void showEditLcnDialog(LcnDatabaseHelper.Channel ch) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(ch.getDisplayLcn());
        input.setSelection(input.getText().length());
        input.setTextColor(0xFFFFFFFF);
        input.setBackgroundColor(0xFF151D2A);
        input.setPadding(32, 24, 32, 24);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Edit LCN for " + ch.name)
                .setMessage("Original default: " + ch.lcn)
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newLcn = input.getText().toString().trim();
                    if (!newLcn.isEmpty()) {
                        dbHelper.setCustomLcn(activeProviderId, ch.channelId, newLcn);
                        ch.customLcn = newLcn;
                        Toast.makeText(this, "Updated LCN to " + newLcn, Toast.LENGTH_SHORT).show();
                        loadChannelsAndCodes();
                    }
                })
                .setNeutralButton("Reset Default", (dialog, which) -> {
                    dbHelper.setCustomLcn(activeProviderId, ch.channelId, "");
                    ch.customLcn = null;
                    Toast.makeText(this, "Reset to default LCN " + ch.lcn, Toast.LENGTH_SHORT).show();
                    loadChannelsAndCodes();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    // RecyclerView Adapter for Channels tab
    private class SidebarChannelsAdapter extends RecyclerView.Adapter<SidebarChannelsAdapter.ViewHolder> {
        interface Listener {
            void onChannelClick(LcnDatabaseHelper.Channel ch);
        }

        interface LongListener {
            void onChannelLongClick(LcnDatabaseHelper.Channel ch);
        }

        private final List<LcnDatabaseHelper.Channel> list;
        private final Listener listener;
        private final LongListener longListener;

        SidebarChannelsAdapter(List<LcnDatabaseHelper.Channel> list, Listener listener, LongListener longListener) {
            this.list = list;
            this.listener = listener;
            this.longListener = longListener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel_row, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LcnDatabaseHelper.Channel ch = list.get(position);
            holder.lcn.setText(ch.getDisplayLcn());
            holder.name.setText(ch.name);

            int resId = 0;
            if (ch.channelId > 0) {
                int idx = logoCache.indexOfKey(ch.channelId);
                if (idx >= 0) {
                    resId = logoCache.valueAt(idx);
                } else {
                    resId = getResources().getIdentifier("channel_" + ch.channelId, "drawable", getPackageName());
                    logoCache.put(ch.channelId, resId);
                }
            }
            if (resId != 0) {
                holder.logo.setImageResource(resId);
                holder.logo.setVisibility(View.VISIBLE);
            } else {
                holder.logo.setImageDrawable(null);
                holder.logo.setVisibility(View.GONE);
            }

            int useCount = stbPrefs.getInt("ch_use_" + activeProviderId + "_" + ch.getDisplayLcn(), 0);
            if (useCount > 0) {
                holder.genre.setText((ch.genre != null ? ch.genre : ""));
                holder.genre.setTextColor(0xFF38BDF8);
            } else {
                holder.genre.setText(ch.genre != null ? ch.genre : "");
                holder.genre.setTextColor(0x8CFFFFFF);
            }

            if (holder.fav != null) {
                holder.fav.setVisibility(ch.isFavorite ? View.VISIBLE : View.GONE);
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onChannelClick(ch);
            });

            holder.itemView.setOnLongClickListener(v -> {
                if (longListener != null) longListener.onChannelLongClick(ch);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView lcn;
            final ImageView logo;
            final TextView name;
            final TextView genre;
            final ImageView fav;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                lcn = itemView.findViewById(R.id.channel_lcn);
                logo = itemView.findViewById(R.id.channel_logo);
                name = itemView.findViewById(R.id.channel_name);
                genre = itemView.findViewById(R.id.channel_genre);
                fav = itemView.findViewById(R.id.channel_fav);
            }
        }
    }
}
