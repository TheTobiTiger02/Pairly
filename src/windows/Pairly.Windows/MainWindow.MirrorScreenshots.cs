using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Interop;
using System.Windows.Media;
using Pairly.Windows.Localization;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Image = System.Windows.Controls.Image;

namespace Pairly.Windows;

/// <summary>
/// The phone screen window for <c>--render-screenshots</c>: playing with controls hidden and shown,
/// full screen, connecting and needing attention. Its controls are separate overlay windows, so
/// these are captured from the screen rather than rendered from one visual tree.
/// </summary>
public partial class MainWindow
{
    private async Task RenderMirrorScreenshotsAsync(string folder, string prefix, Func<int, Task> settle, List<string> failures)
    {
        var window = new MirrorWindow("Pixel 8", "screenshot-phone", "balanced | 1080x2400 | 60 fps | h264")
        {
            Topmost = true
        };
        window.Show();
        try
        {
            window.ShowPreview(BuildDemoPhoneScreen(), 1080, 2400, "1080x2400");
            window.SetControlsShown(false);
            await settle(500);
            CaptureWindow(window, Path.Combine(folder, $"{prefix}-mirror-idle.png"), failures);

            window.SetControlsShown(true);
            await settle(500);
            CaptureWindow(window, Path.Combine(folder, $"{prefix}-mirror-hover.png"), failures);

            window.ToggleFullScreen();
            window.SetControlsShown(true);
            await settle(700);
            CaptureWindow(window, Path.Combine(folder, $"{prefix}-mirror-fullscreen.png"), failures);
            window.ToggleFullScreen();
        }
        finally
        {
            window.Close();
        }

        var connecting = new MirrorWindow("Pixel 8", "screenshot-phone", string.Empty) { Topmost = true };
        connecting.Show();
        try
        {
            await settle(600);
            CaptureWindow(connecting, Path.Combine(folder, $"{prefix}-mirror-connecting.png"), failures);

            connecting.ShowConnectionError(L.T("the_hardware_video_decoder_could_not_start_c"));
            await settle(500);
            CaptureWindow(connecting, Path.Combine(folder, $"{prefix}-mirror-error.png"), failures);
        }
        finally
        {
            connecting.Close();
        }
    }

    /// <summary>Copies the window's screen area, overlay windows included.</summary>
    private static void CaptureWindow(Window window, string path, List<string> failures)
    {
        try
        {
            var handle = new WindowInteropHelper(window).Handle;
            if (!GetWindowRect(handle, out var rect))
            {
                throw new InvalidOperationException("GetWindowRect failed.");
            }

            var width = rect.Right - rect.Left;
            var height = rect.Bottom - rect.Top;
            using var bitmap = new System.Drawing.Bitmap(width, height);
            using (var graphics = System.Drawing.Graphics.FromImage(bitmap))
            {
                graphics.CopyFromScreen(rect.Left, rect.Top, 0, 0, new System.Drawing.Size(width, height));
            }

            bitmap.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
        catch (Exception ex)
        {
            failures.Add($"{Path.GetFileName(path)}: {ex.Message}");
        }
    }

    /// <summary>An invented home screen: wallpaper, clock and a row of app tiles.</summary>
    private static UIElement BuildDemoPhoneScreen()
    {
        var grid = new Grid { ClipToBounds = true };
        grid.Children.Add(new Image { Source = DemoPicture(3), Stretch = Stretch.UniformToFill });

        var top = new StackPanel { Margin = new Thickness(0, 70, 0, 0), HorizontalAlignment = HorizontalAlignment.Center };
        top.Children.Add(new TextBlock
        {
            Text = "18:40",
            FontSize = 64,
            FontWeight = FontWeights.Light,
            Foreground = Brushes.White,
            HorizontalAlignment = HorizontalAlignment.Center
        });
        top.Children.Add(new TextBlock
        {
            Text = DateTime.Today.ToString("dddd, d MMMM", LocalizationSource.Instance.Culture),
            FontSize = 15,
            Foreground = Brushes.White,
            HorizontalAlignment = HorizontalAlignment.Center
        });
        grid.Children.Add(top);

        var tiles = new UniformGrid { Columns = 4, Rows = 2, Margin = new Thickness(16, 0, 16, 100), VerticalAlignment = VerticalAlignment.Bottom };
        var colors = new[] { 0xFF4285F4, 0xFF34A853, 0xFFFBBC05, 0xFFEA4335, 0xFF7E57C2, 0xFF26A69A, 0xFFEC407A, 0xFF5C6BC0 };
        foreach (var color in colors)
        {
            tiles.Children.Add(new Border
            {
                Width = 52,
                Height = 52,
                Margin = new Thickness(8),
                CornerRadius = new CornerRadius(16),
                Background = new SolidColorBrush(Color.FromArgb((byte)(color >> 24), (byte)(color >> 16), (byte)(color >> 8), (byte)color))
            });
        }

        grid.Children.Add(tiles);
        return grid;
    }

    private struct NativeRect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
    private static extern bool GetWindowRect(nint hwnd, out NativeRect rect);
}
