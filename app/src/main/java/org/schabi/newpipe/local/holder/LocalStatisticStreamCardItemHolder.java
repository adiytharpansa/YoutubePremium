package org.schabi.newpipe.local.holder;

import android.view.View;
import android.view.ViewGroup;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.stream.StreamStatisticsEntry;
import org.schabi.newpipe.local.LocalItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.CardMetaLine;

import java.time.format.DateTimeFormatter;

/**
 * EggPlay: YouTube-style card for locally stored streams (history, most played).
 */
public class LocalStatisticStreamCardItemHolder extends LocalStatisticStreamItemHolder {
    private final View itemMoreButton;

    public LocalStatisticStreamCardItemHolder(final LocalItemBuilder infoItemBuilder,
                                              final ViewGroup parent) {
        super(infoItemBuilder, R.layout.list_stream_card_item, parent);
        itemMoreButton = itemView.findViewById(R.id.itemMoreButton);
    }

    @Override
    public void updateFromItem(final LocalItem localItem,
                               final HistoryRecordManager historyRecordManager,
                               final DateTimeFormatter dateTimeFormatter) {
        super.updateFromItem(localItem, historyRecordManager, dateTimeFormatter);

        if (!(localItem instanceof StreamStatisticsEntry)) {
            return;
        }
        final StreamStatisticsEntry item = (StreamStatisticsEntry) localItem;

        CardMetaLine.merge(itemUploaderView, itemAdditionalDetails,
                item.getStreamEntity().getUploader());

        itemMoreButton.setOnClickListener(view -> {
            if (itemBuilder.getOnItemSelectedListener() != null) {
                itemBuilder.getOnItemSelectedListener().held(item);
            }
        });
    }
}
