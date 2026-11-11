using System.Diagnostics;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Animation;
using ComboBox = System.Windows.Controls.ComboBox;
using RadioButton = System.Windows.Controls.RadioButton;
using Panel = System.Windows.Controls.Panel;
using Pairly.Windows.Controls;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>
/// The window frame around the pages: the two layouts (navigation pane or top tabs), Home and
/// Help, appearance and language, the hidden design preview, What's new and the guided tour.
/// </summary>
public partial class MainWindow
{
    private const int HomeTabIndex = 5;
    private const int NotificationsTabIndex = 6;
    private const int HelpTabIndex = 7;
    private const string RepositoryUrl = "https://github.com/TheTobiTiger02/Pairly";

    private bool _syncingChoices;
    private CoachMarkOverlay? _tour;

    private void InitializeShell()
    {
        AboutVersionButton.Content = CurrentVersion() ?? "dev";
        SyncChoiceCombos();
        // Swatches and previews follow light and dark mode.
        ThemeManager.ThemeChanged += (_, _) => Dispatcher.BeginInvoke(BuildAppearancePickers);
        // Start on Home with its navigation item checked (the tab control's first page is Messages).
        SelectPage(HomeTabIndex);
        MainTabs.SelectedIndex = HomeTabIndex;
        LocalizationSource.Instance.LanguageChanged += (_, _) =>
        {
            ApplyBindingLanguage();
            RefreshLocalizedCodeText();
        };
        ApplyBindingLanguage();
    }

    /// <summary>
    /// Dates and times formatted in XAML bindings follow the element's Language, which WPF leaves
    /// at en-US whatever the app's culture is. Without this a German UI shows "11:17 PM".
    /// </summary>
    private void ApplyBindingLanguage() =>
        Language = System.Windows.Markup.XmlLanguage.GetLanguage(
            System.Globalization.CultureInfo.CurrentUICulture.IetfLanguageTag);

    /// <summary>Texts set from code rather than bound in XAML, refreshed after a language switch.</summary>
    private void RefreshLocalizedCodeText()
    {
        if (PairingOverlay.Visibility == Visibility.Visible)
        {
            PairingTitleText.Text = L.T(_onboardingMode ? "onboarding_title" : "pairing_title");
            SetPairingStep(_pairingStep);
        }
        UpdateRealtimeStatusText();
        if (_updateStatus is not null)
        {
            UpdateStatusText.Text = _updateStatus();
        }
        if (_startupStatus is not null)
        {
            StartupRegistrationStatusText.Text = _startupStatus();
        }
    }

    private Func<string>? _updateStatus;
    private Func<string>? _startupStatus;

    /// <summary>Sets a status line from code so it is re-translated when the language changes.</summary>
    private void ShowUpdateStatus(Func<string> text) => UpdateStatusText.Text = (_updateStatus = text)();

    private void ShowStartupStatus(Func<string> text) => StartupRegistrationStatusText.Text = (_startupStatus = text)();

    // --- Navigation -----------------------------------------------------------------------------

    /// <summary>Shows a page and checks its item in the navigation pane.</summary>
    private void SelectPage(int index)
    {
        var roots = new FrameworkElement[] { NavPane };
        // The logical tree exists from the constructor on; the visual tree only after the window loads.
        var item = roots.SelectMany(LogicalDescendants).OfType<RadioButton>()
            .FirstOrDefault(button => button.GroupName.StartsWith("MainNavigation", StringComparison.Ordinal) && button.Tag?.ToString() == index.ToString());
        if (item is null || item.IsChecked == true)
        {
            MainTabs.SelectedIndex = index;
        }
        if (item is not null)
        {
            item.IsChecked = true;
        }
    }

    private static IEnumerable<object> LogicalDescendants(object root)
    {
        if (root is not DependencyObject node)
        {
            yield break;
        }
        foreach (var child in LogicalTreeHelper.GetChildren(node))
        {
            yield return child;
            foreach (var descendant in LogicalDescendants(child))
            {
                yield return descendant;
            }
        }
    }

    private void HomeNavigate_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: not null } element && int.TryParse(element.Tag.ToString(), out var index))
        {
            SelectPage(index);
        }
    }

    // --- Help -----------------------------------------------------------------------------------

    private void HelpTour_Click(object sender, RoutedEventArgs e) => StartTour();

    private void HelpReplaySetup_Click(object sender, RoutedEventArgs e) => ShowPairingPanel(PairingStep.Welcome, onboarding: true);

    private void HelpDiagnostics_Click(object sender, RoutedEventArgs e)
    {
        SelectPage(SettingsTabIndex);
        DiagnosticsSettingsNav.IsChecked = true;
    }

    private void HelpReport_Click(object sender, RoutedEventArgs e) => OpenUrl(RepositoryUrl + "/issues");

    private void HelpPrivacy_Click(object sender, RoutedEventArgs e) => OpenUrl(RepositoryUrl + "/blob/main/PRIVACY.md");

    private static void OpenUrl(string url) => Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

    // --- Appearance and language ----------------------------------------------------------------

    private void SyncChoiceCombos()
    {
        _syncingChoices = true;
        SelectByTag(ThemeCombo, _preferences.Theme);
        SelectByTag(OnboardingStyleCombo, _preferences.Style);
        SelectByTag(OnboardingThemeCombo, _preferences.Theme);
        SelectByTag(LanguageCombo, _preferences.Language);
        SelectByTag(OnboardingLanguageCombo, _preferences.Language);
        _syncingChoices = false;
        BuildAppearancePickers();
    }

    /// <summary>
    /// Settings > Appearance: one small preview per style, drawn in that style's own colors with the
    /// chosen accent, and a row of accent swatches in the current style's tones.
    /// </summary>
    private void BuildAppearancePickers()
    {
        if (StylePickerPanel is null)
        {
            return;
        }
        var dark = ThemeManager.IsDark;
        _syncingChoices = true;
        StylePickerPanel.Children.Clear();
        foreach (var style in ThemeManager.Styles)
        {
            StylePickerPanel.Children.Add(StyleOption(style, dark));
        }

        AccentPickerPanel.Children.Clear();
        var current = _preferences.Accent ?? "Violet";
        foreach (var accent in ThemeManager.Accents)
        {
            var selected = string.Equals(accent, current, StringComparison.OrdinalIgnoreCase);
            var name = L.T("accent_" + accent.ToLowerInvariant());
            var face = new Grid { Width = 36, Height = 36 };
            face.Children.Add(new Border
            {
                CornerRadius = new CornerRadius(18),
                BorderThickness = new Thickness(2),
                BorderBrush = selected ? (System.Windows.Media.Brush)FindResource("TextPrimaryBrush") : System.Windows.Media.Brushes.Transparent
            });
            face.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Width = 26,
                Height = 26,
                Fill = new System.Windows.Media.SolidColorBrush(ThemeManager.Swatch(_preferences.Style, accent, dark))
            });
            if (accent == "System")
            {
                face.Children.Add(new TextBlock
                {
                    Text = "\uE790",
                    FontFamily = (System.Windows.Media.FontFamily)FindResource("IconFontFamily"),
                    FontSize = 12,
                    Foreground = dark ? System.Windows.Media.Brushes.Black : System.Windows.Media.Brushes.White,
                    HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
                    VerticalAlignment = System.Windows.VerticalAlignment.Center
                });
            }
            var option = new RadioButton
            {
                GroupName = "Accent",
                IsChecked = selected,
                Tag = accent,
                Content = face,
                Margin = new Thickness(0, 0, 4, 4),
                Cursor = System.Windows.Input.Cursors.Hand,
                ToolTip = name,
                Template = PlainRadioTemplate()
            };
            System.Windows.Automation.AutomationProperties.SetName(option, name);
            option.Checked += AccentOption_Checked;
            AccentPickerPanel.Children.Add(option);
        }
        AccentNameText.Text = L.T("accent_" + current.ToLowerInvariant());
        _syncingChoices = false;
    }

    private RadioButton StyleOption(string style, bool dark)
    {
        var p = ThemeManager.For(style, _preferences.Accent ?? "Violet", dark);
        var clay = style == "Clay";
        static System.Windows.Media.SolidColorBrush Brush(System.Windows.Media.Color color) => new(color);
        // A tiny Home: the side pane with the phone card and selected page, and a card with an accent button.
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(64) });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        var side = new StackPanel();
        side.Children.Add(new Border { Height = 34, CornerRadius = new CornerRadius(clay ? 12 : 7), Background = Brush(p.Hero) });
        side.Children.Add(new Border { Height = 12, Margin = new Thickness(0, 8, 8, 0), CornerRadius = new CornerRadius(6), Background = Brush(p.NavSelected) });
        side.Children.Add(new Border { Height = 6, Width = 30, Margin = new Thickness(4, 8, 0, 0), HorizontalAlignment = System.Windows.HorizontalAlignment.Left, CornerRadius = new CornerRadius(3), Background = Brush(p.TextTertiary) });
        grid.Children.Add(side);
        var lines = new StackPanel();
        lines.Children.Add(new Border { Height = 7, Width = 60, HorizontalAlignment = System.Windows.HorizontalAlignment.Left, CornerRadius = new CornerRadius(3.5), Background = Brush(p.Text) });
        lines.Children.Add(new Border { Height = 5, Width = 80, Margin = new Thickness(0, 6, 0, 0), HorizontalAlignment = System.Windows.HorizontalAlignment.Left, CornerRadius = new CornerRadius(2.5), Background = Brush(p.TextSecondary) });
        lines.Children.Add(new Border { Height = 14, Width = 46, Margin = new Thickness(0, 10, 0, 0), HorizontalAlignment = System.Windows.HorizontalAlignment.Left, CornerRadius = new CornerRadius(clay ? 7 : 4), Background = Brush(p.Accent) });
        var card = new Border
        {
            Margin = new Thickness(8, 0, 0, 0),
            CornerRadius = new CornerRadius(clay ? 14 : 8),
            Background = Brush(p.Card),
            BorderBrush = Brush(p.CardStroke),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(8),
            Child = lines
        };
        Grid.SetColumn(card, 1);
        grid.Children.Add(card);
        var preview = new Border
        {
            Height = 96,
            CornerRadius = new CornerRadius(clay ? 16 : 10),
            Background = Brush(p.Window),
            Padding = new Thickness(10),
            Child = grid
        };

        var label = new StackPanel { Margin = new Thickness(2, 8, 0, 0) };
        label.Children.Add(new TextBlock
        {
            Text = L.T(clay ? "style_clay" : "style_glass"),
            Foreground = (System.Windows.Media.Brush)FindResource("TextPrimaryBrush"),
            FontWeight = FontWeights.Bold,
            FontFamily = new System.Windows.Media.FontFamily(new Uri("pack://application:,,,/"), clay ? "./Fonts/#Nunito" : "./Fonts/#Manrope")
        });
        label.Children.Add(new TextBlock { Text = L.T(clay ? "style_clay_detail" : "style_glass_detail"), Style = (Style)FindResource("CaptionText") });
        var body = new StackPanel();
        body.Children.Add(preview);
        body.Children.Add(label);

        var selected = string.Equals(style, _preferences.Style, StringComparison.OrdinalIgnoreCase);
        var frame = new Border
        {
            Padding = new Thickness(8),
            CornerRadius = new CornerRadius(clay ? 22 : 14),
            BorderThickness = new Thickness(selected ? 2 : 1),
            BorderBrush = (System.Windows.Media.Brush)FindResource(selected ? "TextPrimaryBrush" : "CardStrokeBrush"),
            Background = System.Windows.Media.Brushes.Transparent,
            Child = body
        };
        var option = new RadioButton
        {
            GroupName = "Style",
            IsChecked = selected,
            Tag = style,
            Content = frame,
            Margin = new Thickness(0, 0, 12, 0),
            Cursor = System.Windows.Input.Cursors.Hand,
            Template = PlainRadioTemplate()
        };
        System.Windows.Automation.AutomationProperties.SetName(option, L.T(clay ? "style_clay" : "style_glass"));
        option.Checked += StyleOption_Checked;
        return option;
    }

    /// <summary>A radio button that shows only its content.</summary>
    private static ControlTemplate PlainRadioTemplate() =>
        new(typeof(RadioButton)) { VisualTree = new FrameworkElementFactory(typeof(ContentPresenter)) };

    private void StyleOption_Checked(object sender, RoutedEventArgs e)
    {
        if (!_syncingChoices && sender is RadioButton { Tag: string style } && style != _preferences.Style)
        {
            ApplyAppearance(style, _preferences.Accent ?? "Violet");
        }
    }

    private void AccentOption_Checked(object sender, RoutedEventArgs e)
    {
        if (!_syncingChoices && sender is RadioButton { Tag: string accent } && accent != _preferences.Accent)
        {
            ApplyAppearance(_preferences.Style, accent);
        }
    }

    private void ApplyAppearance(string style, string accent)
    {
        _preferences.Style = style;
        _preferences.Accent = accent;
        _preferences.Save();
        ThemeManager.Style = style;
        ThemeManager.Accent = accent;
        ThemeManager.ApplySystemTheme(System.Windows.Application.Current.Resources);
        // Rebuilt after the click has finished, so the clicked option isn't removed mid-event.
        Dispatcher.BeginInvoke(SyncChoiceCombos, System.Windows.Threading.DispatcherPriority.Background);
    }

    private static void SelectByTag(ComboBox combo, string tag) =>
        combo.SelectedItem = combo.Items.OfType<ComboBoxItem>().FirstOrDefault(item => string.Equals(item.Tag?.ToString(), tag, StringComparison.Ordinal))
            ?? combo.Items.OfType<ComboBoxItem>().FirstOrDefault();

    private static string SelectedTag(object sender) =>
        (sender as ComboBox)?.SelectedItem is ComboBoxItem { Tag: { } tag } ? tag.ToString() ?? string.Empty : string.Empty;

    private void ThemeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingChoices || !IsInitialized)
        {
            return;
        }
        _preferences.Theme = SelectedTag(sender) is { Length: > 0 } theme ? theme : "System";
        _preferences.Save();
        ThemeManager.Preference = _preferences.Theme;
        ThemeManager.ApplySystemTheme(System.Windows.Application.Current.Resources);
        SyncChoiceCombos();
    }

    private void StyleCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingChoices || !IsInitialized)
        {
            return;
        }
        ApplyAppearance(SelectedTag(sender) is { Length: > 0 } style ? style : "Glass", _preferences.Accent ?? "Violet");
    }

    private void LanguageCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingChoices || !IsInitialized)
        {
            return;
        }
        _preferences.Language = SelectedTag(sender);
        _preferences.Save();
        LocalizationSource.Instance.SetLanguage(_preferences.Language);
        SyncChoiceCombos();
    }

    // --- What's new and the tour ----------------------------------------------------------------

    private static string? CurrentVersion()
    {
        var version = typeof(MainWindow).Assembly.GetName().Version;
        return version is null || version.Major == 0 && version.Minor == 0 && version.Build == 0 ? null : version.ToString(3);
    }

    private async Task ShowWhatsNewThenTourAsync()
    {
        var version = CurrentVersion();
        if (version is not null && !string.Equals(_preferences.LastSeenVersion, version, StringComparison.Ordinal))
        {
            var notes = await ReleaseNotes.FetchAsync(version);
            if (notes is { Count: > 0 })
            {
                WhatsNewTitle.Text = L.F("whats_new_title", version);
                WhatsNewList.ItemsSource = notes;
                WhatsNewOverlay.Visibility = Visibility.Visible;
                if (Motion.Enabled)
                {
                    WhatsNewOverlay.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180)));
                }
                return; // The tour, if still due, follows when this closes.
            }
            _preferences.LastSeenVersion = version;
            _preferences.Save();
        }

        if (!_preferences.TourCompleted)
        {
            StartTour();
        }
    }

    private void WhatsNewClose_Click(object sender, RoutedEventArgs e)
    {
        WhatsNewOverlay.Visibility = Visibility.Collapsed;
        _preferences.LastSeenVersion = CurrentVersion() ?? string.Empty;
        _preferences.Save();
        if (!_preferences.TourCompleted)
        {
            StartTour();
        }
    }

    private void StartTour()
    {
        _tour?.Close();
        SelectPage(HomeTabIndex);
        // Build the tour once Home is laid out, so its quick actions count as a visible step.
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(ShowTour));
    }

    private void ShowTour()
    {
        var steps = new List<CoachMarkOverlay.Step>
        {
            new(DeviceCard, L.T("tour_phone_title"), L.T("tour_phone_body")),
            new(NavItemsPanel, L.T("tour_pages_title"), L.T("tour_pages_body")),
            new(HomeQuickActions, L.T("tour_actions_title"), L.T("tour_actions_body")),
            new(NavNotifications, L.T("tour_notifications_title"), L.T("tour_notifications_body")),
            new(NavFooterPanel, L.T("tour_help_title"), L.T("tour_help_body"))
        };
        _tour = new CoachMarkOverlay(steps, L.T("action_back"), L.T("action_next"), L.T("action_done"), L.T("action_skip_tour"), L.T("tour_counter"));
        Grid.SetRowSpan(_tour, 2);
        Grid.SetColumnSpan(_tour, 2);
        Panel.SetZIndex(_tour, 30);
        _tour.Finished += (_, _) =>
        {
            RootGrid.Children.Remove(_tour);
            _tour = null;
            _preferences.TourCompleted = true;
            _preferences.Save();
        };
        RootGrid.Children.Add(_tour);
    }
}

/// <summary>The "What's new" lines of a GitHub release (the Release workflow writes that section).</summary>
internal static class ReleaseNotes
{
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Pairly-Windows");
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return client;
    }

    public static async Task<IReadOnlyList<string>?> FetchAsync(string version)
    {
        try
        {
            using var response = await Http.GetAsync($"https://api.github.com/repos/TheTobiTiger02/Pairly/releases/tags/v{version}");
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            return Parse(json.RootElement.TryGetProperty("body", out var body) ? body.GetString() ?? string.Empty : string.Empty);
        }
        catch
        {
            return null;
        }
    }

    public static IReadOnlyList<string>? Parse(string body)
    {
        var lines = body.Replace("\r", string.Empty).Split('\n');
        var start = Array.FindIndex(lines, line => line.TrimStart().StartsWith("## ", StringComparison.Ordinal) && line.Contains("new", StringComparison.OrdinalIgnoreCase));
        var section = (start >= 0 ? lines.Skip(start + 1) : lines).TakeWhile(line => !line.TrimStart().StartsWith("## ", StringComparison.Ordinal));
        var notes = section
            .Select(line => line.Trim())
            .Where(line => line.Length > 0 && !line.StartsWith("**Full changelog", StringComparison.OrdinalIgnoreCase))
            .Select(line => System.Text.RegularExpressions.Regex.Replace(line.TrimStart('-', '*', ' '), @"\[([^\]]+)\]\([^)]+\)", "$1").Replace("**", string.Empty).Replace("`", string.Empty).Trim())
            .Where(line => line.Length > 0)
            .ToList();
        return notes.Count > 0 ? notes : null;
    }
}
