using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>
/// <c>Pairly.Windows.exe --render-screenshots &lt;folder&gt;</c> renders every page in both layouts,
/// light and dark, English and German, plus setup, the tour and What's new, then exits. CI runs it
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
                foreach (var theme in language == "en" ? new[] { "Light", "Dark" } : new[] { "Light" })
                {
                    ThemeManager.Preference = theme;
                    ThemeManager.ApplySystemTheme(System.Windows.Application.Current.Resources);
                    foreach (var layout in language == "en" ? new[] { "Pane", "Tabs" } : new[] { "Pane" })
                    {
                        _preferences.Layout = layout;
                        ApplyLayout(layout);
                        var prefix = $"{language}-{theme.ToLowerInvariant()}-{layout.ToLowerInvariant()}";
                        foreach (var (page, name) in ScreenshotPages)
                        {
                            if (layout == "Tabs" && page == NotificationsTabIndex)
                            {
                                continue;
                            }
                            SelectPage(page);
                            await Settle();
                            Save($"{prefix}-{name}");
                        }

                        if (layout == "Pane")
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
                        }

                        SelectPage(HomeTabIndex);
                        StartTour();
                        await Settle(400);
                        Save($"{prefix}-tour");
                        _tour?.Close();
                    }

                    WhatsNewTitle.Text = L.F("whats_new_title", "1.4.0");
                    WhatsNewList.ItemsSource = new[] { "A cleaner window with a navigation pane", "German translation", "A guided setup and tour" };
                    WhatsNewOverlay.Visibility = Visibility.Visible;
                    await Settle();
                    Save($"{language}-{theme.ToLowerInvariant()}-whats-new");
                    WhatsNewOverlay.Visibility = Visibility.Collapsed;
                }
            }
        }
        catch (Exception ex)
        {
            failures.Add(ex.ToString());
        }

        File.WriteAllText(Path.Combine(folder, "result.txt"), failures.Count == 0 ? "ok" : string.Join(Environment.NewLine + Environment.NewLine, failures));
        _exitFully = true;
        System.Windows.Application.Current.Shutdown(failures.Count == 0 ? 0 : 1);
    }
}
