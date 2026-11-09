package org.schabi.newpipe.settings;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.App;
import org.schabi.newpipe.R;
import org.schabi.newpipe.settings.migration.MigrationManager;
import org.schabi.newpipe.util.DeviceUtils;

import java.io.File;
import java.util.Set;

/*
 * Created by k3b on 07.01.2016.
 *
 * Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
 * NewPipeSettings.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Helper class for global settings.
 */
public final class NewPipeSettings {
    private NewPipeSettings() { }

    public static void initSettings(final Context context) {
        // first run migrations, then setDefaultValues, since the latter requires the correct types
        MigrationManager.runMigrationsIfNeeded(context);

        // EggPlay: Indonesian content (language and country) by default on first run
        final SharedPreferences langPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String contentLangKey = context.getString(R.string.content_language_key);
        final String contentCountryKey = context.getString(R.string.content_country_key);
        if (!langPrefs.contains(contentLangKey) || !langPrefs.contains(contentCountryKey)) {
            final SharedPreferences.Editor langEditor = langPrefs.edit();
            if (!langPrefs.contains(contentLangKey)) {
                langEditor.putString(contentLangKey, "id");
            }
            if (!langPrefs.contains(contentCountryKey)) {
                langEditor.putString(contentCountryKey, "ID");
            }
            langEditor.apply();
        }

        // readAgain is true so that if new settings are added their default value is set
        PreferenceManager.setDefaultValues(context, R.xml.main_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.video_audio_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.download_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.appearance_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.history_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.content_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.player_notification_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.update_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.debug_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.backup_restore_settings, true);

        saveDefaultVideoDownloadDirectory(context);
        saveDefaultAudioDownloadDirectory(context);

        disableMediaTunnelingIfNecessary(context);
    }

    public static void saveDefaultVideoDownloadDirectory(final Context context) {
        saveDefaultDirectory(context, R.string.download_path_video_key,
                Environment.DIRECTORY_MOVIES);
    }

    public static void saveDefaultAudioDownloadDirectory(final Context context) {
        saveDefaultDirectory(context, R.string.download_path_audio_key,
                Environment.DIRECTORY_MUSIC);
    }

    /**
     * Makes sure a usable download folder is saved, so the user is never forced to pick one
     * with a system file manager (which some devices don't have at all).
     *
     * @param context the context
     */
    public static void ensureDownloadDirectories(final Context context) {
        saveDefaultVideoDownloadDirectory(context);
        saveDefaultAudioDownloadDirectory(context);
    }

    private static void saveDefaultDirectory(final Context context, final int keyID,
                                             final String defaultDirectoryName) {
        if (useStorageAccessFramework(context)) {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String key = context.getString(keyID);
        final String downloadPath = prefs.getString(key, null);

        if (!isNullOrEmpty(downloadPath)) {
            final boolean isSafPath = downloadPath.startsWith("content:");
            final boolean isOldStylePath = downloadPath.charAt(0) == File.separatorChar;
            final boolean isAppSpecificFallback =
                    downloadPath.contains("/Android/data/" + context.getPackageName());
            // keep the current path, unless it can't work in direct (non-SAF) mode, or it is the
            // app-private fallback although shared storage is accessible now
            if (!isSafPath && !isOldStylePath
                    && !(isAppSpecificFallback && canUseSharedStorage(context))) {
                return;
            }
        }

        prefs.edit()
                .putString(key, getDefaultDownloadDir(context, defaultDirectoryName)
                        .toURI().toString())
                .apply();
    }

    /**
     * @param context the context
     * @return {@code true} if files can be written directly to the public storage of the device
     */
    public static boolean canUseSharedStorage(final Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Default download folder, created automatically (no picker, no permission dialog):
     * {@code Download/EggPlay} on Android 10+, {@code Movies|Music/EggPlay} before that.
     * If the public folder can't be written, the app's own folder on the device storage is used.
     *
     * @param context the context
     * @param defaultDirectoryName the name of the default directory (e.g. Movies or Music)
     * @return the default download folder
     */
    @NonNull
    public static File getDefaultDownloadDir(final Context context,
                                             final String defaultDirectoryName) {
        if (canUseSharedStorage(context)) {
            final File publicDir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new File(getDir(Environment.DIRECTORY_DOWNLOADS), "EggPlay")
                    : new File(getDir(defaultDirectoryName), "EggPlay");
            if (isWritableFolder(publicDir)) {
                return publicDir;
            }
        }
        final File appDir = context.getExternalFilesDir(defaultDirectoryName);
        return appDir != null ? appDir : new File(context.getFilesDir(), defaultDirectoryName);
    }

    private static boolean isWritableFolder(final File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            final File probe = File.createTempFile(".eggplay", ".tmp", dir);
            return probe.delete() || !probe.exists();
        } catch (final Exception e) {
            return false;
        }
    }

    @NonNull
    public static File getDir(final String defaultDirectoryName) {
        return new File(Environment.getExternalStorageDirectory(), defaultDirectoryName);
    }

    /**
     * @param context the context
     * @return {@code true} if a system folder picker (Storage Access Framework) is available
     */
    public static boolean isSafPickerAvailable(final Context context) {
        try {
            return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .resolveActivity(context.getPackageManager()) != null;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * SAF is only used if the user turned it on <b>and</b> the device actually has a folder picker.
     * Otherwise downloads go straight to the device storage.
     *
     * @param context the context
     * @return {@code true} if the Storage Access Framework should be used
     */
    public static boolean useStorageAccessFramework(final Context context) {
        if (!isSafPickerAvailable(context)) {
            return false;
        }

        final String key = context.getString(R.string.storage_use_saf);
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        return prefs.getBoolean(key, false);
    }

    private static boolean showSearchSuggestions(final Context context,
                                                 final SharedPreferences sharedPreferences,
                                                 @StringRes final int key) {
        final Set<String> enabledSearchSuggestions = sharedPreferences.getStringSet(
                context.getString(R.string.show_search_suggestions_key), null);

        if (enabledSearchSuggestions == null) {
            return true; // defaults to true
        } else {
            return enabledSearchSuggestions.contains(context.getString(key));
        }
    }

    public static boolean showLocalSearchSuggestions(final Context context,
                                                     final SharedPreferences sharedPreferences) {
        return showSearchSuggestions(context, sharedPreferences,
                R.string.show_local_search_suggestions_key);
    }

    public static boolean showRemoteSearchSuggestions(final Context context,
                                                      final SharedPreferences sharedPreferences) {
        return showSearchSuggestions(context, sharedPreferences,
                R.string.show_remote_search_suggestions_key);
    }

    private static void disableMediaTunnelingIfNecessary(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String disabledTunnelingKey = context.getString(R.string.disable_media_tunneling_key);
        final String disabledTunnelingAutomaticallyKey =
                context.getString(R.string.disabled_media_tunneling_automatically_key);
        final String blacklistVersionKey =
                context.getString(R.string.media_tunneling_device_blacklist_version);

        final int lastMediaTunnelingUpdate = prefs.getInt(blacklistVersionKey, 0);
        final boolean wasDeviceBlacklistUpdated =
                DeviceUtils.MEDIA_TUNNELING_DEVICE_BLACKLIST_VERSION != lastMediaTunnelingUpdate;
        final boolean wasMediaTunnelingEnabledByUser =
                prefs.getInt(disabledTunnelingAutomaticallyKey, -1) == 0
                        && !prefs.getBoolean(disabledTunnelingKey, false);

        if (App.getInstance().isFirstRun()
                || (wasDeviceBlacklistUpdated && !wasMediaTunnelingEnabledByUser)) {
            setMediaTunneling(context);
        }
    }

    /**
     * Check if device does not support media tunneling
     * and disable that exoplayer feature if necessary.
     * @see DeviceUtils#shouldSupportMediaTunneling()
     * @param context
     */
    public static void setMediaTunneling(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!DeviceUtils.shouldSupportMediaTunneling()) {
            prefs.edit()
                    .putBoolean(context.getString(R.string.disable_media_tunneling_key), true)
                    .putInt(context.getString(
                            R.string.disabled_media_tunneling_automatically_key), 1)
                    .putInt(context.getString(R.string.media_tunneling_device_blacklist_version),
                            DeviceUtils.MEDIA_TUNNELING_DEVICE_BLACKLIST_VERSION)
                    .apply();
        } else {
            prefs.edit()
                    .putInt(context.getString(R.string.media_tunneling_device_blacklist_version),
                            DeviceUtils.MEDIA_TUNNELING_DEVICE_BLACKLIST_VERSION).apply();
        }
    }
}
