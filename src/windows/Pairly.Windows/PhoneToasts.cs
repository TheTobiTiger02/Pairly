using System.IO;
using System.Security;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace Pairly.Windows;

/// <summary>What one Windows notification shows and what its parts send back when used.</summary>
internal sealed record ToastContent(
    string Tag,
    string Group,
    string Title,
    string Body,
    string LaunchArguments,
    string? Attribution = null,
    string? IconPath = null,
    string? PicturePath = null,
    string? ReplyPlaceholder = null,
    string? ReplyLabel = null,
    string? ReplyArguments = null,
    string? ButtonLabel = null,
    string? ButtonArguments = null,
    bool Quiet = false);

/// <summary>
/// Windows notifications with a reply box and buttons, in place of the notification-area balloons.
/// Pairly is running whenever it shows one, so a click is handled by the running app; nothing has
/// to start it.
/// </summary>
internal sealed class PhoneToasts
{
    // The installed app is not a packaged app, so Windows needs a name and an icon for this id.
    private const string AppId = "Pairly.Desktop";
    private const string ReplyInputId = "reply";
    private const int KeptToasts = 40;

    private readonly ToastNotifier? _notifier;
    private readonly bool _packaged;
    // Windows only reports a click while the notification object is alive.
    private readonly Dictionary<string, ToastNotification> _shown = new(StringComparer.Ordinal);
    private readonly Queue<string> _order = new();

    /// <summary>The arguments of the part that was used, and the typed reply when there is one.</summary>
    public event Action<string, string?>? Activated;

    public PhoneToasts()
    {
        try
        {
            _packaged = StartupRegistration.IsPackaged();
            if (_packaged)
            {
                _notifier = ToastNotificationManager.CreateToastNotifier();
            }
            else
            {
                RegisterAppId();
                _notifier = ToastNotificationManager.CreateToastNotifier(AppId);
            }
        }
        catch (Exception ex)
        {
            AppLog.Write("Windows notifications are not available", ex);
            _notifier = null;
        }
    }

    public bool Available => _notifier is not null;

    /// <summary>A short id Windows accepts as tag or group (at most 64 characters).</summary>
    public static string Key(string value) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)), 0, 16);

    /// <summary>Shows the notification. False when Windows refused, so the caller can fall back.</summary>
    public bool Show(ToastContent content)
    {
        if (_notifier is null)
        {
            return false;
        }

        try
        {
            var document = new XmlDocument();
            document.LoadXml(BuildXml(content));
            var toast = new ToastNotification(document)
            {
                Tag = content.Tag,
                Group = content.Group,
                SuppressPopup = content.Quiet,
                ExpirationTime = DateTimeOffset.Now.AddDays(1)
            };
            toast.Activated += (_, args) =>
            {
                if (args is ToastActivatedEventArgs activated)
                {
                    var typed = activated.UserInput.TryGetValue(ReplyInputId, out var value) ? value as string : null;
                    Activated?.Invoke(activated.Arguments, typed);
                }
            };

            var key = content.Group + "/" + content.Tag;
            lock (_shown)
            {
                if (!_shown.ContainsKey(key))
                {
                    _order.Enqueue(key);
                }

                _shown[key] = toast;
                while (_order.Count > KeptToasts)
                {
                    _shown.Remove(_order.Dequeue());
                }
            }

            _notifier.Show(toast);
            return true;
        }
        catch (Exception ex)
        {
            AppLog.Write("A Windows notification could not be shown", ex);
            return false;
        }
    }

    /// <summary>Takes the notification off the screen and out of the notification center.</summary>
    public void Remove(string tag, string group)
    {
        if (_notifier is null)
        {
            return;
        }

        try
        {
            lock (_shown)
            {
                _shown.Remove(group + "/" + tag);
            }

            if (_packaged)
            {
                ToastNotificationManager.History.Remove(tag, group);
            }
            else
            {
                ToastNotificationManager.History.Remove(tag, group, AppId);
            }
        }
        catch (Exception ex)
        {
            AppLog.Write("A Windows notification could not be removed", ex);
        }
    }

    /// <summary>
    /// Saves a picture the phone sent so the notification can point at it. Null when there is none
    /// or it cannot be written. The same picture is written once.
    /// </summary>
    public static string? PictureFile(string? base64)
    {
        if (string.IsNullOrEmpty(base64))
        {
            return null;
        }

        try
        {
            var folder = Path.Combine(Path.GetTempPath(), "Pairly", "notifications");
            var path = Path.Combine(folder, Key(base64) + ".png");
            if (!File.Exists(path))
            {
                Directory.CreateDirectory(folder);
                File.WriteAllBytes(path, Convert.FromBase64String(base64));
            }

            return path;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or FormatException)
        {
            return null;
        }
    }

    private static string BuildXml(ToastContent content)
    {
        static string E(string? text) => SecurityElement.Escape(text ?? string.Empty) ?? string.Empty;
        static string Source(string path) => E(new Uri(path).AbsoluteUri);

        var xml = new StringBuilder();
        xml.Append($"<toast launch=\"{E(content.LaunchArguments)}\"><visual><binding template=\"ToastGeneric\">");
        xml.Append($"<text>{E(content.Title)}</text>");
        if (content.Body.Length > 0)
        {
            xml.Append($"<text>{E(content.Body)}</text>");
        }

        if (!string.IsNullOrEmpty(content.Attribution))
        {
            xml.Append($"<text placement=\"attribution\">{E(content.Attribution)}</text>");
        }

        if (content.IconPath is not null)
        {
            xml.Append($"<image placement=\"appLogoOverride\" src=\"{Source(content.IconPath)}\" />");
        }

        if (content.PicturePath is not null)
        {
            xml.Append($"<image placement=\"hero\" src=\"{Source(content.PicturePath)}\" />");
        }

        xml.Append("</binding></visual>");
        var hasReply = content.ReplyArguments is not null;
        var hasButton = content.ButtonArguments is not null;
        if (hasReply || hasButton)
        {
            xml.Append("<actions>");
            if (hasReply)
            {
                xml.Append($"<input id=\"{ReplyInputId}\" type=\"text\" placeHolderContent=\"{E(content.ReplyPlaceholder)}\" />");
                xml.Append($"<action content=\"{E(content.ReplyLabel)}\" arguments=\"{E(content.ReplyArguments)}\" hint-inputId=\"{ReplyInputId}\" activationType=\"foreground\" />");
            }

            if (hasButton)
            {
                xml.Append($"<action content=\"{E(content.ButtonLabel)}\" arguments=\"{E(content.ButtonArguments)}\" activationType=\"foreground\" />");
            }

            xml.Append("</actions>");
        }

        xml.Append("</toast>");
        return xml.ToString();
    }

    private static void RegisterAppId()
    {
        using var key = Registry.CurrentUser.CreateSubKey($@"Software\Classes\AppUserModelId\{AppId}");
        key.SetValue("DisplayName", "Pairly");
        if (AppIconFile() is { } icon)
        {
            key.SetValue("IconUri", icon);
        }
    }

    /// <summary>The app icon as a picture file, which is what Windows wants for the notification header.</summary>
    private static string? AppIconFile()
    {
        try
        {
            var folder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pairly");
            var path = Path.Combine(folder, "notification-icon.png");
            if (!File.Exists(path) && Environment.ProcessPath is { } exe)
            {
                Directory.CreateDirectory(folder);
                using var icon = System.Drawing.Icon.ExtractAssociatedIcon(exe);
                using var bitmap = icon?.ToBitmap();
                bitmap?.Save(path, System.Drawing.Imaging.ImageFormat.Png);
            }

            return File.Exists(path) ? path : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        {
            return null;
        }
    }
}
