using Microsoft.Win32;
using System.Windows;
using System.Windows.Media;
using Color = System.Windows.Media.Color;

namespace Pairly.Windows;

internal static class ThemeManager
{
    private const string PersonalizeKey = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private const string AccentKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\Accent";

    // AccentPalette holds eight RGBA entries: Light3, Light2, Light1, Base, Dark1, Dark2, Dark3, unused.
    private const int AccentLight2 = 1;
    private const int AccentDark1 = 4;

    public static bool IsDark { get; private set; } = true;

    public static bool IsHighContrast { get; private set; }

    /// <summary>Raised after the palette changes so windows can update their native title bar and backdrop.</summary>
    public static event EventHandler? ThemeChanged;

    /// <summary>"System" follows Windows; "Light" and "Dark" override it. High contrast always wins.</summary>
    public static string Preference { get; set; } = "System";

    // Pairly's indigo, as on the phone: a lighter tint on dark surfaces, the brand shade on light ones.
    private static readonly Color BrandLight = Color.FromRgb(0x4F, 0x5B, 0xE8);
    private static readonly Color BrandDark = Color.FromRgb(0x9A, 0xA2, 0xFF);

    public static void ApplySystemTheme(ResourceDictionary resources)
    {
        IsHighContrast = SystemParameters.HighContrast;
        IsDark = !IsHighContrast && Preference switch
        {
            "Light" => false,
            "Dark" => true,
            _ => !IsWindowsLightTheme()
        };
        var themeSource = IsHighContrast
            ? "Themes/HighContrast.xaml"
            : IsDark ? "Themes/Dark.xaml" : "Themes/Light.xaml";
        var dictionaries = resources.MergedDictionaries;
        var currentTheme = dictionaries.FirstOrDefault(dictionary => dictionary.Contains("Theme.Name"));

        if (currentTheme?.Source?.OriginalString.EndsWith(themeSource, StringComparison.OrdinalIgnoreCase) != true)
        {
            var replacement = new ResourceDictionary
            {
                Source = new Uri(themeSource, UriKind.Relative)
            };

            if (currentTheme is null)
            {
                dictionaries.Insert(0, replacement);
            }
            else
            {
                dictionaries[dictionaries.IndexOf(currentTheme)] = replacement;
            }

            currentTheme = replacement;
        }

        if (!IsHighContrast)
        {
            var accent = IsDark ? BrandDark : BrandLight;
            SetBrush(currentTheme, "AccentBrush", accent);
            SetBrush(currentTheme, "AccentHoverBrush", WithAlpha(accent, 0xE6));
            SetBrush(currentTheme, "AccentPressedBrush", WithAlpha(accent, 0xCC));
            SetBrush(currentTheme, "AccentSubtleBrush", WithAlpha(accent, IsDark ? (byte)0x2E : (byte)0x1F));
            SetBrush(currentTheme, "AccentTextBrush", IsDark ? Color.FromRgb(0x11, 0x16, 0x50) : Colors.White);
        }

        if (IsDark && !IsHighContrast)
        {
            // Deep navy surfaces, the same as the phone app's dark mode.
            var window = Color.FromRgb(0x0D, 0x12, 0x20);
            var raised = Color.FromRgb(0x14, 0x1A, 0x2B);
            var elevated = Color.FromRgb(0x1B, 0x22, 0x36);
            SetBrush(currentTheme, "SolidBackgroundBrush", window);
            SetBrush(currentTheme, "WindowBrush", window);
            SetBrush(currentTheme, "NavigationBrush", raised);
            SetBrush(currentTheme, "SurfaceBrush", raised);
            SetBrush(currentTheme, "FlyoutBrush", raised);
            SetBrush(currentTheme, "SurfaceElevatedBrush", elevated);
        }

        ThemeChanged?.Invoke(null, EventArgs.Empty);
    }

    private static void SetBrush(ResourceDictionary dictionary, string key, Color color)
    {
        if (dictionary[key] is SolidColorBrush existing && existing.Color == color)
        {
            return;
        }

        var brush = new SolidColorBrush(color);
        brush.Freeze();
        dictionary[key] = brush;
    }

    private static Color WithAlpha(Color color, byte alpha) => Color.FromArgb(alpha, color.R, color.G, color.B);

    private static Color? TryReadSystemAccent(bool dark)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(AccentKey);
            if (key?.GetValue("AccentPalette") is not byte[] palette || palette.Length < 32)
            {
                return null;
            }

            // Windows itself uses the lighter tint on dark surfaces and the darker shade on light ones.
            var offset = (dark ? AccentLight2 : AccentDark1) * 4;
            return Color.FromRgb(palette[offset], palette[offset + 1], palette[offset + 2]);
        }
        catch
        {
            return null;
        }
    }

    private static bool IsWindowsLightTheme()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(PersonalizeKey);
            return key?.GetValue("AppsUseLightTheme") is int value && value != 0;
        }
        catch
        {
            return false;
        }
    }
}
