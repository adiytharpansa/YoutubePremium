package org.schabi.newpipe.fragments.list.kiosk;

import android.os.Bundle;

import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.util.KioskTranslator;
import org.schabi.newpipe.util.ServiceHelper;

public class DefaultKioskFragment extends KioskFragment {

    /** EggPlay: kiosk used as the Home source for YouTube instead of the default one. */
    private static final String HOME_KIOSK_ID_YOUTUBE = "Trending";

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (serviceId < 0) {
            updateSelectedDefaultKiosk();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (serviceId != ServiceHelper.getSelectedServiceId(requireContext())) {
            if (currentWorker != null) {
                currentWorker.dispose();
            }
            updateSelectedDefaultKiosk();
            reloadContent();
        }
    }

    /**
     * EggPlay: the extractor's default kiosk for YouTube is the live kiosk (YouTube
     * removed its trending page), which would turn the Home tab into a wall of live
     * streams. Use the Trending kiosk as the Home source instead, so live streams
     * stay in the dedicated Live tab.
     *
     * @param kioskList the kiosk list of the selected service
     * @return the kiosk id to show on the Home tab
     */
    private String getHomeKioskId(final KioskList kioskList) {
        if (serviceId == ServiceList.YouTube.getServiceId()
                && kioskList.getAvailableKiosks().contains(HOME_KIOSK_ID_YOUTUBE)) {
            return HOME_KIOSK_ID_YOUTUBE;
        }
        return kioskList.getDefaultKioskId();
    }

    private void updateSelectedDefaultKiosk() {
        try {
            serviceId = ServiceHelper.getSelectedServiceId(requireContext());

            final KioskList kioskList = NewPipe.getService(serviceId).getKioskList();
            kioskId = getHomeKioskId(kioskList);
            url = kioskList.getListLinkHandlerFactoryByType(kioskId).fromId(kioskId).getUrl();

            kioskTranslatedName = KioskTranslator.getTranslatedKioskName(kioskId, requireContext());
            name = kioskTranslatedName;

            currentInfo = null;
            currentNextPage = null;
        } catch (final ExtractionException e) {
            showError(new ErrorInfo(e, UserAction.REQUESTED_KIOSK,
                    "Loading default kiosk for selected service"));
        }
    }
}
