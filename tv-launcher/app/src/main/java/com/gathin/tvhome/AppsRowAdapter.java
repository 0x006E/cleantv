package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppsRowAdapter extends RecyclerView.Adapter<AppsRowAdapter.ViewHolder> {

    public interface Listener {
        void onAppClick(AppItem item);
        void onAppFocus(AppItem item, View view);
        boolean onAppNavigateUp(int position);
        boolean onAppNavigateDown(int position);
    }

    public static class AppItem {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        final int accentColor;

        public AppItem(String label, ComponentName component, Drawable banner, Drawable icon, int accentColor) {
            this.label = label;
            this.component = component;
            this.pkg = component != null ? component.getPackageName() : "";
            this.banner = banner;
            this.icon = icon;
            this.accentColor = accentColor;
        }
    }

    private final Context context;
    private final List<AppItem> items = new ArrayList<>();
    private final Listener listener;

    public AppsRowAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setItems(List<AppItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppItem item = items.get(position);
        holder.name.setText(item.label);
        holder.name.setVisibility(View.INVISIBLE); // Keep clean tvOS style labels on focus

        if (item.banner != null) {
            holder.banner.setImageDrawable(item.banner);
            holder.banner.setVisibility(View.VISIBLE);
            holder.icon.setVisibility(View.GONE);
            holder.label.setVisibility(View.GONE);
        } else {
            holder.banner.setImageDrawable(null);
            holder.banner.setVisibility(View.GONE);
            holder.icon.setImageDrawable(item.icon);
            holder.icon.setVisibility(View.VISIBLE);
            holder.label.setText(item.label);
            holder.label.setVisibility(View.VISIBLE);
        }

        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onAppClick(item);
        });

        holder.card.setOnLongClickListener(v -> {
            if (item.pkg.isEmpty()) return false;
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + item.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(i);
            } catch (ActivityNotFoundException ignored) {}
            return true;
        });

        holder.card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(8f).setDuration(160).start();
                holder.name.setVisibility(View.VISIBLE);
                holder.name.animate().alpha(1f).translationY(0f).setDuration(160).start();
                if (listener != null) listener.onAppFocus(item, v);
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start();
                holder.name.animate().alpha(0f).translationY(-6f).setDuration(100).withEndAction(() -> holder.name.setVisibility(View.INVISIBLE)).start();
            }
        });

        holder.card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = holder.getBindingAdapterPosition();
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (listener != null) return listener.onAppNavigateUp(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (listener != null) return listener.onAppNavigateDown(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) {
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos == getItemCount() - 1) {
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        ViewHolder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
        }
    }
}

