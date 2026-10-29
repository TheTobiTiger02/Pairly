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
