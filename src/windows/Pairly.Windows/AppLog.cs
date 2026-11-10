using System.Diagnostics;
using System.IO;

namespace Pairly.Windows;

/// <summary>
/// Problems the app ran into, kept in %LOCALAPPDATA%\Pairly\logs\desktop.log so a release build
/// leaves something to look at. The file starts over once it passes 1 MB.
/// </summary>
internal static class AppLog
{
    private const long MaxBytes = 1024 * 1024;
    private static readonly object Gate = new();
    private static readonly string LogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pairly", "logs", "desktop.log");

    public static void Write(string message, Exception? exception = null)
    {
        Debug.WriteLine($"{message} {exception}");
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
                if (File.Exists(LogPath) && new FileInfo(LogPath).Length > MaxBytes)
                {
                    File.Delete(LogPath);
                }

                File.AppendAllText(LogPath, $"{DateTimeOffset.Now:u} {message}{(exception is null ? string.Empty : Environment.NewLine + exception)}{Environment.NewLine}");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Logging must never be the thing that breaks the app.
        }
    }
}
