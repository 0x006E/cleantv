package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChannelsAdapter extends RecyclerView.Adapter<ChannelsAdapter.ChannelViewHolder> {

    public interface OnChannelListener {
        void onChannelClick(LcnDatabaseHelper.Channel channel);
        void onChannelFocus(LcnDatabaseHelper.Channel channel, View view);
        void onBrowseAllClick();
        boolean onChannelNavigateUp(int position);
        boolean onChannelNavigateDown(int position);
    }

    private final Context context;
    private final SharedPreferences stbPrefs;
    private int providerId = 43;
    private final List<LcnDatabaseHelper.Channel> channels = new ArrayList<>();
    private final OnChannelListener listener;
    private final SparseIntArray logoResCache = new SparseIntArray();

    public ChannelsAdapter(Context context, OnChannelListener listener) {
        this.context = context;
        this.stbPrefs = context.getSharedPreferences("stb_settings", Context.MODE_PRIVATE);
        this.listener = listener;
    }

    private int getChannelLogoRes(int channelId) {
        if (channelId <= 0) return 0;
        int idx = logoResCache.indexOfKey(channelId);
        if (idx >= 0) return logoResCache.valueAt(idx);
        int resId = context.getResources().getIdentifier("channel_" + channelId, "drawable", context.getPackageName());
        logoResCache.put(channelId, resId);
        return resId;
    }

    public void setProviderId(int providerId) {
        this.providerId = providerId;
    }

    public void setChannels(List<LcnDatabaseHelper.Channel> list) {
        this.channels.clear();
        if (list != null) {
            List<LcnDatabaseHelper.Channel> sorted = new ArrayList<>(list);
            Collections.sort(sorted, (a, b) -> {
                int useA = stbPrefs.getInt("ch_use_" + providerId + "_" + a.lcn, 0);
                int useB = stbPrefs.getInt("ch_use_" + providerId + "_" + b.lcn, 0);
                if (useA != useB) {
                    return Integer.compare(useB, useA); // Higher usage comes first
                }
                try {
                    return Integer.compare(Integer.parseInt(a.lcn), Integer.parseInt(b.lcn));
                } catch (Exception e) {
                    return a.lcn.compareTo(b.lcn);
                }
            });
            // Show top 30 most used / popular channels in the shelf
            int limit = Math.min(sorted.size(), 30);
            this.channels.addAll(sorted.subList(0, limit));
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
        return new ChannelViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        if (position < channels.size()) {
            LcnDatabaseHelper.Channel ch = channels.get(position);
            int useCount = stbPrefs.getInt("ch_use_" + providerId + "_" + ch.lcn, 0);
            if (useCount > 0) {
                holder.genre.setText("★ " + (ch.genre != null && !ch.genre.isEmpty() ? ch.genre : "WATCHED"));
                holder.genre.setTextColor(0xFF38BDF8);
            } else {
                holder.genre.setText(ch.genre != null && !ch.genre.isEmpty() ? ch.genre : "GENERAL");
                holder.genre.setTextColor(0xFFA1A1AA);
            }
            holder.lcn.setText(ch.lcn);
            holder.lcn.setVisibility(View.VISIBLE);
            holder.name.setText(ch.name);

            int logoRes = getChannelLogoRes(ch.channelId);
            if (logoRes != 0) {
                holder.logo.setImageResource(logoRes);
                holder.logo.setVisibility(View.VISIBLE);
            } else {
                holder.logo.setImageDrawable(null);
                holder.logo.setVisibility(View.GONE);
            }

            holder.card.setOnClickListener(v -> {
                if (listener != null) listener.onChannelClick(ch);
            });
            holder.card.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onBrowseAllClick();
                    return true;
                }
                return false;
            });

            holder.card.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(8f).setDuration(160).start();
                    if (listener != null) listener.onChannelFocus(ch, v);
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start();
                }
            });
        } else {
            // "Browse All" / "Guide" Card at the end of the shelf
            holder.genre.setText("ALL CHANNELS");
            holder.genre.setTextColor(0xFF38BDF8);
            holder.lcn.setText("GUIDE");
            holder.lcn.setVisibility(View.VISIBLE);
            holder.name.setText("All Channels...");
            holder.logo.setImageDrawable(null);
            holder.logo.setVisibility(View.GONE);

            holder.card.setOnClickListener(v -> {
                if (listener != null) listener.onBrowseAllClick();
            });
            holder.card.setOnLongClickListener(null);

            holder.card.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(8f).setDuration(160).start();
                    if (listener != null) {
                        listener.onChannelFocus(new LcnDatabaseHelper.Channel("GUIDE", "All Channels · Guide", "GUIDE", ""), v);
                    }
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start();
                }
            });
        }

        // DPAD Direction navigation
        holder.card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = holder.getBindingAdapterPosition();
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (listener != null) return listener.onChannelNavigateUp(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (listener != null) return listener.onChannelNavigateDown(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) {
                return true; // stay on first item
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos == getItemCount() - 1) {
                return true; // stay on last item
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return channels.isEmpty() ? 1 : channels.size() + 1; // +1 for "All Channels" guide card
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final TextView genre;
        final TextView lcn;
        final TextView name;
        final ImageView logo;

        ChannelViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.channel_card);
            genre = itemView.findViewById(R.id.channel_genre);
            lcn = itemView.findViewById(R.id.channel_lcn);
            name = itemView.findViewById(R.id.channel_name);
            logo = itemView.findViewById(R.id.channel_logo);
        }
    }
}
