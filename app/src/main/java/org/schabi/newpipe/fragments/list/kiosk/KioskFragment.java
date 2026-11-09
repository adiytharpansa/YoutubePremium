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
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.services.media_ccc.extractors.MediaCCCLiveStreamKiosk;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.KioskTranslator;
import org.schabi.newpipe.util.Localization;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.reactivex.rxjava3.core.Single;

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
        return ExtractorHelper.getKioskInfo(serviceId, url, forceReload).map(info -> {
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
