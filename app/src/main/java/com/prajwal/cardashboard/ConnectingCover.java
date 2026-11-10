package com.prajwal.cardashboard;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

/**
 * "Connecting to your phone…" over the whole screen while CarLink reconnects at car start, so CarLink's own connecting
 * screen is never seen. A "Display over other apps" window: it doesn't pause the app under it, so CarLink stays in
 * front and keeps connecting. It takes no focus (CarLink's window stays the focused, active one) but takes the
 * touches. Its AutoPlay button hands over to CarLink. Main thread only.
 */
final class ConnectingCover {
    private static View shown;

    private ConnectingCover() {
    }

    /** Puts the cover up; false (and nothing shown) without "Display over other apps". */
    static boolean show(Context context, Runnable onShowCarLink) {
        if (shown != null) return true;
        Context app = context.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) return false;
        View cover = LayoutInflater.from(new ContextThemeWrapper(app, R.style.Theme_Dashboard))
                .inflate(R.layout.autoplay_cover, null);
        cover.findViewById(R.id.autoplayCoverShow).setOnClickListener(v -> onShowCarLink.run());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            app.getSystemService(WindowManager.class).addView(cover, params);
        } catch (RuntimeException e) {
            Log.w(AutoPlay.TAG, "connecting cover could not be shown", e);
            return false;
        }
        shown = cover;
        return true;
    }

    static boolean isShown() {
        return shown != null;
    }

    static void hide() {
        View cover = shown;
        if (cover == null) return;
        shown = null;
        try {
            cover.getContext().getSystemService(WindowManager.class).removeView(cover);
        } catch (RuntimeException e) {
            Log.w(AutoPlay.TAG, "connecting cover could not be removed", e);
        }
    }
}
