using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>
/// The setup dialog. First run walks through welcome, features, getting the Android app, pairing,
/// preferences and a summary; "Add a device" later reuses the middle part (app, pair, done).
/// </summary>
public partial class MainWindow
{
    private static readonly PairingStep[] OnboardingSequence =
        { PairingStep.Welcome, PairingStep.Features, PairingStep.GetApp, PairingStep.Pair, PairingStep.Preferences, PairingStep.Done };

    private static readonly PairingStep[] AddDeviceSequence = { PairingStep.GetApp, PairingStep.Pair, PairingStep.Done };

    private bool _onboardingMode;
    private bool _phonePairedThisSession;

    private PairingStep[] CurrentSequence => _onboardingMode ? OnboardingSequence : AddDeviceSequence;

    private void OpenPairingPanel_Click(object sender, RoutedEventArgs e)
    {
        DevicePickerToggle.IsChecked = false;
        ShowPairingPanel(PairingStep.Pair, onboarding: false);
    }

    private void HomeGetApp_Click(object sender, RoutedEventArgs e) => ShowPairingPanel(PairingStep.GetApp, onboarding: false);

    private void ClosePairingPanel_Click(object sender, RoutedEventArgs e) => ClosePairingPanel();

    /// <summary>The footer's left button: one step back.</summary>
    private void PairingShowGetApp_Click(object sender, RoutedEventArgs e)
    {
        var index = Array.IndexOf(CurrentSequence, _pairingStep);
        if (index > 0)
        {
            SetPairingStep(CurrentSequence[index - 1], forward: false);
        }
    }

    private void PairingPrimary_Click(object sender, RoutedEventArgs e)
    {
        switch (_pairingStep)
        {
            case PairingStep.Preferences:
                ApplyOnboardingPreferences();
                SetPairingStep(PairingStep.Done);
                break;
            case PairingStep.Done:
                FinishPairingPanel(startTour: _onboardingMode);
                break;
            default:
                var index = Array.IndexOf(CurrentSequence, _pairingStep);
                SetPairingStep(CurrentSequence[Math.Min(index + 1, CurrentSequence.Length - 1)]);
                break;
        }
    }

    /// <summary>The footer's second button: "Skip for now" while pairing during setup, "Close" otherwise.</summary>
    private void PairingSecondary_Click(object sender, RoutedEventArgs e)
    {
        if (_onboardingMode && _pairingStep == PairingStep.Pair)
        {
            SetPairingStep(PairingStep.Preferences);
        }
        else if (_onboardingMode && _pairingStep == PairingStep.Done)
        {
            FinishPairingPanel(startTour: false);
        }
        else
        {
            ClosePairingPanel();
        }
    }

    private void OnPhonePaired()
    {
        _phonePairedThisSession = true;
        SetPairingStep(_onboardingMode ? PairingStep.Preferences : PairingStep.Done);
    }

    /// <summary>Decides what a fresh start shows once the host has answered with its device list.</summary>
    private void OnFirstDeviceList(int deviceCount)
    {
        if (!_preferences.OnboardingCompleted)
        {
            if (deviceCount == 0)
            {
                _preferences.LastSeenVersion = CurrentVersion() ?? string.Empty;
                _preferences.Save();
                ShowPairingPanel(PairingStep.Welcome, onboarding: true);
                return;
            }

            // Paired before setup existed: skip it, but show what changed and where things are now.
            _preferences.OnboardingCompleted = true;
            _preferences.Save();
        }
        else if (deviceCount == 0)
        {
            ShowPairingPanel(PairingStep.GetApp, onboarding: false);
            return;
        }

        _ = ShowWhatsNewThenTourAsync();
    }

    private void ShowPairingPanel(PairingStep step, bool onboarding)
    {
        _onboardingMode = onboarding;
        _phonePairedThisSession = false;
        PairingTitleText.Text = L.T(onboarding ? "onboarding_title" : "pairing_title");
        PairingCloseButton.Visibility = onboarding ? Visibility.Collapsed : Visibility.Visible;
        SyncChoiceCombos();
        OnboardingStartupCheck.IsChecked = StartWithWindowsCheck.IsChecked != false || !StartupRegistration.IsEnabled;
        OnboardingTrayCheck.IsChecked = _preferences.MinimizeToTray;
        PairingOverlay.Visibility = Visibility.Visible;
        SetPairingStep(step);
        PairingPanel.Focus();

        if (Motion.Enabled)
        {
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            PairingOverlay.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180)));
            PairingPanelScale.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(0.96, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
            PairingPanelScale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(0.96, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
        }
    }

    private void ClosePairingPanel()
    {
        PairingOverlay.Visibility = Visibility.Collapsed;
        _pairingTimer?.Stop();
        _pairingTicket = null;
    }

    private void FinishPairingPanel(bool startTour)
    {
        var wasOnboarding = _onboardingMode;
        ClosePairingPanel();
        if (!wasOnboarding)
        {
            return;
        }

        _preferences.OnboardingCompleted = true;
        _preferences.Save();
        SelectPage(HomeTabIndex);
        if (startTour)
        {
            StartTour();
        }
    }

    private void ApplyOnboardingPreferences()
    {
        MinimizeToTrayCheck.IsChecked = OnboardingTrayCheck.IsChecked == true;
        StartWithWindowsCheck.IsChecked = OnboardingStartupCheck.IsChecked == true;
    }

    private void SetPairingStep(PairingStep step, bool forward = true)
    {
        _pairingStep = step;
        var sequence = CurrentSequence;
        var index = Math.Max(0, Array.IndexOf(sequence, step));

        // Progress bar and "Step x of y".
        PairingProgressDone.Width = new GridLength(index + 1, GridUnitType.Star);
        PairingProgressRest.Width = new GridLength(sequence.Length - index - 1, GridUnitType.Star);
        PairingStepText.Text = L.F("onboarding_step_counter", index + 1, sequence.Length);

        FrameworkElement[] steps = { OnboardingWelcomeStep, OnboardingFeaturesStep, PairingGetAppStep, PairingPairStep, OnboardingPreferencesStep, PairingDoneStep };
        var visible = step switch
        {
            PairingStep.Welcome => OnboardingWelcomeStep,
            PairingStep.Features => OnboardingFeaturesStep,
            PairingStep.GetApp => PairingGetAppStep,
            PairingStep.Pair => PairingPairStep,
            PairingStep.Preferences => (FrameworkElement)OnboardingPreferencesStep,
            _ => PairingDoneStep
        };
        foreach (var element in steps)
        {
            element.Visibility = element == visible ? Visibility.Visible : Visibility.Collapsed;
        }
        AnimateStepIn(visible, forward);

        // Footer: Back on the left; the main action and an optional second action on the right.
        PairingBackButton.Content = L.T("action_back");
        PairingBackButton.Visibility = index > 0 && step != PairingStep.Done ? Visibility.Visible : Visibility.Collapsed;
        PairingPrimaryButton.Visibility = step == PairingStep.Pair ? Visibility.Collapsed : Visibility.Visible;
        PairingPrimaryButton.Content = L.T(step switch
        {
            PairingStep.Welcome => "action_get_started",
            PairingStep.Features => "action_next",
            PairingStep.GetApp => "action_have_app",
            PairingStep.Preferences => "action_next",
            _ => _onboardingMode ? "action_show_me_around" : "action_done"
        });
        PairingSecondaryButton.Visibility = (_onboardingMode && step is PairingStep.Pair or PairingStep.Done) || (!_onboardingMode && step != PairingStep.Done)
            ? Visibility.Visible
            : Visibility.Collapsed;
        PairingSecondaryButton.Content = L.T(_onboardingMode
            ? step == PairingStep.Done ? "action_skip_tour" : "action_skip_for_now"
            : "action_close");

        switch (step)
        {
            case PairingStep.GetApp:
                InstallQrImage.Source ??= Pairing.PairingQr.Render(Pairing.PairingQr.AndroidApkUrl);
                _pairingTimer?.Stop();
                break;
            case PairingStep.Pair:
                var addresses = GetLocalIPv4Addresses();
                PairingAddressesText.Text = addresses.Length == 0
                    ? L.T("pairing_no_address")
                    : L.F("pairing_addresses", string.Join(", ", addresses));
                _ = StartPairingAsync();
                break;
            case PairingStep.Done:
                _pairingTimer?.Stop();
                var paired = _phonePairedThisSession || _devices.Count > 0;
                if (!_phonePairedThisSession)
                {
                    PairingDoneTitle.Text = L.T(paired ? "pairing_done_title" : "onboarding_done_no_phone_title");
                }
                PairingDoneBody.Text = L.T(paired ? "pairing_done_body" : "onboarding_done_no_phone_body");
                break;
            default:
                _pairingTimer?.Stop();
                break;
        }
    }

    private static void AnimateStepIn(FrameworkElement element, bool forward)
    {
        if (!Motion.Enabled)
        {
            return;
        }

        var translate = new TranslateTransform();
        element.RenderTransform = translate;
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        translate.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(forward ? 28 : -28, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
        element.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)));
    }
}

/// <summary>Animations follow Windows' "Animation effects" setting and are off while rendering screenshots.</summary>
internal static class Motion
{
    public static bool Enabled => SystemParameters.ClientAreaAnimation && App.ScreenshotDirectory is null;
}
