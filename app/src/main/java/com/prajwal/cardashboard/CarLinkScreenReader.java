package com.prajwal.cardashboard;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * Accessibility service that only looks at CarLink's own screen, for AutoPlay's car-start cover (ConnectingCover):
 * while CarLink connects, its window has text ("Starting CarPlay", "Reaching phone", Cancel); once CarPlay or
 * Android Auto is on screen, the window holds only the projected video and no text at all. Checked on a test phone
 * with an iPhone; it doesn't depend on CarLink's wording. Reads nothing from other apps (packageNames in its config).
 */
public class CarLinkScreenReader extends AccessibilityService {
    enum Screen {
        /** The service isn't running (Accessibility is off for Car Dashboard). */
        UNKNOWN,
        /** No CarLink window in front. */
        NOT_SHOWN,
        /** A CarLink window with text: Home, the connecting screen, an error. */
        CONNECTING,
        /** A CarLink window with no text: the phone's projection. */
        PROJECTING
    }

    private static volatile CarLinkScreenReader running;

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing to do per event: AutoPlay asks findScreen() while its cover is up.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        running = null;
        super.onDestroy();
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName mine = new ComponentName(context, CarLinkScreenReader.class);
        for (String entry : enabled.split(":")) {
            if (mine.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    /**
     * What CarLink shows now. The active window first: AutoPlay's cover takes no focus, so CarLink's activity stays
     * the active window under it. Then the window list, in case the cover hides CarLink's window from it.
     */
    static Screen findScreen() {
        CarLinkScreenReader service = running;
        if (service == null) return Screen.UNKNOWN;
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (isCarLink(active)) return countText(active) > 0 ? Screen.CONNECTING : Screen.PROJECTING;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (isCarLink(root)) return countText(root) > 0 ? Screen.CONNECTING : Screen.PROJECTING;
        }
        return Screen.NOT_SHOWN;
    }

    private static boolean isCarLink(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null
                && AutoPlay.CARLINK_PACKAGE.contentEquals(root.getPackageName());
    }

    /** Visible nodes with text or a description under {@code node}. */
    private static int countText(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) return 0;
        int found = hasWords(node.getText()) || hasWords(node.getContentDescription()) ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) found += countText(node.getChild(i));
        return found;
    }

    private static boolean hasWords(CharSequence text) {
        return text != null && text.toString().trim().length() > 0;
    }
}
