using Microsoft.Extensions.Hosting;
using SmartphoneLink.Core;

namespace SmartphoneLink.Server;

public sealed class ContentTransferCleanupService(
    ContentTransferStore transfers,
    ClipboardHub clipboard) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(30));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            transfers.PruneExpired();
            clipboard.PruneExpired();
        }
    }
}
