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

/**
 * Universal adapter for all rails (Apps, Channels, Inputs).
 * Takes RailCard data sources and applies uniform motion, elevation shadow, and typography.
 */
public class RailCardsAdapter extends RecyclerView.Adapter<RailCardsAdapter.ViewHolder> {

    public interface FocusListener {
        void onCardFocus(RailCard card, View view);
    }

    public interface NavigationListener {
        boolean onNavigateUp(int position);
        boolean onNavigateDown(int position);
    }

    private final List<RailCard> items = new ArrayList<>();
    private FocusListener focusListener;
    private NavigationListener navigationListener;

    public void setItems(List<RailCard> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public List<RailCard> getItems() {
        return items;
    }

    public void setFocusListener(FocusListener l) {
        this.focusListener = l;
    }

    public void setNavigationListener(NavigationListener l) {
        this.navigationListener = l;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_rail_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RailCard card = items.get(position);
        holder.title.setText(card.title);
        holder.title.setVisibility(View.INVISIBLE);
        holder.title.setAlpha(0f);
        holder.title.setTranslationY(-6f * holder.title.getResources().getDisplayMetrics().density);

        // Reset visibility
        holder.bannerFill.setVisibility(View.GONE);
        holder.artworkCenter.setVisibility(View.GONE);
        holder.artworkIcon.setVisibility(View.GONE);
        holder.cardLabel.setVisibility(View.GONE);
        holder.textFallback.setVisibility(View.GONE);

        // Bind artwork according to style
        if (card.artworkStyle == RailCard.ArtworkStyle.BANNER_FILL && card.artworkDrawable != null) {
            holder.bannerFill.setImageDrawable(card.artworkDrawable);
            holder.bannerFill.setVisibility(View.VISIBLE);
        } else if (card.artworkStyle == RailCard.ArtworkStyle.LOGO_CENTER) {
            if (card.artworkRes != 0) {
                holder.artworkCenter.setImageResource(card.artworkRes);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            } else if (card.artworkDrawable != null) {
                holder.artworkCenter.setImageDrawable(card.artworkDrawable);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            } else {
                holder.textFallback.setText(card.title);
                holder.textFallback.setVisibility(View.VISIBLE);
            }
        } else if (card.artworkStyle == RailCard.ArtworkStyle.ICON_CENTER) {
            if (card.artworkRes != 0) {
                holder.artworkCenter.setImageResource(card.artworkRes);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            } else if (card.artworkDrawable != null) {
                holder.artworkCenter.setImageDrawable(card.artworkDrawable);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            }
        } else if (card.artworkStyle == RailCard.ArtworkStyle.APP_ICON) {
            if (card.artworkDrawable != null) {
                holder.artworkIcon.setImageDrawable(card.artworkDrawable);
                holder.artworkIcon.setVisibility(View.VISIBLE);
            }
            holder.cardLabel.setText(card.title);
            holder.cardLabel.setVisibility(View.VISIBLE);
        }

        // Subtle badge (LCN, Active, etc.)
        if (card.badge != null && !card.badge.isEmpty()) {
            holder.badge.setText(card.badge);
            holder.badge.setVisibility(View.VISIBLE);
        } else {
            holder.badge.setVisibility(View.GONE);
        }

        // Clicks
        holder.card.setOnClickListener(v -> {
            if (card.actionListener != null) card.actionListener.onClick(card);
        });
        holder.card.setOnLongClickListener(v -> {
            if (card.actionListener != null) return card.actionListener.onLongClick(card);
            return false;
        });

        // Unified elevation shadow & text motion from TvDesignTokens
        holder.card.setOnFocusChangeListener((v, hasFocus) -> {
            TvDesignTokens.animateFocus(v, holder.title, hasFocus);
            if (hasFocus && focusListener != null) {
                focusListener.onCardFocus(card, v);
            }
        });

        // DPAD Direction navigation
        holder.card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = holder.getBindingAdapterPosition();
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && navigationListener != null) {
                return navigationListener.onNavigateUp(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && navigationListener != null) {
                return navigationListener.onNavigateDown(pos);
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
        final ImageView bannerFill;
        final ImageView artworkCenter;
        final ImageView artworkIcon;
        final TextView cardLabel;
        final TextView textFallback;
        final TextView badge;
        final TextView title;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            bannerFill = itemView.findViewById(R.id.banner_fill);
            artworkCenter = itemView.findViewById(R.id.artwork_center);
            artworkIcon = itemView.findViewById(R.id.artwork_icon);
            cardLabel = itemView.findViewById(R.id.card_label);
            textFallback = itemView.findViewById(R.id.text_fallback);
            badge = itemView.findViewById(R.id.badge);
            title = itemView.findViewById(R.id.title);

            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRect(0, 0, view.getWidth(), view.getHeight());
                }
            });
            card.setClipToOutline(true);
        }
    }
}

