package org.schabi.newpipe.info_list.holder;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.fragment.app.FragmentActivity;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.preview.InlinePreview;
import org.schabi.newpipe.util.CardMetaLine;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.StreamAvatars;
import org.schabi.newpipe.util.image.CoilHelper;

/**
 * EggPlay: YouTube-style card for a stream. Full-bleed thumbnail, channel avatar
 * (opens the channel), title, a single meta line and an overflow button.
 */
public class StreamCardInfoItemHolder extends StreamInfoItemHolder {
    private final ImageView itemUploaderAvatarView;
    private final View itemMoreButton;

    public StreamCardInfoItemHolder(final InfoItemBuilder infoItemBuilder, final ViewGroup parent) {
        super(infoItemBuilder, R.layout.list_stream_card_item, parent);
        itemUploaderAvatarView = itemView.findViewById(R.id.itemUploaderAvatarView);
        itemMoreButton = itemView.findViewById(R.id.itemMoreButton);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        super.updateFromItem(infoItem, historyRecordManager);

        if (!(infoItem instanceof StreamInfoItem)) {
            return;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;

        CardMetaLine.merge(itemUploaderView, itemAdditionalDetails, item.getUploaderName());
        InlinePreview.bind(itemView, item.getServiceId(), item.getUrl(), item.getStreamType());

        CoilHelper.INSTANCE.loadAvatar(itemUploaderAvatarView, StreamAvatars.of(item));

        final Context context = itemBuilder.getContext();
        final String uploaderUrl = item.getUploaderUrl();
        if (!TextUtils.isEmpty(uploaderUrl) && context instanceof FragmentActivity) {
            itemUploaderAvatarView.setOnClickListener(view -> NavigationHelper
                    .openChannelFragment(
                            ((FragmentActivity) context).getSupportFragmentManager(),
                            item.getServiceId(), uploaderUrl,
                            item.getUploaderName() == null ? "" : item.getUploaderName()));
        } else {
            itemUploaderAvatarView.setOnClickListener(null);
            itemUploaderAvatarView.setClickable(false);
        }

        itemMoreButton.setOnClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().held(item);
            }
        });
    }
}
