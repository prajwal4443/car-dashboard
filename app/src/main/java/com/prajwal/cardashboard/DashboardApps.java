package com.prajwal.cardashboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which apps get the dashboard's big tiles, and which media session its Now playing card follows. Pure logic, so it
 * runs in unit tests without a device.
 */
final class DashboardApps {
    /** PlaybackState.STATE_PLAYING and STATE_BUFFERING, copied so this class needs no Android classes. */
    static final int STATE_PLAYING = 3;
    static final int STATE_BUFFERING = 6;

    private DashboardApps() {
    }

    /**
     * The packages for the tiles, at most {@code count}: the apps added to Home (HomeTiles), in the order given (the
     * caller sorts them by name). Skips anything not launchable, taken off Home, or excluded (this app, the Maps
     * app), and duplicates.
     */
    static List<String> pickTilePackages(List<String> added, Collection<String> launchable, Collection<String> removed,
                                         Collection<String> excluded, int count) {
        Set<String> chosen = new LinkedHashSet<>();
        for (String pkg : added) {
            if (chosen.size() >= count) break;
            if (pkg == null || pkg.isEmpty()) continue;
            if (!launchable.contains(pkg) || removed.contains(pkg) || excluded.contains(pkg)) continue;
            chosen.add(pkg);
        }
        return new ArrayList<>(chosen);
    }

    /**
     * The session the Now playing card follows, given each active session's playback state in the order the system
     * lists them (most recently active first): the first one playing or buffering, else the most recent one, or -1
     * when there is none.
     */
    static int pickSessionIndex(int[] playbackStates) {
        if (playbackStates.length == 0) return -1;
        for (int i = 0; i < playbackStates.length; i++) {
            if (isPlaying(playbackStates[i])) return i;
        }
        return 0;
    }

    /** True when the play button should show pause. */
    static boolean isPlaying(int playbackState) {
        return playbackState == STATE_PLAYING || playbackState == STATE_BUFFERING;
    }

    /** The power-of-two BitmapFactory sample size that keeps a picture's short side at least target pixels. */
    static int calculateSampleSize(int width, int height, int target) {
        int sample = 1;
        if (width <= 0 || height <= 0 || target <= 0) return sample;
        while (Math.min(width, height) / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    /** Brightness of the dashboard background tint (HSV value): dark enough for white text. */
    static final float BACKDROP_VALUE = 0.28f;
    /** Most saturation the tint keeps, so a neon cover still gives a calm background. */
    static final float BACKDROP_MAX_SATURATION = 0.65f;

    /**
     * The dashboard's background tint from album art pixels (ARGB): the average colour of the vivid pixels, or of all
     * pixels when none is vivid (a black-and-white cover), at BACKDROP_VALUE brightness. Opaque.
     */
    static int calculateBackdropColor(int[] pixels) {
        long[] vivid = new long[4];
        long[] all = new long[4];
        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF, g = (pixel >> 8) & 0xFF, b = pixel & 0xFF;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            add(all, r, g, b);
            if (max >= 48 && max - min >= 40) add(vivid, r, g, b);
        }
        long[] pick = vivid[3] > 0 ? vivid : all;
        if (pick[3] == 0) return 0xFF000000;
        float[] hsv = convertRgbToHsv((int) (pick[0] / pick[3]), (int) (pick[1] / pick[3]), (int) (pick[2] / pick[3]));
        return convertHsvToRgb(hsv[0], Math.min(hsv[1], BACKDROP_MAX_SATURATION), BACKDROP_VALUE);
    }

    private static void add(long[] sum, int r, int g, int b) {
        sum[0] += r;
        sum[1] += g;
        sum[2] += b;
        sum[3]++;
    }

    /** Hue 0-360, saturation and value 0-1. */
    static float[] convertRgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float hue = 0;
        if (delta > 0) {
            if (max == rf) hue = 60 * (((gf - bf) / delta) % 6);
            else if (max == gf) hue = 60 * (((bf - rf) / delta) + 2);
            else hue = 60 * (((rf - gf) / delta) + 4);
        }
        if (hue < 0) hue += 360;
        return new float[] {hue, max == 0 ? 0 : delta / max, max};
    }

    /** Opaque ARGB from hue 0-360, saturation and value 0-1. */
    static int convertHsvToRgb(float hue, float saturation, float value) {
        float chroma = value * saturation;
        float x = chroma * (1 - Math.abs((hue / 60) % 2 - 1));
        float m = value - chroma;
        float r, g, b;
        if (hue < 60) { r = chroma; g = x; b = 0; }
        else if (hue < 120) { r = x; g = chroma; b = 0; }
        else if (hue < 180) { r = 0; g = chroma; b = x; }
        else if (hue < 240) { r = 0; g = x; b = chroma; }
        else if (hue < 300) { r = x; g = 0; b = chroma; }
        else { r = chroma; g = 0; b = x; }
        return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }

    /**
     * A colour of its own for a song whose album art never loaded (offline): a hue from the song's title and artist,
     * the same every time that song plays. [card top, card bottom, dashboard background tint], opaque.
     */
    static int[] calculateSongColors(String songKey) {
        float hue = (songKey.hashCode() & 0x7fffffff) % 360;
        return new int[] {convertHsvToRgb(hue, 0.6f, 0.55f), convertHsvToRgb(hue, 0.6f, 0.22f),
                convertHsvToRgb(hue, 0.55f, BACKDROP_VALUE)};
    }

    /** The file name album art is kept under on the unit, from its link or song: hex SHA-1, so any key is a safe name. */
    static String buildArtCacheName(String key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return name.append(".jpg").toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is part of every Java runtime", e);
        }
    }

    /** "312 MB" for memory freed by Clear, or null under 1 MB (nothing worth reporting). */
    static String formatFreedMemory(long bytes) {
        long megabytes = bytes / (1024 * 1024);
        return megabytes >= 1 ? megabytes + " MB" : null;
    }

    /** "1:24" or "1:02:03" for a position in milliseconds; negative or unknown positions read "0:00". */
    static String formatPosition(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest);
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, rest);
    }
}
