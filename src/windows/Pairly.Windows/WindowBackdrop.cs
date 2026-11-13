using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Shell;
using System.Windows.Interop;
using System.Windows.Media;
using Brushes = System.Windows.Media.Brushes;

namespace Pairly.Windows;

/// <summary>
/// Gives a window a native frame that follows the app's light/dark theme, rounded corners, and
/// the theme's own ground color. No Mica: it tints the window with the wallpaper, which hides the
/// ground the design is built on.
/// </summary>
internal static class WindowBackdrop
{
    private const int DwmwaUseImmersiveDarkMode = 20;
    private const int DwmwaWindowCornerPreference = 33;
    private const int DwmwaSystemBackdropType = 38;
    private const int DwmsbtNone = 1;
    private const int DwmwcpDoNotRound = 1;
    private const int DwmwcpRound = 2;

    // DWMWA_SYSTEMBACKDROP_TYPE first shipped in Windows 11 22H2.
    private const int SystemBackdropMinimumBuild = 22621;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>Call once the window has a handle; the backdrop then tracks theme changes on its own.</summary>
    /// <param name="frameOnly">
    /// Rounded corners and a theme-coloured frame only: for windows whose content covers the
    /// whole client area anyway, such as video.
    /// </param>
    public static void Attach(Window window, bool frameOnly = false)
    {
        void Apply(object? sender, EventArgs e) => window.Dispatcher.BeginInvoke(() => ApplyCore(window, frameOnly));

        if (new WindowInteropHelper(window).Handle == IntPtr.Zero)
        {
            window.SourceInitialized += (_, _) => ApplyCore(window, frameOnly);
        }
        else
        {
            ApplyCore(window, frameOnly);
        }

        ThemeManager.ThemeChanged += Apply;
        window.Closed += (_, _) => ThemeManager.ThemeChanged -= Apply;
    }

    /// <summary>Square corners for a window covering a whole monitor, round ones otherwise.</summary>
    public static void SetRoundedCorners(Window window, bool rounded)
    {
        var handle = new WindowInteropHelper(window).Handle;
        if (handle == IntPtr.Zero)
        {
            return;
        }

        var corners = rounded ? DwmwcpRound : DwmwcpDoNotRound;
        DwmSetWindowAttribute(handle, DwmwaWindowCornerPreference, ref corners, sizeof(int));
    }

    private static void ApplyCore(Window window, bool frameOnly)
    {
        var handle = new WindowInteropHelper(window).Handle;
        if (handle == IntPtr.Zero || HwndSource.FromHwnd(handle) is not { } source)
        {
            return;
        }

        // The opaque ground covers the system's caption buttons, so CaptionButtons draws its own.
        if (!frameOnly && WindowChrome.GetWindowChrome(window) is { UseAeroCaptionButtons: true } chrome)
        {
            var updated = (WindowChrome)chrome.Clone();
            updated.UseAeroCaptionButtons = false;
            WindowChrome.SetWindowChrome(window, updated);
        }

        var dark = ThemeManager.IsDark ? 1 : 0;
        DwmSetWindowAttribute(handle, DwmwaUseImmersiveDarkMode, ref dark, sizeof(int));
        var corners = DwmwcpRound;
        DwmSetWindowAttribute(handle, DwmwaWindowCornerPreference, ref corners, sizeof(int));
        if (frameOnly)
        {
            return;
        }

        if (Environment.OSVersion.Version.Build >= SystemBackdropMinimumBuild)
        {
            var backdrop = DwmsbtNone;
            DwmSetWindowAttribute(handle, DwmwaSystemBackdropType, ref backdrop, sizeof(int));
        }

        window.SetResourceReference(Window.BackgroundProperty, "SolidBackgroundBrush");
    }
}
