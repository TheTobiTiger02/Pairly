package dev.pairly.android.features.shizuku;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Pairly's helper process, running as the shell user through Shizuku. Every method is narrow and
 * acts only on Pairly itself: there is deliberately no general "run this command" call.
 */
interface IShizukuShell {
    void destroy() = 16777114;

    // Clipboard (background sync)
    Bundle snapshot() = 1;
    ParcelFileDescriptor openContent(long revision) = 2;

    // Settings
    void turnOffWirelessDebugging() = 3;
    boolean setScreenCaptureAllowed(String packageName, boolean allowed) = 4;
    int wirelessDebugging() = 5;
    /** Grants Pairly's own permissions and special access; returns the keys that were set up. */
    String[] grantAccess(String packageName, in String[] keys) = 6;

    // Input from the PC while mirroring (video pixels plus the video size they refer to).
    // oneway: delivered in order, without making the stream wait for the phone.
    oneway void touch(int action, long pointerId, int x, int y, int width, int height, int buttons) = 10;
    oneway void scroll(int x, int y, int width, int height, int horizontal, int vertical) = 11;
    oneway void key(int action, int keyCode, int repeatCount, int metaState) = 12;
    oneway void text(String value) = 13;
    oneway void navigate(int target) = 14;
}
