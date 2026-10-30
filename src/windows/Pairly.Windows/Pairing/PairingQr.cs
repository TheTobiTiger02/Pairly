using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace Pairly.Windows.Pairing;

/// <summary>QR codes shown while adding a phone: one installs the Android app, one pairs it.</summary>
internal static class PairingQr
{
    public const string AndroidApkUrl = "https://github.com/TheTobiTiger02/Pairly/releases/latest/download/Pairly.apk";
    public const string ReleasePageUrl = "https://github.com/TheTobiTiger02/Pairly/releases/latest";

    /// <summary>
    /// The link the Android app scans (or the system camera opens) to pair without typing.
    /// It carries the certificate fingerprint, so the phone pins the PC from the QR itself rather
    /// than trusting the unauthenticated UDP discovery reply. Keep in sync with PairingLink.kt.
    /// </summary>
    public static string BuildPairingLink(PairingTicketResponse ticket, string pcName, IEnumerable<string> addresses) =>
        "pairly://pair?v=1"
        + $"&id={Uri.EscapeDataString(ticket.HostId)}"
        + $"&name={Uri.EscapeDataString(pcName)}"
        + $"&port={ticket.WssPort}"
        + $"&fp={ticket.CertificateFingerprint.ToLowerInvariant()}"
        + $"&code={Uri.EscapeDataString(ticket.Code)}"
        + $"&ip={string.Join(",", addresses)}";

    /// <summary>Dark modules on white with a quiet zone, so phones scan it in light and dark themes.</summary>
    public static BitmapImage Render(string content)
    {
        using var generator = new QRCodeGenerator();
        // Low error correction keeps the pairing link's modules large; screens don't get damaged.
        using var data = generator.CreateQrCode(content, QRCodeGenerator.ECCLevel.L);
        var png = new PngByteQRCode(data).GetGraphic(pixelsPerModule: 10, drawQuietZones: true);

        var image = new BitmapImage();
        using var stream = new MemoryStream(png);
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }
}
