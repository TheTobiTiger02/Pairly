using System.Windows;
using Microsoft.Win32;
using System.Security.Principal;

namespace SmartphoneLink.Windows;

public partial class App : System.Windows.Application
{
    private Mutex? _instanceMutex;
    private bool _ownsInstanceMutex;

    internal static bool StartHidden { get; private set; }
    internal static WindowsClipboardSync ClipboardSync { get; private set; } = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        StartHidden = e.Args.Any(argument => string.Equals(argument, "--background", StringComparison.OrdinalIgnoreCase))
            || StartupRegistration.LaunchedByStartupTask();
        var userIdentity = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        _instanceMutex = new Mutex(false, $@"Local\SmartphoneLink.Host.{userIdentity}");
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
                    "Smartphone Link is already running for this Windows user. Close the existing window or its tray icon before starting another copy.",
                    "Smartphone Link is already running",
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
