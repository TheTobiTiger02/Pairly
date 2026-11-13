using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>
/// Dragging photos and phone files out of the window, into Explorer or any app that takes files.
///
/// Windows wants file paths when the drag starts, but the files are still on the phone. So the drag
/// carries the paths the files will have, and they are fetched in the moment the mouse button is
/// let go over something that takes them, before Windows hands the drop to that app.
/// </summary>
public partial class MainWindow
{
    private static readonly string DragFolder = Path.Combine(Path.GetTempPath(), "Pairly", "drag");

    /// <summary>A file that can be dragged out: where it will be on this PC and how to get it there.</summary>
    private sealed record DragFile(string Name, string LocalPath, Func<Task<bool>> FetchAsync);

    // True while a drag that started in this window is under way, so the window does not offer to
    // send the phone's own files back to the phone.
    private bool _draggingOut;

    private static string DragPathFor(string fileName)
    {
        var safe = string.Concat(fileName.Select(c => Path.GetInvalidFileNameChars().Contains(c) ? '_' : c));
        var folder = Path.Combine(DragFolder, Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(folder);
        return Path.Combine(folder, safe.Length == 0 ? "file" : safe);
    }

    /// <summary>Removes what earlier drags left, except recent ones another app may still be copying.</summary>
    private static void CleanDragFolder()
    {
        try
        {
            if (!Directory.Exists(DragFolder))
            {
                return;
            }

            foreach (var folder in Directory.GetDirectories(DragFolder))
            {
                if (DateTime.UtcNow - Directory.GetLastWriteTimeUtc(folder) > TimeSpan.FromHours(1))
                {
                    Directory.Delete(folder, recursive: true);
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Temporary files; Windows clears them eventually.
        }
    }

    private void DragOut(UIElement source, IReadOnlyList<DragFile> files)
    {
        if (files.Count == 0 || _draggingOut)
        {
            return;
        }

        var data = new System.Windows.DataObject(System.Windows.DataFormats.FileDrop, files.Select(file => file.LocalPath).ToArray());
        var effect = System.Windows.DragDropEffects.None;
        var released = false;
        var failed = false;

        void OnFeedback(object sender, System.Windows.GiveFeedbackEventArgs e) => effect = e.Effects;

        void OnQueryContinue(object sender, System.Windows.QueryContinueDragEventArgs e)
        {
            if (released || e.EscapePressed || (e.KeyStates & System.Windows.DragDropKeyStates.LeftMouseButton) != 0)
            {
                return;
            }

            released = true;
            if (effect == System.Windows.DragDropEffects.None)
            {
                // Let go over something that takes no files: nothing to fetch.
                return;
            }

            if (!FetchForDrop(files))
            {
                failed = true;
                e.Action = System.Windows.DragAction.Cancel;
                e.Handled = true;
            }
        }

        CleanDragFolder();
        _draggingOut = true;
        source.GiveFeedback += OnFeedback;
        source.QueryContinueDrag += OnQueryContinue;
        try
        {
            System.Windows.DragDrop.DoDragDrop(source, data, System.Windows.DragDropEffects.Copy);
        }
        finally
        {
            source.GiveFeedback -= OnFeedback;
            source.QueryContinueDrag -= OnQueryContinue;
            _draggingOut = false;
            DropOverlay.Visibility = Visibility.Collapsed;
        }

        if (failed)
        {
            ShowToast(files.Count == 1 ? L.F("drag_fetch_failed", files[0].Name) : L.T("drag_fetch_failed_many"), success: false);
        }
    }

    /// <summary>
    /// Fetches the files while Windows waits for the drop. The window keeps working meanwhile, so
    /// the notice shows and a long transfer does not look like a hang.
    /// </summary>
    private bool FetchForDrop(IReadOnlyList<DragFile> files)
    {
        ShowToast(files.Count == 1 ? L.F("drag_fetching", files[0].Name) : L.F("drag_fetching_many", files.Count));
        Mouse.OverrideCursor = System.Windows.Input.Cursors.Wait;
        try
        {
            var all = FetchAllAsync(files);
            if (!all.IsCompleted)
            {
                var frame = new DispatcherFrame();
                all.ContinueWith(_ => frame.Continue = false, TaskScheduler.FromCurrentSynchronizationContext());
                Dispatcher.PushFrame(frame);
            }

            return all.IsCompletedSuccessfully && all.Result;
        }
        finally
        {
            Mouse.OverrideCursor = null;
        }
    }

    private static async Task<bool> FetchAllAsync(IReadOnlyList<DragFile> files)
    {
        foreach (var file in files)
        {
            try
            {
                if (!await file.FetchAsync())
                {
                    return false;
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.Net.Http.HttpRequestException or TaskCanceledException)
            {
                AppLog.Write($"Fetching {file.Name} for a drag failed", ex);
                return false;
            }
        }

        return true;
    }

    // --- Photos -----------------------------------------------------------------------------------

    /// <summary>The dragged photo, or every selected photo when the dragged one is among them.</summary>
    private void DragPhotosOut(MediaItemViewModel dragged)
    {
        var selected = SelectedPhotos();
        var photos = selected.Contains(dragged) ? selected : [dragged];
        DragOut(PhotosList, photos.Select(photo =>
        {
            var path = DragPathFor(photo.FileName);
            return new DragFile(photo.FileName, path, async () =>
            {
                var bytes = await DownloadPhotoAsync(photo);
                if (bytes is null)
                {
                    return false;
                }

                await File.WriteAllBytesAsync(path, bytes);
                return true;
            });
        }).ToArray());
    }

    // --- Files ------------------------------------------------------------------------------------

    private StorageItemViewModel? _pressedStorageItem;
    private System.Windows.Point _pressedStorageAt;

    private void StorageItemsList_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _pressedStorageItem = (e.OriginalSource as FrameworkElement)?.DataContext is StorageItemViewModel { IsFile: true } item ? item : null;
        _pressedStorageAt = e.GetPosition(StorageItemsList);
    }

    private void StorageItemsList_PreviewMouseMove(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (_pressedStorageItem is not { } item || e.LeftButton != MouseButtonState.Pressed)
        {
            _pressedStorageItem = null;
            return;
        }

        var moved = e.GetPosition(StorageItemsList) - _pressedStorageAt;
        if (Math.Abs(moved.X) < SystemParameters.MinimumHorizontalDragDistance
            && Math.Abs(moved.Y) < SystemParameters.MinimumVerticalDragDistance)
        {
            return;
        }

        _pressedStorageItem = null;
        if (GetSelectedDevice() is not { } device || _embeddedServer.FilePush is not { } transfers)
        {
            return;
        }

        var path = DragPathFor(item.Name);
        DragOut(StorageItemsList,
        [
            new DragFile(item.Name, path, async () =>
                (await Task.Run(() => transfers.PullAsync(device.DeviceId, item.Payload.Path, path))).Succeeded)
        ]);
    }

    /// <summary>
    /// Saves a phone file to this PC over the stream connection, so its size does not matter.
    /// Returns what went wrong, or null.
    /// </summary>
    private async Task<string?> DownloadStorageFileAsync(string deviceId, StorageItemViewModel item, string destination)
    {
        if (_embeddedServer.FilePush is not { } transfers)
        {
            return L.T("this_file_could_not_be_downloaded_try_again");
        }

        var lastPercent = -1;
        var progress = new Progress<double>(fraction =>
        {
            var percent = (int)(fraction * 100);
            if (percent != lastPercent)
            {
                lastPercent = percent;
                StorageStatusText.Text = L.F("downloading_item_percent", item.Name, percent);
            }
        });
        var outcome = await Task.Run(() => transfers.PullAsync(deviceId, item.Payload.Path, destination, progress));
        return outcome.Succeeded ? null : outcome.Message;
    }
}
