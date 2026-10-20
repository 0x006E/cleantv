package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class RailsAdapter extends RecyclerView.Adapter<RailsAdapter.RailViewHolder> {

    public static final int RAIL_APPS = 0;
    public static final int RAIL_CHANNELS = 1;
    public static final int RAIL_INPUTS = 2;
    public static final int RAIL_ALL_APPS = 3;

    public interface OnRailHeaderClickListener {
        void onRailHeaderClick(int railType);
    }

    public static class RailConfig {
        final int type;
        final String title;
        final int iconRes;
        final int color;
        final RecyclerView.Adapter<?> adapter;

        public RailConfig(int type, String title, int iconRes, int color, RecyclerView.Adapter<?> adapter) {
            this.type = type;
            this.title = title;
            this.iconRes = iconRes;
            this.color = color;
            this.adapter = adapter;
        }
    }

    private final Context context;
    private final List<RailConfig> rails = new ArrayList<>();
    private final OnRailHeaderClickListener headerClickListener;
    private final RecyclerView.RecycledViewPool sharedPool = new RecyclerView.RecycledViewPool();

    public RailsAdapter(Context context, OnRailHeaderClickListener headerClickListener) {
        this.context = context;
        this.headerClickListener = headerClickListener;
    }

    public void setRails(List<RailConfig> list) {
        rails.clear();
        if (list != null) rails.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RailViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_rail_row, parent, false);
        RailViewHolder vh = new RailViewHolder(v);
        vh.recycler.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        vh.recycler.setItemViewCacheSize(10);
        vh.recycler.setRecycledViewPool(sharedPool);
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull RailViewHolder holder, int position) {
        RailConfig cfg = rails.get(position);
        holder.title.setText(cfg.title);
        holder.icon.setImageResource(cfg.iconRes);

        // Modern Android TV header style
        if (holder.icon != null) {
            holder.icon.setColorFilter((0xCC << 24) | (cfg.color & 0x00FFFFFF));
        }
        holder.title.setTextColor(0xFFE4E4E7);

        if (holder.recycler.getAdapter() != cfg.adapter) {
            holder.recycler.setAdapter(cfg.adapter);
        }

        holder.header.setOnClickListener(v -> {
            if (headerClickListener != null) headerClickListener.onRailHeaderClick(cfg.type);
        });
    }

    @Override
    public int getItemCount() {
        return rails.size();
    }

    public static class RailViewHolder extends RecyclerView.ViewHolder {
        final View header;
        final ImageView icon;
        final TextView title;
        final RecyclerView recycler;

        RailViewHolder(View v) {
            super(v);
            header = v.findViewById(R.id.rail_header);
            icon = v.findViewById(R.id.rail_icon);
            title = v.findViewById(R.id.rail_title);
            recycler = v.findViewById(R.id.rail_recycler);
        }

        public void focusFirstItem() {
            recycler.post(() -> {
                RecyclerView.ViewHolder vh = recycler.findViewHolderForAdapterPosition(0);
                View v = vh != null ? vh.itemView : (recycler.getChildCount() > 0 ? recycler.getChildAt(0) : null);
                if (v != null) {
                    View card = v.findViewById(R.id.card);
                    if (card == null) card = v.findViewById(R.id.channel_card);
                    if (card == null) card = v.findViewById(R.id.input_card);
                    if (card != null) card.requestFocus();
                }
            });
        }
    }
}

