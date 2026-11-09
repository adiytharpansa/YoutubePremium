package org.schabi.newpipe.preview;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamType;

/**
 * EggPlay: entry points for the YouTube-style inline autoplay preview.
 * Card binders call {@link #bind}; list owners call {@link #attach}.
 */
public final class InlinePreview {
    private InlinePreview() {
    }

    /** What a card currently shows; stored as a view tag so the controller stays data-agnostic. */
    static final class Target {
        final int serviceId;
        final String url;
        final boolean playable;

        Target(final int serviceId, final String url, final boolean playable) {
            this.serviceId = serviceId;
            this.url = url;
            this.playable = playable;
        }

        String key() {
            return serviceId + "|" + url;
        }
    }

    public static void bind(@NonNull final View cardView, final int serviceId,
                            @Nullable final String url, @Nullable final StreamType type) {
        final boolean playable = url != null && type != null
                && type != StreamType.AUDIO_STREAM
                && type != StreamType.AUDIO_LIVE_STREAM
                && type != StreamType.POST_LIVE_AUDIO_STREAM;
        final Target old = (Target) cardView.getTag(R.id.tag_inline_preview);
        final Target now = new Target(serviceId, url == null ? "" : url, playable);
        cardView.setTag(R.id.tag_inline_preview, now);
        if (old == null || !old.key().equals(now.key())) {
            InlinePreviewController.resetCard(cardView);
        }
    }

    public static void attach(@NonNull final RecyclerView recyclerView) {
        if (recyclerView.getTag(R.id.tag_inline_preview_controller) == null) {
            recyclerView.setTag(R.id.tag_inline_preview_controller,
                    new InlinePreviewController(recyclerView));
        }
    }
}
