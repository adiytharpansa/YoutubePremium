/*
 * SPDX-FileCopyrightText: 2017-2024 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.fragments.list.kiosk;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;

import com.evernote.android.state.State;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.services.media_ccc.extractors.MediaCCCLiveStreamKiosk;
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeTrendingExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.KioskTranslator;
import org.schabi.newpipe.util.Localization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class KioskFragment extends BaseListInfoFragment<StreamInfoItem, KioskInfo> {
    @State
    String kioskId = "";

    /**
     * EggPlay: lets the bottom navigation know which kiosk is shown.
     *
     * @return the id of the kiosk shown by this fragment
     */
    public String getKioskId() {
        return kioskId;
    }
    String kioskTranslatedName;

    /** EggPlay: infos already filtered / ranked (they can be served again from the cache). */
    private static final Map<KioskInfo, Boolean> PROCESSED_INFOS =
            new WeakHashMap<>();

    /** EggPlay: foreign kiosk info borrowed as the Home shell, and its original items. */
    private KioskInfo borrowedShell;
    private List<StreamInfoItem> borrowedShellItems;

    /** EggPlay: topical trending kiosks mixed into the YouTube Home feed. */
    private static final String[] HOME_MIX_KIOSK_IDS = {
            "trending_music", "trending_gaming", "trending_movies_and_shows",
            "trending_podcasts_episodes"};

    /** EggPlay: maximum number of items in the mixed Home feed. */
    private static final int MAX_HOME_ITEMS = 80;
    @State
    ContentCountry contentCountry;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    public static KioskFragment getInstance(final int serviceId) throws ExtractionException {
        return getInstance(serviceId, NewPipe.getService(serviceId)
                .getKioskList().getDefaultKioskId());
    }

    public static KioskFragment getInstance(final int serviceId, final String kioskId)
            throws ExtractionException {
        final KioskFragment instance = new KioskFragment();
        final StreamingService service = NewPipe.getService(serviceId);
        final ListLinkHandlerFactory kioskLinkHandlerFactory = service.getKioskList()
                .getListLinkHandlerFactoryByType(kioskId);
        instance.setInitialData(serviceId,
                kioskLinkHandlerFactory.fromId(kioskId).getUrl(), kioskId);
        instance.kioskId = kioskId;
        return instance;
    }

    public KioskFragment() {
        super(UserAction.REQUESTED_KIOSK);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        kioskTranslatedName = KioskTranslator.getTranslatedKioskName(kioskId, activity);
        name = kioskTranslatedName;
        contentCountry = Localization.getPreferredContentCountry(requireContext());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!Localization.getPreferredContentCountry(requireContext()).equals(contentCountry)) {
            reloadContent();
        }
        if (useAsFrontPage && activity != null) {
            try {
                setTitle(kioskTranslatedName);
            } catch (final Exception e) {
                showSnackBarError(new ErrorInfo(e, UserAction.UI_ERROR, "Setting kiosk title"));
            }
        }
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_kiosk, container, false);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        final ActionBar supportActionBar = activity.getSupportActionBar();
        if (supportActionBar != null && useAsFrontPage) {
            supportActionBar.setDisplayHomeAsUpEnabled(false);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * EggPlay: the Live kiosk keeps live streams, every other kiosk (Home) hides them.
     *
     * @return true if this fragment shows the Live kiosk
     */
    private boolean isLiveKiosk() {
        return "live".equals(kioskId);
    }

    /**
     * EggPlay: personalised ranking is applied only to the Home (default kiosk) tab.
     *
     * @return true if this fragment is the default (Home) kiosk
     */
    private boolean isHomeKiosk() {
        return this instanceof DefaultKioskFragment;
    }

    @Override
    public Single<KioskInfo> loadResult(final boolean forceReload) {
        contentCountry = Localization.getPreferredContentCountry(requireContext());
        final Context appContext = requireContext().getApplicationContext();
        final Single<KioskInfo> main =
                ExtractorHelper.getKioskInfo(serviceId, url, forceReload);
        if (isHomeKiosk() && serviceId == ServiceList.YouTube.getServiceId()) {
            return loadHomeMix(main, appContext, forceReload);
        }
        return main.map(info -> {
            if (!isLiveKiosk()) {
                synchronized (PROCESSED_INFOS) {
                    // the info may come from the cache: process each instance only once
                    if (PROCESSED_INFOS.put(info, Boolean.TRUE) == null) {
                        List<StreamInfoItem> items =
                                HomeRecommender.withoutLive(info.getRelatedItems());
                        if (isHomeKiosk()) {
                            items = HomeRecommender.rank(appContext, items, true);
                        }
                        info.setRelatedItems(items);
                    }
                }
            }
            return info;
        });
    }

    /**
     * EggPlay: builds the Home feed from the Trending kiosk plus the topical trending
     * kiosks (music, gaming, movies &amp; shows, podcasts), interleaved and ranked
     * locally by {@link HomeRecommender}. Every source is optional: a kiosk that
     * fails to load, or is unavailable, is simply left out, and a failed main kiosk
     * is retried through {@link #loadHomeInfoRescue} before a topical kiosk info is
     * borrowed as the shell (see {@link #finishHomeMix}).
     *
     * @param main the Single loading the main (Trending) kiosk info
     * @param appContext application context used for ranking
     * @param forceReload whether the topical kiosks may come from the cache
     * @return a Single emitting the shell info with the mixed item list set on it
     */
    private Single<KioskInfo> loadHomeMix(final Single<KioskInfo> main,
                                          final Context appContext,
                                          final boolean forceReload) {
        final List<Single<List<KioskInfo>>> sources = new ArrayList<>();
        final Throwable[] mainError = new Throwable[1];
        sources.add(main.map(Collections::singletonList)
                .doOnError(error -> mainError[0] = error)
                .onErrorResumeNext(error -> loadHomeInfoRescue()
                        .map(Collections::singletonList)
                        .doOnError(rescueError -> mainError[0] = rescueError)
                        .onErrorReturnItem(Collections.emptyList()))
                .subscribeOn(Schedulers.io()));
        try {
            final KioskList kioskList = NewPipe.getService(serviceId).getKioskList();
            for (final String mixId : HOME_MIX_KIOSK_IDS) {
                if (!kioskList.getAvailableKiosks().contains(mixId)) {
                    continue;
                }
                final String mixUrl = kioskList.getListLinkHandlerFactoryByType(mixId)
                        .fromId(mixId).getUrl();
                sources.add(ExtractorHelper.getKioskInfo(serviceId, mixUrl, forceReload)
                        .map(Collections::singletonList)
                        .onErrorReturnItem(Collections.emptyList())
                        .subscribeOn(Schedulers.io()));
            }
        } catch (final ExtractionException e) {
            // some topical kiosk is unavailable: keep the sources collected so far
        }
        return Single.zip(sources, lists -> {
            final List<List<KioskInfo>> slots = new ArrayList<>();
            for (final Object list : lists) {
                //noinspection unchecked
                slots.add((List<KioskInfo>) list);
            }
            return slots;
        }).flatMap(slots -> finishHomeMix(slots, appContext, mainError[0]));
    }

    /**
     * EggPlay: second attempt at loading the Home kiosk info after the regular load
     * failed. The YouTube Trending extractor derives the kiosk name from the page
     * header and throws a ParsingException ("Could not get Trending name") when the
     * header has an unexpected shape, which happens in some locales even though the
     * items themselves parse fine. The fragment never displays that name (it uses
     * the translated kiosk name), so reload through a name-tolerant extractor: a
     * cosmetic name failure must not take the whole Home tab down.
     *
     * @return a Single emitting a fresh (uncached) info for this fragment's kiosk
     */
    private Single<KioskInfo> loadHomeInfoRescue() {
        return Single.fromCallable(() -> {
            final StreamingService service = NewPipe.getService(serviceId);
            final ListLinkHandler linkHandler = service.getKioskList()
                    .getListLinkHandlerFactoryByType(kioskId).fromId(kioskId);
            final YoutubeTrendingExtractor extractor =
                    new YoutubeTrendingExtractor(service, linkHandler, kioskId) {
                        @Override
                        public String getName() {
                            try {
                                return super.getName();
                            } catch (final ParsingException e) {
                                return kioskId;
                            }
                        }
                    };
            extractor.fetchPage();
            return KioskInfo.getInfo(extractor);
        }).subscribeOn(Schedulers.io());
    }

    /**
     * EggPlay: picks the info that carries the mixed Home feed and fills it with the
     * merged items. The first source that loaded wins: normally the Home kiosk
     * itself (or its rescue info); otherwise a topical kiosk info is borrowed as
     * the shell and given its own items back in {@link #handleResult}. Only when
     * no source loaded at all is the main load error re-emitted, so a real failure
     * (e.g. no network) still shows the error screen instead of an empty feed.
     *
     * @param slots one slot per source, holding its info or empty if it failed
     * @param appContext application context used for ranking
     * @param mainError the failure of the main source (and of its rescue), if any
     * @return a Single emitting the shell info filled with the mixed items
     */
    private Single<KioskInfo> finishHomeMix(final List<List<KioskInfo>> slots,
                                            final Context appContext,
                                            final Throwable mainError) {
        final List<List<StreamInfoItem>> buckets = new ArrayList<>();
        KioskInfo shell = null;
        boolean borrowed = false;
        for (int i = 0; i < slots.size(); i++) {
            final KioskInfo info = slots.get(i).isEmpty() ? null : slots.get(i).get(0);
            buckets.add(info == null ? Collections.emptyList() : info.getRelatedItems());
            if (shell == null && info != null) {
                shell = info;
                borrowed = i > 0;
            }
        }
        if (shell == null) {
            if (mainError != null) {
                return Single.error(mainError);
            }
            return Single.error(new ExtractionException("Could not load the home feed"));
        }
        if (borrowed) {
            borrowedShell = shell;
            borrowedShellItems = shell.getRelatedItems();
        }
        setHomeItems(shell, appContext, buckets);
        return Single.just(shell);
    }

    /**
     * EggPlay: replaces the Home info's items with the merged, live-free, ranked list.
     * Runs only once per info instance, like the filter in {@link #loadResult}.
     *
     * @param info the Home kiosk info to fill
     * @param appContext application context used for ranking
     * @param buckets the item lists to merge, most important first
     */
    private void setHomeItems(final KioskInfo info, final Context appContext,
                              final List<List<StreamInfoItem>> buckets) {
        synchronized (PROCESSED_INFOS) {
            if (PROCESSED_INFOS.put(info, Boolean.TRUE) == null) {
                info.setRelatedItems(HomeRecommender.rank(appContext,
                        mergeHomeItems(buckets), true));
            }
        }
    }

    /**
     * EggPlay: interleaves the item lists round-robin so the Home feed mixes topics
     * instead of stacking whole kiosks; live streams and duplicates are dropped.
     *
     * @param buckets the item lists to merge, most important first
     * @return the merged list, at most {@link #MAX_HOME_ITEMS} items
     */
    private List<StreamInfoItem> mergeHomeItems(final List<List<StreamInfoItem>> buckets) {
        final List<StreamInfoItem> merged = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final int[] positions = new int[buckets.size()];
        boolean added = true;
        while (added && merged.size() < MAX_HOME_ITEMS) {
            added = false;
            for (int i = 0; i < buckets.size() && merged.size() < MAX_HOME_ITEMS; i++) {
                final List<StreamInfoItem> bucket = buckets.get(i);
                while (positions[i] < bucket.size()) {
                    final StreamInfoItem item = bucket.get(positions[i]++);
                    if (HomeRecommender.isLive(item) || item.getUrl() == null
                            || !seen.add(item.getUrl())) {
                        continue;
                    }
                    merged.add(item);
                    added = true;
                    break;
                }
            }
        }
        return merged;
    }

    @Override
    public Single<ListExtractor.InfoItemsPage<StreamInfoItem>> loadMoreItemsLogic() {
        final Context appContext = requireContext().getApplicationContext();
        return loadMoreFiltered(currentNextPage, appContext, 0);
    }

    /**
     * Loads the next page and drops live streams. If a whole page was live streams, the
     * following page is fetched right away (max 3 hops) so infinite scroll does not stall.
     *
     * @param page the page to load next
     * @param appContext application context used for the next-page request
     * @param hops how many live-only pages have already been skipped
     * @return a Single emitting the filtered page of stream items
     */
    private Single<ListExtractor.InfoItemsPage<StreamInfoItem>> loadMoreFiltered(
            final Page page, final Context appContext, final int hops) {
        return ExtractorHelper.getMoreKioskItems(serviceId, url, page).flatMap(result -> {
            if (isLiveKiosk()) {
                return Single.just(result);
            }
            List<StreamInfoItem> items = HomeRecommender.withoutLive(result.getItems());
            if (items.isEmpty() && Page.isValid(result.getNextPage()) && hops < 3) {
                return loadMoreFiltered(result.getNextPage(), appContext, hops + 1);
            }
            if (isHomeKiosk()) {
                items = HomeRecommender.rank(appContext, items, false);
            }
            return Single.just(new ListExtractor.InfoItemsPage<>(
                    items, result.getNextPage(), result.getErrors()));
        });
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void handleResult(@NonNull final KioskInfo result) {
        super.handleResult(result);

        name = kioskTranslatedName;
        setTitle(kioskTranslatedName);
        purgeLiveItems();
        restoreBorrowedShell(result);
    }

    /**
     * EggPlay: when the Home feed borrowed another kiosk's info as its shell (see
     * {@link #finishHomeMix}), the merged items have been copied into the list
     * adapter by now, so give the borrowed info its own items back. Otherwise the
     * shared info cache would serve the Home mix on that kiosk's own tab as well.
     *
     * @param result the info that was just handled
     */
    private void restoreBorrowedShell(@NonNull final KioskInfo result) {
        if (borrowedShell != result) {
            return;
        }
        result.setRelatedItems(borrowedShellItems);
        synchronized (PROCESSED_INFOS) {
            PROCESSED_INFOS.remove(result);
        }
        borrowedShell = null;
        borrowedShellItems = null;
    }

    @Override
    public void handleNextItems(final ListExtractor.InfoItemsPage<StreamInfoItem> result) {
        if (isLiveKiosk()) {
            super.handleNextItems(result);
            return;
        }
        super.handleNextItems(new ListExtractor.InfoItemsPage<>(
                HomeRecommender.withoutLive(result.getItems()),
                result.getNextPage(), result.getErrors()));
    }

    @Override
    public void readFrom(@NonNull final Queue<Object> savedObjects) throws Exception {
        super.readFrom(savedObjects);
        purgeLiveItems();
    }

    /**
     * EggPlay: last line of defence for the Home feed. The item list can be repopulated
     * without going through {@link #loadResult} (e.g. list state restored by StateSaver
     * from an older app version that had no live filter); drop live streams that slipped
     * in that way so they are only ever shown in the Live kiosk.
     */
    private void purgeLiveItems() {
        if (isLiveKiosk() || infoListAdapter == null) {
            return;
        }
        final boolean removed = infoListAdapter.getItemsList().removeIf(item ->
                item instanceof StreamInfoItem
                        && HomeRecommender.isLive((StreamInfoItem) item));
        if (removed) {
            infoListAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void showEmptyState() {
        // show "no live streams" for live stream kiosk
        super.showEmptyState();
        if (MediaCCCLiveStreamKiosk.KIOSK_ID.equals(currentInfo.getId())
                && ServiceList.MediaCCC.getServiceId() == currentInfo.getServiceId()) {
            setEmptyStateMessage(R.string.no_live_streams);
        }
    }
}
