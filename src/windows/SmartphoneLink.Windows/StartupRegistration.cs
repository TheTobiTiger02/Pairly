using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Text;

namespace SmartphoneLink.Windows;

internal static class StartupRegistration
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string SettingsKeyPath = @"Software\Smartphone Link";
    private const string RunValueName = "Smartphone Link";
    private const string OptOutValueName = "StartupOptOut";
    private const int AppModelErrorNoPackage = 15700;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = false)]
    private static extern int GetCurrentPackageFullName(ref int packageFullNameLength, StringBuilder? packageFullName);

    public static bool IsEnabled
    {
        get
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
                return key?.GetValue(RunValueName) is string command
                    && string.Equals(command, ExpectedCommand(), StringComparison.OrdinalIgnoreCase);
            }
            catch
            {
                return false;
            }
        }
    }

    public static bool EnableAfterPairingIfAllowed()
    {
        if (IsOptedOut || IsEnabled)
        {
            return IsEnabled;
        }

        return SetEnabled(enabled: true, rememberUserChoice: false);
    }

    public static bool SetEnabled(bool enabled, bool rememberUserChoice = true)
    {
        try
        {
            using var runKey = Registry.CurrentUser.CreateSubKey(RunKeyPath, writable: true);
            if (enabled)
            {
                var command = ExpectedCommand();
                if (string.IsNullOrWhiteSpace(command))
                {
                    return false;
                }

                runKey.SetValue(RunValueName, command, RegistryValueKind.String);
            }
            else
            {
                runKey.DeleteValue(RunValueName, throwOnMissingValue: false);
            }

            if (rememberUserChoice)
            {
                using var settingsKey = Registry.CurrentUser.CreateSubKey(SettingsKeyPath, writable: true);
                settingsKey.SetValue(OptOutValueName, enabled ? 0 : 1, RegistryValueKind.DWord);
            }

            return true;
        }
        catch
        {
            return false;
        }
    }

    private static bool IsOptedOut
    {
        get
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(SettingsKeyPath);
                return key?.GetValue(OptOutValueName) is int value && value != 0;
            }
            catch
            {
                return false;
            }
        }
    }

    private static string? ExpectedCommand()
    {
        if (IsPackaged())
        {
            // Stable app-execution alias declared by the MSIX package; unlike WindowsApps paths it survives updates.
            return "SmartphoneLink.exe --background";
        }

        var executable = Environment.ProcessPath;
        return string.IsNullOrWhiteSpace(executable) ? null : $"\"{executable}\" --background";
    }

    private static bool IsPackaged()
    {
        var length = 0;
        var result = GetCurrentPackageFullName(ref length, null);
        return result != AppModelErrorNoPackage;
    }
}
