using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Text;
using Windows.ApplicationModel;

namespace Pairly.Windows;

internal static class StartupRegistration
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string SettingsKeyPath = @"Software\Pairly";
    private const string RunValueName = "Pairly";
    private const string OptOutValueName = "StartupOptOut";
    private const int AppModelErrorNoPackage = 15700;
    // Declared in packaging/windows/AppxManifest.xml.template; the user controls it in Settings > Apps > Startup.
    private const string StartupTaskId = "PairlyStartup";

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = false)]
    private static extern int GetCurrentPackageFullName(ref int packageFullNameLength, StringBuilder? packageFullName);

    public static bool IsEnabled
    {
        get
        {
            try
            {
                if (IsPackaged())
                {
                    return GetStartupTask().State is StartupTaskState.Enabled or StartupTaskState.EnabledByPolicy;
                }

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
            if (IsPackaged())
            {
                return SetStartupTaskEnabled(enabled, rememberUserChoice);
            }

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

    private static bool SetStartupTaskEnabled(bool enabled, bool rememberUserChoice)
    {
        var task = GetStartupTask();
        if (!enabled)
        {
            task.Disable();
        }
        else if (task.State is StartupTaskState.Disabled)
        {
            // DisabledByUser is left alone: only Windows Settings may turn it back on.
            task.RequestEnableAsync().AsTask().GetAwaiter().GetResult();
        }

        if (rememberUserChoice)
        {
            using var settingsKey = Registry.CurrentUser.CreateSubKey(SettingsKeyPath, writable: true);
            settingsKey.SetValue(OptOutValueName, enabled ? 0 : 1, RegistryValueKind.DWord);
        }

        var state = task.State;
        return enabled
            ? state is StartupTaskState.Enabled or StartupTaskState.EnabledByPolicy
            : state is not StartupTaskState.Enabled;
    }

    /// <summary>A packaged startup task passes no command line, so sign-in launches are detected by activation kind.</summary>
    public static bool LaunchedByStartupTask()
    {
        try
        {
            return IsPackaged()
                && AppInstance.GetActivatedEventArgs()?.Kind == global::Windows.ApplicationModel.Activation.ActivationKind.StartupTask;
        }
        catch
        {
            return false;
        }
    }

    private static StartupTask GetStartupTask() =>
        StartupTask.GetAsync(StartupTaskId).AsTask().GetAwaiter().GetResult();

    private static string? ExpectedCommand()
    {
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
