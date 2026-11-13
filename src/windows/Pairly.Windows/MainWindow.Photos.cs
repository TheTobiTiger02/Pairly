using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using Pairly.Protocol;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>The Photos page: the whole library in a month-grouped grid, and the in-app viewer.</summary>
public partial class MainWindow
{
    private const int PhotoPageSize = 250;
    private const double ViewerMaxZoom = 8;
    private const int ViewerCacheSize = 6;
    private const int ViewerMaxDecodeWidth = 3840;

    // What the grid currently shows: the search text, the album (null = all) and the sort order.
    private string _photoSearch = string.Empty;
    private string? _photoAlbum;
    private string _photoSort = "newest";
    private HashSet<int> _visiblePhotoMonths = new();

    private List<MediaItemViewModel> _viewerPhotos = new();
    private int _viewerIndex;
    private CancellationTokenSource? _viewerLoad;
    private readonly LinkedList<(string MediaId, BitmapSource Image)> _viewerCache = new();
    private double _viewerZoom = 1;
    private System.Windows.Point? _viewerDragFrom;

    private bool PhotoSortIsByDate => _photoSort is "newest" or "oldest";

    /// <summary>Every photo the PC knows about for this phone, read page by page from the local host.</summary>
    /// <remarks>
    /// Returns null when the phone re-indexed its library between two pages: the host then refuses
    /// the old cursor. The caller keeps what it shows and the next refresh reads the new library.
    /// </remarks>
    private async Task<MediaItemViewModel[]?> FetchPhotosAsync(string deviceId)
    {
        var photos = new List<MediaItemViewModel>();
        string? cursor = null;
        do
        {
            var url = $"v1/media/{Uri.EscapeDataString(deviceId)}?pageSize={PhotoPageSize}"
                + (cursor is null ? string.Empty : $"&cursor={Uri.EscapeDataString(cursor)}");
            using var response = await _host.GetAsync(url);
            if (cursor is not null && response.StatusCode == System.Net.HttpStatusCode.BadRequest)
            {
                return null;
            }

            response.EnsureSuccessStatusCode();
            var page = await response.Content.ReadFromJsonAsync<MediaIndexPagePayload>(JsonOptions);
            if (page is null)
            {
                break;
            }

            photos.AddRange(page.Items.Select(item => new MediaItemViewModel(
                deviceId,
                item.MediaId,
                item.FileName,
                PhotoDetails(item.Width, item.Height, item.ByteCount),
                $"http://127.0.0.1:7816/v1/media/{Uri.EscapeDataString(deviceId)}/{Uri.EscapeDataString(item.MediaId)}/thumb",
                item.TakenAt,
                item.ByteCount,
                item.AlbumName,
                item.Width,
                item.Height,
                item.OrientationDegrees)));
            cursor = page.Cursor;
        }
        while (!string.IsNullOrEmpty(cursor));

        return photos.ToArray();
    }

    private static string PhotoDetails(int width, int height, long byteCount) =>
        width > 0 && height > 0 ? $"{width} × {height} · {FormatSize(byteCount)}" : FormatSize(byteCount);

    /// <summary>A file size the way Explorer shows it; the unit symbols are the same in every language.</summary>
    internal static string FormatSize(long bytes)
    {
        var culture = LocalizationSource.Instance.Culture;
        return bytes switch
        {
            < 1024 => string.Format(culture, "{0:n0} B", bytes),
            < 1024 * 1024 => string.Format(culture, "{0:n0} KB", bytes / 1024.0),
            < 1024L * 1024 * 1024 => string.Format(culture, "{0:0.#} MB", bytes / (1024.0 * 1024)),
            _ => string.Format(culture, "{0:0.##} GB", bytes / (1024.0 * 1024 * 1024))
        };
    }

    /// <summary>
    /// Brings the grid in line with the phone's library without rebuilding it: photos are added,
    /// updated and removed in place, so a refresh never moves the scroll position.
    /// </summary>
    private void ReconcilePhotos(IReadOnlyList<MediaItemViewModel> latest)
    {
        var latestIds = latest.Select(photo => photo.MediaId).ToHashSet(StringComparer.Ordinal);
        var existingById = _photos.Where(photo => !photo.IsSectionHeader)
            .ToDictionary(photo => photo.MediaId, StringComparer.Ordinal);
        var added = latest.Count(photo => !existingById.ContainsKey(photo.MediaId));
        var removed = existingById.Keys.Count(id => !latestIds.Contains(id));
        var monthsBefore = _visiblePhotoMonths;

        if (added + removed > 200)
        {
            // A large change (the first load, another phone) is one rebuild, not hundreds of inserts.
            var kept = latest.Select(incoming =>
            {
                if (!existingById.TryGetValue(incoming.MediaId, out var existing))
                {
                    return incoming;
                }

                existing.Update(incoming);
                return existing;
            }).ToArray();
            _allPhotos = kept;
            _visiblePhotoMonths = VisiblePhotoMonths();
            var culture = LocalizationSource.Instance.Culture;
            _photos.ReplaceAll(kept.Concat(kept.Select(photo => photo.MonthKey).Distinct()
                .Select(month => MediaItemViewModel.MonthHeader(month, MonthTitle(month, culture)))));
        }
        else
        {
            // A few new or deleted photos slot in where they belong; the scroll position stays.
            for (var index = _photos.Count - 1; index >= 0; index--)
            {
                if (!_photos[index].IsSectionHeader && !latestIds.Contains(_photos[index].MediaId))
                {
                    _photos.RemoveAt(index);
                }
            }

            foreach (var incoming in latest)
            {
                if (existingById.TryGetValue(incoming.MediaId, out var existing))
                {
                    existing.Update(incoming);
                }
                else
                {
                    _photos.Add(incoming);
                }
            }

            _allPhotos = _photos.Where(photo => !photo.IsSectionHeader).ToArray();
            _visiblePhotoMonths = VisiblePhotoMonths();
            ReconcileMonthHeaders();

            // A month appeared or emptied under the current filter: its heading has to be re-checked.
            if (!monthsBefore.SetEquals(_visiblePhotoMonths))
            {
                _photosView?.Refresh();
            }
        }

        UpdatePhotoAlbums();
        UpdatePhotoSummary();
    }

    private void ReconcileMonthHeaders()
    {
        var months = _allPhotos.Select(photo => photo.MonthKey).ToHashSet();
        var culture = LocalizationSource.Instance.Culture;
        for (var index = _photos.Count - 1; index >= 0; index--)
        {
            var item = _photos[index];
            if (!item.IsSectionHeader)
            {
                continue;
            }

            if (!months.Remove(item.MonthKey))
            {
                _photos.RemoveAt(index);
            }
            else
            {
                item.FileName = MonthTitle(item.MonthKey, culture);
            }
        }

        foreach (var month in months)
        {
            _photos.Add(MediaItemViewModel.MonthHeader(month, MonthTitle(month, culture)));
        }
    }

    private static string MonthTitle(int monthKey, System.Globalization.CultureInfo culture) =>
        new DateTime(monthKey / 12, monthKey % 12 + 1, 1).ToString("MMMM yyyy", culture);

    private bool PhotoMatchesFilter(MediaItemViewModel photo) =>
        (_photoSearch.Length == 0
            || photo.FileName.Contains(_photoSearch, StringComparison.OrdinalIgnoreCase)
            || (photo.AlbumName?.Contains(_photoSearch, StringComparison.OrdinalIgnoreCase) ?? false))
        && (_photoAlbum is null || string.Equals(photo.AlbumName, _photoAlbum, StringComparison.OrdinalIgnoreCase));

    private HashSet<int> VisiblePhotoMonths() =>
        PhotoSortIsByDate
            ? _allPhotos.Where(PhotoMatchesFilter).Select(photo => photo.MonthKey).ToHashSet()
            : new HashSet<int>();

    private void UpdatePhotoAlbums()
    {
        if (PhotoAlbumFilter is null)
        {
            return;
        }

        var all = L.T("all_albums");
        var albumItems = new[] { all }
            .Concat(_allPhotos
                .Select(photo => photo.AlbumName)
                .Where(album => !string.IsNullOrWhiteSpace(album))
                .Select(album => album!)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(album => album, StringComparer.OrdinalIgnoreCase))
            .ToArray();
        ReconcileOrderedCollection(_photoAlbums, albumItems, item => item);
        var wanted = _photoAlbum is not null && albumItems.Contains(_photoAlbum, StringComparer.OrdinalIgnoreCase) ? _photoAlbum : all;
        if (!string.Equals(PhotoAlbumFilter.SelectedItem as string, wanted, StringComparison.OrdinalIgnoreCase))
        {
            PhotoAlbumFilter.SelectedItem = wanted;
        }
    }

    /// <summary>The user changed the search, album or sort order (or the language): rebuild the view.</summary>
    private void ApplyPhotoFilters()
    {
        if (PhotosList is null || PhotoAlbumFilter is null || _photosView is null)
        {
            return;
        }

        _photoSearch = PhotoSearchBox?.Text?.Trim() ?? string.Empty;
        _photoSort = (PhotoSortCombo?.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "newest";
        var album = PhotoAlbumFilter.SelectedItem as string;
        _photoAlbum = album is null || string.Equals(album, L.T("all_albums"), StringComparison.OrdinalIgnoreCase) ? null : album;
        _visiblePhotoMonths = VisiblePhotoMonths();

        using (_photosView.DeferRefresh())
        {
            _photosView.Filter = item => item is MediaItemViewModel photo
                && (photo.IsSectionHeader ? _visiblePhotoMonths.Contains(photo.MonthKey) : PhotoMatchesFilter(photo));
            _photosView.CustomSort = Comparer<MediaItemViewModel>.Create(ComparePhotos);
        }

        UpdatePhotoSummary();
    }

    private int ComparePhotos(MediaItemViewModel left, MediaItemViewModel right)
    {
        switch (_photoSort)
        {
            case "largest":
                return right.ByteCount.CompareTo(left.ByteCount);
            case "name":
                return StringComparer.OrdinalIgnoreCase.Compare(left.FileName, right.FileName);
        }

        // By date: month by month, each month's heading first, then its photos.
        var oldest = _photoSort == "oldest";
        var byMonth = left.MonthKey.CompareTo(right.MonthKey);
        if (byMonth != 0)
        {
            return oldest ? byMonth : -byMonth;
        }

        if (left.IsSectionHeader != right.IsSectionHeader)
        {
            return left.IsSectionHeader ? -1 : 1;
        }

        var byTime = left.TakenAt.CompareTo(right.TakenAt);
        if (byTime == 0)
        {
            byTime = StringComparer.Ordinal.Compare(left.MediaId, right.MediaId);
        }

        return oldest ? byTime : -byTime;
    }

    /// <summary>The one place that writes the line under the Photos title and the empty state.</summary>
    private void UpdatePhotoSummary()
    {
        if (PhotosStatusText is null || _photosView is null)
        {
            return;
        }

        var device = GetSelectedDevice();
        var shown = _photosView.Cast<MediaItemViewModel>().Count(photo => !photo.IsSectionHeader);
        PhotosStatusText.Text = device is null
            ? L.T("select_a_paired_device_to_browse_photos")
            : !PermissionGranted(device, "photos")
                ? FeatureOffText("photos")
                : _allPhotos.Length == 0
                    ? L.T("no_photos_from_phone_yet")
                    : shown == _allPhotos.Length
                        ? L.F("n_photos", _allPhotos.Length)
                        : L.F("showing_of_photo_s", shown, _allPhotos.Length);

        if (PhotosEmptyState is not null)
        {
            PhotosEmptyState.Visibility = shown == 0 ? Visibility.Visible : Visibility.Collapsed;
            PhotosEmptyBody.Text = L.T(_allPhotos.Length == 0 ? "photos_empty_body" : "photos_empty_filtered");
        }
    }

    private MediaItemViewModel[] SelectedPhotos() =>
        PhotosList.SelectedItems.Cast<MediaItemViewModel>().Where(photo => !photo.IsSectionHeader).ToArray();

    private void PhotoSearchBox_TextChanged(object sender, TextChangedEventArgs e) => ApplyPhotoFilters();

    private void PhotoSortCombo_SelectionChanged(object sender, SelectionChangedEventArgs e) => ApplyPhotoFilters();

    private void PhotoAlbumFilter_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var album = PhotoAlbumFilter.SelectedItem as string;
        var wanted = album is null || string.Equals(album, L.T("all_albums"), StringComparison.OrdinalIgnoreCase) ? null : album;
        if (!string.Equals(wanted, _photoAlbum, StringComparison.OrdinalIgnoreCase))
        {
            ApplyPhotoFilters();
        }
    }

    private void PhotoTileSizeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_photoGridPanel is not null)
        {
            _photoGridPanel.ItemSize = CurrentPhotoTileSize();
        }
    }

    private void PhotosList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (PhotoSelectionText is not null)
        {
            var count = SelectedPhotos().Length;
            PhotoSelectionText.Text = L.F("n_selected", count);
            SavePhotosButton.IsEnabled = count > 0;
            OpenPhotoButton.IsEnabled = count == 1;
        }
    }

    private void OpenSelectedPhoto_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedPhotos().FirstOrDefault() is { } photo)
        {
            OpenPhotoViewer(photo);
        }
        else
        {
            PhotosStatusText.Text = L.T("select_a_photo_first");
        }
    }

    private void PhotosList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is MediaItemViewModel { IsSectionHeader: false } photo)
        {
            OpenPhotoViewer(photo);
        }
    }

    private void PhotosList_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Enter && SelectedPhotos().FirstOrDefault() is { } photo)
        {
            OpenPhotoViewer(photo);
            e.Handled = true;
        }
        else if (e.Key == Key.C && Keyboard.Modifiers == ModifierKeys.Control && SelectedPhotos().FirstOrDefault() is { } selected)
        {
            _ = CopyPhotoAsync(selected);
            e.Handled = true;
        }
    }

    private async void CopyPhotoInline_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: MediaItemViewModel photo })
        {
            await CopyPhotoAsync(photo);
        }
    }

    private async void CopySelectedPhoto_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedPhotos().FirstOrDefault() is { } photo)
        {
            await CopyPhotoAsync(photo);
        }
    }

    /// <summary>
    /// Puts the original on the Windows clipboard, as a picture and as a file, so it pastes into a
    /// chat or an editor as well as into a folder.
    /// </summary>
    private async Task CopyPhotoAsync(MediaItemViewModel photo)
    {
        try
        {
            ShowToast(L.T("copying_photo"));
            var bytes = await DownloadPhotoAsync(photo);
            if (bytes is null)
            {
                ShowToast(L.T("photo_copy_failed"), success: false);
                return;
            }

            var directory = Path.Combine(Path.GetTempPath(), "Pairly", "photos");
            Directory.CreateDirectory(directory);
            var path = Path.Combine(directory, SanitizeFileName(photo.FileName));
            await File.WriteAllBytesAsync(path, bytes);

            var data = new System.Windows.DataObject();
            data.SetFileDropList(new System.Collections.Specialized.StringCollection { path });
            if (DecodeForClipboard(bytes, photo.OrientationDegrees) is { } picture)
            {
                data.SetImage(picture);
            }

            System.Windows.Clipboard.SetDataObject(data, copy: true);
            ShowToast(L.T("photo_copied"));
        }
        catch (Exception ex) when (ex is IOException or HttpRequestException or TaskCanceledException or System.Runtime.InteropServices.ExternalException)
        {
            ShowToast(L.T("photo_copy_failed"), success: false);
            AppLog.Write("Photo copy failed", ex);
        }
    }

    /// <summary>The picture upright, or null for a format Windows cannot decode (the file is still copied).</summary>
    private static BitmapSource? DecodeForClipboard(byte[] bytes, int orientationDegrees)
    {
        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.StreamSource = new MemoryStream(bytes);
            bitmap.Rotation = orientationDegrees switch
            {
                90 => Rotation.Rotate90,
                180 => Rotation.Rotate180,
                270 => Rotation.Rotate270,
                _ => Rotation.Rotate0
            };
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }
        catch (Exception ex) when (ex is NotSupportedException or System.Runtime.InteropServices.COMException or IOException)
        {
            return null;
        }
    }

    private void OpenPhotoInline_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: MediaItemViewModel photo })
        {
            OpenPhotoViewer(photo);
        }
    }

    private async void OpenPhotoExternallyInline_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: MediaItemViewModel photo })
        {
            await OpenPhotoExternallyAsync(photo);
        }
    }

    private async void SavePhotoInline_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: MediaItemViewModel photo })
        {
            await SavePhotoWithDialogAsync(photo);
        }
    }

    private string PhotoContentUrl(MediaItemViewModel photo) =>
        $"v1/media/{Uri.EscapeDataString(photo.DeviceId)}/{Uri.EscapeDataString(photo.MediaId)}/content";

    /// <summary>Downloads the original from the phone. Null (with the reason in the status line) when it fails.</summary>
    private async Task<byte[]?> DownloadPhotoAsync(MediaItemViewModel photo, CancellationToken cancellationToken = default)
    {
        using var response = await _host.GetAsync(PhotoContentUrl(photo), cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            PhotosStatusText.Text = L.F("photo_not_loaded", photo.FileName);
            return null;
        }

        return await response.Content.ReadAsByteArrayAsync(cancellationToken);
    }

    /// <summary>Opens the original in the Windows app for pictures, from a temporary copy.</summary>
    private async Task OpenPhotoExternallyAsync(MediaItemViewModel photo)
    {
        try
        {
            PhotosStatusText.Text = L.F("downloading_item", photo.FileName);
            var bytes = await DownloadPhotoAsync(photo);
            if (bytes is null)
            {
                return;
            }

            var directory = Path.Combine(Path.GetTempPath(), "Pairly", "photos");
            Directory.CreateDirectory(directory);
            var targetPath = Path.Combine(directory, SanitizeFileName(photo.FileName));
            await File.WriteAllBytesAsync(targetPath, bytes);
            Process.Start(new ProcessStartInfo(targetPath) { UseShellExecute = true });
            PhotosStatusText.Text = L.F("opened_item", photo.FileName);
        }
        catch (Exception ex)
        {
            PhotosStatusText.Text = UserFacingError(L.T("the_photo_could_not_be_opened_check_the_phon"), ex);
        }
    }

    private async Task SavePhotoWithDialogAsync(MediaItemViewModel photo)
    {
        try
        {
            var extension = Path.GetExtension(photo.FileName);
            var saveDialog = new Microsoft.Win32.SaveFileDialog
            {
                FileName = photo.FileName,
                Filter = $"{L.T("image_files")}|*.jpg;*.jpeg;*.png;*.webp;*.heic;*.gif{(extension.Length > 1 ? ";*" + extension : string.Empty)}|{L.T("all_files")}|*.*"
            };
            if (saveDialog.ShowDialog(this) != true)
            {
                return;
            }

            PhotosStatusText.Text = L.F("downloading_item", photo.FileName);
            var bytes = await DownloadPhotoAsync(photo);
            if (bytes is null)
            {
                return;
            }

            await File.WriteAllBytesAsync(saveDialog.FileName, bytes);
            PhotosStatusText.Text = L.F("saved_to", Path.GetFileName(saveDialog.FileName));
        }
        catch (Exception ex)
        {
            PhotosStatusText.Text = UserFacingError(L.T("the_selected_photo_could_not_be_saved_try_ag"), ex);
        }
    }

    private async void SaveSelectedPhotos_Click(object sender, RoutedEventArgs e)
    {
        var selectedPhotos = SelectedPhotos();
        if (selectedPhotos.Length == 0)
        {
            PhotosStatusText.Text = L.T("select_one_or_more_photos_first");
            return;
        }

        if (selectedPhotos.Length == 1)
        {
            await SavePhotoWithDialogAsync(selectedPhotos[0]);
            return;
        }

        using var folderDialog = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = L.T("save_selected_pairly_photos"),
            UseDescriptionForTitle = true,
            ShowNewFolderButton = true
        };
        if (folderDialog.ShowDialog() != System.Windows.Forms.DialogResult.OK)
        {
            return;
        }

        SavePhotosButton.IsEnabled = false;
        var failed = new List<string>();
        try
        {
            for (var index = 0; index < selectedPhotos.Length; index++)
            {
                var photo = selectedPhotos[index];
                PhotosStatusText.Text = L.F("saving_photo_n_of", index + 1, selectedPhotos.Length);
                try
                {
                    var bytes = await DownloadPhotoAsync(photo);
                    if (bytes is null)
                    {
                        failed.Add(photo.FileName);
                        continue;
                    }

                    var fileName = SanitizeFileName(photo.FileName);
                    var targetPath = Path.Combine(folderDialog.SelectedPath, fileName);
                    if (File.Exists(targetPath))
                    {
                        targetPath = Path.Combine(
                            folderDialog.SelectedPath,
                            $"{Path.GetFileNameWithoutExtension(fileName)}-{photo.MediaId[..Math.Min(8, photo.MediaId.Length)]}{Path.GetExtension(fileName)}");
                    }

                    await File.WriteAllBytesAsync(targetPath, bytes);
                }
                catch (Exception ex) when (ex is HttpRequestException or IOException or UnauthorizedAccessException or TaskCanceledException)
                {
                    failed.Add(photo.FileName);
                }
            }
        }
        finally
        {
            SavePhotosButton.IsEnabled = SelectedPhotos().Length > 0;
        }

        var saved = selectedPhotos.Length - failed.Count;
        PhotosStatusText.Text = L.F("saved_of_selected_photo_s_to", saved, selectedPhotos.Length, folderDialog.SelectedPath)
            + (failed.Count == 0 ? string.Empty : " " + L.F("photos_not_saved", string.Join(", ", failed.Take(5)) + (failed.Count > 5 ? "…" : string.Empty)));
    }

    // --- Viewer ---------------------------------------------------------------------------------

    /// <summary>Opens the photo over the window. Arrow keys step through the photos as the grid lists them.</summary>
    private void OpenPhotoViewer(MediaItemViewModel photo)
    {
        if (photo.IsSectionHeader || _photosView is null)
        {
            return;
        }

        _viewerPhotos = _photosView.Cast<MediaItemViewModel>().Where(item => !item.IsSectionHeader).ToList();
        _viewerIndex = Math.Max(0, _viewerPhotos.IndexOf(photo));
        PhotoViewerOverlay.Visibility = Visibility.Visible;
        if (Motion.Enabled)
        {
            PhotoViewerOverlay.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(140)));
        }

        PhotoViewerOverlay.Focus();
        _ = ShowViewerPhotoAsync();
    }

    private void ClosePhotoViewer()
    {
        _viewerLoad?.Cancel();
        PhotoViewerOverlay.BeginAnimation(OpacityProperty, null);
        PhotoViewerOverlay.Visibility = Visibility.Collapsed;
        PhotoViewerImage.Source = null;
        if (_viewerIndex < _viewerPhotos.Count)
        {
            // Leave the grid where the viewer ended up.
            var current = _viewerPhotos[_viewerIndex];
            PhotosList.SelectedItem = current;
            PhotosList.ScrollIntoView(current);
        }

        _viewerPhotos = new List<MediaItemViewModel>();
        PhotosList.Focus();
    }

    private void StepPhotoViewer(int delta)
    {
        var next = Math.Clamp(_viewerIndex + delta, 0, _viewerPhotos.Count - 1);
        if (next != _viewerIndex)
        {
            _viewerIndex = next;
            _ = ShowViewerPhotoAsync();
        }
    }

    private async Task ShowViewerPhotoAsync()
    {
        _viewerLoad?.Cancel();
        _viewerLoad?.Dispose();
        var load = _viewerLoad = new CancellationTokenSource();
        var photo = _viewerPhotos[_viewerIndex];

        ResetViewerZoom();
        PhotoViewerTitle.Text = photo.FileName;
        PhotoViewerDetails.Text = string.Join(" · ", new[]
        {
            photo.TakenAt.LocalDateTime.ToString("f", LocalizationSource.Instance.Culture),
            photo.Details,
            photo.AlbumName
        }.Where(part => !string.IsNullOrWhiteSpace(part)));
        PhotoViewerCounter.Text = L.F("photo_n_of", _viewerIndex + 1, _viewerPhotos.Count);
        PhotoViewerPrevious.IsEnabled = _viewerIndex > 0;
        PhotoViewerNext.IsEnabled = _viewerIndex < _viewerPhotos.Count - 1;
        PhotoViewerError.Visibility = Visibility.Collapsed;

        var cached = _viewerCache.FirstOrDefault(entry => entry.MediaId == photo.MediaId).Image;
        // The thumbnail fills in at once; the original replaces it when it has arrived.
        PhotoViewerImage.Source = cached ?? photo.Thumbnail;
        PhotoViewerProgress.Visibility = cached is null ? Visibility.Visible : Visibility.Collapsed;
        if (cached is null)
        {
            try
            {
                var image = await LoadViewerImageAsync(photo, load.Token);
                if (load.IsCancellationRequested)
                {
                    return;
                }

                if (image is null)
                {
                    PhotoViewerError.Visibility = Visibility.Visible;
                }
                else
                {
                    PhotoViewerImage.Source = image;
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex) when (ex is HttpRequestException or IOException)
            {
                PhotoViewerError.Visibility = Visibility.Visible;
            }

            PhotoViewerProgress.Visibility = Visibility.Collapsed;
        }

        // Have the next photo ready so stepping forward is instant.
        if (_viewerIndex + 1 < _viewerPhotos.Count && !load.IsCancellationRequested)
        {
            try
            {
                await LoadViewerImageAsync(_viewerPhotos[_viewerIndex + 1], load.Token);
            }
            catch (Exception ex) when (ex is OperationCanceledException or HttpRequestException or IOException)
            {
            }
        }
    }

    /// <summary>The original, decoded off the UI thread and turned the way the phone shows it. Null when Windows can't decode the format.</summary>
    private async Task<BitmapSource?> LoadViewerImageAsync(MediaItemViewModel photo, CancellationToken cancellationToken)
    {
        var cached = _viewerCache.FirstOrDefault(entry => entry.MediaId == photo.MediaId).Image;
        if (cached is not null)
        {
            return cached;
        }

        using var response = await _host.GetAsync(PhotoContentUrl(photo), cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            return null;
        }

        var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        var image = await Task.Run(() =>
        {
            try
            {
                var bitmap = new BitmapImage();
                using var stream = new MemoryStream(bytes);
                bitmap.BeginInit();
                bitmap.CacheOption = BitmapCacheOption.OnLoad;
                if (photo.PixelWidth > ViewerMaxDecodeWidth)
                {
                    bitmap.DecodePixelWidth = ViewerMaxDecodeWidth;
                }

                bitmap.Rotation = photo.OrientationDegrees switch
                {
                    90 => Rotation.Rotate90,
                    180 => Rotation.Rotate180,
                    270 => Rotation.Rotate270,
                    _ => Rotation.Rotate0
                };
                bitmap.StreamSource = stream;
                bitmap.EndInit();
                bitmap.Freeze();
                return (BitmapSource?)bitmap;
            }
            catch (Exception ex) when (ex is NotSupportedException or FileFormatException or InvalidOperationException or IOException or ArgumentException)
            {
                return null;
            }
        }, cancellationToken);

        if (image is not null)
        {
            _viewerCache.AddFirst((photo.MediaId, image));
            while (_viewerCache.Count > ViewerCacheSize)
            {
                _viewerCache.RemoveLast();
            }
        }

        return image;
    }

    private void ResetViewerZoom()
    {
        _viewerZoom = 1;
        PhotoViewerScale.ScaleX = PhotoViewerScale.ScaleY = 1;
        PhotoViewerTranslate.X = PhotoViewerTranslate.Y = 0;
        PhotoViewerStage.Cursor = null;
    }

    /// <summary>Zooms so the point under <paramref name="anchor"/> (relative to the stage centre) stays put.</summary>
    private void ZoomViewer(double zoom, System.Windows.Point anchor)
    {
        zoom = Math.Clamp(zoom, 1, ViewerMaxZoom);
        if (zoom <= 1.001)
        {
            ResetViewerZoom();
            return;
        }

        var ratio = zoom / _viewerZoom;
        PhotoViewerTranslate.X = anchor.X - (anchor.X - PhotoViewerTranslate.X) * ratio;
        PhotoViewerTranslate.Y = anchor.Y - (anchor.Y - PhotoViewerTranslate.Y) * ratio;
        _viewerZoom = zoom;
        PhotoViewerScale.ScaleX = PhotoViewerScale.ScaleY = zoom;
        PhotoViewerStage.Cursor = System.Windows.Input.Cursors.SizeAll;
        ClampViewerPan();
    }

    private void ClampViewerPan()
    {
        var limitX = Math.Max(0, (PhotoViewerImage.ActualWidth * _viewerZoom - PhotoViewerStage.ActualWidth) / 2);
        var limitY = Math.Max(0, (PhotoViewerImage.ActualHeight * _viewerZoom - PhotoViewerStage.ActualHeight) / 2);
        PhotoViewerTranslate.X = Math.Clamp(PhotoViewerTranslate.X, -limitX, limitX);
        PhotoViewerTranslate.Y = Math.Clamp(PhotoViewerTranslate.Y, -limitY, limitY);
    }

    private System.Windows.Point StageCentreOffset(System.Windows.Input.MouseEventArgs e)
    {
        var position = e.GetPosition(PhotoViewerStage);
        return new System.Windows.Point(position.X - PhotoViewerStage.ActualWidth / 2, position.Y - PhotoViewerStage.ActualHeight / 2);
    }

    private void PhotoViewerStage_MouseWheel(object sender, MouseWheelEventArgs e)
    {
        ZoomViewer(_viewerZoom * (e.Delta > 0 ? 1.25 : 0.8), StageCentreOffset(e));
        e.Handled = true;
    }

    private void PhotoViewerStage_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2)
        {
            ZoomViewer(_viewerZoom > 1 ? 1 : 2.5, StageCentreOffset(e));
            return;
        }

        if (ReferenceEquals(e.OriginalSource, PhotoViewerStage) && _viewerZoom <= 1)
        {
            // A click beside the photo closes the viewer, like clicking outside a dialog.
            ClosePhotoViewer();
            return;
        }

        if (_viewerZoom > 1)
        {
            _viewerDragFrom = e.GetPosition(PhotoViewerStage);
            PhotoViewerStage.CaptureMouse();
        }
    }

    private void PhotoViewerStage_MouseMove(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (_viewerDragFrom is not { } from || e.LeftButton != MouseButtonState.Pressed)
        {
            return;
        }

        var now = e.GetPosition(PhotoViewerStage);
        PhotoViewerTranslate.X += now.X - from.X;
        PhotoViewerTranslate.Y += now.Y - from.Y;
        _viewerDragFrom = now;
        ClampViewerPan();
    }

    private void PhotoViewerStage_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        _viewerDragFrom = null;
        PhotoViewerStage.ReleaseMouseCapture();
    }

    private void PhotoViewerOverlay_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        var centre = new System.Windows.Point(0, 0);
        switch (e.Key)
        {
            case Key.Escape:
                ClosePhotoViewer();
                break;
            case Key.Left:
            case Key.PageUp:
                StepPhotoViewer(-1);
                break;
            case Key.Right:
            case Key.PageDown:
            case Key.Space:
                StepPhotoViewer(1);
                break;
            case Key.Home:
                StepPhotoViewer(-_viewerPhotos.Count);
                break;
            case Key.End:
                StepPhotoViewer(_viewerPhotos.Count);
                break;
            case Key.Add:
            case Key.OemPlus:
                ZoomViewer(_viewerZoom * 1.25, centre);
                break;
            case Key.Subtract:
            case Key.OemMinus:
                ZoomViewer(_viewerZoom * 0.8, centre);
                break;
            case Key.D0:
            case Key.NumPad0:
                ResetViewerZoom();
                break;
            case Key.S when Keyboard.Modifiers == ModifierKeys.Control:
                PhotoViewerSave_Click(sender, e);
                break;
            case Key.C when Keyboard.Modifiers == ModifierKeys.Control:
                PhotoViewerCopy_Click(sender, e);
                break;
            default:
                return;
        }

        e.Handled = true;
    }

    private void PhotoViewerClose_Click(object sender, RoutedEventArgs e) => ClosePhotoViewer();
    private void PhotoViewerPrevious_Click(object sender, RoutedEventArgs e) => StepPhotoViewer(-1);
    private void PhotoViewerNext_Click(object sender, RoutedEventArgs e) => StepPhotoViewer(1);

    private async void PhotoViewerCopy_Click(object sender, RoutedEventArgs e)
    {
        if (_viewerIndex < _viewerPhotos.Count)
        {
            await CopyPhotoAsync(_viewerPhotos[_viewerIndex]);
        }
    }

    private async void PhotoViewerSave_Click(object sender, RoutedEventArgs e)
    {
        if (_viewerIndex < _viewerPhotos.Count)
        {
            await SavePhotoWithDialogAsync(_viewerPhotos[_viewerIndex]);
        }
    }

    private async void PhotoViewerOpenExternally_Click(object sender, RoutedEventArgs e)
    {
        if (_viewerIndex < _viewerPhotos.Count)
        {
            await OpenPhotoExternallyAsync(_viewerPhotos[_viewerIndex]);
        }
    }
}
