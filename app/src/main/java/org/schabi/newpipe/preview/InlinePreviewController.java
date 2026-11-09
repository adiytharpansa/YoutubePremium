package org.schabi.newpipe.preview;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.video.VideoSize;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * EggPlay: plays the most visible video card of a list inline, like the YouTube feed.
 *
 * <ul>
 *   <li>One shared ExoPlayer, moved from card to card; starts shortly after a card settles.</li>
 *   <li>Data friendly: Wi-Fi only by default, max 720p on unmetered / 480p on metered
 *       networks, small buffer, disabled on Data Saver, battery saver and low-RAM devices.</li>
 *   <li>Stream info of at most {@link #PREFETCH_MAX} nearby cards is prefetched.</li>
 *   <li>Muted by default (unmute lasts for the current session only) and never starts
 *       while the main player is open; on error it retries at lower quality.</li>
 * </ul>
 */
public final class InlinePreviewController {
    private static final int TICK_MS = 350;
    private static final int START_DELAY_MS = 220;
    private static final float MIN_VISIBLE = 0.6f;
    private static final float KEEP_VISIBLE = 0.35f;
    private static final int MAX_HEIGHT_UNMETERED = 720;
    private static final int MAX_HEIGHT_METERED = 480;
    private static final int IDLE_TICK_MS = 900;
    private static final long FAIL_TTL_MS = 5 * 60 * 1000L;
    private static final float PREFETCH_MIN_VISIBLE = 0.3f;
    static final int PREFETCH_MAX = 2;
    static final String MODE_WIFI = "wifi";
    static final String MODE_ALWAYS = "always";
    static final String MODE_OFF = "off";

    /** Unmuting only lasts until the process ends, so audio never surprises the user later. */
    private static volatile boolean unmutedThisSession = false;
    private static final float HIDDEN_ALPHA = 0.01f;

    private final RecyclerView rv;
    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompositeDisposable prefetchDisposables = new CompositeDisposable();
    private final Set<String> prefetched =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> failed = new ConcurrentHashMap<>();

    @Nullable private ExoPlayer player;
    @Nullable private PlayerDataSource dataSource;
    @Nullable private VideoPlaybackResolver resolver;
    @Nullable private Disposable loadDisposable;
    @Nullable private LifecycleOwner lifecycleOwner;

    @Nullable private View host;
    @Nullable private InlinePreview.Target current;
    @Nullable private StreamInfo currentInfo;
    @Nullable private String pendingKey;
    @Nullable private View pendingHost;
    private long pendingSince;
    private volatile int maxHeight = MAX_HEIGHT_UNMETERED;
    private int retryLevel;
    private float videoAspect = 0f;
    private boolean resumed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            evaluate();
            handler.postDelayed(this, host != null || pendingKey != null ? TICK_MS : IDLE_TICK_MS);
        }
    };

    private final RecyclerView.OnScrollListener scrollListener =
            new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull final RecyclerView recyclerView,
                                         final int newState) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE && resumed) {
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
        }
    };

    private final LifecycleEventObserver lifecycleObserver = (source, event) -> {
        if (event == Lifecycle.Event.ON_RESUME) {
            setResumed(true);
        } else if (event == Lifecycle.Event.ON_PAUSE) {
            setResumed(false);
        }
    };

    InlinePreviewController(@NonNull final RecyclerView recyclerView) {
        rv = recyclerView;
        appContext = recyclerView.getContext().getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(appContext);

        rv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull final View v) {
                rv.removeOnScrollListener(scrollListener);
                rv.addOnScrollListener(scrollListener);
                lifecycleOwner = ViewTreeLifecycleOwner.get(rv);
                if (lifecycleOwner != null) {
                    lifecycleOwner.getLifecycle().addObserver(lifecycleObserver);
                } else {
                    setResumed(true);
                }
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull final View v) {
                rv.removeOnScrollListener(scrollListener);
                if (lifecycleOwner != null) {
                    lifecycleOwner.getLifecycle().removeObserver(lifecycleObserver);
                    lifecycleOwner = null;
                }
                setResumed(false);
            }
        });
        if (rv.isAttachedToWindow()) {
            rv.removeOnScrollListener(scrollListener);
            rv.addOnScrollListener(scrollListener);
            lifecycleOwner = ViewTreeLifecycleOwner.get(rv);
            if (lifecycleOwner != null) {
                lifecycleOwner.getLifecycle().addObserver(lifecycleObserver);
            } else {
                setResumed(true);
            }
        }
    }

    // region lifecycle

    private void setResumed(final boolean value) {
        if (resumed == value) {
            return;
        }
        resumed = value;
        handler.removeCallbacks(tick);
        if (value) {
            // A failure may have been a short network drop: give every video another chance
            failed.clear();
            handler.post(tick);
        } else {
            stopPlayback();
            prefetchDisposables.clear();
            prefetched.clear();
            releasePlayer();
        }
    }

    // endregion

    // region candidate selection

    private static final class Candidate {
        final View view;
        final InlinePreview.Target target;
        final float fraction;
        final float distance;

        Candidate(final View view, final InlinePreview.Target target,
                  final float fraction, final float distance) {
            this.view = view;
            this.target = target;
            this.fraction = fraction;
            this.distance = distance;
        }
    }

    private String mode() {
        final String m = prefs.getString(appContext.getString(R.string.inline_preview_mode_key),
                MODE_WIFI);
        return m == null ? MODE_WIFI : m;
    }

    private boolean isMetered(@NonNull final ConnectivityManager cm) {
        return cm.isActiveNetworkMetered();
    }

    private boolean isOnline(@NonNull final ConnectivityManager cm) {
        final android.net.Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean isEnabled() {
        final String mode = mode();
        if (MODE_OFF.equals(mode)) {
            return false;
        }
        // Never autoplay on low-RAM devices or in battery saver
        final ActivityManager am = (ActivityManager) appContext
                .getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.isLowRamDevice()) {
            return false;
        }
        final PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isPowerSaveMode()) {
            return false;
        }
        // Do not compete with the main player (popup / background / fullscreen)
        if (PlayerHolder.getInstance().isPlayerOpen()) {
            return false;
        }
        final ConnectivityManager cm = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || !isOnline(cm)) {
            return false;
        }
        if (isMetered(cm)) {
            if (MODE_WIFI.equals(mode)) {
                return false;
            }
            // Respect system Data Saver even when the user chose "always"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && cm.getRestrictBackgroundStatus()
                    == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                return false;
            }
        }
        return true;
    }

    private int baseMaxHeight() {
        final ConnectivityManager cm = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && isMetered(cm) ? MAX_HEIGHT_METERED : MAX_HEIGHT_UNMETERED;
    }

    private boolean isFailed(@NonNull final String key) {
        final Long at = failed.get(key);
        if (at == null) {
            return false;
        }
        if (SystemClock.uptimeMillis() - at > FAIL_TTL_MS) {
            failed.remove(key);
            return false;
        }
        return true;
    }

    private void markFailed(@NonNull final String key) {
        if (failed.size() > 200) {
            failed.clear();
        }
        failed.put(key, SystemClock.uptimeMillis());
    }

    private float visibleFraction(@NonNull final View thumb, @NonNull final Rect out) {
        if (!thumb.isShown() || thumb.getHeight() <= 0 || !thumb.getGlobalVisibleRect(out)) {
            return 0f;
        }
        return Math.min(1f, out.height() / (float) thumb.getHeight());
    }

    @Nullable
    private Candidate findBest() {
        final Rect rvRect = new Rect();
        if (!rv.isShown() || !rv.getGlobalVisibleRect(rvRect)) {
            return null;
        }
        final float centerY = rvRect.exactCenterY();
        final Rect r = new Rect();
        Candidate best = null;
        Candidate keep = null;
        for (int i = 0; i < rv.getChildCount(); i++) {
            final View child = rv.getChildAt(i);
            final Object tag = child.getTag(R.id.tag_inline_preview);
            if (!(tag instanceof InlinePreview.Target)) {
                continue;
            }
            final InlinePreview.Target t = (InlinePreview.Target) tag;
            final View thumb = child.findViewById(R.id.itemThumbnailView);
            if (!t.playable || thumb == null || isFailed(t.key())) {
                continue;
            }
            final float f = visibleFraction(thumb, r);
            final Candidate c = new Candidate(child, t, f, Math.abs(r.exactCenterY() - centerY));
            if (child == host && f >= KEEP_VISIBLE) {
                keep = c;
            }
            if (f >= MIN_VISIBLE && (best == null || c.distance < best.distance)) {
                best = c;
            }
        }
        // Hysteresis: stay on the playing card unless another one is clearly more central.
        if (keep != null && (best == null || best.view == keep.view
                || keep.distance <= best.distance + rv.getHeight() * 0.25f)) {
            return keep;
        }
        return best;
    }

    private void evaluate() {
        if (InlinePreview.isSuspended()) {
            // The main player is open on top: free the decoder and the audio focus for it
            stopPlayback();
            releasePlayer();
            return;
        }
        if (!isEnabled()) {
            stopPlayback();
            return;
        }
        prefetchVisible();

        // The playing card was recycled or rebound to another video
        if (host != null && current != null) {
            final Object tag = host.getTag(R.id.tag_inline_preview);
            if (!host.isAttachedToWindow() || !(tag instanceof InlinePreview.Target)
                    || !((InlinePreview.Target) tag).key().equals(current.key())) {
                stopPlayback();
            }
        }

        final Candidate best = findBest();
        if (best == null) {
            pendingKey = null;
            pendingHost = null;
            stopPlayback();
            return;
        }
        if (best.view == host && best.target.key().equals(playingKey())) {
            pendingKey = null;
            return;
        }
        if (!best.target.key().equals(pendingKey) || best.view != pendingHost) {
            pendingKey = best.target.key();
            pendingHost = best.view;
            pendingSince = SystemClock.uptimeMillis();
            return;
        }
        if (SystemClock.uptimeMillis() - pendingSince >= START_DELAY_MS) {
            startPlayback(best.view, best.target);
        }
    }

    @Nullable
    private String playingKey() {
        return current == null ? null : current.key();
    }

    // endregion

    // region prefetch

    private void prefetchVisible() {
        final Rect r = new Rect();
        int started = 0;
        for (int i = 0; i < rv.getChildCount() && started < PREFETCH_MAX; i++) {
            final View child = rv.getChildAt(i);
            final Object tag = child.getTag(R.id.tag_inline_preview);
            if (!(tag instanceof InlinePreview.Target)) {
                continue;
            }
            final InlinePreview.Target t = (InlinePreview.Target) tag;
            final View thumb = child.findViewById(R.id.itemThumbnailView);
            if (!t.playable || thumb == null || isFailed(t.key())
                    || visibleFraction(thumb, r) < PREFETCH_MIN_VISIBLE
                    || !prefetched.add(t.key())) {
                continue;
            }
            if (prefetched.size() > 300) {
                prefetched.clear();
            }
            started++;
            final Disposable[] holder = new Disposable[1];
            holder[0] = ExtractorHelper.getStreamInfo(t.serviceId, t.url, false)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally(() -> {
                        if (holder[0] != null) {
                            prefetchDisposables.delete(holder[0]);
                        }
                    })
                    .subscribe(info -> { }, e -> prefetched.remove(t.key()));
            prefetchDisposables.add(holder[0]);
        }
    }

    // endregion

    // region playback

    private void ensurePlayer() {
        if (player != null) {
            return;
        }
        dataSource = new PlayerDataSource(appContext,
                new DefaultBandwidthMeter.Builder(appContext).build());
        resolver = new VideoPlaybackResolver(appContext, dataSource,
                new VideoPlaybackResolver.QualityResolver() {
                    @Override
                    public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
                        return pickIndex(sortedVideos);
                    }

                    @Override
                    public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                                          final String playbackQuality) {
                        return pickIndex(sortedVideos);
                    }
                });
        player = new ExoPlayer.Builder(appContext,
                new DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(5000, 15000, 800, 1500)
                        .build())
                .build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                showVideo();
            }

            @Override
            public void onVideoSizeChanged(@NonNull final VideoSize size) {
                if (size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height;
                    applyCrop();
                }
            }

            @Override
            public void onPlayerError(@NonNull final PlaybackException error) {
                onPlaybackError();
            }
        });
        applyAudio();
    }

    private int pickIndex(@NonNull final List<VideoStream> sorted) {
        if (sorted.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < sorted.size(); i++) {
            final int h = sorted.get(i).getHeight();
            if (h <= 0 || h <= maxHeight) {
                return i;
            }
        }
        return sorted.size() - 1;
    }

    private boolean isMuted() {
        return !unmutedThisSession;
    }

    private void applyAudio() {
        if (player == null) {
            return;
        }
        final boolean muted = isMuted();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), !muted);
        player.setVolume(muted ? 0f : 1f);
    }

    private void startPlayback(@NonNull final View cardView,
                               @NonNull final InlinePreview.Target target) {
        stopPlayback();
        ensurePlayer();
        host = cardView;
        current = target;
        retryLevel = 0;
        maxHeight = baseMaxHeight();
        pendingKey = null;
        videoAspect = 0f;

        loadDisposable = ExtractorHelper.getStreamInfo(target.serviceId, target.url, false)
                .subscribeOn(Schedulers.io())
                .map(info -> {
                    final MediaSource source = resolver == null ? null : resolver.resolve(info);
                    if (source == null) {
                        throw new IllegalStateException("No playable stream");
                    }
                    return new Loaded(info, source);
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(loaded -> {
                    if (host != cardView || current != target) {
                        return;
                    }
                    currentInfo = loaded.info;
                    play(loaded.source);
                }, error -> {
                    markFailed(target.key());
                    if (current == target) {
                        stopPlayback();
                    }
                });
    }

    private static final class Loaded {
        final StreamInfo info;
        final MediaSource source;

        Loaded(final StreamInfo info, final MediaSource source) {
            this.info = info;
            this.source = source;
        }
    }

    private void play(@NonNull final MediaSource source) {
        final View cardView = host;
        if (cardView == null) {
            return;
        }
        final TextureView texture = cardView.findViewById(R.id.itemPreviewTexture);
        if (texture == null || player == null) {
            return;
        }
        bindMuteButton(cardView);
        player.clearVideoSurface();
        player.setVideoTextureView(texture);
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void onPlaybackError() {
        final InlinePreview.Target target = current;
        if (target == null || currentInfo == null || resolver == null || player == null) {
            return;
        }
        if (retryLevel >= 2) {
            markFailed(target.key());
            stopPlayback();
            return;
        }
        maxHeight = retryLevel == 0 ? Math.min(maxHeight, 480) : 360;
        retryLevel++;
        final MediaSource source = resolver.resolve(currentInfo);
        if (source == null) {
            markFailed(target.key());
            stopPlayback();
            return;
        }
        player.stop();
        play(source);
    }

    private void showVideo() {
        if (host == null) {
            return;
        }
        final TextureView texture = host.findViewById(R.id.itemPreviewTexture);
        applyCrop();
        if (texture != null) {
            texture.animate().alpha(1f).setDuration(220).start();
        }
        final View mute = host.findViewById(R.id.itemPreviewMute);
        if (mute != null) {
            mute.setAlpha(0f);
            mute.setVisibility(View.VISIBLE);
            mute.animate().alpha(1f).setDuration(220).start();
        }
    }

    /** Emulates centerCrop (like the thumbnail) on the TextureView. */
    private void applyCrop() {
        if (host == null || videoAspect <= 0f) {
            return;
        }
        final TextureView texture = host.findViewById(R.id.itemPreviewTexture);
        if (texture == null || texture.getWidth() == 0 || texture.getHeight() == 0) {
            return;
        }
        final float viewAspect = texture.getWidth() / (float) texture.getHeight();
        final float sx = videoAspect > viewAspect ? videoAspect / viewAspect : 1f;
        final float sy = videoAspect > viewAspect ? 1f : viewAspect / videoAspect;
        final Matrix m = new Matrix();
        m.setScale(sx, sy, texture.getWidth() / 2f, texture.getHeight() / 2f);
        texture.setTransform(m);
    }

    private void bindMuteButton(@NonNull final View cardView) {
        final View mute = cardView.findViewById(R.id.itemPreviewMute);
        if (!(mute instanceof ImageView)) {
            return;
        }
        updateMuteIcon((ImageView) mute);
        mute.setOnClickListener(v -> {
            unmutedThisSession = isMuted();
            applyAudio();
            updateMuteIcon((ImageView) mute);
        });
    }

    private void updateMuteIcon(@NonNull final ImageView mute) {
        final boolean muted = isMuted();
        mute.setImageResource(muted ? R.drawable.ic_eg_volume_off : R.drawable.ic_eg_volume_up);
        mute.setContentDescription(appContext.getString(
                muted ? R.string.inline_preview_unmute : R.string.inline_preview_mute));
    }

    private void stopPlayback() {
        if (loadDisposable == null && host == null && current == null) {
            return; // nothing is playing, this runs on every tick
        }
        if (loadDisposable != null) {
            loadDisposable.dispose();
            loadDisposable = null;
        }
        if (player != null) {
            player.stop();
            player.clearMediaItems();
            player.clearVideoSurface();
        }
        if (host != null) {
            resetCard(host);
        }
        host = null;
        current = null;
        currentInfo = null;
        retryLevel = 0;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        resolver = null;
        dataSource = null;
    }

    // endregion

    static void resetCard(@NonNull final View cardView) {
        final View texture = cardView.findViewById(R.id.itemPreviewTexture);
        if (texture != null) {
            texture.animate().cancel();
            texture.setAlpha(HIDDEN_ALPHA);
        }
        final View mute = cardView.findViewById(R.id.itemPreviewMute);
        if (mute != null) {
            mute.animate().cancel();
            mute.setVisibility(View.GONE);
            mute.setOnClickListener(null);
        }
    }
}
