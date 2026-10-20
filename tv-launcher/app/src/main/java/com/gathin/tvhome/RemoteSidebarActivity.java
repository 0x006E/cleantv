package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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
 * Translucent activity overlay displaying the Apple TV-styled AV1 STB remote and channel browser.
 * Accessible from the home grid, top bar, or via long-press on KEYCODE_DPAD_CENTER / MENU from any input.
 */
public class RemoteSidebarActivity extends Activity {

    private final Map<String, String> keyCodes = new HashMap<>();
    private final List<LcnDatabaseHelper.Channel> channels = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int activeProviderId = 43;
    private String activeProviderTitle = "Kerala Vision Digital";
    private int activeRemoteId = 43;
    private int activeRemoteBitLength = 32;

    private LcnDatabaseHelper dbHelper;
    private SensyIrManager irManager;
    private SharedPreferences stbPrefs;

    private TextView statusText;
    private View tabChannels;
    private View tabRemote;
    private View channelsContainer;
    private View remoteContainer;
    private RecyclerView channelsList;
    private SidebarChannelsAdapter channelsAdapter;

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

        activeProviderId = stbPrefs.getInt("provider_id", 43);
        activeProviderTitle = stbPrefs.getString("provider_title", "Kerala Vision Digital");
        activeRemoteId = stbPrefs.getInt("remote_id", 43);
        activeRemoteBitLength = stbPrefs.getInt("bit_length", 32);

        TextView title = findViewById(R.id.remote_title);
        TextView subtitle = findViewById(R.id.remote_subtitle);
        statusText = findViewById(R.id.remote_status_text);

        if (title != null) title.setText(activeProviderTitle);
        if (subtitle != null) subtitle.setText("AV1 Set-Top Box · Sorted by Most Used");

        tabChannels = findViewById(R.id.tab_channels);
        tabRemote = findViewById(R.id.tab_remote);
        channelsContainer = findViewById(R.id.channels_container);
        remoteContainer = findViewById(R.id.remote_container);

        // Tab Switching
        if (tabChannels != null) {
            tabChannels.setSelected(true);
            tabChannels.setOnClickListener(v -> switchTab(true));
        }
        if (tabRemote != null) {
            tabRemote.setOnClickListener(v -> switchTab(false));
        }

        // Channels Recycler
        channelsList = findViewById(R.id.sidebar_channels_list);
        if (channelsList != null) {
            channelsList.setLayoutManager(new LinearLayoutManager(this));
            channelsAdapter = new SidebarChannelsAdapter(channels, this::onChannelClicked);
            channelsList.setAdapter(channelsAdapter);
        }

        // Bind button actions for Remote tab
        bindKey(R.id.btn_power, "POWER");
        bindKey(R.id.btn_mute, "VOL MUTE");
        bindKey(R.id.btn_vol_up, "VOL UP");
        bindKey(R.id.btn_vol_down, "VOL DOWN");
        bindKey(R.id.btn_ch_up, "CH UP");
        bindKey(R.id.btn_ch_down, "CH DOWN");
        bindKey(R.id.btn_dpad_up, "UP");
        bindKey(R.id.btn_dpad_down, "DOWN");
        bindKey(R.id.btn_dpad_left, "LEFT");
        bindKey(R.id.btn_dpad_right, "RIGHT");
        bindKey(R.id.btn_dpad_ok, "OK");
        bindKey(R.id.btn_menu, "MENU");
        bindKey(R.id.btn_back, "BACK");
        bindKey(R.id.btn_exit, "EXIT");

        bindKey(R.id.btn_recall, "RECALL");

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

        // Tap backdrop to dismiss
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.setOnClickListener(v -> finish());
        }

        loadChannelsAndCodes();
    }

    private void switchTab(boolean showChannels) {
        if (tabChannels != null) tabChannels.setSelected(showChannels);
        if (tabRemote != null) tabRemote.setSelected(!showChannels);

        if (channelsContainer != null) {
            channelsContainer.setVisibility(showChannels ? View.VISIBLE : View.GONE);
        }
        if (remoteContainer != null) {
            remoteContainer.setVisibility(showChannels ? View.GONE : View.VISIBLE);
        }

        if (showChannels && channelsList != null) {
            channelsList.requestFocus();
        } else if (!showChannels) {
            View okBtn = findViewById(R.id.btn_dpad_ok);
            if (okBtn != null) okBtn.requestFocus();
        }
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

            // Sort channels by frequency of use descending!
            Collections.sort(list, (a, b) -> {
                int useA = stbPrefs.getInt("ch_use_" + activeProviderId + "_" + a.lcn, 0);
                int useB = stbPrefs.getInt("ch_use_" + activeProviderId + "_" + b.lcn, 0);
                if (useA != useB) {
                    return Integer.compare(useB, useA); // Higher usage comes first
                }
                try {
                    return Integer.compare(Integer.parseInt(a.lcn), Integer.parseInt(b.lcn));
                } catch (Exception e) {
                    return a.lcn.compareTo(b.lcn);
                }
            });

            handler.post(() -> {
                keyCodes.clear();
                keyCodes.putAll(codes);
                channels.clear();
                channels.addAll(list);
                if (channelsAdapter != null) {
                    channelsAdapter.notifyDataSetChanged();
                }
                if (channelsList != null && !channels.isEmpty()) {
                    channelsList.requestFocus();
                }
            });
        }).start();
    }

    private void onChannelClicked(LcnDatabaseHelper.Channel ch) {
        // Increment usage frequency
        String prefKey = "ch_use_" + activeProviderId + "_" + ch.lcn;
        int currentCount = stbPrefs.getInt(prefKey, 0);
        stbPrefs.edit().putInt(prefKey, currentCount + 1).apply();

        Toast.makeText(this, "Tuning " + ch.lcn + " " + ch.name + " (AV1)...", Toast.LENGTH_SHORT).show();

        // Blast IR digits
        irManager.tuneChannelAsync(ch.lcn, keyCodes, activeRemoteBitLength, null);

        // Switch TV Input to AV1
        openAvInput();

        // Finish activity overlay so video displays full screen
        finish();
    }

    private void openAvInput() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                CharSequence label = info.loadLabel(this);
                String labelStr = label != null ? label.toString().toUpperCase(Locale.ROOT) : "";
                if (labelStr.contains("AV") || info.getId().toLowerCase(Locale.ROOT).contains("composite") || info.getId().endsWith("/HW3")) {
                    openInput(info.getId());
                    return;
                }
            }
        }
        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput("com.xiaomi.mitv.tvinput/.TvInputService/HW3");
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            android.util.Log.w("RemoteSidebar", "Fallback to default openInput", e);
        }
    }

    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
        } catch (Exception e) {
            android.util.Log.w("RemoteSidebar", "Cannot open input " + inputId, e);
        }
    }

    private void bindKey(int resId, String keyName) {
        View v = findViewById(resId);
        if (v == null) return;

        v.setOnClickListener(view -> {
            String hex = keyCodes.get(keyName);
            if ((hex == null || hex.isEmpty()) && "RECALL".equals(keyName)) {
                for (String alias : new String[]{"PREV", "LAST", "LAST_CH", "KEY_40", "RETURN"}) {
                    if (keyCodes.containsKey(alias)) {
                        hex = keyCodes.get(alias);
                        break;
                    }
                }
                if (hex == null) hex = "0x14eb8a75";
            }

            if (hex != null && !hex.isEmpty()) {
                if (statusText != null) statusText.setText("Transmitting " + keyName + "...");
                final String finalHex = hex;
                irManager.transmitNecAsync(finalHex, bitLength(), () -> {
                    if (statusText != null) statusText.setText("Sent " + keyName + " [" + finalHex + "]");
                });
            } else {
                if (statusText != null) statusText.setText("No code for " + keyName);
            }
        });
    }

    private int bitLength() {
        return activeRemoteBitLength > 0 ? activeRemoteBitLength : 32;
    }

    private class SidebarChannelsAdapter extends RecyclerView.Adapter<SidebarChannelsAdapter.ViewHolder> {
        interface OnItemClickListener {
            void onItemClick(LcnDatabaseHelper.Channel channel);
        }

        private final List<LcnDatabaseHelper.Channel> items;
        private final OnItemClickListener listener;
        private final android.util.SparseIntArray logoCache = new android.util.SparseIntArray();

        SidebarChannelsAdapter(List<LcnDatabaseHelper.Channel> items, OnItemClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel_row, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LcnDatabaseHelper.Channel ch = items.get(position);
            holder.lcn.setText(ch.lcn);
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

            int useCount = stbPrefs.getInt("ch_use_" + activeProviderId + "_" + ch.lcn, 0);
            if (useCount > 0) {
                holder.genre.setText("★ " + (ch.genre != null ? ch.genre : ""));
                holder.genre.setTextColor(0xFF38BDF8); // Electric sky blue accent
            } else {
                holder.genre.setText(ch.genre != null ? ch.genre : "");
                holder.genre.setTextColor(0x8CFFFFFF);
            }

            holder.itemView.setOnClickListener(v -> listener.onItemClick(ch));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView lcn;
            final ImageView logo;
            final TextView name;
            final TextView genre;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                lcn = itemView.findViewById(R.id.channel_lcn);
                logo = itemView.findViewById(R.id.channel_logo);
                name = itemView.findViewById(R.id.channel_name);
                genre = itemView.findViewById(R.id.channel_genre);
            }
        }
    }
}

