using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using Pairly.Protocol;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>Files the phone shared with this PC (Android share sheet or the phone's Home → Send).</summary>
public partial class MainWindow
{
    private readonly ObservableCollection<ReceivedFileViewModel> _receivedFiles = new();
    private string? _lastReceivedFolder;

    private void ShowReceivedFile(FileShareReceivedPayload received)
    {
        _receivedFiles.Insert(0, new ReceivedFileViewModel(received.FileName, received.FilePath, received.ByteCount));
        while (_receivedFiles.Count > 5) _receivedFiles.RemoveAt(_receivedFiles.Count - 1);
        ReceivedFilesList.ItemsSource ??= _receivedFiles;
        ReceivedFilesCard.Visibility = Visibility.Visible;
        _lastReceivedFolder = Path.GetDirectoryName(received.FilePath);

        string[] openFolder = ["folder", _lastReceivedFolder ?? string.Empty];
        ShowPcNotification(L.T("file_received"), L.F("file_received_detail", received.FileName), openFolder, L.T("open_folder"), openFolder);
    }

    private void OpenReceivedFolder_Click(object sender, RoutedEventArgs e)
    {
        var folder = _lastReceivedFolder ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Pairly");
        Directory.CreateDirectory(folder);
        StartShell(folder);
    }

    private void OpenReceivedFile_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string path } && File.Exists(path))
        {
            // Opens Explorer with the file selected rather than running whatever was shared.
            StartShell("explorer.exe", $"/select,\"{path}\"");
        }
    }

    private static void StartShell(string target, string? arguments = null)
    {
        try
        {
            Process.Start(new ProcessStartInfo(target) { Arguments = arguments ?? string.Empty, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not open {target}: {ex.Message}");
        }
    }
}

public sealed record ReceivedFileViewModel(string FileName, string FilePath, long ByteCount)
{
    public string SizeText => ByteCount switch
    {
        < 1024 => $"{ByteCount} B",
        < 1024 * 1024 => $"{ByteCount / 1024.0:0.#} KB",
        < 1024L * 1024 * 1024 => $"{ByteCount / (1024.0 * 1024):0.#} MB",
        _ => $"{ByteCount / (1024.0 * 1024 * 1024):0.##} GB"
    };
}
