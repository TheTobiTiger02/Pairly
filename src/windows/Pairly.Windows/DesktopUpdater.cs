using System.Diagnostics;
using Velopack;
using Velopack.Sources;

namespace Pairly.Windows;

/// <summary>
/// Self-updates the GitHub (Velopack) install of Pairly. The Microsoft Store package is updated by
/// the Store, and development builds never update, so both report <see cref="IsEnabled"/> false.
/// </summary>
internal sealed class DesktopUpdater
{
    private const string RepositoryUrl = "https://github.com/TheTobiTiger02/Pairly";

    private readonly UpdateManager? _manager;
    private UpdateInfo? _pending;

    public DesktopUpdater()
    {
        try
        {
            var manager = new UpdateManager(new GithubSource(RepositoryUrl, accessToken: null, prerelease: false));
            _manager = manager.IsInstalled ? manager : null;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Velopack update manager unavailable: {ex.Message}");
        }
    }

    public bool IsEnabled => _manager is not null;

    /// <summary>Version downloaded and waiting for a restart, if any.</summary>
    public string? PendingVersion => _pending?.TargetFullRelease.Version.ToString();

    /// <summary>Checks GitHub and downloads a newer release in the background.</summary>
    public async Task<bool> CheckAndDownloadAsync()
    {
        if (_manager is null || _pending is not null)
        {
            return _pending is not null;
        }

        var update = await _manager.CheckForUpdatesAsync();
        if (update is null)
        {
            return false;
        }

        await _manager.DownloadUpdatesAsync(update);
        _pending = update;
        return true;
    }

    /// <summary>
    /// Starts the updater, which waits for this process to exit, installs the downloaded release
    /// and starts Pairly again. The caller then closes the app normally.
    /// </summary>
    public void ApplyOnExitAndRestart()
    {
        if (_manager is not null && _pending is not null)
        {
            _manager.WaitExitThenApplyUpdates(_pending.TargetFullRelease, silent: true, restart: true);
        }
    }
}
