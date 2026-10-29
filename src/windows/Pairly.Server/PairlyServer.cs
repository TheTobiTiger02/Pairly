using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Pairly.Core;
using Pairly.Protocol;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json;

namespace Pairly.Server;

public sealed class PairlyServer
{
    public const string DefaultHttpUrl = "http://127.0.0.1:7816";
    private static readonly JsonSerializerOptions DiscoveryJsonOptions = new(JsonSerializerDefaults.Web);

    private WebApplication? _app;
    private System.Net.Sockets.UdpClient? _udpListener;
    private CancellationTokenSource? _udpCts;

    public bool IsRunning => _app is not null;

    /// <summary>
    /// Transport/pairing/mirror event log for this host. The desktop shell writes its own
    /// client-side socket events here too, so one view shows both ends of the loopback link.
    /// </summary>
    public DiagnosticsLog Diagnostics { get; } = new();

    /// <summary>
    /// Routes accepted video streams to whatever renders them. Null until the host is running,
    /// because the hub lives in the host's service provider.
    /// </summary>
    public Streaming.MirrorStreamHub? MirrorStreams =>
        _app?.Services.GetService<Streaming.MirrorStreamHub>();

    /// <summary>Starts ADB-mode streams. Null until the host is running.</summary>
    public Adb.AdbMirrorLauncher? AdbMirror =>
        _app?.Services.GetService<Adb.AdbMirrorLauncher>();

    /// <summary>Sends PC files to a phone. Null until the host is running.</summary>
    public Streaming.FilePushService? FilePush =>
        _app?.Services.GetService<Streaming.FilePushService>();

    public async Task StartAsync(
        string url = DefaultHttpUrl,
        CancellationToken cancellationToken = default,
        string? storageDirectory = null)
    {
        if (_app is not null)
        {
            return;
        }

        var isDefaultHost = string.Equals(url, DefaultHttpUrl, StringComparison.OrdinalIgnoreCase);
        if (isDefaultHost)
        {
            HostPortPreflight.VerifyRequiredEndpointsAvailable();
        }

        WebApplication? startingApp = null;
        try
        {
            startingApp = Build(url, storageDirectory, Diagnostics);
            await startingApp.StartAsync(cancellationToken);
            if (isDefaultHost)
            {
                StartUdpDiscoveryListener(startingApp.Services.GetRequiredService<HostIdentity>());
            }

            _app = startingApp;
        }
        catch
        {
            StopUdpDiscoveryListener();
            if (startingApp is not null)
            {
                try
                {
                    await startingApp.StopAsync(CancellationToken.None);
                }
                catch
                {
                    // Disposal below still releases any endpoints from a partial start.
                }

                await startingApp.DisposeAsync();
            }

            _app = null;
            throw;
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        StopUdpDiscoveryListener();

        if (_app is null)
        {
            return;
        }

        await _app.StopAsync(cancellationToken);
        await _app.DisposeAsync();
        _app = null;
    }

    private void StartUdpDiscoveryListener(HostIdentity identity)
    {
        _udpCts = new CancellationTokenSource();
        try
        {
            _udpListener = new System.Net.Sockets.UdpClient(System.Net.Sockets.AddressFamily.InterNetwork);
            _udpListener.ExclusiveAddressUse = true;
            _udpListener.Client.Bind(new System.Net.IPEndPoint(System.Net.IPAddress.Any, 7817));
            _udpListener.EnableBroadcast = true;
            
            var token = _udpCts.Token;
            Task.Run(async () =>
            {
                while (!token.IsCancellationRequested)
                {
                    try
                    {
                        var result = await _udpListener.ReceiveAsync(token);
                        var message = System.Text.Encoding.UTF8.GetString(result.Buffer);
                        if (message is "PAIRLY_DISCOVER_REQUEST" or "PAIRLY_DISCOVER_V2")
                        {
                            var responseObj = new DiscoveryResponsePayload(
                                identity.HostId,
                                identity.PcName,
                                HostIdentity.ProtocolVersion,
                                HostIdentity.LanPort,
                                HostIdentity.SecureLanPort,
                                identity.CertificateFingerprint,
                                Networking.EndpointEnumerator.Enumerate());
                            var responseJson = SerializeDiscoveryResponse(responseObj);
                            var responseBytes = System.Text.Encoding.UTF8.GetBytes(responseJson);
                            await _udpListener.SendAsync(responseBytes, responseBytes.Length, result.RemoteEndPoint);
                        }
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                    catch (Exception)
                    {
                        await Task.Delay(100, token);
                    }
                }
            }, token);
        }
        catch (Exception ex) when (ex is System.Net.Sockets.SocketException or InvalidOperationException)
        {
            StopUdpDiscoveryListener();
            throw new HostStartupException(7817, "UDP", ex);
        }
    }

    public static string SerializeDiscoveryResponse(DiscoveryResponsePayload response) =>
        JsonSerializer.Serialize(response, DiscoveryJsonOptions);

    private void StopUdpDiscoveryListener()
    {
        try
        {
            _udpCts?.Cancel();
            _udpCts?.Dispose();
            _udpCts = null;
        }
        catch { }

        try
        {
            _udpListener?.Close();
            _udpListener?.Dispose();
            _udpListener = null;
        }
        catch { }
    }

    public static WebApplication Build(
        string url = DefaultHttpUrl,
        string? storageDirectory = null,
        DiagnosticsLog? diagnostics = null)
    {
        var identityDirectory = storageDirectory
            ?? (string.Equals(url, DefaultHttpUrl, StringComparison.OrdinalIgnoreCase)
                ? HostIdentity.DefaultStorageDirectory
                : Path.Combine(Path.GetTempPath(), "Pairly", $"host-test-{Guid.NewGuid():N}"));
        var identity = HostIdentity.LoadOrCreate(identityDirectory);
        var builder = WebApplication.CreateBuilder();
        if (string.Equals(url, DefaultHttpUrl, StringComparison.OrdinalIgnoreCase))
        {
            builder.WebHost.UseUrls(
                DefaultHttpUrl,
                $"https://0.0.0.0:{HostIdentity.SecureLanPort}");
            builder.WebHost.ConfigureKestrel(options =>
            {
                options.ConfigureHttpsDefaults(https => https.ServerCertificate = identity.Certificate);
            });
        }
        else
        {
            builder.WebHost.UseUrls(url);
        }

        ConfigureServices(builder.Services, identity, DeviceRegistry.DefaultStoragePath, diagnostics);

        var app = builder.Build();
        ConfigureApp(app);
        return app;
    }

    public static WebApplication BuildSecureForTesting(
        string httpUrl,
        string httpsUrl,
        string storageDirectory)
    {
        var identity = HostIdentity.LoadOrCreate(storageDirectory);
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls(httpUrl, httpsUrl);
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.ConfigureHttpsDefaults(https => https.ServerCertificate = identity.Certificate);
        });
        ConfigureServices(
            builder.Services,
            identity,
            Path.Combine(storageDirectory, "devices.json"));
        var app = builder.Build();
        ConfigureApp(app);
        return app;
    }

    public static void ConfigureServices(IServiceCollection services)
    {
        ConfigureServices(services, HostIdentity.LoadOrCreate());
    }

    private static void ConfigureServices(IServiceCollection services, HostIdentity identity)
        => ConfigureServices(services, identity, DeviceRegistry.DefaultStoragePath);

    private static void ConfigureServices(
        IServiceCollection services,
        HostIdentity identity,
        string deviceRegistryPath,
        DiagnosticsLog? diagnostics = null)
    {
        services.AddSingleton(identity);
        services.AddSingleton(diagnostics ?? new DiagnosticsLog());
        services.AddSingleton(_ => new DeviceRegistry(deviceRegistryPath));
        services.AddSingleton<ClipboardHub>();
        services.AddSingleton<ClipboardRelay>();
        services.AddSingleton<ContentTransferStore>();
        services.AddHostedService<ContentTransferCleanupService>();
        services.AddSingleton<MediaCatalog>();
        services.AddSingleton<MediaContentBroker>();
        services.AddSingleton<MediaThumbnailCache>();
        services.AddSingleton<NotificationStore>();
        services.AddSingleton<SmsStore>();
        services.AddSingleton<WebSocketSessionHub>();
        services.AddHostedService<SessionStaleMonitor>();
        services.AddSingleton<MirrorSessionRegistry>();
        services.AddHostedService<MirrorLeaseMonitor>();
        services.AddSingleton<CommandResultBroker>();
        services.AddSingleton<ResponseBroker<StorageListResponsePayload>>();
        services.AddSingleton<ResponseBroker<StorageFileResponsePayload>>();
        services.AddSingleton<ResponseBroker<DiagnosticsSnapshotPayload>>();
        services.AddSingleton<ResponseBroker<AppsListResponsePayload>>();
        services.AddSingleton<ResponseBroker<AppsLaunchResultPayload>>();
        services.AddSingleton<MediaSessionStore>();
        services.AddSingleton<Networking.EndpointEnumerator>();
        services.AddHostedService(provider => provider.GetRequiredService<Networking.EndpointEnumerator>());
        services.AddSingleton<Streaming.StreamTicketStore>();
        services.AddSingleton<Streaming.FilePushService>();
        services.AddSingleton<Adb.AdbMirrorLauncher>();
        services.AddSingleton<Streaming.MirrorStreamHub>();
        services.AddSingleton<Streaming.StreamListener>();
        services.AddHostedService(provider => provider.GetRequiredService<Streaming.StreamListener>());
        services.AddSingleton<HostWebSocketHandler>();
    }

    public static void ConfigureApp(WebApplication app)
    {
        // The listener admits sockets; the hub pairs each one with its format announcement. Wiring
        // them here keeps the listener ignorant of what a mirror is.
        var streamListener = app.Services.GetRequiredService<Streaming.StreamListener>();
        var mirrorStreams = app.Services.GetRequiredService<Streaming.MirrorStreamHub>();
        var filePush = app.Services.GetRequiredService<Streaming.FilePushService>();
        streamListener.ConnectionAccepted += (connection, _) =>
        {
            if (connection.Kind == Streaming.StreamKind.File)
            {
                filePush.AttachFileStream(connection);
                return Task.CompletedTask;
            }

            if (connection.Kind == Streaming.StreamKind.Video)
            {
                mirrorStreams.AttachVideoStream(connection);
                return Task.CompletedTask;
            }

            if (connection.Kind == Streaming.StreamKind.Control)
            {
                mirrorStreams.AttachControlStream(connection);
                return Task.CompletedTask;
            }

            if (connection.Kind == Streaming.StreamKind.Audio)
            {
                mirrorStreams.AttachAudioStream(connection);
                return Task.CompletedTask;
            }

            return connection.DisposeAsync().AsTask();
        };

        app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(20),
            KeepAliveTimeout = TimeSpan.FromSeconds(60)
        });

        // Phones only need the authenticated WebSocket transport. Every
        // management, media and viewer HTTP endpoint remains local to Windows.
        app.Use(async (context, next) =>
        {
            var isLoopback = IsLoopback(context.Connection.RemoteIpAddress);
            if ((context.Request.Path.StartsWithSegments("/ws/v1/session")
                    && (context.Request.IsHttps || isLoopback))
                || isLoopback)
            {
                await next();
                return;
            }

            context.Response.StatusCode = context.Request.Path.StartsWithSegments("/ws/v1/session")
                ? StatusCodes.Status426UpgradeRequired
                : StatusCodes.Status403Forbidden;
            await context.Response.WriteAsJsonAsync(new
            {
                message = context.Response.StatusCode == StatusCodes.Status426UpgradeRequired
                    ? "LAN device sessions require pinned WSS on port 7818."
                    : "This endpoint is available only on this PC."
            });
        });

        app.MapGet("/", (HostIdentity identity) => Results.Ok(new
        {
            service = "Pairly.Host",
            protocolVersion = HostIdentity.ProtocolVersion,
            identity.HostId,
            identity.PcName,
            securePort = HostIdentity.SecureLanPort,
            identity.CertificateFingerprint,
            embedded = true,
            endpoints = new[]
            {
                "/ws/v1/session",
                "/v1/media/{deviceId}",
                "/v1/media/{deviceId}/{mediaId}/thumb",
                "/v1/media/{deviceId}/{mediaId}/content"
            }
        }));

        app.MapPost("/v1/pairing-ticket", (DeviceRegistry registry, HostIdentity identity) =>
        {
            var ticket = registry.OpenPairingTicket();
            return Results.Ok(new PairingTicketResponsePayload(
                ticket.Code,
                ticket.ExpiresAt,
                identity.HostId,
                HostIdentity.ProtocolVersion,
                HostIdentity.SecureLanPort,
                identity.CertificateFingerprint));
        });

        app.MapGet("/v1/devices", (DeviceRegistry registry) => Results.Ok(registry.GetDevices()));

        app.MapGet("/v1/diagnostics/trust-store", (DeviceRegistry registry) =>
        {
            return Results.Ok(registry.GetPersistenceStatus());
        });

        app.MapGet("/v1/diagnostics/sessions", (WebSocketSessionHub sessions) =>
        {
            return Results.Ok(sessions.GetStatuses());
        });

        app.MapGet("/v1/diagnostics/log", (int? maxEntries, string? category, DiagnosticsLog diagnostics) =>
        {
            DiagnosticsCategory? filter = null;
            if (!string.IsNullOrWhiteSpace(category))
            {
                if (!Enum.TryParse<DiagnosticsCategory>(category, ignoreCase: true, out var parsed))
                {
                    return Results.BadRequest(new { error = $"Unknown diagnostics category '{category}'." });
                }

                filter = parsed;
            }

            return Results.Ok(new
            {
                connectedForSeconds = (long?)diagnostics.ConnectedFor?.TotalSeconds,
                reconnectReasons = diagnostics.ReconnectReasons()
                    .Select(reason => new { reason = reason.Key, count = reason.Value }),
                entries = diagnostics.Snapshot(maxEntries ?? 200, filter)
            });
        });

        app.MapPost("/v1/devices/{deviceId}/diagnostics", async (
            string deviceId,
            int? maxEntries,
            WebSocketSessionHub sessions,
            ResponseBroker<DiagnosticsSnapshotPayload> snapshots,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var requestId = Guid.NewGuid().ToString("N");
            var waitForResponse = snapshots.WaitForAsync(requestId, TimeSpan.FromSeconds(10), cancellationToken);
            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.DiagnosticsRequest,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                new DiagnosticsRequestPayload(requestId, Math.Clamp(maxEntries ?? 200, 1, 500)));
            await sessions.SendAsync(deviceId, envelope, cancellationToken);

            var response = await waitForResponse;
            return response is null
                ? Results.Problem(
                    "Timed out waiting for the diagnostics snapshot from the device.",
                    statusCode: StatusCodes.Status504GatewayTimeout)
                : Results.Ok(response);
        });

        app.MapPost("/v1/diagnostics/trust-store/repair", (DeviceRegistry registry) =>
        {
            var repaired = registry.RepairPersistenceFromBackup();
            var status = registry.GetPersistenceStatus();
            return repaired
                ? Results.Ok(status)
                : Results.BadRequest(status);
        });

        app.MapDelete("/v1/devices/{deviceId}", (string deviceId, DeviceRegistry registry, WebSocketSessionHub sessions) =>
        {
            sessions.Detach(deviceId);
            return registry.Revoke(deviceId) ? Results.NoContent() : Results.NotFound();
        });

        app.MapPost("/v1/devices/{deviceId}/sync", async (
            string deviceId,
            DeviceSyncRequestPayload? payload,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var request = payload ?? new DeviceSyncRequestPayload(Array.Empty<string>());
            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.DeviceSyncRequest,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                request);
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            return Results.Accepted(value: new { deviceId, requested = request.Features });
        });

        app.MapGet("/v1/media/{deviceId}", (
            string deviceId,
            int? pageSize,
            string? cursor,
            string? albumId,
            string? search,
            MediaCatalog catalog) =>
        {
            return catalog.TryGetPage(
                deviceId,
                pageSize ?? 100,
                cursor,
                albumId,
                search,
                out var page,
                out var error)
                ? Results.Ok(page)
                : Results.BadRequest(new { message = error });
        });

        app.MapGet("/v1/clipboard", (int? count, ClipboardHub clipboard) =>
        {
            return Results.Ok(clipboard.GetRecent(count ?? 20));
        });

        app.MapGet("/v1/clipboard/deliveries", (string? itemId, ClipboardHub clipboard) =>
        {
            return Results.Ok(clipboard.GetDeliveries(itemId));
        });

        app.MapGet("/v1/clipboard/{itemId}/content", (
            string itemId,
            ContentTransferStore contentTransfers) =>
        {
            if (!contentTransfers.TryGetCompletedByItemId(itemId, out var transfer))
            {
                return Results.NotFound(new { message = "Clipboard image is unavailable or expired." });
            }

            return Results.Stream(new ChunkSequenceStream(transfer.Chunks), "image/png");
        });

        app.MapPost("/v1/clipboard/{itemId}/ack", async (
            string itemId,
            ClipboardAckPayload acknowledgement,
            ClipboardHub clipboard,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            var normalized = acknowledgement with { ItemId = itemId };
            if (!clipboard.Acknowledge("pc-host", normalized))
            {
                return Results.BadRequest(new { message = "Clipboard acknowledgement did not match a pending PC delivery." });
            }

            var source = clipboard.GetReceipt(itemId)?.SourceDeviceId;
            if (!string.IsNullOrWhiteSpace(source)
                && !string.Equals(source, "pc-host", StringComparison.Ordinal))
            {
                await sessions.SendAsync(
                    source,
                    ProtocolEnvelope.Create(
                        ProtocolMessageTypes.ClipboardAck,
                        "pc-host",
                        DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                        normalized),
                    cancellationToken);
            }

            return Results.Ok(normalized);
        });

        app.MapPost("/v1/clipboard", async (
            HostClipboardRequest request,
            ClipboardRelay clipboardRelay,
            ContentTransferStore contentTransfers,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            var contentType = request.ContentType ?? "text/plain";
            var itemId = request.ItemId ?? Guid.NewGuid().ToString("N");
            var expiresAt = request.ExpiresAt ?? DateTimeOffset.UtcNow.Add(ClipboardHub.ItemLifetime);
            if (expiresAt <= DateTimeOffset.UtcNow) return Results.BadRequest(new { message = "Clipboard copy expired." });
            string? transferId = null;
            string? contentHash = request.ContentHash?.Trim().ToLowerInvariant();
            CompletedContentTransfer? completedTransfer = null;

            if (string.Equals(contentType, "image/png", StringComparison.OrdinalIgnoreCase))
            {
                if (string.IsNullOrWhiteSpace(request.Base64))
                {
                    return Results.BadRequest(new { message = "PNG clipboard requests require Base64 bytes." });
                }

                byte[] imageBytes;
                try
                {
                    imageBytes = Convert.FromBase64String(request.Base64);
                }
                catch (FormatException)
                {
                    return Results.BadRequest(new { message = "Clipboard image contained invalid Base64 data." });
                }

                string? pngError = null;
                if (imageBytes.Length > ClipboardHub.MaxImageBytes
                    || !PngClipboardValidator.TryValidate(imageBytes, out pngError))
                {
                    return Results.BadRequest(new
                    {
                        message = pngError ?? $"Clipboard image exceeds the {ClipboardHub.MaxImageBytes} byte limit."
                    });
                }

                var actualHash = Convert.ToHexString(SHA256.HashData(imageBytes)).ToLowerInvariant();
                if (contentHash is not null
                    && !string.Equals(contentHash, actualHash, StringComparison.Ordinal))
                {
                    return Results.BadRequest(new { message = "Clipboard content hash did not match the PNG bytes." });
                }

                contentHash = actualHash;
                transferId = "pc-" + itemId;
                var chunkSize = ContentTransferStore.MaxChunkBytes;
                var chunkCount = Math.Max(1, (int)Math.Ceiling(imageBytes.Length / (double)chunkSize));
                var start = new ContentTransferStartPayload(
                    transferId,
                    itemId,
                    "pc-host",
                    "image/png",
                    request.FileName,
                    imageBytes.Length,
                    chunkSize,
                    chunkCount,
                    actualHash,
                    expiresAt);
                var begin = contentTransfers.Begin(start);
                if (!begin.Accepted)
                {
                    return Results.StatusCode(StatusCodes.Status503ServiceUnavailable);
                }

                for (var index = 0; index < chunkCount; index++)
                {
                    var offset = index * chunkSize;
                    var count = Math.Min(chunkSize, imageBytes.Length - offset);
                    var chunkBytes = imageBytes.AsSpan(offset, count).ToArray();
                    var appended = contentTransfers.Append(new ContentTransferChunkPayload(
                        transferId,
                        index,
                        Convert.ToBase64String(chunkBytes),
                        Convert.ToHexString(SHA256.HashData(chunkBytes)).ToLowerInvariant()));
                    if (!appended.Accepted)
                    {
                        contentTransfers.Remove(transferId);
                        return Results.BadRequest(new { appended.Reason });
                    }
                }

                var completed = contentTransfers.Complete(new ContentTransferCompletePayload(transferId, actualHash));
                if (!completed.Accepted || completed.Transfer is null)
                {
                    contentTransfers.Remove(transferId);
                    return Results.BadRequest(new { completed.Reason });
                }

                completedTransfer = completed.Transfer;
            }

            var item = new ClipboardItemPayload(
                itemId,
                "pc-host",
                contentType,
                request.Text,
                request.FileName,
                completedTransfer?.Start.ByteCount ?? request.ByteCount ?? request.Text?.Length ?? 0,
                Ephemeral: true,
                ContentHash: contentHash,
                ExpiresAt: expiresAt,
                TransferId: transferId,
                Base64: null,
                MimeType: contentType,
                OriginDeviceId: "pc-host");
            var result = await clipboardRelay.PublishAsync(item, completedTransfer, cancellationToken);

            if (!result.Accepted || result.Item is null)
            {
                if (transferId is not null)
                {
                    contentTransfers.Remove(transferId);
                }

                return Results.BadRequest(new { result.Reason });
            }

            return Results.Ok(new
            {
                result.Item.ItemId,
                result.Item.RelaySequence,
                result.Item.ContentHash,
                recipients = result.RecipientDeviceIds
            });
        });

        app.MapGet("/v1/notifications", (NotificationStore notifications) =>
        {
            return Results.Ok(notifications.GetActive());
        });

        app.MapGet("/v1/notifications/{deviceId}", (string deviceId, NotificationStore notifications) =>
        {
            return Results.Ok(notifications.GetActive(deviceId));
        });

        app.MapDelete("/v1/notifications/{deviceId}", (string deviceId, NotificationStore notifications) =>
        {
            return Results.Ok(new { removed = notifications.Clear(deviceId) });
        });

        app.MapPost("/v1/notifications/{deviceId}/action", async (
            string deviceId,
            NotificationActionPayload payload,
            WebSocketSessionHub sessions,
            CommandResultBroker commandResults,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var requestId = string.IsNullOrWhiteSpace(payload.RequestId)
                ? Guid.NewGuid().ToString("N")
                : payload.RequestId;
            var commandPayload = payload with { RequestId = requestId };
            var waitForResult = commandResults.WaitForAsync(requestId, TimeSpan.FromSeconds(5), cancellationToken);
            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.NotificationAction,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                commandPayload);
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            return ToCommandResult(await waitForResult);
        });

        app.MapGet("/v1/sms/threads", (SmsStore sms) =>
        {
            return Results.Ok(sms.GetThreads());
        });

        app.MapGet("/v1/sms/{deviceId}/threads", (string deviceId, SmsStore sms) =>
        {
            return Results.Ok(sms.GetThreads(deviceId));
        });

        app.MapGet("/v1/sms/threads/{threadId}/messages", (string threadId, SmsStore sms) =>
        {
            return Results.Ok(sms.GetMessages(threadId));
        });

        app.MapGet("/v1/sms/{deviceId}/threads/{threadId}/messages", (string deviceId, string threadId, SmsStore sms) =>
        {
            return Results.Ok(sms.GetMessages(deviceId, threadId));
        });

        app.MapPost("/v1/sms/{deviceId}/send", async (
            string deviceId,
            SmsSendPayload payload,
            WebSocketSessionHub sessions,
            CommandResultBroker commandResults,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var requestId = string.IsNullOrWhiteSpace(payload.RequestId)
                ? Guid.NewGuid().ToString("N")
                : payload.RequestId;
            var commandPayload = payload with { RequestId = requestId };
            var waitForResult = commandResults.WaitForAsync(requestId, TimeSpan.FromSeconds(8), cancellationToken);
            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.SmsSend,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                commandPayload);
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            return ToCommandResult(await waitForResult);
        });

        app.MapGet("/v1/devices/{deviceId}/apps", async (
            string deviceId,
            WebSocketSessionHub sessions,
            ResponseBroker<AppsListResponsePayload> appsList,
            CancellationToken cancellationToken) =>
        {
            var requestId = Guid.NewGuid().ToString("N");
            var waitForResponse = appsList.WaitForAsync(requestId, TimeSpan.FromSeconds(20), cancellationToken);
            var sent = await sessions.SendAsync(
                deviceId,
                ProtocolEnvelope.Create(
                    ProtocolMessageTypes.AppsListRequest,
                    "pc-host",
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    new AppsListRequestPayload(requestId)),
                cancellationToken);
            if (!sent)
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var response = await waitForResponse;
            return response is null
                ? Results.Problem("The phone did not send its app list in time.", statusCode: StatusCodes.Status504GatewayTimeout)
                : string.IsNullOrWhiteSpace(response.Error)
                    ? Results.Ok(response)
                    : Results.BadRequest(new { message = response.Error });
        });

        app.MapPost("/v1/devices/{deviceId}/apps/{packageName}/launch", async (
            string deviceId,
            string packageName,
            WebSocketSessionHub sessions,
            ResponseBroker<AppsLaunchResultPayload> appsLaunch,
            CancellationToken cancellationToken) =>
        {
            var requestId = Guid.NewGuid().ToString("N");
            var waitForResponse = appsLaunch.WaitForAsync(requestId, TimeSpan.FromSeconds(10), cancellationToken);
            var sent = await sessions.SendAsync(
                deviceId,
                ProtocolEnvelope.Create(
                    ProtocolMessageTypes.AppsLaunch,
                    "pc-host",
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    new AppsLaunchPayload(requestId, packageName)),
                cancellationToken);
            if (!sent)
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var response = await waitForResponse;
            return response is null
                ? Results.Problem("The phone did not confirm the launch in time.", statusCode: StatusCodes.Status504GatewayTimeout)
                : response.Status == "launched"
                    ? Results.Ok(response)
                    : Results.BadRequest(new { message = response.Error ?? "The app could not be opened." });
        });

        app.MapPost("/v1/devices/{deviceId}/storage/list", async (
            string deviceId,
            StorageListRequestPayload payload,
            WebSocketSessionHub sessions,
            ResponseBroker<StorageListResponsePayload> storageList,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var requestId = string.IsNullOrWhiteSpace(payload.RequestId)
                ? Guid.NewGuid().ToString("N")
                : payload.RequestId;
            var commandPayload = payload with { RequestId = requestId };
            var waitForResponse = storageList.WaitForAsync(requestId, TimeSpan.FromSeconds(10), cancellationToken);
            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.StorageListRequest,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                commandPayload);
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            var response = await waitForResponse;
            if (response == null)
            {
                return Results.Problem("Timed out waiting for storage list response from device.", statusCode: StatusCodes.Status504GatewayTimeout);
            }
            if (!string.IsNullOrWhiteSpace(response.Error))
            {
                return Results.BadRequest(new { message = response.Error });
            }
            return Results.Ok(response);
        });

        app.MapPost("/v1/devices/{deviceId}/storage/file", async (
            string deviceId,
            StorageFileRequestPayload payload,
            WebSocketSessionHub sessions,
            ResponseBroker<StorageFileResponsePayload> storageFile,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var requestId = string.IsNullOrWhiteSpace(payload.RequestId)
                ? Guid.NewGuid().ToString("N")
                : payload.RequestId;
            var commandPayload = payload with { RequestId = requestId };
            var waitForResponse = storageFile.WaitForAsync(requestId, TimeSpan.FromSeconds(20), cancellationToken);
            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.StorageFileRequest,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                commandPayload);
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            var response = await waitForResponse;
            if (response == null)
            {
                return Results.Problem("Timed out waiting for file response from device.", statusCode: StatusCodes.Status504GatewayTimeout);
            }
            if (!string.IsNullOrWhiteSpace(response.Error))
            {
                return Results.BadRequest(new { message = response.Error });
            }
            return Results.Ok(response);
        });

        app.MapPost("/v1/devices/{deviceId}/storage/delete", async (
            string deviceId,
            StorageDeleteRequestPayload payload,
            WebSocketSessionHub sessions,
            CommandResultBroker commandResults,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var requestId = string.IsNullOrWhiteSpace(payload.RequestId)
                ? Guid.NewGuid().ToString("N")
                : payload.RequestId;
            var commandPayload = payload with { RequestId = requestId };
            var waitForResponse = commandResults.WaitForAsync(requestId, TimeSpan.FromSeconds(10), cancellationToken);
            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.StorageDeleteRequest,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                commandPayload);
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            return ToCommandResult(await waitForResponse);
        });

        app.MapGet("/v1/devices/{deviceId}/media-sessions", (string deviceId, MediaSessionStore mediaSessions) =>
            Results.Ok(mediaSessions.Get(deviceId)));

        app.MapPost("/v1/devices/{deviceId}/media-sessions/command", async (
            string deviceId,
            MediaSessionCommandPayload payload,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            if (string.IsNullOrWhiteSpace(payload.PackageName) || !MediaSessionStore.IsKnownAction(payload.Action))
            {
                return Results.BadRequest(new { message = "Unknown media session or action." });
            }

            var sent = await sessions.SendAsync(
                deviceId,
                ProtocolEnvelope.Create(
                    ProtocolMessageTypes.MediaSessionCommand,
                    "pc-host",
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    payload),
                cancellationToken);
            return sent ? Results.Accepted() : Results.NotFound(new { message = "Target device is not connected." });
        });

        app.MapPost("/v1/devices/{deviceId}/call/action", async (
            string deviceId,
            CallActionPayload payload,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.CallAction,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                payload);
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            return Results.Accepted();
        });

        // Navigation keeps riding the control channel rather than the input stream socket: it is
        // three buttons at human speed, and it must keep working while that socket reconnects.
        app.MapPost("/v1/mirror/{deviceId}/nav", async (
            string deviceId,
            string key,
            MirrorSessionRegistry mirrors,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            if (!sessions.HasSession(deviceId))
            {
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            if (key is not ("back" or "home" or "recents" or "notifications" or "power"))
            {
                return Results.BadRequest(new { message = $"Unsupported navigation target '{key}'." });
            }

            var session = mirrors.GetActiveForDevice(deviceId);
            if (session is null)
            {
                return Results.BadRequest(new { message = "No mirror session is active for this device." });
            }

            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.MirrorInput,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                new MirrorInputPayload(session.SessionId, deviceId, "nav", Key: key));
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            return Results.Accepted();
        });

        app.MapPost("/v1/mirror/{deviceId}/prepare", (string deviceId, MirrorSessionRegistry mirrors) =>
        {
            return Results.Ok(mirrors.Prepare(deviceId));
        });

        app.MapPost("/v1/mirror/{deviceId}/start", async (
            string deviceId,
            string? sessionId,
            int? maxWidth,
            int? maxHeight,
            int? maxFps,
            string? profile,
            string? preferredCodec,
            int? leaseSeconds,
            MirrorSessionRegistry mirrors,
            WebSocketSessionHub sessions,
            Streaming.StreamTicketStore streamTickets,
            Streaming.MirrorStreamHub streams,
            CancellationToken cancellationToken) =>
        {
            var normalizedLeaseSeconds = Math.Clamp(leaseSeconds ?? 90, 30, 300);
            var startResult = mirrors.StartExclusive(deviceId, sessionId, TimeSpan.FromSeconds(normalizedLeaseSeconds));
            var session = startResult.Session;
            if (session is null)
            {
                return Results.BadRequest(new { message = "Mirror session did not match the target device." });
            }

            if (!sessions.HasSession(deviceId))
            {
                mirrors.StopSession(session.SessionId, "device_offline");
                return Results.NotFound(new { message = "Target device is not connected." });
            }

            foreach (var superseded in startResult.SupersededSessions)
            {
                await sessions.SendAsync(
                    superseded.DeviceId,
                    ProtocolEnvelope.Create(
                        ProtocolMessageTypes.MirrorStop,
                        "pc-host",
                        DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                        new MirrorSignalPayload(
                            superseded.SessionId,
                            superseded.DeviceId,
                            "stop",
                            "Another phone screen was selected.")),
                    cancellationToken);
            }

            streams.Reset(deviceId);

            // The stream id ties the ticket, the socket and the format announcement together, so a
            // late socket from a previous session cannot be mistaken for this one.
            var streamId = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var ticket = streamTickets.Issue(deviceId, session.SessionId, streamId, Streaming.StreamKind.Video);
            var controlStreamId = streamId + 1;
            var controlTicket = streamTickets.Issue(deviceId, session.SessionId, controlStreamId, Streaming.StreamKind.Control);
            var audioStreamId = streamId + 2;
            var audioTicket = streamTickets.Issue(deviceId, session.SessionId, audioStreamId, Streaming.StreamKind.Audio);

            var codecs = string.Equals(preferredCodec, "h265", StringComparison.OrdinalIgnoreCase)
                ? new[] { "h265", "h264" }
                : new[] { "h264" };

            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.StreamOpen,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                new StreamOpenPayload(
                    session.SessionId,
                    streamId,
                    "video",
                    // Empty means "the address you already reached me on". The phone knows which
                    // endpoint it is connected through - LAN or Tailscale - and the host does not.
                    string.Empty,
                    Streaming.StreamListener.Port,
                    Convert.ToBase64String(ticket.Value),
                    codecs,
                    true,
                    Convert.ToBase64String(controlTicket.Value),
                    controlStreamId,
                    Convert.ToBase64String(audioTicket.Value),
                    audioStreamId));

            await sessions.SendAsync(deviceId, envelope, cancellationToken);

            // Quality travels separately so the same message can retune a running mirror.
            await sessions.SendAsync(
                deviceId,
                ProtocolEnvelope.Create(
                    ProtocolMessageTypes.MirrorQuality,
                    "pc-host",
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    new MirrorQualityPayload(
                        session.SessionId,
                        maxWidth ?? 0,
                        maxHeight ?? 0,
                        maxFps ?? 60,
                        0,
                        profile ?? "auto")),
                cancellationToken);

            return Results.Ok(session);
        });

        app.MapPost("/v1/mirror/{deviceId}/stop", async (
            string deviceId,
            MirrorSessionRegistry mirrors,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            var session = mirrors.Stop(deviceId);
            if (session is null)
            {
                return Results.NotFound();
            }

            var envelope = ProtocolEnvelope.Create(
                ProtocolMessageTypes.MirrorStop,
                "pc-host",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                new MirrorSignalPayload(session.SessionId, deviceId, "stop", string.Empty));
            await sessions.SendAsync(deviceId, envelope, cancellationToken);
            return Results.Ok(session);
        });

        app.MapPost("/v1/mirror/{deviceId}/lease", (
            string deviceId,
            string sessionId,
            int? leaseSeconds,
            MirrorSessionRegistry mirrors) =>
        {
            var session = mirrors.Get(sessionId);
            if (session is null || !string.Equals(session.DeviceId, deviceId, StringComparison.Ordinal))
            {
                return Results.NotFound(new { message = "Mirror lease was not found." });
            }

            var renewed = mirrors.Touch(
                sessionId,
                session.State,
                TimeSpan.FromSeconds(Math.Clamp(leaseSeconds ?? 90, 30, 300)));
            return Results.Ok(renewed);
        });

        app.MapGet("/v1/media/{deviceId}/{mediaId}/thumb", async (
            string deviceId,
            string mediaId,
            MediaCatalog catalog,
            MediaContentBroker mediaContent,
            MediaThumbnailCache thumbnailCache,
            ContentTransferStore contentTransfers,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            return await RequestMediaContentAsync(
                deviceId,
                mediaId,
                "thumb",
                catalog,
                mediaContent,
                thumbnailCache,
                contentTransfers,
                sessions,
                cancellationToken);
        });

        app.MapGet("/v1/media/{deviceId}/{mediaId}/content", async (
            string deviceId,
            string mediaId,
            MediaCatalog catalog,
            MediaContentBroker mediaContent,
            MediaThumbnailCache thumbnailCache,
            ContentTransferStore contentTransfers,
            WebSocketSessionHub sessions,
            CancellationToken cancellationToken) =>
        {
            return await RequestMediaContentAsync(
                deviceId,
                mediaId,
                "content",
                catalog,
                mediaContent,
                thumbnailCache,
                contentTransfers,
                sessions,
                cancellationToken);
        });

        app.Map("/ws/v1/session", async (HttpContext context, HostWebSocketHandler handler) =>
        {
            if (!context.WebSockets.IsWebSocketRequest)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                await context.Response.WriteAsync("Expected a WebSocket request.");
                return;
            }

            await handler.HandleAsync(context);
        });
    }

    private static async Task<IResult> RequestMediaContentAsync(
        string deviceId,
        string mediaId,
        string kind,
        MediaCatalog catalog,
        MediaContentBroker mediaContent,
        MediaThumbnailCache thumbnailCache,
        ContentTransferStore contentTransfers,
        WebSocketSessionHub sessions,
        CancellationToken cancellationToken)
    {
        var item = catalog.GetItem(deviceId, mediaId);
        if (item is null)
        {
            return Results.NotFound(new { message = "Media item is not indexed on this PC yet." });
        }

        if (!sessions.HasSession(deviceId))
        {
            return Results.NotFound(new { message = "Target device is not connected." });
        }

        if (kind.Equals("thumb", StringComparison.Ordinal) && thumbnailCache.TryGet(deviceId, item, out var cachedBytes))
        {
            return Results.File(cachedBytes, "image/jpeg");
        }

        var requestId = Guid.NewGuid().ToString("N");
        var waitForResponse = mediaContent.WaitForAsync(
            requestId,
            deviceId,
            mediaId,
            kind,
            TimeSpan.FromSeconds(30),
            cancellationToken);
        var request = ProtocolEnvelope.Create(
            ProtocolMessageTypes.MediaContentRequest,
            "pc-host",
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            new MediaContentRequestPayload(
                requestId,
                mediaId,
                kind,
                TransferMode: "chunks",
                MaxChunkSize: ContentTransferStore.MaxChunkBytes,
                ProtocolVersion: HostIdentity.ProtocolVersion));

        await sessions.SendAsync(deviceId, request, cancellationToken);
        var delivery = await waitForResponse;
        if (delivery is null)
        {
            return Results.Problem("Timed out waiting for the Android device to send media bytes.", statusCode: StatusCodes.Status504GatewayTimeout);
        }

        var response = delivery.Response;

        if (!string.IsNullOrWhiteSpace(response.Error))
        {
            return Results.NotFound(new { message = response.Error });
        }

        var contentType = string.IsNullOrWhiteSpace(response.ContentType)
            ? item.MimeType
            : response.ContentType;
        var fileName = kind.Equals("content", StringComparison.Ordinal)
            ? response.FileName ?? item.FileName
            : null;

        if (delivery.Transfer is not null)
        {
            var transfer = delivery.Transfer;
            if (kind.Equals("thumb", StringComparison.Ordinal))
            {
                var thumbnailBytes = CopyChunks(transfer);
                contentTransfers.Remove(transfer.Start.TransferId);
                thumbnailCache.Store(deviceId, item, thumbnailBytes);
                return Results.File(thumbnailBytes, contentType);
            }

            return Results.Stream(
                new ChunkSequenceStream(
                    transfer.Chunks,
                    () => contentTransfers.Remove(transfer.Start.TransferId)),
                contentType,
                fileName,
                enableRangeProcessing: false);
        }

        if (string.IsNullOrWhiteSpace(response.Base64))
        {
            return Results.Problem("Android returned an empty media payload.", statusCode: StatusCodes.Status502BadGateway);
        }

        byte[] bytes;
        try
        {
            bytes = Convert.FromBase64String(response.Base64);
        }
        catch (FormatException)
        {
            return Results.Problem("Android returned invalid media bytes.", statusCode: StatusCodes.Status502BadGateway);
        }

        if (kind.Equals("thumb", StringComparison.Ordinal))
        {
            thumbnailCache.Store(deviceId, item, bytes);
        }

        return Results.File(bytes, contentType, fileName);
    }

    private static byte[] CopyChunks(CompletedContentTransfer transfer)
    {
        if (transfer.Start.ByteCount > int.MaxValue)
        {
            throw new InvalidOperationException("Media transfer is too large to materialize.");
        }

        var bytes = new byte[(int)transfer.Start.ByteCount];
        var offset = 0;
        foreach (var chunk in transfer.Chunks)
        {
            chunk.CopyTo(bytes, offset);
            offset += chunk.Length;
        }

        return bytes;
    }

    private static IResult ToCommandResult(CommandResultPayload? result)
    {
        if (result is null)
        {
            return Results.Problem("Timed out waiting for the Android device to finish the command.", statusCode: StatusCodes.Status504GatewayTimeout);
        }

        var response = new
        {
            result.RequestId,
            result.Feature,
            result.Success,
            result.Message
        };

        return result.Success
            ? Results.Ok(response)
            : Results.BadRequest(response);
    }

    private static bool IsLoopback(IPAddress? address)
    {
        if (address is null)
        {
            return false;
        }

        return IPAddress.IsLoopback(address)
            || (address.IsIPv4MappedToIPv6 && IPAddress.IsLoopback(address.MapToIPv4()));
    }
}
