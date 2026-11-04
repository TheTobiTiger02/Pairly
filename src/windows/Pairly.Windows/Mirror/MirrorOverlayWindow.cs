using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Brushes = System.Windows.Media.Brushes;

namespace Pairly.Windows.Mirror;

/// <summary>
/// A transparent, owned window that floats mirror controls over the video.
///
/// The video is a child HWND, and WPF cannot draw over one (airspace), so the controls cannot live
/// in the mirror window's own visual tree. A separate layered window can sit above it. It never
/// activates: a click on a button must not take keyboard focus from the video, or typing would
/// stop reaching the phone.
/// </summary>
public class MirrorOverlayWindow : Window
{
    private const int GwlExStyle = -20;
    private const int WsExNoActivate = 0x08000000;
    private const int WsExToolWindow = 0x00000080;
    private static readonly Duration FadeDuration = new(TimeSpan.FromMilliseconds(160));

    private bool _wantVisible;

    public MirrorOverlayWindow()
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ShowInTaskbar = false;
        ShowActivated = false;
        ResizeMode = ResizeMode.NoResize;
        Focusable = false;
        Opacity = 0;
        // The app's window style for fonts and colours; the transparent background set above wins.
        SetResourceReference(StyleProperty, typeof(Window));
        SourceInitialized += (_, _) =>
        {
            var handle = new WindowInteropHelper(this).Handle;
            SetWindowLongW(handle, GwlExStyle, GetWindowLongW(handle, GwlExStyle) | WsExNoActivate | WsExToolWindow);
        };
    }

    /// <summary>Whether the bar is shown (or fading in), as opposed to hidden or fading out.</summary>
    public bool IsShown => _wantVisible;

    /// <summary>Fades the bar in or out. A faded-out bar is hidden, so it cannot catch the mouse.</summary>
    public void FadeTo(bool visible, bool animate = true)
    {
        if (visible == _wantVisible && (visible ? IsVisible : !IsVisible || Opacity == 0))
        {
            return;
        }

        _wantVisible = visible;
        if (visible && !IsVisible)
        {
            Show();
        }

        var animation = new DoubleAnimation(visible ? 1 : 0, animate ? FadeDuration : new Duration(TimeSpan.Zero));
        animation.Completed += (_, _) =>
        {
            if (!_wantVisible)
            {
                Hide();
            }
        };
        BeginAnimation(OpacityProperty, animation);
    }

    [DllImport("user32.dll")]
    private static extern int GetWindowLongW(nint hwnd, int index);

    [DllImport("user32.dll")]
    private static extern int SetWindowLongW(nint hwnd, int index, int value);
}
