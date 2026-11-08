using Microsoft.Win32;
using System.Windows;
using System.Windows.Media;
using Color = System.Windows.Media.Color;
using FontFamily = System.Windows.Media.FontFamily;

namespace Pairly.Windows;

/// <summary>
/// Pairly's look: a style (Glass or Clay) plus an accent color, in light or dark. The phone app uses
/// the same names and colors (ui/theme/Theme.kt); keep the tables in sync.
/// </summary>
internal static class ThemeManager
{
    private const string PersonalizeKey = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private const string AccentKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\Accent";

    // AccentPalette holds eight RGBA entries: Light3, Light2, Light1, Base, Dark1, Dark2, Dark3, unused.
    private const int AccentLight2 = 1;
    private const int AccentDark1 = 4;

    public static readonly string[] Styles = ["Glass", "Clay"];

    /// <summary>Accent choices; "System" uses the Windows accent color.</summary>
    public static readonly string[] Accents = ["System", "Indigo", "Violet", "Blue", "Teal", "Green", "Amber", "Coral", "Pink"];

    public static bool IsDark { get; private set; } = true;

    public static bool IsHighContrast { get; private set; }

    /// <summary>Raised after the palette changes so windows can update their native title bar and backdrop.</summary>
    public static event EventHandler? ThemeChanged;

    /// <summary>"System" follows Windows; "Light" and "Dark" override it. High contrast always wins.</summary>
    public static string Preference { get; set; } = "System";

    /// <summary>"Glass" (Manrope, neutral flat panes, graphite dark) or "Clay" (Nunito, warm, accent-filled cards, rounder).</summary>
    public static string Style { get; set; } = "Glass";

    public static string Accent { get; set; } = "Violet";

    public static bool IsClay => string.Equals(Style, "Clay", StringComparison.OrdinalIgnoreCase);

    /// <summary>Every color and radius one style needs in one mode.</summary>
    internal sealed record Palette(
        Color Accent, Color OnAccent, Color AccentSubtle,
        Color Window, Color Surface, Color Elevated,
        Color Card, Color CardStroke,
        Color Text, Color TextSecondary, Color TextTertiary,
        Color Hero, Color OnHero,
        Color NavSelected, Color OnNavSelected,
        double Control, double Card_, double Overlay);

    // Glass: the accent on neutral light grounds, and a light tone of it for graphite dark.
    private static readonly Dictionary<string, (int Accent, int Dark)> GlassTones = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Indigo"] = (0x4F46E5, 0xA5B4FC),
        ["Violet"] = (0x6D3FD9, 0xC4B5FD),
        ["Blue"] = (0x1F5BD8, 0x93C5FD),
        ["Teal"] = (0x0F766E, 0x5EEAD4),
        ["Green"] = (0x127536, 0x86EFAC),
        ["Amber"] = (0xA14A06, 0xFCD34D),
        ["Coral"] = (0xC2410C, 0xFDBA9F),
        ["Pink"] = (0xBE185D, 0xF9A8D4),
    };

    // Clay light: soft fill for cards, deep tone for buttons and text on it; dark: a pastel tone.
    private static readonly Dictionary<string, (int Soft, int Deep, int Dark)> ClayTones = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Indigo"] = (0xE2E1FF, 0x3730A3, 0xB4B4FF),
        ["Violet"] = (0xEDE3FF, 0x5B21B6, 0xCDB4FF),
        ["Blue"] = (0xD9E9FF, 0x1D4ED8, 0xA7CCFF),
        ["Teal"] = (0xCFF3EC, 0x115E59, 0x8EE8DA),
        ["Green"] = (0xD6F3DA, 0x166534, 0x9EE6A9),
        ["Amber"] = (0xFFEBC4, 0x92400E, 0xFFD27A),
        ["Coral"] = (0xFFE0D3, 0x9A3412, 0xFFB49E),
        ["Pink"] = (0xFFDDEE, 0x9D174D, 0xFFB0D6),
    };

    // Glass: neutral, no tint. Light is near-white with white cards; dark is graphite.
    private static readonly Color GlassPaper = Rgb(0xF6F6F7);
    private static readonly Color GlassHairline = Rgb(0xE6E6E9);
    private static readonly Color GlassInk = Rgb(0x18181B);
    private static readonly Color GraphiteNight = Rgb(0x0E0E10);
    private static readonly Color GraphiteCard = Rgb(0x17171A);
    private static readonly Color GraphiteElevated = Rgb(0x1F1F23);
    private static readonly Color GraphiteText = Rgb(0xEDEDEF);

    // Clay: warm. Cream and warm ink in light, warm charcoal in dark.
    private static readonly Color ClayCream = Rgb(0xFBF7F1);
    private static readonly Color ClayInk = Rgb(0x2B2520);
    private static readonly Color ClayCharcoal = Rgb(0x1A1714);
    private static readonly Color ClayCharcoalCard = Rgb(0x26221E);

    /// <summary>The swatch shown for an accent in the current style and mode.</summary>
    public static Color Swatch(string style, string accent, bool dark)
    {
        if (string.Equals(accent, "System", StringComparison.OrdinalIgnoreCase))
        {
            return TryReadSystemAccent(dark) ?? Rgb(0x0067C0);
        }
        return string.Equals(style, "Clay", StringComparison.OrdinalIgnoreCase)
            ? Rgb(dark ? ClayTones[accent].Dark : ClayTones[accent].Deep)
            : Rgb(dark ? GlassTones[accent].Dark : GlassTones[accent].Accent);
    }

    /// <summary>The palette for a style, accent and mode. "System" needs the Windows accent; null falls back to Violet.</summary>
    internal static Palette For(string style, string accent, bool dark, Color? systemAccent = null)
    {
        var useSystem = string.Equals(accent, "System", StringComparison.OrdinalIgnoreCase) && systemAccent is not null;
        if (!useSystem && !GlassTones.ContainsKey(accent))
        {
            accent = "Violet";
        }

        if (string.Equals(style, "Clay", StringComparison.OrdinalIgnoreCase))
        {
            // Warm and colorful: cards filled with the accent's soft tone, deep-toned buttons and phone card.
            var (soft, deep, tone) = useSystem
                ? (Mix(Colors.White, systemAccent!.Value, 0.16), systemAccent!.Value, systemAccent!.Value)
                : (Rgb(ClayTones[accent].Soft), Rgb(ClayTones[accent].Deep), Rgb(ClayTones[accent].Dark));
            if (!dark)
            {
                var ground = Mix(ClayCream, soft, 0.18);
                return new(deep, Colors.White, Mix(soft, deep, 0.12),
                    ground, Mix(ground, soft, 0.55), Colors.White,
                    soft, WithAlpha(soft, 0x00),
                    ClayInk, Rgb(0x675C52), Rgb(0x8A7E73),
                    deep, Colors.White,
                    deep, Colors.White,
                    18, 28, 28);
            }
            var card = Mix(ClayCharcoalCard, tone, 0.10);
            var pastel = Mix(card, tone, 0.85);
            return new(tone, ClayCharcoal, Mix(card, tone, 0.16),
                ClayCharcoal, card, Mix(card, Colors.White, 0.05),
                card, WithAlpha(card, 0x00),
                Rgb(0xF5EFE8), Rgb(0xC2B8AC), Rgb(0x9C9083),
                pastel, ClayCharcoal,
                pastel, ClayCharcoal,
                18, 28, 28);
        }

        // Neutral and flat: white or graphite panes with hairline edges; the accent marks actions and state.
        var (glassAccent, darkTone) = useSystem
            ? (systemAccent!.Value, systemAccent!.Value)
            : (Rgb(GlassTones[accent].Accent), Rgb(GlassTones[accent].Dark));
        if (!dark)
        {
            return new(glassAccent, Colors.White, Mix(glassAccent, Colors.White, 0.88),
                GlassPaper, Colors.White, Colors.White,
                Colors.White, GlassHairline,
                GlassInk, Rgb(0x52525B), Rgb(0x71717A),
                GlassInk, Colors.White,
                GlassInk, Colors.White,
                6, 14, 14);
        }
        return new(darkTone, GlassInk, Mix(GraphiteCard, darkTone, 0.16),
            GraphiteNight, GraphiteCard, GraphiteElevated,
            GraphiteCard, WithAlpha(Colors.White, 0x12),
            GraphiteText, Rgb(0xA1A1AA), Rgb(0x71717A),
            GraphiteElevated, GraphiteText,
            darkTone, GlassInk,
            6, 14, 14);
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

        var family = IsClay ? "Nunito" : "Manrope";
        var font = new FontFamily(new Uri("pack://application:,,,/"), $"./Fonts/#{family}, Segoe UI");
        resources["AppFontFamily"] = font;
        resources["DisplayFontFamily"] = font;

        resources["CardShadow"] = null;
        var onHero = resources["OnHeroResources"] as ResourceDictionary;
        onHero?.Clear();

        if (!IsHighContrast)
        {
            var palette = For(Style, Accent, IsDark, TryReadSystemAccent(IsDark));
            SetBrush(currentTheme, "AccentBrush", palette.Accent);
            SetBrush(currentTheme, "AccentHoverBrush", WithAlpha(palette.Accent, 0xE6));
            SetBrush(currentTheme, "AccentPressedBrush", WithAlpha(palette.Accent, 0xCC));
            SetBrush(currentTheme, "AccentSubtleBrush", palette.AccentSubtle);
            SetBrush(currentTheme, "AccentTextBrush", palette.OnAccent);
            SetBrush(currentTheme, "SolidBackgroundBrush", palette.Window);
            SetBrush(currentTheme, "WindowBrush", palette.Window);
            SetBrush(currentTheme, "NavigationBrush", palette.Window);
            SetBrush(currentTheme, "SurfaceBrush", palette.Surface);
            SetBrush(currentTheme, "FlyoutBrush", palette.Elevated);
            SetBrush(currentTheme, "SurfaceElevatedBrush", palette.Elevated);
            SetBrush(currentTheme, "CardFillBrush", palette.Card);
            SetBrush(currentTheme, "CardFillSecondaryBrush", palette.Surface);
            SetBrush(currentTheme, "CardStrokeBrush", palette.CardStroke);
            SetBrush(currentTheme, "TextPrimaryBrush", palette.Text);
            SetBrush(currentTheme, "TextSecondaryBrush", palette.TextSecondary);
            SetBrush(currentTheme, "TextTertiaryBrush", palette.TextTertiary);
            SetBrush(currentTheme, "HeroBrush", palette.Hero);
            SetBrush(currentTheme, "NavSelectedBrush", palette.NavSelected);
            SetBrush(currentTheme, "NavSelectedTextBrush", palette.OnNavSelected);
            resources["ControlRadius"] = new CornerRadius(palette.Control);
            resources["CardRadius"] = new CornerRadius(palette.Card_);
            resources["OverlayRadius"] = new CornerRadius(palette.Overlay);
            resources["OverlayFooterRadius"] = new CornerRadius(0, 0, palette.Overlay, palette.Overlay);
            resources["PillRadius"] = new CornerRadius(IsClay ? 24 : 10);
            // Clay cards float on a soft shadow in their own accent; Glass stays flat.
            resources["CardShadow"] = IsClay && !IsDark
                ? new System.Windows.Media.Effects.DropShadowEffect { Color = palette.Accent, Opacity = 0.16, BlurRadius = 18, ShadowDepth = 4, Direction = 270 }
                : null;

            // Content on the phone card: the card's own ink, with translucent controls of the same ink.
            if (onHero is not null)
            {
                var ink = palette.OnHero;
                SetBrush(onHero, "TextPrimaryBrush", ink);
                SetBrush(onHero, "TextSecondaryBrush", WithAlpha(ink, 0xE6));
                SetBrush(onHero, "TextTertiaryBrush", WithAlpha(ink, 0xCC));
                SetBrush(onHero, "AccentBrush", ink);
                SetBrush(onHero, "AccentSubtleBrush", WithAlpha(ink, 0x2E));
                SetBrush(onHero, "ControlFillBrush", WithAlpha(ink, 0x1F));
                SetBrush(onHero, "ControlFillHoverBrush", WithAlpha(ink, 0x33));
                SetBrush(onHero, "ControlFillPressedBrush", WithAlpha(ink, 0x17));
                SetBrush(onHero, "ControlStrokeBrush", WithAlpha(ink, 0x1F));
                SetBrush(onHero, "SurfaceHoverBrush", WithAlpha(ink, 0x1A));
                SetBrush(onHero, "SurfacePressedBrush", WithAlpha(ink, 0x12));
                SetBrush(onHero, "ControlElevationBorderBrush", WithAlpha(ink, 0x1F));
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

    /// <summary>Text pairs below 4.5:1 across every style, accent and mode; empty when all pass.</summary>
    internal static IReadOnlyList<string> ContrastFailures()
    {
        var failures = new List<string>();
        foreach (var style in Styles)
        {
            foreach (var accent in Accents.Skip(1))
            {
                foreach (var dark in new[] { false, true })
                {
                    var p = For(style, accent, dark);
                    var card = Color.FromRgb(
                        (byte)Math.Round(p.Card.R * p.Card.A / 255.0 + p.Window.R * (1 - p.Card.A / 255.0)),
                        (byte)Math.Round(p.Card.G * p.Card.A / 255.0 + p.Window.G * (1 - p.Card.A / 255.0)),
                        (byte)Math.Round(p.Card.B * p.Card.A / 255.0 + p.Window.B * (1 - p.Card.A / 255.0)));
                    var checks = new (string Name, Color Fg, Color Bg)[]
                    {
                        ("text on window", p.Text, p.Window),
                        ("secondary text on window", p.TextSecondary, p.Window),
                        ("text on card", p.Text, card),
                        ("secondary text on card", p.TextSecondary, card),
                        ("accent on card", p.Accent, card),
                        ("accent on window", p.Accent, p.Window),
                        ("accent button text", p.OnAccent, p.Accent),
                        ("phone card text", p.OnHero, p.Hero),
                        ("selected page", p.OnNavSelected, p.NavSelected),
                    };
                    foreach (var check in checks)
                    {
                        var ratio = Contrast(check.Fg, check.Bg);
                        if (ratio < 4.5)
                        {
                            failures.Add($"{style} {accent} {(dark ? "dark" : "light")}: {check.Name} {ratio:0.00}:1");
                        }
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
