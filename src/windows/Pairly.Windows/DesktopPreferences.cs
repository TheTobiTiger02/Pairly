using System.IO;
using System.Text.Json;

namespace Pairly.Windows;

internal sealed class DesktopPreferences
{
    private static readonly string SettingsPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Pairly",
        "desktop-preferences.json");

    public bool MinimizeToTray { get; set; } = true;

    /// <summary>Show a Windows notification when a new notification arrives on the phone.</summary>
    public bool ShowPhoneNotifications { get; set; } = true;

    /// <summary>Mirror through the PC's own ADB connection when the phone has Wireless debugging on. Off by default:
    /// Power mode on the phone gives the same prompt-free mirroring and real input without keeping Wireless debugging on.</summary>
    public bool MirrorUseAdb { get; set; }

    /// <summary>"System", "Light" or "Dark".</summary>
    public string Theme { get; set; } = "System";

    /// <summary>Read from files written before the single design; never written again.</summary>
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public string? Style { get; set; }

    /// <summary>"System" (the Windows accent) or one of the named accents in ThemeManager.Accents.</summary>
    public string? Accent { get; set; }

    /// <summary>"" follows Windows; "en" or "de".</summary>
    public string Language { get; set; } = string.Empty;


    public bool OnboardingCompleted { get; set; }
    public bool TourCompleted { get; set; }

    /// <summary>The version whose "What's new" was last shown.</summary>
    public string LastSeenVersion { get; set; } = string.Empty;
    public HashSet<string> MutedNotificationPackages { get; set; } = new(StringComparer.Ordinal);

    public static DesktopPreferences Load()
    {
        try
        {
            if (!File.Exists(SettingsPath))
            {
                return new DesktopPreferences();
            }

            var preferences = JsonSerializer.Deserialize<DesktopPreferences>(File.ReadAllText(SettingsPath));
            if (preferences is null)
            {
                return new DesktopPreferences();
            }

            // Styles (Aurora and Daybreak, then Glass and Clay) are gone; the first two carried
            // their own accent, which a file from that time has no other record of.
            preferences.Accent ??= preferences.Style switch
            {
                "Aurora" => "Indigo",
                "Daybreak" => "Coral",
                _ => "Violet"
            };
            preferences.Style = null;
            if (!ThemeManager.Accents.Contains(preferences.Accent, StringComparer.OrdinalIgnoreCase))
            {
                preferences.Accent = "Violet";
            }

            preferences.MutedNotificationPackages = new HashSet<string>(
                preferences.MutedNotificationPackages ?? new HashSet<string>(),
                StringComparer.Ordinal);
            return preferences;
        }
        catch
        {
            return new DesktopPreferences();
        }
    }

    public void Save()
    {
        try
        {
            var directory = Path.GetDirectoryName(SettingsPath)!;
            Directory.CreateDirectory(directory);
            var temporaryPath = SettingsPath + ".tmp";
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(temporaryPath, SettingsPath, overwrite: true);
        }
        catch
        {
            // Preferences are non-critical; connection and trust must remain unaffected by a settings write failure.
        }
    }
}
