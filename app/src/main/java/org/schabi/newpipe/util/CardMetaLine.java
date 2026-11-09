package org.schabi.newpipe.util;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * EggPlay: the YouTube-style video card shows "channel • views • date" on one line.
 * The shared binders still fill {@code itemUploaderView} and {@code itemAdditionalDetails}
 * separately, so this merges the second into the first.
 */
public final class CardMetaLine {
    private CardMetaLine() {
    }

    /**
     * Writes {@code uploader • details} into {@code uploaderView} and hides {@code detailsView}.
     *
     * @param uploaderView the single line meta text view
     * @param detailsView  the view holding "views • date" (hidden afterwards)
     * @param uploader     the uploader / channel name, may be null or empty
     */
    public static void merge(final TextView uploaderView,
                             @Nullable final TextView detailsView,
                             @Nullable final CharSequence uploader) {
        final CharSequence details = detailsView == null ? "" : detailsView.getText();
        uploaderView.setText(Localization.concatenateStrings(
                uploader == null ? "" : uploader.toString(),
                details == null ? "" : details.toString()));
        if (detailsView != null) {
            detailsView.setVisibility(View.GONE);
        }
    }

    /**
     * Sets the colour of a duration / live badge while keeping its rounded shape.
     * {@code View.setBackgroundColor} would replace the rounded drawable by a flat rectangle.
     *
     * @param badge the badge view
     * @param color the new background colour
     */
    public static void setBadgeColor(final View badge, @ColorInt final int color) {
        final Drawable background = badge.getBackground();
        if (background instanceof GradientDrawable) {
            ((GradientDrawable) background.mutate()).setColor(color);
        } else {
            badge.setBackgroundColor(color);
        }
    }
}
