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

    /// <summary>"Aurora" (indigo-violet, calm) or "Daybreak" (warm coral, rounder); the same two looks as the phone app.</summary>
    public static string Style { get; set; } = "Aurora";

    private static bool Daybreak => string.Equals(Style, "Daybreak", StringComparison.OrdinalIgnoreCase);

    /// <summary>One look in one mode: accent, its text, surfaces, hero gradient and corner radii.</summary>
    private sealed record Palette(
        Color Accent, Color OnAccent, Color Window, Color Navigation, Color Surface, Color Elevated,
        Color HeroStart, Color HeroEnd, double Control, double Card, double Overlay);

    private static Palette CurrentPalette(bool dark) => (Daybreak, dark) switch
    {
        (false, false) => new(Rgb(0x4B3FD8), Colors.White, Rgb(0xF4F3FF), Rgb(0xEFEDFF), Rgb(0xFFFFFF), Rgb(0xFFFFFF), Rgb(0x4B3FD8), Rgb(0x7C4DEB), 6, 12, 12),
        (false, true) => new(Rgb(0xA9A3FF), Rgb(0x1C1477), Rgb(0x13121F), Rgb(0x1A1929), Rgb(0x1E1D30), Rgb(0x26243A), Rgb(0x3B32B8), Rgb(0x6A41D6), 6, 12, 12),
        (true, false) => new(Rgb(0xC2462A), Colors.White, Rgb(0xFFF7F1), Rgb(0xFBEFE8), Rgb(0xFFFFFF), Rgb(0xFFFFFF), Rgb(0xC2462A), Rgb(0xE8603F), 10, 18, 18),
        (true, true) => new(Rgb(0xFFB59E), Rgb(0x5C1806), Rgb(0x1C1411), Rgb(0x231915), Rgb(0x2B1F1A), Rgb(0x352721), Rgb(0xB4431F), Rgb(0xD0583A), 10, 18, 18)
    };

    private static Color Rgb(int value) => Color.FromRgb((byte)(value >> 16), (byte)(value >> 8), (byte)value);

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
            var palette = CurrentPalette(IsDark);
            SetBrush(currentTheme, "AccentBrush", palette.Accent);
            SetBrush(currentTheme, "AccentHoverBrush", WithAlpha(palette.Accent, 0xE6));
            SetBrush(currentTheme, "AccentPressedBrush", WithAlpha(palette.Accent, 0xCC));
            SetBrush(currentTheme, "AccentSubtleBrush", WithAlpha(palette.Accent, IsDark ? (byte)0x2E : (byte)0x1F));
            SetBrush(currentTheme, "AccentTextBrush", palette.OnAccent);
            SetBrush(currentTheme, "SolidBackgroundBrush", palette.Window);
            SetBrush(currentTheme, "WindowBrush", palette.Window);
            SetBrush(currentTheme, "NavigationBrush", palette.Navigation);
            SetBrush(currentTheme, "SurfaceBrush", IsDark ? palette.Surface : Rgb(0xFCFCFF));
            SetBrush(currentTheme, "FlyoutBrush", IsDark ? palette.Surface : Colors.White);
            SetBrush(currentTheme, "SurfaceElevatedBrush", palette.Elevated);
            // Cards: solid in the style's color in dark mode; translucent white over Mica in light mode.
            if (IsDark)
            {
                SetBrush(currentTheme, "CardFillBrush", palette.Surface);
                SetBrush(currentTheme, "CardFillSecondaryBrush", palette.Navigation);
            }

            var hero = new LinearGradientBrush(palette.HeroStart, palette.HeroEnd, new System.Windows.Point(0, 0), new System.Windows.Point(1, 1));
            hero.Freeze();
            currentTheme["HeroBrush"] = hero;
            resources["ControlRadius"] = new CornerRadius(palette.Control);
            resources["CardRadius"] = new CornerRadius(palette.Card);
            resources["OverlayRadius"] = new CornerRadius(palette.Overlay);
        }

        // Content on the hero is white; in high contrast the hero is a plain window, so it isn't.
        if (resources["OnHeroResources"] is ResourceDictionary onHero)
        {
            if (IsHighContrast)
            {
                onHero.Clear();
            }
            else if (onHero.Count == 0)
            {
                foreach (var key in new ResourceDictionary { Source = new Uri("pack://application:,,,/Themes/OnHero.xaml") }.Keys.Cast<object>().ToArray())
                {
                    onHero[key] = new ResourceDictionary { Source = new Uri("pack://application:,,,/Themes/OnHero.xaml") }[key];
                }
            }
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
