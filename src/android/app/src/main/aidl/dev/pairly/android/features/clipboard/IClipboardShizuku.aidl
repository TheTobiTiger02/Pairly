package dev.pairly.android.features.clipboard;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IClipboardShizuku {
    void destroy() = 16777114;
    Bundle snapshot() = 1;
    ParcelFileDescriptor openContent(long revision) = 2;
    void turnOffWirelessDebugging() = 3;
}
