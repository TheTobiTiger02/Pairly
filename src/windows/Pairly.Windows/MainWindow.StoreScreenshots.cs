using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Pairly.Core;
using Pairly.Protocol;
using Pairly.Windows.Localization;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using Point = System.Windows.Point;

namespace Pairly.Windows;

/// <summary>
/// <c>Pairly.Windows.exe --render-store-screenshots &lt;folder&gt;</c> fills the window with invented
/// demo content (a phone, conversations, notifications, photos, files) and renders the pages the
/// Microsoft Store listing shows, in English and German, light and dark. Nothing talks to a phone;
/// the "Store listing" workflow frames these images with captions and uploads them.
/// </summary>
public partial class MainWindow
{
    private const string DemoDeviceId = "demo-phone";

    /// <summary>A fixed early evening, so the demo times look natural whenever CI renders them.</summary>
    private static DateTimeOffset DemoNow => new(DateTime.Today.AddHours(18).AddMinutes(40));
    private string _demoLanguage = "en";

    private static readonly (string Name, int Page, string Theme)[] StoreShots =
    {
        ("01-home", HomeTabIndex, "Light"),
        ("02-messages", 0, "Light"),
        ("03-notifications", NotificationsTabIndex, "Light"),
        ("04-photos", 1, "Light"),
        ("05-files", 2, "Light"),
        ("06-home-dark", HomeTabIndex, "Dark"),
        ("07-messages-dark", 0, "Dark")
    };

    private async Task RunStoreScreenshotsAsync()
    {
        var folder = App.StoreScreenshotDirectory!;
        Directory.CreateDirectory(folder);
        var failures = new List<string>();
        WindowState = WindowState.Normal;
        Width = 1440;
        Height = 900;

        async Task Settle(int delayMs = 400)
        {
            await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
            await Task.Delay(delayMs);
            await Dispatcher.InvokeAsync(UpdateLayout, DispatcherPriority.ApplicationIdle);
        }

        try
        {
            foreach (var language in new[] { "en", "de" })
            {
                LocalizationSource.Instance.SetLanguage(language);
                _demoLanguage = language;
                // Dates and times in the language's format (23:17 rather than 11:17 PM in German).
                var culture = System.Globalization.CultureInfo.GetCultureInfo(language == "de" ? "de-DE" : "en-US");
                System.Globalization.CultureInfo.CurrentCulture = culture;
                Language = System.Windows.Markup.XmlLanguage.GetLanguage(culture.IetfLanguageTag);
                ApplyDemoData();
                foreach (var (name, page, theme) in StoreShots)
                {
                    ThemeManager.Preference = theme;
                    ThemeManager.ApplySystemTheme(System.Windows.Application.Current.Resources);
                    SelectPage(page);
                    if (page == 0 && SmsThreadsList.Items.Count > 0)
                    {
                        // Reselect so the conversation loads in the current language.
                        SmsThreadsList.SelectedIndex = -1;
                        SmsThreadsList.SelectedIndex = 0;
                    }
                    await Settle();
                    SaveStoreShot(Path.Combine(folder, $"{language}-{name}.png"), failures);
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

    /// <summary>Renders the window at twice its size so the framed Store images stay sharp.</summary>
    private void SaveStoreShot(string path, List<string> failures)
    {
        try
        {
            const double scale = 2;
            var width = RootGrid.ActualWidth;
            var height = RootGrid.ActualHeight;
            var visual = new DrawingVisual();
            using (var context = visual.RenderOpen())
            {
                var background = TryFindResource("SolidBackgroundBrush") as Brush ?? Brushes.Black;
                context.DrawRectangle(background, null, new Rect(0, 0, width, height));
                context.DrawRectangle(new VisualBrush(RootGrid), null, new Rect(0, 0, width, height));
            }
            var bitmap = new RenderTargetBitmap((int)Math.Ceiling(width * scale), (int)Math.Ceiling(height * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var stream = File.Create(path);
            encoder.Save(stream);
        }
        catch (Exception ex)
        {
            failures.Add($"{Path.GetFileName(path)}: {ex}");
        }
    }

    /// <summary>Puts the demo phone and its content into the same collections the host would fill.</summary>
    private void ApplyDemoData()
    {
        var de = _demoLanguage == "de";
        var now = DemoNow;
        _realtimeConnected = true;
        var granted = new[] { "notifications", "sms", "photos", "mirroring", "calls", "files", "clipboard" }
            .ToDictionary(key => key, _ => "granted");
        var phone = new LinkedDevice(
            DemoDeviceId, "Pixel 8", "android", string.Empty,
            new DeviceCapabilitiesPayload(true, true, true, true, true),
            now.AddDays(-12), now.AddSeconds(-5), Connected: true, Permissions: granted, BatteryLevel: 82, IsCharging: false);
        UpdateDeviceCollection(new[] { phone }, DemoDeviceId);

        // Messages
        var threads = DemoThreads(de, now);
        ReconcileSmsThreads(threads.Select(thread => new SmsThreadViewModel(DemoDeviceId, thread)).ToArray());
        FilterSmsThreads();
        SmsStatusText.Text = string.Empty;

        // Notifications
        ReconcileNotifications(DemoNotifications(de, now));
        ApplyNotificationFilters(updateAppFilter: true);
        NotificationStatusText.Text = string.Empty;

        // Photos, drawn in code so no real pictures are needed.
        var photos = Enumerable.Range(0, 24).Select(index =>
        {
            var photo = new MediaItemViewModel(DemoDeviceId, $"demo-{index}", $"PXL_2026{index:0000}.jpg", "4032x3024", string.Empty,
                now.AddHours(-index * 7), 3_400_000 + index * 41_000, index % 3 == 0 ? (de ? "Urlaub" : "Holiday") : "Camera");
            photo.SetPreview(DemoPicture(index));
            return photo;
        }).ToArray();
        ReconcilePhotos(photos);
        ApplyPhotoFilters();
        PhotosStatusText.Text = string.Empty;
        MirrorStatusText.Text = L.F("connected_mirror_permission", phone.DisplayName, "granted");

        // Files: the phone's Download folder.
        _storageDeviceId = DemoDeviceId;
        _currentStoragePath = "/Download";
        _allStorageItems = new[]
        {
            new StorageItemPayload("Camera", "/Download/Camera", true, 0, now.AddDays(-1).ToUnixTimeMilliseconds()),
            new StorageItemPayload(de ? "Rechnung März.pdf" : "Invoice March.pdf", "/Download/a.pdf", false, 184_320, now.AddHours(-3).ToUnixTimeMilliseconds()),
            new StorageItemPayload(de ? "Bordkarte.pdf" : "Boarding pass.pdf", "/Download/b.pdf", false, 96_512, now.AddHours(-20).ToUnixTimeMilliseconds()),
            new StorageItemPayload("Presentation.pptx", "/Download/c.pptx", false, 4_812_800, now.AddDays(-2).ToUnixTimeMilliseconds()),
            new StorageItemPayload("Voice memo.m4a", "/Download/d.m4a", false, 1_204_224, now.AddDays(-3).ToUnixTimeMilliseconds()),
            new StorageItemPayload("Sunset.jpg", "/Download/e.jpg", false, 2_936_012, now.AddDays(-4).ToUnixTimeMilliseconds())
        }.Select(item => new StorageItemViewModel(item)).ToArray();
        FilterStorageItems();
        UpdateBreadcrumbs(_currentStoragePath);
        StorageStatusText.Text = string.Empty;

        // Now playing and a file just received from the phone.
        _mediaStates[DemoDeviceId] = new MediaSessionStatePayload(
            new[]
            {
                new MediaSessionInfo("demo.music", "Music", "paused", "Golden Hour Drive", "The Northern Lights", "Summer Roads",
                    214_000, 81_000, now, 1f, true, true, true, true)
            },
            now);
        UpdateNowPlaying();
        _receivedFiles.Clear();
        _receivedFiles.Add(new ReceivedFileViewModel(de ? "Bordkarte.pdf" : "Boarding pass.pdf", @"C:\Users\You\Downloads\Pairly\b.pdf", 96_512));
        _receivedFiles.Add(new ReceivedFileViewModel("PXL_20261102_184512.jpg", @"C:\Users\You\Downloads\Pairly\p.jpg", 3_512_330));
        ReceivedFilesList.ItemsSource = _receivedFiles;
        ReceivedFilesCard.Visibility = Visibility.Visible;

        StatusText.Text = de ? "Pixel 8 ist verbunden." : "Pixel 8 is connected.";
    }

    private static IReadOnlyList<SmsMessagePayload> DemoMessages(string threadId, bool de, DateTimeOffset now)
    {
        if (threadId != "t1")
        {
            return Array.Empty<SmsMessagePayload>();
        }

        var lines = de
            ? new (bool Me, string Text)[]
            {
                (false, "Bist du heute Abend zum Essen da?"),
                (true, "Ja, bin um 7 da. Soll ich was mitbringen?"),
                (false, "Vielleicht Brot vom Bäcker an der Ecke"),
                (true, "Mach ich! Bis später"),
                (false, "Super, bis dann")
            }
            : new (bool Me, string Text)[]
            {
                (false, "Are you coming for dinner tonight?"),
                (true, "Yes, I'll be there at 7. Should I bring anything?"),
                (false, "Maybe some bread from the bakery on the corner"),
                (true, "Will do! See you later"),
                (false, "Great, see you then")
            };
        return lines.Select((line, index) => new SmsMessagePayload($"m{index}", threadId, "+1 555 0100", line.Text, line.Me, now.AddMinutes(-40 + index * 6))).ToArray();
    }

    private static SmsThreadPayload[] DemoThreads(bool de, DateTimeOffset now) => de
        ? new[]
        {
            new SmsThreadPayload("t1", "Mama", "+49 151 0000100", "Super, bis dann", now.AddMinutes(-16), 1),
            new SmsThreadPayload("t2", "Alex", "+49 151 0000101", "Hast du die Präsentation schon gesehen?", now.AddHours(-1), 2),
            new SmsThreadPayload("t3", "Team Mittagessen", "+49 151 0000102", "Jonas: 12:30 beim Italiener?", now.AddHours(-3), 0),
            new SmsThreadPayload("t4", "Sam", "+49 151 0000103", "Danke fürs Mitnehmen gestern!", now.AddHours(-20), 0),
            new SmsThreadPayload("t5", "Zahnarztpraxis", "+49 151 0000104", "Erinnerung: Termin morgen um 9:00", now.AddDays(-1), 0),
            new SmsThreadPayload("t6", "Paketdienst", "+49 151 0000105", "Dein Paket kommt heute zwischen 14 und 16 Uhr.", now.AddDays(-2), 0)
        }
        : new[]
        {
            new SmsThreadPayload("t1", "Mom", "+1 555 0100", "Great, see you then", now.AddMinutes(-16), 1),
            new SmsThreadPayload("t2", "Alex Morgan", "+1 555 0101", "Did you see the slides yet?", now.AddHours(-1), 2),
            new SmsThreadPayload("t3", "Team lunch", "+1 555 0102", "Jordan: 12:30 at the Italian place?", now.AddHours(-3), 0),
            new SmsThreadPayload("t4", "Sam", "+1 555 0103", "Thanks for the ride yesterday!", now.AddHours(-20), 0),
            new SmsThreadPayload("t5", "Dental office", "+1 555 0104", "Reminder: appointment tomorrow at 9:00 AM", now.AddDays(-1), 0),
            new SmsThreadPayload("t6", "Parcel delivery", "+1 555 0105", "Your package arrives today between 2 and 4 PM.", now.AddDays(-2), 0)
        };

    private static NotificationPayload[] DemoNotifications(bool de, DateTimeOffset now) => new[]
    {
        new NotificationPayload("n1", "demo.chat", "Chat", "Alex Morgan", de ? "Hast du die Präsentation schon gesehen?" : "Did you see the slides yet?", true, now.AddMinutes(-4), Clearable: true),
        new NotificationPayload("n2", "demo.calendar", de ? "Kalender" : "Calendar", de ? "Team-Meeting in 15 Minuten" : "Team meeting in 15 minutes", de ? "Konferenzraum 2" : "Conference room 2", false, now.AddMinutes(-11), Clearable: true),
        new NotificationPayload("n3", "demo.bank", "Bank", de ? "Zahlung eingegangen" : "Payment received", de ? "Du hast 42,00 € von Sam erhalten." : "You received $42.00 from Sam.", false, now.AddMinutes(-35), Clearable: true),
        new NotificationPayload("n4", "demo.delivery", de ? "Pakete" : "Deliveries", de ? "Paket unterwegs" : "Package on its way", de ? "Lieferung heute zwischen 14 und 16 Uhr" : "Arriving today between 2 and 4 PM", false, now.AddHours(-2), Clearable: true),
        new NotificationPayload("n5", "demo.fitness", "Fitness", de ? "Tagesziel erreicht" : "Daily goal reached", de ? "10.000 Schritte, weiter so!" : "10,000 steps, keep it up!", false, now.AddHours(-5), Clearable: true),
        new NotificationPayload("n6", "demo.news", de ? "Nachrichten" : "News", de ? "Wetter: Sonniges Wochenende" : "Weather: a sunny weekend ahead", de ? "Bis zu 24 °C am Samstag" : "Highs of 75°F on Saturday", false, now.AddHours(-9), Clearable: true)
    };

    /// <summary>A simple landscape: sky gradient, sun, two hill layers, in one of several palettes.</summary>
    private static ImageSource DemoPicture(int index)
    {
        var palettes = new (Color SkyTop, Color SkyBottom, Color Sun, Color Far, Color Near)[]
        {
            (Color.FromRgb(0x5B, 0x8D, 0xEF), Color.FromRgb(0xFF, 0xC9, 0x8B), Color.FromRgb(0xFF, 0xF1, 0xC1), Color.FromRgb(0x5E, 0x7A, 0xB8), Color.FromRgb(0x2E, 0x4A, 0x7D)),
            (Color.FromRgb(0xFF, 0x9A, 0x8B), Color.FromRgb(0xFF, 0xD8, 0x9B), Color.FromRgb(0xFF, 0xF6, 0xDA), Color.FromRgb(0xC9, 0x6B, 0x86), Color.FromRgb(0x6D, 0x3B, 0x6E)),
            (Color.FromRgb(0x8E, 0xD1, 0xFC), Color.FromRgb(0xE0, 0xF4, 0xFF), Color.FromRgb(0xFF, 0xFF, 0xFF), Color.FromRgb(0x7C, 0xB3, 0x8A), Color.FromRgb(0x3E, 0x7D, 0x4F)),
            (Color.FromRgb(0x2B, 0x32, 0x6B), Color.FromRgb(0x7A, 0x5C, 0xC4), Color.FromRgb(0xFF, 0xE9, 0xA8), Color.FromRgb(0x3C, 0x3F, 0x8F), Color.FromRgb(0x1C, 0x1F, 0x4A)),
            (Color.FromRgb(0x4F, 0xC3, 0xC7), Color.FromRgb(0xC8, 0xF1, 0xE5), Color.FromRgb(0xFF, 0xFD, 0xE8), Color.FromRgb(0x3A, 0x9E, 0xA5), Color.FromRgb(0x1F, 0x6E, 0x7A)),
            (Color.FromRgb(0xF6, 0xA1, 0x5B), Color.FromRgb(0xFC, 0xE3, 0xB3), Color.FromRgb(0xFF, 0xF3, 0xD6), Color.FromRgb(0xC4, 0x7A, 0x45), Color.FromRgb(0x7A, 0x45, 0x2A))
        };
        var p = palettes[index % palettes.Length];
        const int w = 320, h = 240;
        var shift = (index * 37) % 90;
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.DrawRectangle(new LinearGradientBrush(p.SkyTop, p.SkyBottom, 90), null, new Rect(0, 0, w, h));
            dc.DrawEllipse(new SolidColorBrush(p.Sun), null, new Point(70 + shift * 2, 80 + (index % 4) * 8), 26, 26);
            dc.DrawGeometry(new SolidColorBrush(p.Far), null, Hills(w, h, 150 - shift / 3, 0.9 + index % 3 * 0.3));
            dc.DrawGeometry(new SolidColorBrush(p.Near), null, Hills(w, h, 185 - shift / 6, 1.6 + index % 2 * 0.4));
        }
        var bitmap = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        bitmap.Freeze();
        return bitmap;

        static Geometry Hills(int width, int height, double baseline, double frequency)
        {
            var figure = new PathFigure { StartPoint = new Point(0, height), IsClosed = true };
            for (var x = 0; x <= width; x += 8)
            {
                figure.Segments.Add(new LineSegment(new Point(x, baseline - 22 * Math.Sin(x / 320.0 * Math.PI * frequency + baseline)), true));
            }
            figure.Segments.Add(new LineSegment(new Point(width, height), true));
            var geometry = new PathGeometry(new[] { figure });
            geometry.Freeze();
            return geometry;
        }
    }
}
