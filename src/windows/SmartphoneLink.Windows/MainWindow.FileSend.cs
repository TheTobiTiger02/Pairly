using System.IO;
using System.Windows;
using DragEventArgs = System.Windows.DragEventArgs;
using DataFormats = System.Windows.DataFormats;
using DragDropEffects = System.Windows.DragDropEffects;

namespace SmartphoneLink.Windows;

/// <summary>
/// PC-to-phone file sending: the "Send files..." button and drag and drop onto the main window or
/// a mirror window. Files land in the phone's Downloads/Smartphone Link folder.
/// </summary>
public partial class MainWindow
{
    private bool _sendingFiles;

    private void SendFiles_Click(object sender, RoutedEventArgs e)
    {
        if (DevicesList.SelectedItem is not DeviceViewModel device)
        {
            StatusText.Text = "Select a phone first.";
            return;
        }

        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = $"Send files to {device.DisplayName}",
            Multiselect = true,
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) == true)
        {
            _ = SendFilesAsync(device.DeviceId, device.DisplayName, dialog.FileNames);
        }
    }

    private void MainWindow_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) && DevicesList.SelectedItem is DeviceViewModel
            ? DragDropEffects.Copy
            : DragDropEffects.None;
        e.Handled = true;
    }

    private void MainWindow_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetData(DataFormats.FileDrop) is string[] { Length: > 0 } files
            && DevicesList.SelectedItem is DeviceViewModel device)
        {
            _ = SendFilesAsync(device.DeviceId, device.DisplayName, files);
        }
    }

    private async Task SendFilesAsync(string deviceId, string deviceName, IReadOnlyList<string> paths)
    {
        var push = _embeddedServer.FilePush;
        if (push is null)
        {
            StatusText.Text = "The host is not running, so files cannot be sent.";
            return;
        }

        if (_sendingFiles)
        {
            StatusText.Text = "Files are already being sent. Wait for them to finish, then try again.";
            return;
        }

        // Folders are skipped rather than walked: sending a whole tree by accident is worse than
        // asking the user to pick the files.
        var files = paths.Where(File.Exists).ToArray();
        var skipped = paths.Count - files.Length;
        if (files.Length == 0)
        {
            StatusText.Text = "Only files can be sent, not folders.";
            return;
        }

        _sendingFiles = true;
        var sent = 0;
        var failures = new List<string>();
        try
        {
            for (var index = 0; index < files.Length; index++)
            {
                var name = Path.GetFileName(files[index]);
                var prefix = files.Length > 1 ? $"({index + 1}/{files.Length}) " : string.Empty;
                var lastPercent = -1;
                var progress = new Progress<double>(fraction =>
                {
                    var percent = (int)(fraction * 100);
                    if (percent != lastPercent)
                    {
                        lastPercent = percent;
                        StatusText.Text = $"Sending {prefix}{name} to {deviceName}: {percent}%";
                    }
                });

                StatusText.Text = $"Sending {prefix}{name} to {deviceName}...";
                var outcome = await Task.Run(() => push.PushAsync(deviceId, files[index], progress));
                if (outcome.Succeeded)
                {
                    sent++;
                }
                else
                {
                    failures.Add(outcome.Message);
                }

                if (files.Length == 1)
                {
                    StatusText.Text = outcome.Message;
                }
            }
        }
        finally
        {
            _sendingFiles = false;
        }

        if (files.Length > 1)
        {
            StatusText.Text = failures.Count == 0
                ? $"Sent {sent} files to {deviceName}'s Downloads folder."
                : $"Sent {sent} of {files.Length} files. {failures[0]}";
        }

        if (skipped > 0)
        {
            StatusText.Text += $" Skipped {skipped} folder(s).";
        }
    }
}
