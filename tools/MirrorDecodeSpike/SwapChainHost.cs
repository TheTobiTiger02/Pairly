using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace MirrorDecodeSpike;

/// <summary>
/// A bare child HWND for the decoder's swap chain to present into.
///
/// WPF cannot draw over an HwndHost (the "airspace" limitation), which is acceptable here and in
/// the real mirror window: the video is a full rectangle with chrome around it, never underneath
/// WPF content. In exchange the swap chain presents straight to the window with no compositor hop.
/// </summary>
public sealed class SwapChainHost : HwndHost
{
    private const int WsChild = 0x40000000;
    private const int WsVisible = 0x10000000;
    private const int WsClipChildren = 0x02000000;
    private const int WsClipSiblings = 0x04000000;

    public nint SurfaceHandle { get; private set; }

    public event Action<int, int>? SurfaceResized;

    protected override HandleRef BuildWindowCore(HandleRef hwndParent)
    {
        var handle = CreateWindowExW(
            0,
            "static",
            null,
            WsChild | WsVisible | WsClipChildren | WsClipSiblings,
            0,
            0,
            1,
            1,
            hwndParent.Handle,
            nint.Zero,
            nint.Zero,
            nint.Zero);

        SurfaceHandle = handle;
        return new HandleRef(this, handle);
    }

    protected override void DestroyWindowCore(HandleRef hwnd)
    {
        DestroyWindow(hwnd.Handle);
        SurfaceHandle = nint.Zero;
    }

    protected override void OnRenderSizeChanged(SizeChangedInfo info)
    {
        base.OnRenderSizeChanged(info);
        var dpi = VisualTreeHelper.GetDpi(this);
        SurfaceResized?.Invoke(
            (int)Math.Round(info.NewSize.Width * dpi.DpiScaleX),
            (int)Math.Round(info.NewSize.Height * dpi.DpiScaleY));
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint CreateWindowExW(
        int exStyle,
        string className,
        string? windowName,
        int style,
        int x,
        int y,
        int width,
        int height,
        nint parent,
        nint menu,
        nint instance,
        nint param);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyWindow(nint hwnd);
}
