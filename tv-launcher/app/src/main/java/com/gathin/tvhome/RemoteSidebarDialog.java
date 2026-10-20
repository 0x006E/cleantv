package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translucent sidebar overlay for tactile STB remote operations and quick channel selection.
 * Designed 100% following cleanyourtv's Apple TV theme, Outfit typography, and glass surfaces.
 */
public class RemoteSidebarDialog extends Dialog {

    public interface OnChannelTuneListener {
        void onTuneChannel(LcnDatabaseHelper.Channel channel);
    }

    private final Map<String, String> keyCodes;
    private final int bitLength;
    private final String providerName;
    private final List<LcnDatabaseHelper.Channel> channels = new ArrayList<>();
    private final SensyIrManager irManager;
    private final OnChannelTuneListener tuneListener;

    private TextView statusText;
    private View tabChannels;
    private View tabRemote;
    private View channelsContainer;
    private View remoteContainer;
    private RecyclerView channelsList;

    public RemoteSidebarDialog(@NonNull Context context,
                               String providerName,
                               Map<String, String> keyCodes,
                               int bitLength,
                               List<LcnDatabaseHelper.Channel> channelList,
                               OnChannelTuneListener tuneListener) {
        super(context, android.R.style.Theme_Translucent_NoTitleBar);
        this.providerName = providerName;
        this.keyCodes = keyCodes;
        this.bitLength = bitLength;
        if (channelList != null) {
            this.channels.addAll(channelList);
        }
        this.tuneListener = tuneListener;
        this.irManager = SensyIrManager.getInstance(context);
    }

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

        TextView title = findViewById(R.id.remote_title);
        TextView subtitle = findViewById(R.id.remote_subtitle);
        statusText = findViewById(R.id.remote_status_text);

        if (title != null) title.setText(providerName != null ? providerName : "AV1 REMOTE");
        if (subtitle != null) subtitle.setText("AV1 Set-Top Box · IR Blaster");

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
            channelsList.setLayoutManager(new LinearLayoutManager(getContext()));
            channelsList.setAdapter(new SidebarChannelsAdapter(channels, ch -> {
                if (statusText != null) {
                    statusText.setText("Tuning " + ch.lcn + " " + ch.name + " (AV1)...");
                }
                if (tuneListener != null) {
                    tuneListener.onTuneChannel(ch);
                }
                dismiss();
            }));
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
            closeBtn.setOnClickListener(v -> dismiss());
        }

        // Tap outside backdrop to dismiss
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.setOnClickListener(v -> dismiss());
        }

        if (channelsList != null && !channels.isEmpty()) {
            channelsList.requestFocus();
        } else if (tabRemote != null) {
            switchTab(false);
        }
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
                irManager.transmitNecAsync(finalHex, bitLength, () -> {
                    if (statusText != null) statusText.setText("Sent " + keyName + " [" + finalHex + "]");
                });
            } else {
                if (statusText != null) statusText.setText("No code for " + keyName);
            }
        });
    }

    private static class SidebarChannelsAdapter extends RecyclerView.Adapter<SidebarChannelsAdapter.ViewHolder> {
        interface OnItemClickListener {
            void onItemClick(LcnDatabaseHelper.Channel channel);
        }

        private final List<LcnDatabaseHelper.Channel> items;
        private final OnItemClickListener listener;

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
            holder.genre.setText(ch.genre != null ? ch.genre : "");
            holder.itemView.setOnClickListener(v -> listener.onItemClick(ch));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView lcn;
            final TextView name;
            final TextView genre;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                lcn = itemView.findViewById(R.id.channel_lcn);
                name = itemView.findViewById(R.id.channel_name);
                genre = itemView.findViewById(R.id.channel_genre);
            }
        }
    }
}
