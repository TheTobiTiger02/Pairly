using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>
/// <c>Pairly.Windows.exe --render-screenshots &lt;folder&gt;</c> renders every page in light and in each
/// dark-mode candidate, English and German, plus setup, the tour and What's new, then exits. CI runs it
/// so a broken page (for example a missing resource) fails the build and the images can be reviewed.
/// </summary>
public partial class MainWindow
{
    private static readonly (int Page, string Name)[] ScreenshotPages =
    {
        (HomeTabIndex, "home"), (0, "messages"), (1, "photos"), (2, "files"), (3, "apps"),
        (NotificationsTabIndex, "notifications"), (HelpTabIndex, "help"), (SettingsTabIndex, "settings")
    };

    private async Task RunScreenshotsAsync()
    {
        var folder = App.ScreenshotDirectory!;
        Directory.CreateDirectory(folder);
        var failures = new List<string>();
        WindowState = WindowState.Normal;
        Width = 1320;
        Height = 860;

        async Task Settle(int delayMs = 250)
        {
            await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
            await Task.Delay(delayMs);
            await Dispatcher.InvokeAsync(UpdateLayout, DispatcherPriority.ApplicationIdle);
        }

        void Save(string name)
        {
            try
            {
                var width = (int)Math.Ceiling(RootGrid.ActualWidth);
                var height = (int)Math.Ceiling(RootGrid.ActualHeight);
                var visual = new DrawingVisual();
                using (var context = visual.RenderOpen())
                {
                    var background = TryFindResource("SolidBackgroundBrush") as Brush ?? Brushes.Black;
                    context.DrawRectangle(background, null, new Rect(0, 0, width, height));
                    context.DrawRectangle(new VisualBrush(RootGrid), null, new Rect(0, 0, width, height));
                }
                var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
                bitmap.Render(visual);
                var encoder = new PngBitmapEncoder();
                encoder.Frames.Add(BitmapFrame.Create(bitmap));
                using var stream = File.Create(Path.Combine(folder, name + ".png"));
                encoder.Save(stream);
            }
            catch (Exception ex)
            {
                failures.Add($"{name}: {ex}");
            }
        }

        try
        {
            foreach (var language in new[] { "en", "de" })
            {
                LocalizationSource.Instance.SetLanguage(language);
                var looks = language == "en"
                    ? new[] { ("Light", "Graphite", "light"), ("Dark", "Graphite", "dark-graphite"), ("Dark", "Navy", "dark-navy"), ("Dark", "Black", "dark-black") }
                    : new[] { ("Light", "Graphite", "light") };
                foreach (var (theme, darkLook, look) in looks)
                {
                    ThemeManager.Preference = theme;
                    ThemeManager.DarkLook = darkLook;
                    ThemeManager.ApplySystemTheme(System.Windows.Application.Current.Resources);
                    var prefix = $"{language}-{look}";
                    // The extra dark candidates only need the pages that show the most surface.
                    var full = look is "light" or "dark-graphite";
                    foreach (var (page, name) in ScreenshotPages)
                    {
                        if (!full && name is not ("home" or "settings" or "messages")) continue;
                        SelectPage(page);
                        await Settle();
                        Save($"{prefix}-{name}");
                    }

                    if (full)
                    {
                        foreach (var step in OnboardingSequence)
                        {
                            if (PairingOverlay.Visibility != Visibility.Visible)
                            {
                                ShowPairingPanel(step, onboarding: true);
                            }
                            SetPairingStep(step);
                            await Settle(step == PairingStep.Pair ? 1500 : 250);
                            Save($"{prefix}-setup-{(int)step}-{step.ToString().ToLowerInvariant()}");
                        }
                        ClosePairingPanel();

                        SelectPage(HomeTabIndex);
                        StartTour();
                        await Settle(400);
                        Save($"{prefix}-tour");
                        _tour?.Close();

                        WhatsNewTitle.Text = L.F("whats_new_title", "1.6.0");
                        WhatsNewList.ItemsSource = new[] { "Files and photos from your phone land in Downloads\\Pairly", "A calmer look that matches the phone app", "Clearer setup" };
                        WhatsNewOverlay.Visibility = Visibility.Visible;
                        await Settle();
                        Save($"{prefix}-whats-new");
                        WhatsNewOverlay.Visibility = Visibility.Collapsed;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            failures.Add(ex.ToString());
        }

        try
        {
            await RunClickThroughAsync(Settle, failures);
        }
        catch (Exception ex)
        {
            failures.Add("click-through: " + ex);
        }

        File.WriteAllText(Path.Combine(folder, "result.txt"), failures.Count == 0 ? "ok" : string.Join(Environment.NewLine + Environment.NewLine, failures));
        _exitFully = true;
        System.Windows.Application.Current.Shutdown(failures.Count == 0 ? 0 : 1);
    }

    /// <summary>
    /// Uses the window like a person would: presses the setup buttons, the tour, and the dark look,
    /// theme and language choices, and checks after each step that the expected thing happened.
    /// Preferences are restored afterwards so a local run leaves nothing changed.
    /// </summary>
    private async Task RunClickThroughAsync(Func<int, Task> settle, List<string> failures)
    {
        var saved = (_preferences.Theme, _preferences.Language, _preferences.DarkLook, _preferences.OnboardingCompleted, _preferences.TourCompleted, _preferences.MinimizeToTray);
        void Expect(bool condition, string what)
        {
            if (!condition)
            {
                failures.Add("click-through: expected " + what);
            }
        }
        static void Press(System.Windows.Controls.Primitives.ButtonBase button) =>
            button.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));
        bool Shown(FrameworkElement element) => element.Visibility == Visibility.Visible;

        try
        {
            LanguageCombo.SelectedIndex = 1; // English
            await settle(250);

            // Setup, start to finish, without pairing a phone.
            ShowPairingPanel(PairingStep.Welcome, onboarding: true);
            await settle(250);
            Expect(Shown(PairingOverlay) && Shown(OnboardingWelcomeStep), "setup to open on the welcome step");
            Press(PairingPrimaryButton);
            await settle(250);
            Expect(Shown(OnboardingFeaturesStep), "Get started to show the features step");
            Press(PairingPrimaryButton);
            await settle(250);
            Expect(Shown(PairingGetAppStep), "Next to show the get-the-app step");
            Press(PairingPrimaryButton);
            await settle(1000);
            Expect(Shown(PairingPairStep), "I have the app to show the pairing step");
            Expect(Shown(PairingSecondaryButton), "pairing to offer Skip for now");
            Press(PairingSecondaryButton);
            await settle(250);
            Expect(Shown(OnboardingPreferencesStep), "Skip for now to show the preferences step");
            OnboardingStartupCheck.IsChecked = StartWithWindowsCheck.IsChecked == true; // leave Windows startup as it was
            Press(PairingPrimaryButton);
            await settle(250);
            Expect(Shown(PairingDoneStep), "Next to show the summary");
            Press(PairingSecondaryButton); // Skip the tour
            await settle(250);
            Expect(!Shown(PairingOverlay), "finishing setup to close the dialog");
            Expect(_preferences.OnboardingCompleted, "finishing setup to be remembered");
            Expect(MainTabs.SelectedIndex == HomeTabIndex, "finishing setup to land on Home");

            // The tour, then skipping it.
            StartTour();
            await settle(400);
            Expect(_tour is { IsVisible: true }, "the tour to appear");
            _tour?.Close();
            await settle(150);
            Expect(_tour is null, "closing the tour to remove it");

            // Navigation through the pane.
            SelectPage(0);
            await settle(150);
            Expect(MainTabs.SelectedIndex == 0 && NavMessages.IsChecked == true, "Messages to open from the navigation pane");

            // Dark-mode candidate, theme and language through the real controls in Settings.
            DarkLookCombo.SelectedIndex = 1; // Navy
            await settle(150);
            Expect(ThemeManager.IsDark && (TryFindResource("WindowBrush") as SolidColorBrush)?.Color == Color.FromRgb(0x0D, 0x12, 0x20), "Navy to switch the dark look");
            DarkLookCombo.SelectedIndex = 0; // Graphite
            await settle(150);

            ThemeCombo.SelectedIndex = 2; // Dark
            await settle(150);
            Expect(ThemeManager.IsDark || ThemeManager.IsHighContrast, "Dark to switch the theme");
            ThemeCombo.SelectedIndex = 1; // Light
            await settle(150);
            Expect(!ThemeManager.IsDark || ThemeManager.IsHighContrast, "Light to switch the theme");

            LanguageCombo.SelectedIndex = 2; // Deutsch
            await settle(150);
            Expect(LocalizationSource.Instance.Culture.TwoLetterISOLanguageName == "de" && L.T("action_next") == "Weiter", "Deutsch to switch the language");
            LanguageCombo.SelectedIndex = 1; // English
            await settle(150);
            Expect(L.T("action_next") == "Next", "English to switch the language back");
        }
        finally
        {
            (_preferences.Theme, _preferences.Language, _preferences.DarkLook, _preferences.OnboardingCompleted, _preferences.TourCompleted, _preferences.MinimizeToTray) = saved;
            _preferences.Save();
        }
    }
}
