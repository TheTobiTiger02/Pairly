using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using Pairly.Windows.Localization;
using KeyEventArgs = System.Windows.Input.KeyEventArgs;

namespace Pairly.Windows;

/// <summary>Toasts, the confirmation dialog, page motion and the app-wide keyboard shortcuts.</summary>
public partial class MainWindow
{
    private DispatcherTimer? _toastTimer;
    private TaskCompletionSource<bool>? _confirm;

    // --- Toast ----------------------------------------------------------------------------------

    /// <summary>A short confirmation at the bottom of the page that leaves on its own.</summary>
    private void ShowToast(string text, bool success = true)
    {
        ToastText.Text = text;
        ToastGlyph.Text = success ? "" : "";
        ToastHost.Visibility = Visibility.Visible;
        System.Windows.Automation.AutomationProperties.SetName(ToastHost, text);
        if (Motion.Enabled)
        {
            var spring = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut };
            ToastHost.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
            ToastOffset.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(14, 0, TimeSpan.FromMilliseconds(420)) { EasingFunction = spring });
            ToastScale.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(0.92, 1, TimeSpan.FromMilliseconds(420)) { EasingFunction = spring });
            ToastScale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(0.92, 1, TimeSpan.FromMilliseconds(420)) { EasingFunction = spring });
        }
        else
        {
            ToastHost.BeginAnimation(OpacityProperty, null);
            ToastHost.Opacity = 1;
        }

        _toastTimer?.Stop();
        _toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.6) };
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer?.Stop();
            if (!Motion.Enabled)
            {
                ToastHost.Visibility = Visibility.Collapsed;
                return;
            }

            var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(180));
            fade.Completed += (_, _) =>
            {
                if (_toastTimer is { IsEnabled: false }) ToastHost.Visibility = Visibility.Collapsed;
            };
            ToastHost.BeginAnimation(OpacityProperty, fade);
        };
        _toastTimer.Start();
    }

    // --- Confirmation ---------------------------------------------------------------------------

    /// <summary>Asks before something that cannot be undone. Esc and a click beside the dialog cancel.</summary>
    private Task<bool> ConfirmAsync(string title, string message, string confirmLabel, bool danger = true)
    {
        _confirm?.TrySetResult(false);
        _confirm = new TaskCompletionSource<bool>();
        ConfirmTitle.Text = title;
        ConfirmMessage.Text = message;
        ConfirmAcceptButton.Content = confirmLabel;
        ConfirmAcceptButton.Style = (Style)FindResource(danger ? "DangerButton" : "MainActionButton");
        ConfirmOverlay.Visibility = Visibility.Visible;
        if (Motion.Enabled)
        {
            ConfirmOverlay.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(140)));
            ConfirmOffset.BeginAnimation(TranslateTransform.YProperty,
                new DoubleAnimation(12, 0, TimeSpan.FromMilliseconds(320)) { EasingFunction = new BackEase { Amplitude = 0.3, EasingMode = EasingMode.EaseOut } });
        }

        // Cancel has the focus, so Enter never confirms a deletion by accident.
        Dispatcher.BeginInvoke(() => ConfirmCancelButton.Focus(), DispatcherPriority.Input);
        return _confirm.Task;
    }

    private void CloseConfirm(bool accepted)
    {
        ConfirmOverlay.Visibility = Visibility.Collapsed;
        _confirm?.TrySetResult(accepted);
        _confirm = null;
    }

    private void ConfirmAccept_Click(object sender, RoutedEventArgs e) => CloseConfirm(true);

    private void ConfirmCancel_Click(object sender, RoutedEventArgs e) => CloseConfirm(false);

    private void ConfirmOverlay_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (ReferenceEquals(e.OriginalSource, ConfirmOverlay))
        {
            CloseConfirm(false);
        }
    }

    // --- Page motion ----------------------------------------------------------------------------

    /// <summary>A page arrives by rising a little while it fades in.</summary>
    private static void AnimatePageIn(FrameworkElement page)
    {
        if (!Motion.Enabled)
        {
            page.Opacity = 1;
            page.RenderTransform = Transform.Identity;
            return;
        }

        var offset = new TranslateTransform();
        page.RenderTransform = offset;
        page.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(200)));
        offset.BeginAnimation(TranslateTransform.YProperty,
            new DoubleAnimation(10, 0, TimeSpan.FromMilliseconds(360)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
    }

    // --- Keyboard -------------------------------------------------------------------------------

    // Ctrl+1..7 in the order of the navigation.
    private static readonly int[] PageShortcutOrder = [HomeTabIndex, 0, 6, 1, 2, 3, SettingsTabIndex];

    private void MainWindow_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            if (ConfirmOverlay.Visibility == Visibility.Visible)
            {
                CloseConfirm(false);
                e.Handled = true;
            }
            else if (WhatsNewOverlay.Visibility == Visibility.Visible)
            {
                WhatsNewOverlay.Visibility = Visibility.Collapsed;
                e.Handled = true;
            }
            else if (PairingOverlay.Visibility == Visibility.Visible && !_onboardingMode)
            {
                ClosePairingPanel();
                e.Handled = true;
            }
            return;
        }

        // Shortcuts belong to the pages, not to a dialog lying over them.
        if (ConfirmOverlay.Visibility == Visibility.Visible || PairingOverlay.Visibility == Visibility.Visible
            || PhotoViewerOverlay.Visibility == Visibility.Visible)
        {
            return;
        }

        if (e.Key == Key.F5)
        {
            RefreshPhoneData_Click(this, new RoutedEventArgs());
            e.Handled = true;
            return;
        }

        if (Keyboard.Modifiers != ModifierKeys.Control)
        {
            return;
        }

        if (e.Key is >= Key.D1 and <= Key.D7)
        {
            SelectPage(PageShortcutOrder[e.Key - Key.D1]);
            e.Handled = true;
        }
        else if (e.Key == Key.F && PageSearchBox() is { } search)
        {
            search.Focus();
            search.SelectAll();
            e.Handled = true;
        }
    }

    /// <summary>The search box of the page that is showing, if it has one.</summary>
    private System.Windows.Controls.TextBox? PageSearchBox() => MainTabs.SelectedIndex switch
    {
        0 => SmsSearchBox,
        1 => PhotoSearchBox,
        2 => StorageSearchBox,
        3 => AppsSearchBox,
        _ => null
    };
}
