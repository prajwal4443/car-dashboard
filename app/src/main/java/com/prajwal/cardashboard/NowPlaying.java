package com.prajwal.cardashboard;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaDescription;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The dashboard's Now playing card (layout now_playing.xml), shared by Home and the split-screen pane.
 *
 * Previous, play/pause and next always work: without a session to follow they go out as media keys, which need no
 * permission. Title, artist, art and progress come from the active media session, which Android only shows to an
 * enabled notification listener (MusicListener); until it is enabled the card offers the setting instead.
 *
 * Fails soft: an unexpected error from a media app's session is logged once and the card falls back to media keys,
 * so it can never take Home down.
 */
final class NowPlaying {
    private static final String TAG = "CarDash.NowPlaying";
    private static final long TICK_MS = 1000;
    private static final int ART_TIMEOUT_MS = 5000;
    private static final int ART_MAX_BYTES = 8 * 1024 * 1024;
    private static final int ART_TARGET_PX = 384;
    private static final int COLOR_SAMPLE_PX = 24;
    /** Album art covers kept on the unit for offline play, about 30 KB each. */
    private static final int MAX_CACHED_ART = 400;
    private static final int MENU_PICK = 1;
    private static final int MENU_OFF = 2;
    private static final int MENU_WIDGET = 3;
    private static final int MENU_LAST_RUN = 4;

    private final Activity activity;
    /** Swaps the card for a widget (Home only); null in the split-screen pane. */
    private final Runnable useWidget;
    private final ImageView art;
    private final ImageView backdrop;
    private final View scrim;
    /** Told the album art's colour (null with no art) so the dashboard can tint its background to match. */
    private final Consumer<Integer> artColor;
    private Bitmap shownCover;
    private final TextView source;
    private final TextView title;
    private final TextView artist;
    private final TextView access;
    private final View progressRow;
    private final ProgressBar progress;
    private final TextView elapsed;
    private final TextView duration;
    private final ImageButton play;
    private final MediaSessionManager sessions;
    private final AudioManager audio;
    private final ComponentName listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::showProgress;
    /** Album art a player only gives as a link (Amazon Music, YouTube Music) loads here, off the main thread. */
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    /** The art (link, or "song:" and the song when there is no link) last asked for, and the last one loaded. */
    private String requestedArtKey;
    private String loadedArtKey;
    private Bitmap loadedArt;
    /** Title and artist of the song on the card ("title\u0000artist"); null when nothing plays. */
    private String songKey;
    /** Loaded album art kept on the unit, so posters still show when the song is played offline. */
    private final File artCacheDir;
    private String shownFallback;
    private boolean coverShown;

    private MediaController controller;
    private boolean started;
    private boolean broken;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            list -> safely(() -> follow(list));

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            safely(NowPlaying.this::showState);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            safely(NowPlaying.this::showMetadata);
        }

        @Override
        public void onSessionDestroyed() {
            safely(() -> bind(null));
        }
    };

    NowPlaying(Activity activity, View root, Runnable useWidget, Consumer<Integer> artColor) {
        this.activity = activity;
        this.useWidget = useWidget;
        this.artColor = artColor;
        backdrop = root.findViewById(R.id.npBackdrop);
        scrim = root.findViewById(R.id.npScrim);
        root.setClipToOutline(true);
        art = root.findViewById(R.id.npArt);
        source = root.findViewById(R.id.npSource);
        title = root.findViewById(R.id.npTitle);
        artist = root.findViewById(R.id.npArtist);
        access = root.findViewById(R.id.npAccess);
        progressRow = root.findViewById(R.id.npProgressRow);
        progress = root.findViewById(R.id.npProgress);
        elapsed = root.findViewById(R.id.npElapsed);
        duration = root.findViewById(R.id.npDuration);
        play = root.findViewById(R.id.npPlay);
        sessions = activity.getSystemService(MediaSessionManager.class);
        audio = activity.getSystemService(AudioManager.class);
        listener = new ComponentName(activity, MusicListener.class);
        artCacheDir = findArtCacheDir(activity);
        art.setClipToOutline(true);
        root.findViewById(R.id.npPrev).setOnClickListener(v -> command(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        play.setOnClickListener(v -> command(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        root.findViewById(R.id.npNext).setOnClickListener(v -> command(KeyEvent.KEYCODE_MEDIA_NEXT));
        View info = root.findViewById(R.id.npInfo);
        info.setOnClickListener(v -> openPlayer());
        info.setOnLongClickListener(v -> {
            showStartupMenu(v);
            return true;
        });
        access.setOnClickListener(v -> openAccessSettings());
        showMetadata();
        showState();
    }

    /** From onStart: follow the active sessions while the card is on screen. */
    void start() {
        started = true;
        safely(() -> {
            try {
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
                access.setVisibility(View.GONE);
                follow(sessions.getActiveSessions(listener));
            } catch (SecurityException notAllowed) {
                access.setVisibility(View.VISIBLE);
                bind(null);
            }
        });
    }

    /** From onDestroy: ends the art loader's thread. */
    void release() {
        artLoader.shutdownNow();
    }

    /** From onStop. */
    void stop() {
        started = false;
        handler.removeCallbacks(tick);
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (RuntimeException ignored) {
        }
        safely(() -> bind(null));
    }

    private void follow(List<MediaController> list) {
        if (list == null || list.isEmpty()) {
            bind(null);
            return;
        }
        int[] states = new int[list.size()];
        for (int i = 0; i < states.length; i++) {
            PlaybackState state = list.get(i).getPlaybackState();
            states[i] = state != null ? state.getState() : PlaybackState.STATE_NONE;
        }
        bind(list.get(DashboardApps.pickSessionIndex(states)));
    }

    private void bind(MediaController next) {
        boolean same = next != null && controller != null && next.getSessionToken().equals(controller.getSessionToken());
        if (!same) {
            if (controller != null) controller.unregisterCallback(callback);
            controller = next;
            if (controller != null) controller.registerCallback(callback, handler);
        }
        showMetadata();
        showState();
    }

    private void showMetadata() {
        MediaMetadata meta = controller != null ? controller.getMetadata() : null;
        CharSequence song = meta != null ? firstText(meta, MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE) : null;
        CharSequence singer = meta != null ? firstText(meta, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) : null;
        songKey = TextUtils.isEmpty(song) ? null : song + "\u0000" + (singer != null ? singer : "");
        title.setText(TextUtils.isEmpty(song) ? activity.getString(R.string.np_nothing_playing) : song);
        artist.setText(singer != null ? singer : "");
        showArt(meta);
        String app = controller != null ? appLabel(controller.getPackageName()) : null;
        String heading = activity.getString(R.string.np_heading);
        source.setText(app != null ? heading + " · " + app.toUpperCase(Locale.getDefault()) : heading);
    }

    /**
     * The art in the metadata if the player put a picture there, else the picture behind its art link: from the copy
     * kept on the unit if there is one (by link, then by song), else loaded and kept.
     */
    private void showArt(MediaMetadata meta) {
        Bitmap cover = meta != null ? firstBitmap(meta) : null;
        if (cover != null) {
            requestedArtKey = null;
            showCover(cover);
            return;
        }
        String uri = meta != null ? firstArtUri(meta) : null;
        String songArtKey = songKey != null ? "song:" + songKey : null;
        String key = uri != null ? uri : songArtKey;
        if (key == null) {
            requestedArtKey = null;
            showCover(null);
        } else if (key.equals(loadedArtKey)) {
            showCover(loadedArt);
        } else if (!key.equals(requestedArtKey)) {
            requestedArtKey = key;
            showCover(null);
            ContentResolver resolver = activity.getContentResolver();
            File cacheDir = artCacheDir;
            artLoader.execute(() -> {
                Bitmap picture = uri != null ? readCachedArt(cacheDir, uri) : null;
                if (picture == null && songArtKey != null) picture = readCachedArt(cacheDir, songArtKey);
                if (picture == null && uri != null) {
                    picture = readArt(resolver, uri);
                    if (picture != null) {
                        writeCachedArt(cacheDir, uri, picture);
                        if (songArtKey != null) writeCachedArt(cacheDir, songArtKey, picture);
                    }
                }
                Bitmap found = picture;
                handler.post(() -> {
                    if (!key.equals(requestedArtKey) || found == null) return;
                    loadedArtKey = key;
                    loadedArt = found;
                    showCover(found);
                });
            });
        }
    }

    /**
     * The cover fills the card behind a dark fade. Without one, a song still gets a colour of its own (from its title,
     * the same each time) on the card and the background; with nothing playing, the card and background go plain.
     */
    private void showCover(Bitmap cover) {
        String fallback = cover == null ? songKey : null;
        if (coverShown && cover == shownCover && Objects.equals(fallback, shownFallback)) return;
        coverShown = true;
        shownCover = cover;
        shownFallback = fallback;
        if (cover != null) {
            backdrop.setImageBitmap(cover);
            backdrop.setVisibility(View.VISIBLE);
            scrim.setVisibility(View.VISIBLE);
            art.setVisibility(View.GONE);
            artColor.accept(calculateCoverColor(cover));
            return;
        }
        scrim.setVisibility(View.GONE);
        art.setVisibility(View.VISIBLE);
        art.setScaleType(ImageView.ScaleType.CENTER);
        art.setImageResource(R.drawable.ic_music_note);
        if (fallback == null) {
            backdrop.setVisibility(View.GONE);
            artColor.accept(null);
            return;
        }
        int[] colors = DashboardApps.calculateSongColors(fallback);
        backdrop.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {colors[0], colors[1]}));
        backdrop.setVisibility(View.VISIBLE);
        artColor.accept(colors[2]);
    }

    /** The background tint for a cover, from a 24x24 copy of it; null if the bitmap can't be read. */
    private static Integer calculateCoverColor(Bitmap cover) {
        try {
            Bitmap readable = cover.getConfig() == Bitmap.Config.HARDWARE
                    ? cover.copy(Bitmap.Config.ARGB_8888, false) : cover;
            Bitmap small = Bitmap.createScaledBitmap(readable, COLOR_SAMPLE_PX, COLOR_SAMPLE_PX, true);
            int[] pixels = new int[COLOR_SAMPLE_PX * COLOR_SAMPLE_PX];
            small.getPixels(pixels, 0, COLOR_SAMPLE_PX, 0, 0, COLOR_SAMPLE_PX, COLOR_SAMPLE_PX);
            return DashboardApps.calculateBackdropColor(pixels);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the cover's colour", e);
            return null;
        }
    }

    private void showState() {
        PlaybackState state = controller != null ? controller.getPlaybackState() : null;
        boolean playing = state != null && DashboardApps.isPlaying(state.getState());
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(activity.getString(playing ? R.string.np_pause : R.string.np_play));
        showProgress();
    }

    private void showProgress() {
        handler.removeCallbacks(tick);
        PlaybackState state = controller != null ? controller.getPlaybackState() : null;
        MediaMetadata meta = controller != null ? controller.getMetadata() : null;
        long length = meta != null ? meta.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (state == null || length <= 0 || state.getPosition() < 0) {
            progressRow.setVisibility(View.INVISIBLE);
            return;
        }
        boolean playing = DashboardApps.isPlaying(state.getState());
        long position = state.getPosition();
        if (playing) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        position = Math.max(0, Math.min(length, position));
        progressRow.setVisibility(View.VISIBLE);
        progress.setMax(1000);
        progress.setProgress((int) (position * 1000 / length));
        elapsed.setText(DashboardApps.formatPosition(position));
        duration.setText(DashboardApps.formatPosition(length));
        if (playing && started) handler.postDelayed(tick, TICK_MS);
    }

    /** The followed session's own controls when there is one, else a media key to whichever app has audio focus. */
    private void command(int keyCode) {
        if (controller != null && !broken) {
            try {
                MediaController.TransportControls controls = controller.getTransportControls();
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    controls.skipToPrevious();
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    controls.skipToNext();
                } else {
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && DashboardApps.isPlaying(state.getState())) controls.pause();
                    else controls.play();
                }
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "session control failed, sending a media key instead", e);
            }
        }
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /** Tapping the song opens the app playing it. */
    private void openPlayer() {
        if (controller == null) return;
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
        PendingIntent sessionActivity = controller.getSessionActivity();
        try {
            if (sessionActivity != null) {
                sessionActivity.send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
        }
        if (launch != null) DashboardActivity.startApp(activity, launch);
    }

    /** Hold the song: play this app when the car starts, stop the app picked before, or use a widget instead. */
    private void showStartupMenu(View anchor) {
        String saved = CarStartup.lookupMusicApp(activity);
        String current = controller != null ? controller.getPackageName() : null;
        PopupMenu menu = new PopupMenu(activity, anchor);
        if (current != null && !current.equals(saved)) {
            menu.getMenu().add(0, MENU_PICK, 0, activity.getString(R.string.np_startup_pick, labelOrPackage(current)));
        }
        if (saved != null) {
            menu.getMenu().add(0, MENU_OFF, 0, activity.getString(R.string.np_startup_off, labelOrPackage(saved)));
        }
        if (useWidget != null) menu.getMenu().add(0, MENU_WIDGET, 0, R.string.np_use_widget);
        String lastRun = CarStartup.lookupLastRun(activity);
        if (lastRun != null) {
            menu.getMenu().add(0, MENU_LAST_RUN, 0, activity.getString(R.string.np_startup_last_run, lastRun))
                    .setEnabled(false);
        }
        if (!menu.getMenu().hasVisibleItems()) {
            Toast.makeText(activity, R.string.np_startup_hint, Toast.LENGTH_LONG).show();
            return;
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_WIDGET) {
                useWidget.run();
                return true;
            }
            boolean pick = item.getItemId() == MENU_PICK;
            CarStartup.saveMusicApp(activity, pick ? current : null);
            Toast.makeText(activity, pick ? activity.getString(R.string.np_startup_saved, labelOrPackage(current))
                    : activity.getString(R.string.np_startup_cleared), Toast.LENGTH_SHORT).show();
            return true;
        });
        menu.show();
    }

    private String labelOrPackage(String pkg) {
        String label = appLabel(pkg);
        return label != null ? label : pkg;
    }

    private void openAccessSettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException e) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private String appLabel(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static CharSequence firstText(MediaMetadata meta, String... keys) {
        for (String key : keys) {
            CharSequence value = meta.getText(key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private static Bitmap firstBitmap(MediaMetadata meta) {
        for (String key : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap value = meta.getBitmap(key);
            if (value != null) return value;
        }
        MediaDescription description = meta.getDescription();
        return description != null ? description.getIconBitmap() : null;
    }

    private static String firstArtUri(MediaMetadata meta) {
        for (String key : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String value = meta.getString(key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        MediaDescription description = meta.getDescription();
        Uri icon = description != null ? description.getIconUri() : null;
        return icon != null ? icon.toString() : null;
    }

    /** Where loaded album art is kept on the unit. */
    static File findArtCacheDir(Context context) {
        return new File(context.getFilesDir(), "art");
    }

    /** {number of kept posters, their total bytes}, for dashboard settings. */
    static long[] measureArtCache(Context context) {
        File[] kept = findArtCacheDir(context).listFiles();
        long bytes = 0;
        if (kept == null) return new long[] {0, 0};
        for (File file : kept) bytes += file.length();
        return new long[] {kept.length, bytes};
    }

    /** Deletes every kept poster; songs show them again once they load online. */
    static void clearArtCache(Context context) {
        File[] kept = findArtCacheDir(context).listFiles();
        if (kept == null) return;
        for (File file : kept) {
            if (!file.delete()) Log.w(TAG, "could not delete " + file);
        }
    }

    private static Bitmap readCachedArt(File dir, String key) {
        File file = new File(dir, DashboardApps.buildArtCacheName(key));
        if (!file.isFile()) return null;
        file.setLastModified(System.currentTimeMillis());   // recently used: kept longest when pruning
        return BitmapFactory.decodeFile(file.getPath());
    }

    /** Keeps a loaded cover on the unit (JPEG), dropping the least recently used beyond MAX_CACHED_ART. */
    private static void writeCachedArt(File dir, String key, Bitmap picture) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File target = new File(dir, DashboardApps.buildArtCacheName(key));
        File partial = new File(dir, target.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(partial)) {
            picture.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            Log.w(TAG, "could not keep album art", e);
            partial.delete();
            return;
        }
        if (!partial.renameTo(target)) partial.delete();
        File[] kept = dir.listFiles();
        if (kept == null || kept.length <= MAX_CACHED_ART) return;
        Arrays.sort(kept, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
        for (int i = 0; i < kept.length - MAX_CACHED_ART; i++) kept[i].delete();
    }

    /** The picture behind an art link (https, or a content URI the player shares), scaled down; null if unreadable. */
    private static Bitmap readArt(ContentResolver resolver, String uri) {
        try {
            byte[] bytes = readArtBytes(resolver, Uri.parse(uri));
            if (bytes == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = DashboardApps.calculateSampleSize(bounds.outWidth, bounds.outHeight, ART_TARGET_PX);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not load album art", e);
            return null;
        }
    }

    private static byte[] readArtBytes(ContentResolver resolver, Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(ART_TIMEOUT_MS);
            connection.setReadTimeout(ART_TIMEOUT_MS);
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input);
            } finally {
                connection.disconnect();
            }
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            return input != null ? readLimited(input) : null;
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > ART_MAX_BYTES) throw new IOException("album art too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    /** Runs one step; the first unexpected error stops following sessions and leaves the media-key buttons. */
    private void safely(Runnable step) {
        if (broken) return;
        try {
            step.run();
        } catch (RuntimeException e) {
            broken = true;
            Log.w(TAG, "Now playing switched to media keys only for this session", e);
            if (controller != null) {
                try {
                    controller.unregisterCallback(callback);
                } catch (RuntimeException ignored) {
                }
            }
            controller = null;
            handler.removeCallbacks(tick);
        }
    }
}
