package org.schabi.newpipe.util;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * EggPlay: reads the uploader avatars of a {@link StreamInfoItem}.
 * <p>
 * Not every extractor release exposes {@code StreamInfoItem#getUploaderAvatars()}, so it is
 * looked up once reflectively. When it is missing, or the service gave no avatar, the card
 * simply shows the placeholder avatar. (A keep rule in proguard-rules.pro protects the method.)
 * </p>
 */
public final class StreamAvatars {
    private static final Method GETTER = findGetter();

    private StreamAvatars() {
    }

    private static Method findGetter() {
        try {
            return StreamInfoItem.class.getMethod("getUploaderAvatars");
        } catch (final NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public static List<Image> of(@NonNull final StreamInfoItem item) {
        if (GETTER == null) {
            return Collections.emptyList();
        }
        try {
            final Object result = GETTER.invoke(item);
            return result instanceof List ? (List<Image>) result : Collections.emptyList();
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return Collections.emptyList();
        }
    }
}
