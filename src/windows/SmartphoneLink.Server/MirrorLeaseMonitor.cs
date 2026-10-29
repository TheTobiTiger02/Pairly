using Microsoft.Extensions.Hosting;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Server;

public sealed class MirrorLeaseMonitor(
    MirrorSessionRegistry mirrors,
    WebSocketSessionHub sessions) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(10));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            foreach (var expired in mirrors.PruneExpired().Where(session => session.Active))
            {
                await sessions.SendAsync(
                    expired.DeviceId,
                    ProtocolEnvelope.Create(
                        ProtocolMessageTypes.MirrorStop,
                        "pc-host",
                        DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                        new MirrorSignalPayload(
                            expired.SessionId,
                            expired.DeviceId,
                            "stop",
                            "Viewer lease expired.")),
                    stoppingToken);
            }
        }
    }
}
