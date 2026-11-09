package org.schabi.newpipe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.stream.StreamStatisticsEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.util.NavigationHelper;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * EggPlay: the "You" tab. Shows viewing statistics computed locally from the history
 * and shortcuts to the library, downloads, settings and about.
 */
public class YouFragment extends BaseFragment {
    private static final int TOP_CHANNELS = 3;

    private final CompositeDisposable disposables = new CompositeDisposable();
    private View rootView;

    /** Plain holder for the numbers shown on screen. */
    private static final class Stats {
        long videos;
        long watchSeconds;
        long subscriptions;
        long playlists;
        final List<ChannelStat> topChannels = new ArrayList<>();
    }

    private static final class ChannelStat {
        final String name;
        long watchCount;

        ChannelStat(final String name) {
            this.name = name;
        }
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_you, container, false);
    }

    @Override
    protected void initViews(final View view, final Bundle savedInstanceState) {
        super.initViews(view, savedInstanceState);
        rootView = view;

        bindStat(R.id.stat_videos, R.drawable.ic_play_arrow, R.string.you_stat_videos);
        bindStat(R.id.stat_watch_time, R.drawable.ic_you_schedule, R.string.you_stat_watch_time);
        bindStat(R.id.stat_subscriptions, R.drawable.ic_subscriptions,
                R.string.you_stat_subscriptions);
        bindStat(R.id.stat_playlists, R.drawable.ic_playlist_play, R.string.you_stat_playlists);

        final LinearLayout menu = view.findViewById(R.id.menu_container);
        final LayoutInflater inflater = LayoutInflater.from(view.getContext());
        addRow(inflater, menu, R.drawable.ic_playlist_play, R.string.you_menu_playlists,
                () -> NavigationHelper.openBookmarksFragment(getFM()), true);
        addRow(inflater, menu, R.drawable.ic_subscriptions, R.string.you_menu_subscriptions,
                () -> NavigationHelper.openSubscriptionFragment(getFM()), true);
        addRow(inflater, menu, R.drawable.ic_history, R.string.you_menu_history,
                () -> NavigationHelper.openStatisticFragment(getFM()), true);
        addRow(inflater, menu, R.drawable.ic_file_download, R.string.you_menu_downloads,
                () -> NavigationHelper.openDownloads(activity), true);
        addRow(inflater, menu, R.drawable.ic_settings, R.string.you_menu_settings,
                () -> NavigationHelper.openSettings(activity), true);
        addRow(inflater, menu, R.drawable.ic_info_outline, R.string.you_menu_about,
                () -> NavigationHelper.openAbout(activity), false);
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle(getString(R.string.bottom_nav_you));
        loadStats();
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        rootView = null;
        super.onDestroyView();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private void bindStat(final int includeId, @DrawableRes final int icon,
                          @StringRes final int label) {
        final View card = rootView.findViewById(includeId);
        ((ImageView) card.findViewById(R.id.stat_icon)).setImageResource(icon);
        ((TextView) card.findViewById(R.id.stat_label)).setText(label);
    }

    private void setStatValue(final int includeId, final String value) {
        final View card = rootView.findViewById(includeId);
        ((TextView) card.findViewById(R.id.stat_value)).setText(value);
    }

    private void addRow(final LayoutInflater inflater, final LinearLayout parent,
                        @DrawableRes final int icon, @StringRes final int title,
                        final Runnable action, final boolean divider) {
        final View row = inflater.inflate(R.layout.item_you_row, parent, false);
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        row.findViewById(R.id.row_divider).setVisibility(divider ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.row_root).setOnClickListener(v -> {
            if (activity != null) {
                action.run();
            }
        });
        parent.addView(row);
    }

    private void showStats(final Stats stats) {
        if (rootView == null) {
            return;
        }
        final NumberFormat nf = NumberFormat.getInstance();
        setStatValue(R.id.stat_videos, nf.format(stats.videos));
        setStatValue(R.id.stat_watch_time, formatDuration(stats.watchSeconds));
        setStatValue(R.id.stat_subscriptions, nf.format(stats.subscriptions));
        setStatValue(R.id.stat_playlists, nf.format(stats.playlists));

        final LinearLayout container = rootView.findViewById(R.id.top_channels_container);
        final View empty = rootView.findViewById(R.id.top_channels_empty);
        // remove rows from a previous load, keep the empty-state text view
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            if (container.getChildAt(i) != empty) {
                container.removeViewAt(i);
            }
        }
        empty.setVisibility(stats.topChannels.isEmpty() ? View.VISIBLE : View.GONE);

        final LayoutInflater inflater = LayoutInflater.from(container.getContext());
        int rank = 1;
        for (final ChannelStat channel : stats.topChannels) {
            final View row = inflater.inflate(R.layout.item_you_channel, container, false);
            ((TextView) row.findViewById(R.id.channel_rank)).setText(String.valueOf(rank++));
            ((TextView) row.findViewById(R.id.channel_name)).setText(channel.name);
            ((TextView) row.findViewById(R.id.channel_count)).setText(
                    getString(R.string.you_watch_count, nf.format(channel.watchCount)));
            container.addView(row);
        }
    }

    private String formatDuration(final long seconds) {
        final long totalMinutes = seconds / 60;
        final long hours = totalMinutes / 60;
        final long minutes = totalMinutes % 60;
        if (hours > 0) {
            return getString(R.string.you_duration_hm, (int) hours, (int) minutes);
        }
        return getString(R.string.you_duration_m, (int) minutes);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Data
    //////////////////////////////////////////////////////////////////////////*/

    private void loadStats() {
        final AppDatabase db =
                NewPipeDatabase.getInstance(requireContext().getApplicationContext());
        disposables.add(Single.fromCallable(() -> computeStats(db))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showStats, throwable -> { /* keep placeholders */ }));
    }

    private static Stats computeStats(final AppDatabase db) {
        final Stats stats = new Stats();

        final List<StreamStatisticsEntry> history = db.streamHistoryDAO().getStatistics()
                .blockingFirst();
        stats.videos = history.size();

        final Map<String, ChannelStat> channels = new HashMap<>();
        for (final StreamStatisticsEntry entry : history) {
            final StreamEntity stream = entry.getStreamEntity();
            final long duration = Math.max(0, stream.getDuration());
            final long watches = Math.max(1, entry.getWatchCount());
            final long progressSeconds = entry.getProgressMillis() / 1000;
            // estimate: each full repeat counts the whole video, the last one its saved progress
            // (finished videos have no saved progress, so they count as fully watched)
            final long lastWatch = progressSeconds > 0 ? Math.min(progressSeconds, duration)
                    : duration;
            stats.watchSeconds += (watches - 1) * duration + lastWatch;

            final String key = stream.getUploaderUrl() != null ? stream.getUploaderUrl()
                    : stream.getUploader();
            if (key != null && stream.getUploader() != null && !stream.getUploader().isEmpty()) {
                channels.computeIfAbsent(key, k -> new ChannelStat(stream.getUploader()))
                        .watchCount += watches;
            }
        }

        final List<ChannelStat> sorted = new ArrayList<>(channels.values());
        Collections.sort(sorted, (a, b) -> Long.compare(b.watchCount, a.watchCount));
        stats.topChannels.addAll(sorted.subList(0, Math.min(TOP_CHANNELS, sorted.size())));

        stats.subscriptions = db.subscriptionDAO().rowCount().blockingFirst();
        stats.playlists = db.playlistDAO().getAll().blockingFirst().size()
                + db.playlistRemoteDAO().getAll().blockingFirst().size();
        return stats;
    }
}
