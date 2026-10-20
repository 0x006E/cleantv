package com.gathin.tvhome;

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
import java.util.List;

public class InputsAdapter extends RecyclerView.Adapter<InputsAdapter.ViewHolder> {

    public interface Listener {
        void onInputClick(InputItem item);
        void onInputFocus(InputItem item, View view);
        boolean onInputNavigateUp(int position);
        boolean onInputNavigateDown(int position);
    }

    public static class InputItem {
        final String id;
        final String title;
        final String badge;
        final int iconRes;
        final int accentColor;

        public InputItem(String id, String title, String badge, int iconRes, int accentColor) {
            this.id = id;
            this.title = title;
            this.badge = badge;
            this.iconRes = iconRes;
            this.accentColor = accentColor;
        }
    }

    private final List<InputItem> items = new ArrayList<>();
    private final Listener listener;

    public InputsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<InputItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_input_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InputItem item = items.get(position);
        holder.title.setText(item.title);
        holder.badge.setText(item.badge);
        holder.icon.setImageResource(item.iconRes);

        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onInputClick(item);
        });

        holder.card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(8f).setDuration(160).start();
                if (listener != null) listener.onInputFocus(item, v);
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start();
            }
        });

        holder.card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = holder.getBindingAdapterPosition();
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (listener != null) return listener.onInputNavigateUp(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (listener != null) return listener.onInputNavigateDown(pos);
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
        final FrameLayout card;
        final ImageView icon;
        final TextView badge;
        final TextView title;

        ViewHolder(View v) {
            super(v);
            card = v.findViewById(R.id.input_card);
            icon = v.findViewById(R.id.input_icon);
            badge = v.findViewById(R.id.input_badge);
            title = v.findViewById(R.id.input_title);
        }
    }
}

