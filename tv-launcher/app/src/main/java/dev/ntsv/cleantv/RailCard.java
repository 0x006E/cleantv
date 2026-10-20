package dev.ntsv.cleantv;

import android.graphics.drawable.Drawable;

/**
 * Universal data model for any rail item (App, Live Channel, Input, etc.).
 * Rails are purely data sources providing RailCard items with actions.
 */
public class RailCard {

    public enum ArtworkStyle {
        BANNER_FILL,   // Full-bleed 16:9 banner (e.g. Netflix, Play Store)
        LOGO_CENTER,   // Centered channel or app logo (e.g. Zee TV, Star Plus)
        ICON_CENTER,   // Centered vector icon (e.g. HDMI 1, AV1, Guide)
        APP_ICON       // Standard app icon with internal label (for non-banner apps)
    }

    public interface ActionListener {
        void onClick(RailCard card);
        boolean onLongClick(RailCard card);
    }

    public final String id;
    public final String title;
    public final String badge;
    public final Drawable artworkDrawable;
    public final int artworkRes;
    public final ArtworkStyle artworkStyle;
    public final int accentColor;
    public final Object payload;
    public final ActionListener actionListener;

    public RailCard(String id,
                    String title,
                    String badge,
                    Drawable artworkDrawable,
                    int artworkRes,
                    ArtworkStyle artworkStyle,
                    int accentColor,
                    Object payload,
                    ActionListener actionListener) {
        this.id = id;
        this.title = title;
        this.badge = badge;
        this.artworkDrawable = artworkDrawable;
        this.artworkRes = artworkRes;
        this.artworkStyle = artworkStyle;
        this.accentColor = accentColor;
        this.payload = payload;
        this.actionListener = actionListener;
    }
}

