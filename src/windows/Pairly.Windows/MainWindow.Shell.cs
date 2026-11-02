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
    private const int DesignPreviewTaps = 7;
    private const string RepositoryUrl = "https://github.com/TheTobiTiger02/Pairly";

    private bool _syncingChoices;
    private int _versionTaps;
    private CoachMarkOverlay? _tour;

    private void InitializeShell()
    {
        AboutVersionButton.Content = CurrentVersion() ?? "dev";
        DesignPreviewCard.Visibility = _preferences.DesignPreviewUnlocked ? Visibility.Visible : Visibility.Collapsed;
        SyncChoiceCombos();
        ApplyLayout(_preferences.Layout);
        MainTabs.SelectedIndex = HomeTabIndex;
        UpdateGreeting();
        LocalizationSource.Instance.LanguageChanged += (_, _) => RefreshLocalizedCodeText();
    }

    /// <summary>Texts set from code rather than bound in XAML, refreshed after a language switch.</summary>
    private void RefreshLocalizedCodeText()
    {
        UpdateGreeting();
        if (PairingOverlay.Visibility == Visibility.Visible)
        {
            PairingTitleText.Text = L.T(_onboardingMode ? "onboarding_title" : "pairing_title");
            SetPairingStep(_pairingStep);
        }
        UpdateRealtimeStatusText();
    }

    private void UpdateGreeting()
    {
        var hour = DateTime.Now.Hour;
        HomeGreetingText.Text = L.T(hour < 5 ? "greeting_evening" : hour < 12 ? "greeting_morning" : hour < 18 ? "greeting_afternoon" : "greeting_evening");
    }

    // --- Layout ---------------------------------------------------------------------------------

    /// <summary>"Pane": navigation on the left. "Tabs": tabs on top, phone and notifications on the left.</summary>
    private void ApplyLayout(string layout)
    {
        var tabs = string.Equals(layout, "Tabs", StringComparison.Ordinal);
        NavPane.Visibility = tabs ? Visibility.Collapsed : Visibility.Visible;
        SidePanel.Visibility = tabs ? Visibility.Visible : Visibility.Collapsed;
        TopTabsPanel.Visibility = tabs ? Visibility.Visible : Visibility.Collapsed;
        TopTabsActions.Visibility = tabs ? Visibility.Visible : Visibility.Collapsed;

        MoveInto(DeviceCard, tabs ? SideDeviceHost : NavDeviceHost);
        MoveInto(NowPlayingCard, tabs ? SideNowPlayingHost : HomeNowPlayingHost);
        MoveInto(NotificationsSection, tabs ? SideNotificationsHost : NotificationsPageHost);
        NowPlayingCard.Margin = tabs ? new Thickness(0, 8, 0, 0) : new Thickness(0);

        if (tabs && MainTabs.SelectedIndex == NotificationsTabIndex)
        {
            SelectPage(HomeTabIndex);
        }
        else
        {
            SelectPage(MainTabs.SelectedIndex);
        }
    }

    private static void MoveInto(FrameworkElement element, ContentControl host)
    {
        if (ReferenceEquals(host.Content, element))
        {
            return;
        }
        if (element.Parent is ContentControl previous)
        {
            previous.Content = null;
        }
        host.Content = element;
    }

    /// <summary>Shows a page and checks its navigation item in whichever layout is active.</summary>
    private void SelectPage(int index)
    {
        var tabs = TopTabsPanel.Visibility == Visibility.Visible;
        var roots = tabs ? new FrameworkElement[] { TopTabsPanel, TopTabsActions } : new FrameworkElement[] { NavPane };
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
        SelectByTag(OnboardingThemeCombo, _preferences.Theme);
        SelectByTag(LanguageCombo, _preferences.Language);
        SelectByTag(OnboardingLanguageCombo, _preferences.Language);
        SelectByTag(LayoutCombo, _preferences.Layout);
        _syncingChoices = false;
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

    private void LayoutCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingChoices || !IsInitialized)
        {
            return;
        }
        _preferences.Layout = SelectedTag(sender) is "Tabs" ? "Tabs" : "Pane";
        _preferences.Save();
        ApplyLayout(_preferences.Layout);
        SelectPage(SettingsTabIndex);
    }

    /// <summary>Clicking the version seven times reveals the design preview (layout switch).</summary>
    private void AboutVersion_Click(object sender, RoutedEventArgs e)
    {
        if (_preferences.DesignPreviewUnlocked)
        {
            return;
        }
        _versionTaps++;
        var left = DesignPreviewTaps - _versionTaps;
        if (left > 0)
        {
            if (left <= 3)
            {
                StatusText.Text = L.F("design_preview_taps_left", left);
            }
            return;
        }
        _preferences.DesignPreviewUnlocked = true;
        _preferences.Save();
        DesignPreviewCard.Visibility = Visibility.Visible;
        StatusText.Text = L.T("design_preview_unlocked");
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
        var tabs = string.Equals(_preferences.Layout, "Tabs", StringComparison.Ordinal);
        var steps = new List<CoachMarkOverlay.Step>
        {
            new(DeviceCard, L.T("tour_phone_title"), L.T("tour_phone_body")),
            new(tabs ? TopTabsPanel : NavItemsPanel, L.T("tour_pages_title"), L.T("tour_pages_body")),
            new(tabs ? DeviceQuickActions : HomeQuickActions, L.T("tour_actions_title"), L.T("tour_actions_body")),
            new(tabs ? NotificationsSection : NavNotifications, L.T("tour_notifications_title"), L.T("tour_notifications_body")),
            new(tabs ? TopTabsActions : NavFooterPanel, L.T("tour_help_title"), L.T("tour_help_body"))
        };
        _tour = new CoachMarkOverlay(steps, L.T("action_back"), L.T("action_next"), L.T("action_done"), L.T("tour_skip"), L.T("tour_counter"));
        Grid.SetRowSpan(_tour, 2);
        Grid.SetColumnSpan(_tour, 3);
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
