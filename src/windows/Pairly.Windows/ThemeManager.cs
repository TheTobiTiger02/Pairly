using Microsoft.Win32;
using System.Windows;
using System.Windows.Media;
using Color = System.Windows.Media.Color;
using FontFamily = System.Windows.Media.FontFamily;

namespace Pairly.Windows;

/// <summary>
/// Pairly's look: one design in light or dark, with an accent color. Neutral grounds, hairline
/// edges, and the accent only on actions, selection and state. The phone app uses the same names
/// and colors (ui/theme/Theme.kt); keep the tables in sync.
/// </summary>
internal static class ThemeManager
{
    private const string PersonalizeKey = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private const string AccentKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\Accent";

    // AccentPalette holds eight RGBA entries: Light3, Light2, Light1, Base, Dark1, Dark2, Dark3, unused.
    private const int AccentLight2 = 1;
    private const int AccentDark1 = 4;

    /// <summary>Accent choices; "System" uses the Windows accent color.</summary>
    public static readonly string[] Accents = ["System", "Indigo", "Violet", "Blue", "Teal", "Green", "Amber", "Coral", "Pink"];

    public static bool IsDark { get; private set; } = true;

    public static bool IsHighContrast { get; private set; }

    /// <summary>Raised after the palette changes so windows can update their native title bar and backdrop.</summary>
    public static event EventHandler? ThemeChanged;

    /// <summary>"System" follows Windows; "Light" and "Dark" override it. High contrast always wins.</summary>
    public static string Preference { get; set; } = "System";

    public static string Accent { get; set; } = "Violet";

    /// <summary>
    /// Every color one mode needs. The window is the ground the navigation sits on; the sheet is
    /// the page laid beside it; cards sit on the sheet.
    /// </summary>
    /// <remarks>
    /// Accent is the tone for text, icons and marks. AccentFill is what buttons, switches and the
    /// user's message bubbles are filled with, always under white: in dark mode that is the deeper
    /// light-mode tone, so no pale surface sits on the dark ground.
    /// </remarks>
    internal sealed record Palette(
        Color Accent, Color AccentFill, Color OnAccent, Color AccentSubtle,
        Color Window, Color Sheet, Color Card, Color Elevated, Color CardStroke,
        Color Text, Color TextSecondary, Color TextTertiary);

    // The accent on light grounds, and a light tone of it for dark grounds.
    private static readonly Dictionary<string, (int Light, int Dark)> Tones = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Indigo"] = (0x4F46E5, 0xA5B4FC),
        ["Violet"] = (0x6D3FD9, 0xC4B5FD),
        ["Blue"] = (0x1F5BD8, 0x93C5FD),
        ["Teal"] = (0x0F766E, 0x5EEAD4),
        ["Green"] = (0x127536, 0x86EFAC),
        ["Amber"] = (0xA14A06, 0xFCD34D),
        ["Coral"] = (0xB03808, 0xFDBA9F),
        ["Pink"] = (0xBE185D, 0xF9A8D4),
    };

    /// <summary>The swatch shown for an accent in the current mode.</summary>
    public static Color Swatch(string accent, bool dark)
    {
        if (string.Equals(accent, "System", StringComparison.OrdinalIgnoreCase))
        {
            return TryReadSystemAccent(dark) ?? Rgb(0x0067C0);
        }
        return Rgb(dark ? Tones[accent].Dark : Tones[accent].Light);
    }

    /// <summary>The palette for an accent and mode. "System" needs the Windows accent; null falls back to Violet.</summary>
    /// <param name="systemFill">The Windows accent's darker shade, for fills in dark mode.</param>
    internal static Palette For(string accent, bool dark, Color? systemAccent = null, Color? systemFill = null)
    {
        var useSystem = string.Equals(accent, "System", StringComparison.OrdinalIgnoreCase) && systemAccent is not null;
        if (!useSystem && !Tones.ContainsKey(accent))
        {
            accent = "Violet";
        }

        var tone = useSystem ? systemAccent!.Value : Rgb(dark ? Tones[accent].Dark : Tones[accent].Light);
        if (!dark)
        {
            return new(tone, tone, Colors.White, Mix(tone, Colors.White, 0.88),
                Rgb(0xECEEF1), Rgb(0xF4F5F7), Colors.White, Colors.White, Rgb(0xDFE1E6),
                Rgb(0x17181B), Rgb(0x555962), Rgb(0x666A74));
        }
        // Dark gray, not black: the ground, the sheet and the cards are three clearly separate steps.
        var card = Rgb(0x26282D);
        var fill = useSystem ? systemFill ?? systemAccent!.Value : Rgb(Tones[accent].Light);
        return new(tone, fill, Colors.White, Mix(card, tone, 0.16),
            Rgb(0x17181B), Rgb(0x1D1E22), card, Rgb(0x2D2F35), Rgb(0x383A41),
            Rgb(0xEDEDF0), Rgb(0xA6A9B2), Rgb(0x8F929B));
    }

    private static Color Rgb(int value) => Color.FromRgb((byte)(value >> 16), (byte)(value >> 8), (byte)value);

    private static Color Mix(Color from, Color to, double amount) => Color.FromRgb(
        (byte)Math.Round(from.R + (to.R - from.R) * amount),
        (byte)Math.Round(from.G + (to.G - from.G) * amount),
        (byte)Math.Round(from.B + (to.B - from.B) * amount));

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

        // Always reload: a previous style may have replaced brushes in the dictionary.
        var replacement = new ResourceDictionary { Source = new Uri(themeSource, UriKind.Relative) };
        if (currentTheme is null)
        {
            dictionaries.Insert(0, replacement);
        }
        else
        {
            dictionaries[dictionaries.IndexOf(currentTheme)] = replacement;
        }
        currentTheme = replacement;

        var font = new FontFamily(new Uri("pack://application:,,,/"), "./Fonts/#Manrope, Segoe UI");
        // Titles only; everything else is Manrope.
        var display = new FontFamily(new Uri("pack://application:,,,/"), "./Fonts/#Bricolage Grotesque, ./Fonts/#Manrope, Segoe UI");
        resources["AppFontFamily"] = font;
        resources["DisplayFontFamily"] = display;
        resources["CardShadow"] = null;

        if (!IsHighContrast)
        {
            var palette = For(Accent, IsDark, TryReadSystemAccent(IsDark), TryReadSystemAccent(dark: false));
            SetBrush(currentTheme, "AccentBrush", palette.Accent);
            SetBrush(currentTheme, "AccentFillBrush", palette.AccentFill);
            SetBrush(currentTheme, "AccentHoverBrush", WithAlpha(palette.AccentFill, 0xE6));
            SetBrush(currentTheme, "AccentPressedBrush", WithAlpha(palette.AccentFill, 0xCC));
            SetBrush(currentTheme, "AccentSubtleBrush", palette.AccentSubtle);
            SetBrush(currentTheme, "AccentTextBrush", palette.OnAccent);
            SetBrush(currentTheme, "SolidBackgroundBrush", palette.Window);
            SetBrush(currentTheme, "WindowBrush", palette.Window);
            SetBrush(currentTheme, "NavigationBrush", palette.Window);
            SetBrush(currentTheme, "SheetBrush", palette.Sheet);
            SetBrush(currentTheme, "LayerBrush", palette.Sheet);
            SetBrush(currentTheme, "SurfaceBrush", palette.Sheet);
            SetBrush(currentTheme, "FlyoutBrush", palette.Elevated);
            SetBrush(currentTheme, "SurfaceElevatedBrush", palette.Elevated);
            SetBrush(currentTheme, "CardFillBrush", palette.Card);
            SetBrush(currentTheme, "CardFillSecondaryBrush", palette.Sheet);
            SetBrush(currentTheme, "CardStrokeBrush", palette.CardStroke);
            SetBrush(currentTheme, "TextPrimaryBrush", palette.Text);
            SetBrush(currentTheme, "TextSecondaryBrush", palette.TextSecondary);
            SetBrush(currentTheme, "TextTertiaryBrush", palette.TextTertiary);
            SetBrush(currentTheme, "HeroBrush", palette.Card);
            SetBrush(currentTheme, "NavSelectedBrush", palette.AccentSubtle);
            SetBrush(currentTheme, "NavSelectedTextBrush", palette.Accent);
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

    /// <summary>WCAG contrast ratio of a (possibly translucent) foreground over an opaque background.</summary>
    internal static double Contrast(Color foreground, Color background)
    {
        static double Channel(byte value)
        {
            var c = value / 255.0;
            return c <= 0.03928 ? c / 12.92 : Math.Pow((c + 0.055) / 1.055, 2.4);
        }
        static double Luminance(Color c) => 0.2126 * Channel(c.R) + 0.7152 * Channel(c.G) + 0.0722 * Channel(c.B);
        var a = foreground.A / 255.0;
        var over = Color.FromRgb(
            (byte)Math.Round(foreground.R * a + background.R * (1 - a)),
            (byte)Math.Round(foreground.G * a + background.G * (1 - a)),
            (byte)Math.Round(foreground.B * a + background.B * (1 - a)));
        var l1 = Luminance(over) + 0.05;
        var l2 = Luminance(background) + 0.05;
        return Math.Max(l1, l2) / Math.Min(l1, l2);
    }

    /// <summary>Text pairs below 4.5:1 across every accent and mode; empty when all pass.</summary>
    internal static IReadOnlyList<string> ContrastFailures()
    {
        var failures = new List<string>();
        foreach (var accent in Accents.Skip(1))
        {
            foreach (var dark in new[] { false, true })
            {
                var p = For(accent, dark);
                var checks = new (string Name, Color Fg, Color Bg)[]
                {
                    ("text on window", p.Text, p.Window),
                    ("secondary text on window", p.TextSecondary, p.Window),
                    ("secondary text on sheet", p.TextSecondary, p.Sheet),
                    ("tertiary text on sheet", p.TextTertiary, p.Sheet),
                    ("text on card", p.Text, p.Card),
                    ("secondary text on card", p.TextSecondary, p.Card),
                    ("accent on card", p.Accent, p.Card),
                    ("accent on sheet", p.Accent, p.Sheet),
                    ("accent on window", p.Accent, p.Window),
                    ("accent button text", p.OnAccent, p.AccentFill),
                    ("selected page", p.Accent, p.AccentSubtle),
                };
                foreach (var check in checks)
                {
                    var ratio = Contrast(check.Fg, check.Bg);
                    if (ratio < 4.5)
                    {
                        failures.Add($"{accent} {(dark ? "dark" : "light")}: {check.Name} {ratio:0.00}:1");
                    }
                }
            }
        }
        return failures;
    }

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
