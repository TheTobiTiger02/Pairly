using System.Windows;
using Microsoft.Win32;
using System.Security.Principal;

using Pairly.Windows.Localization;

namespace Pairly.Windows;

public partial class App : System.Windows.Application
{
    private Mutex? _instanceMutex;
    private bool _ownsInstanceMutex;

    internal static bool StartHidden { get; private set; }
    internal static WindowsClipboardSync ClipboardSync { get; private set; } = null!;
    internal static DesktopPreferences Preferences { get; } = DesktopPreferences.Load();

    /// <summary>Set by --render-screenshots &lt;folder&gt;: render every page to PNG and exit (CI).</summary>
    internal static string? ScreenshotDirectory { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        var screenshotIndex = Array.FindIndex(e.Args, argument => string.Equals(argument, "--render-screenshots", StringComparison.OrdinalIgnoreCase));
        if (screenshotIndex >= 0 && screenshotIndex + 1 < e.Args.Length)
        {
            ScreenshotDirectory = System.IO.Path.GetFullPath(e.Args[screenshotIndex + 1]);
        }

        if (ScreenshotDirectory is not null)
        {
            // CI: a page that fails to load must fail the run with the reason, not hang on a dialog.
            DispatcherUnhandledException += (_, args) =>
            {
                System.IO.Directory.CreateDirectory(ScreenshotDirectory);
                System.IO.File.WriteAllText(System.IO.Path.Combine(ScreenshotDirectory, "result.txt"), args.Exception.ToString());
                args.Handled = true;
                Shutdown(1);
            };
        }

        Localization.LocalizationSource.Instance.SetLanguage(Preferences.Language);
        ThemeManager.Preference = Preferences.Theme;

        StartHidden = ScreenshotDirectory is null && e.Args.Any(argument => string.Equals(argument, "--background", StringComparison.OrdinalIgnoreCase))
            || StartupRegistration.LaunchedByStartupTask();
        var userIdentity = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        _instanceMutex = new Mutex(false, ScreenshotDirectory is null ? $@"Local\Pairly.Host.{userIdentity}" : $@"Local\Pairly.Screenshots.{userIdentity}");
        try
        {
            _ownsInstanceMutex = _instanceMutex.WaitOne(TimeSpan.Zero);
        }
        catch (AbandonedMutexException)
        {
            _ownsInstanceMutex = true;
        }

        if (!_ownsInstanceMutex)
        {
            if (!StartHidden)
            {
                System.Windows.MessageBox.Show(
                    L.T("pairly_is_already_running_for_this_windows_u"),
                    L.T("pairly_is_already_running"),
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
            }
            Shutdown();
            return;
        }

        ThemeManager.ApplySystemTheme(Resources);
        SystemEvents.UserPreferenceChanged += SystemEvents_UserPreferenceChanged;
        SystemParameters.StaticPropertyChanged += SystemParameters_StaticPropertyChanged;
        ClipboardSync = new WindowsClipboardSync(Dispatcher);
        base.OnStartup(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        ClipboardSync?.Dispose();
        SystemEvents.UserPreferenceChanged -= SystemEvents_UserPreferenceChanged;
        SystemParameters.StaticPropertyChanged -= SystemParameters_StaticPropertyChanged;
        if (_ownsInstanceMutex)
        {
            _instanceMutex?.ReleaseMutex();
        }
        _instanceMutex?.Dispose();
        base.OnExit(e);
    }

    private void SystemEvents_UserPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
    {
        Dispatcher.BeginInvoke(() => ThemeManager.ApplySystemTheme(Resources));
    }

    private void SystemParameters_StaticPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(e.PropertyName)
            || string.Equals(e.PropertyName, nameof(SystemParameters.HighContrast), StringComparison.Ordinal))
        {
            Dispatcher.BeginInvoke(() => ThemeManager.ApplySystemTheme(Resources));
        }
    }
}
