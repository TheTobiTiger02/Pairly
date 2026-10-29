using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Pairly.Core;
using Pairly.Protocol;

namespace Pairly.Server;

public sealed class HostWebSocketHandler(
    DeviceRegistry devices,
    ClipboardHub clipboard,
    ClipboardRelay clipboardRelay,
    ContentTransferStore contentTransfers,
    MediaCatalog media,
    MediaContentBroker mediaContent,
    NotificationStore notifications,
    SmsStore sms,
    WebSocketSessionHub sessions,
    MirrorSessionRegistry mirrors,
    CommandResultBroker commandResults,
    ResponseBroker<StorageListResponsePayload> storageList,
    ResponseBroker<StorageFileResponsePayload> storageFile,
    ResponseBroker<DiagnosticsSnapshotPayload> diagnosticsSnapshots,
    Streaming.MirrorStreamHub mirrorStreams,
    HostIdentity hostIdentity,
    Networking.EndpointEnumerator endpoints,
    MediaSessionStore mediaSessions,
    Streaming.FilePushService filePush,
    ResponseBroker<AppsListResponsePayload> appsList,
    ResponseBroker<AppsLaunchResultPayload> appsLaunch,
    ILogger<HostWebSocketHandler> logger)
{
    private static readonly TimeSpan ChallengeLifetime = TimeSpan.FromMinutes(2);
    private const int MaxInboundMessageBytes = 12 * 1024 * 1024;

    private readonly ConcurrentDictionary<string, PendingClipboardTransfer> _pendingClipboardTransfers = new(StringComparer.Ordinal);
    private long _hostSequence;

    public async Task HandleAsync(HttpContext context)
    {
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        var connection = new ConnectionSession(IsLoopback(context.Connection.RemoteIpAddress));

        if (connection.IsLoopback
            && string.Equals(context.Request.Query["role"], "pc-app", StringComparison.OrdinalIgnoreCase))
        {
            ClaimSession(connection, $"pc-app-{Guid.NewGuid():N}", socket, isPhysicalDevice: false);
            await SendSessionReadyAsync(socket, connection, context.RequestAborted);
        }

        connection.SetChallenge(new SessionChallengePayload(
            Guid.NewGuid().ToString("N"),
            Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant(),
            hostIdentity.HostId,
            DateTimeOffset.UtcNow.Add(ChallengeLifetime),
            HostIdentity.ProtocolVersion));
        await WebSocketSessionHub.SendAsync(
            socket,
            ProtocolEnvelope.Create(
                ProtocolMessageTypes.SessionChallenge,
                "pc-host",
                NextSequence(),
                connection.Challenge),
            context.RequestAborted);

        try
        {
            while (socket.State == WebSocketState.Open && !context.RequestAborted.IsCancellationRequested)
            {
                var json = await ReceiveTextAsync(socket, context.RequestAborted);
                if (json is null)
                {
                    break;
                }

                if (connection.Authenticated)
                {
                    sessions.MarkActivity(
                        connection.SessionId!,
                        socket,
                        connection.Generation);
                }

                await RouteAsync(socket, json, connection, context.RequestAborted);
            }
        }
        catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
        {
        }
        catch (WebSocketException ex)
        {
            logger.LogInformation(ex, "WebSocket session ended after a network change.");
        }
        finally
        {
            foreach (var entry in _pendingClipboardTransfers.Where(entry => entry.Value.SourceDeviceId == connection.SessionId && entry.Value.Generation == connection.Generation))
                _pendingClipboardTransfers.TryRemove(entry);
            if (connection.SessionId is not null
                && sessions.Detach(connection.SessionId, socket))
            {
                if (connection.IsPhysicalDevice)
                {
                    devices.MarkConnected(connection.SessionId, connected: false);
                }
            }
        }
    }

    private async Task RouteAsync(
        WebSocket socket,
        string json,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        ProtocolEnvelope envelope;
        try
        {
            envelope = JsonProtocolSerializer.Deserialize(json);
        }
        catch (JsonException ex)
        {
            await SendErrorAsync(socket, "bad_json", ex.Message, null, cancellationToken);
            return;
        }

        var validation = ProtocolValidator.Validate(envelope);
        if (!validation.IsValid)
        {
            await SendErrorAsync(socket, "invalid_envelope", validation.Error ?? "Envelope was invalid.", envelope.Id, cancellationToken);
            return;
        }

        if (envelope.Type == ProtocolMessageTypes.PairHello)
        {
            await HandlePairHelloAsync(socket, envelope, connection, cancellationToken);
            return;
        }

        if (envelope.Type == ProtocolMessageTypes.SessionAuthenticate)
        {
            await HandleSessionAuthenticateAsync(socket, envelope, connection, cancellationToken);
            return;
        }

        if (!await EnsureAuthorizedAsync(socket, envelope, connection, cancellationToken))
        {
            return;
        }

        try
        {
            switch (envelope.Type)
            {
                case ProtocolMessageTypes.DeviceState:
                    HandleDeviceState(envelope, connection);
                    break;
                case ProtocolMessageTypes.DeviceForget:
                    await HandleDeviceForgetAsync(socket, envelope, connection, cancellationToken);
                    break;
                case ProtocolMessageTypes.ClipboardItem:
                    await HandleClipboardItemAsync(envelope, connection, cancellationToken);
                    break;
                case ProtocolMessageTypes.ClipboardAck:
                    await HandleClipboardAckAsync(envelope, connection, cancellationToken);
                    break;
                case ProtocolMessageTypes.ContentTransferStart:
                    HandleContentTransferStart(envelope, connection);
                    break;
                case ProtocolMessageTypes.ContentTransferChunk:
                    HandleContentTransferChunk(envelope, connection);
                    break;
                case ProtocolMessageTypes.ContentTransferComplete:
                    await HandleContentTransferCompleteAsync(envelope, connection, cancellationToken);
                    break;
                case ProtocolMessageTypes.MediaIndexPage:
                    media.UpsertPage(envelope.ReadPayload<MediaIndexPagePayload>()
                        ?? throw new InvalidOperationException("Missing media page payload."));
                    break;
                case ProtocolMessageTypes.MediaContentResponse:
                    if (!connection.IsPhysicalDevice)
                    {
                        throw new InvalidOperationException("Only an authenticated phone may return media bytes.");
                    }

                    var mediaResponse = envelope.ReadPayload<MediaContentResponsePayload>()
                        ?? throw new InvalidOperationException("Missing media content response payload.");
                    var acceptedMediaResponse = mediaContent.AcceptResponse(connection.SessionId!, mediaResponse);
                    if (!acceptedMediaResponse.Accepted)
                    {
                        throw new InvalidOperationException(acceptedMediaResponse.Reason);
                    }
                    break;
                case ProtocolMessageTypes.NotificationSnapshotBegin:
                    notifications.BeginSnapshot(
                        envelope.DeviceId,
                        envelope.ReadPayload<NotificationSnapshotPayload>()
                            ?? throw new InvalidOperationException("Missing notification snapshot payload."));
                    break;
                case ProtocolMessageTypes.NotificationUpsert:
                    notifications.Upsert(
                        envelope.DeviceId,
                        envelope.ReadPayload<NotificationPayload>()
                            ?? throw new InvalidOperationException("Missing notification payload."));
                    break;
                case ProtocolMessageTypes.NotificationRemove:
                    var removed = envelope.ReadPayload<NotificationRemovePayload>()
                        ?? throw new InvalidOperationException("Missing notification removal payload.");
                    notifications.Remove(envelope.DeviceId, removed.NotificationId);
                    break;
                case ProtocolMessageTypes.NotificationSnapshotEnd:
                    notifications.EndSnapshot(
                        envelope.DeviceId,
                        envelope.ReadPayload<NotificationSnapshotPayload>()
                            ?? throw new InvalidOperationException("Missing notification snapshot payload."));
                    break;
                case ProtocolMessageTypes.SmsThreadPage:
                    sms.UpsertThreads(
                        envelope.DeviceId,
                        envelope.ReadPayload<SmsThreadPagePayload>()
                            ?? throw new InvalidOperationException("Missing SMS thread page payload."));
                    break;
                case ProtocolMessageTypes.SmsMessagePage:
                    sms.UpsertMessages(
                        envelope.DeviceId,
                        envelope.ReadPayload<SmsMessagePagePayload>()
                            ?? throw new InvalidOperationException("Missing SMS message page payload."));
                    break;
                case ProtocolMessageTypes.CommandResult:
                    commandResults.Complete(envelope.ReadPayload<CommandResultPayload>()
                        ?? throw new InvalidOperationException("Missing command result payload."));
                    break;
                case ProtocolMessageTypes.StorageListResponse:
                    {
                        var payload = envelope.ReadPayload<StorageListResponsePayload>()
                            ?? throw new InvalidOperationException("Missing storage list response payload.");
                        storageList.Complete(payload.RequestId, payload);
                    }
                    break;
                case ProtocolMessageTypes.StorageFileResponse:
                    {
                        var payload = envelope.ReadPayload<StorageFileResponsePayload>()
                            ?? throw new InvalidOperationException("Missing storage file response payload.");
                        storageFile.Complete(payload.RequestId, payload);
                    }
                    break;
                case ProtocolMessageTypes.MirrorVideoFormat:
                    {
                        var payload = envelope.ReadPayload<MirrorVideoFormatPayload>()
                            ?? throw new InvalidOperationException("Missing mirror video format payload.");
                        mirrorStreams.AnnounceFormat(envelope.DeviceId, payload);
                        mirrors.Touch(payload.SessionId, "streaming");
                    }
                    break;
                case ProtocolMessageTypes.StreamReady:
                    {
                        var payload = envelope.ReadPayload<StreamReadyPayload>()
                            ?? throw new InvalidOperationException("Missing stream ready payload.");
                        mirrors.Touch(payload.SessionId, "streaming");
                    }
                    break;
                case ProtocolMessageTypes.StreamClose:
                    {
                        var payload = envelope.ReadPayload<StreamClosePayload>()
                            ?? throw new InvalidOperationException("Missing stream close payload.");
                        mirrors.Touch(payload.SessionId, "stopped");
                    }
                    break;
                case ProtocolMessageTypes.DiagnosticsSnapshot:
                    {
                        var payload = envelope.ReadPayload<DiagnosticsSnapshotPayload>()
                            ?? throw new InvalidOperationException("Missing diagnostics snapshot payload.");
                        diagnosticsSnapshots.Complete(payload.RequestId, payload);
                    }
                    break;
                case ProtocolMessageTypes.AppsListResponse:
                    {
                        var payload = envelope.ReadPayload<AppsListResponsePayload>()
                            ?? throw new InvalidOperationException("Missing app list payload.");
                        appsList.Complete(payload.RequestId, payload);
                    }

                    break;
                case ProtocolMessageTypes.AppsLaunchResult:
                    {
                        var payload = envelope.ReadPayload<AppsLaunchResultPayload>()
                            ?? throw new InvalidOperationException("Missing app launch result payload.");
                        appsLaunch.Complete(payload.RequestId, payload);
                    }

                    break;
                case ProtocolMessageTypes.FilePushResult:
                    if (connection.IsPhysicalDevice)
                    {
                        filePush.CompleteResult(
                            envelope.DeviceId,
                            envelope.ReadPayload<FilePushResultPayload>()
                                ?? throw new InvalidOperationException("Missing file push result payload."));
                    }

                    break;
                case ProtocolMessageTypes.MediaSessionState:
                    if (connection.IsPhysicalDevice)
                    {
                        mediaSessions.Update(
                            envelope.DeviceId,
                            envelope.ReadPayload<MediaSessionStatePayload>()
                                ?? throw new InvalidOperationException("Missing media session payload."));
                        await RelayToPcAppsAsync(envelope, cancellationToken);
                    }

                    break;
                case ProtocolMessageTypes.CallRinging:
                case ProtocolMessageTypes.CallIdle:
                case ProtocolMessageTypes.CallActive:
                    await RelayToPcAppsAsync(envelope, cancellationToken);
                    break;
                case ProtocolMessageTypes.MirrorInput:
                    await HandleMirrorInputRelayAsync(envelope, cancellationToken);
                    break;
                case ProtocolMessageTypes.MirrorSignal:
                case ProtocolMessageTypes.MirrorStop:
                    await HandleLegacyMirrorRelayAsync(envelope, cancellationToken);
                    break;
                case ProtocolMessageTypes.MirrorConfigure:
                    await HandleMirrorConfigureRelayAsync(envelope, cancellationToken);
                    break;
                case ProtocolMessageTypes.MirrorTelemetry:
                    await HandleMirrorTelemetryRelayAsync(envelope, cancellationToken);
                    break;
                case ProtocolMessageTypes.MirrorState:
                    await HandleMirrorStateRelayAsync(envelope, cancellationToken);
                    break;
                case ProtocolMessageTypes.HeartbeatPing:
                    await HandleHeartbeatPingAsync(socket, envelope, cancellationToken);
                    break;
                case ProtocolMessageTypes.HeartbeatPong:
                case ProtocolMessageTypes.SessionReady:
                    break;
            }
        }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or FormatException)
        {
            logger.LogWarning(ex, "Failed to process envelope {EnvelopeId}", envelope.Id);
            await SendErrorAsync(socket, "payload_error", ex.Message, envelope.Id, cancellationToken);
        }
    }

    private async Task<bool> EnsureAuthorizedAsync(
        WebSocket socket,
        ProtocolEnvelope envelope,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        if (!connection.Authenticated)
        {
            if (connection.IsLoopback
                && WebSocketSessionHub.IsTransientSessionId(envelope.DeviceId)
                && IsAllowedLocalMessage(envelope.Type))
            {
                ClaimSession(connection, envelope.DeviceId, socket, isPhysicalDevice: false);
            }
            else
            {
                await SendErrorAsync(
                    socket,
                    "authentication_required",
                    "Pairing or session authentication must succeed before feature messages are accepted.",
                    envelope.Id,
                    cancellationToken);
                return false;
            }
        }

        if (connection.SessionId is null
            || !string.Equals(connection.SessionId, envelope.DeviceId, StringComparison.Ordinal))
        {
            await SendErrorAsync(
                socket,
                "device_id_mismatch",
                "An authenticated socket cannot claim a different device id.",
                envelope.Id,
                cancellationToken);
            return false;
        }

        if (!sessions.IsCurrent(connection.SessionId, socket, connection.Generation))
        {
            await SendErrorAsync(socket, "session_replaced", "A newer connection owns this device session.", envelope.Id, cancellationToken);
            return false;
        }
        return true;
    }

    private async Task HandlePairHelloAsync(
        WebSocket socket,
        ProtocolEnvelope envelope,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        if (connection.Authenticated && connection.IsPhysicalDevice)
        {
            await SendErrorAsync(socket, "already_authenticated", "This socket already owns a device session.", envelope.Id, cancellationToken);
            return;
        }

        var hello = envelope.ReadPayload<PairHelloPayload>()
            ?? throw new InvalidOperationException("Missing pair hello payload.");
        PairingResult result;
        if (!string.IsNullOrWhiteSpace(hello.HostId)
            && !string.Equals(hello.HostId, hostIdentity.HostId, StringComparison.Ordinal))
        {
            result = new PairingResult(
                false,
                hello.DeviceId,
                string.Empty,
                "The selected PC identity changed. Search again and select the verified PC.",
                "host_identity_mismatch");
        }
        else
        {
            result = devices.ConfirmPairing(hello);
        }

        if (result.Accepted)
        {
            ClaimSession(connection, result.DeviceId, socket, isPhysicalDevice: true);
            devices.MarkConnected(result.DeviceId, connected: true);
        }
        else if (!string.IsNullOrWhiteSpace(hello.TrustKey))
        {
            logger.LogWarning(
                "Trusted reconnect rejected for device {DeviceId}: {Reason}",
                hello.DeviceId,
                result.Reason);
        }

        var response = ProtocolEnvelope.Create(
            ProtocolMessageTypes.PairConfirm,
            "pc-host",
            NextSequence(),
            result.ToPayload(
                hostIdentity.HostId,
                hostIdentity.PcName,
                HostIdentity.ProtocolVersion,
                HostIdentity.SecureLanPort,
                hostIdentity.CertificateFingerprint));
        await WebSocketSessionHub.SendAsync(socket, response, cancellationToken);

        if (result.Accepted)
        {
            await SendSessionReadyAsync(socket, connection, cancellationToken);
        }
    }

    private async Task HandleSessionAuthenticateAsync(
        WebSocket socket,
        ProtocolEnvelope envelope,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        var payload = envelope.ReadPayload<SessionAuthenticatePayload>()
            ?? throw new InvalidOperationException("Missing session authentication payload.");
        var challenge = connection.Challenge;
        if (connection.Authenticated
            || challenge is null
            || challenge.ExpiresAt <= DateTimeOffset.UtcNow
            || !string.Equals(payload.ChallengeId, challenge.ChallengeId, StringComparison.Ordinal)
            || !devices.AuthenticateProof(
                payload.DeviceId,
                challenge.ChallengeId,
                challenge.Nonce,
                hostIdentity.HostId,
                payload.Proof))
        {
            await SendErrorAsync(socket, "authentication_rejected", "The session proof was rejected.", envelope.Id, cancellationToken);
            return;
        }

        ClaimSession(connection, payload.DeviceId, socket, isPhysicalDevice: true);
        devices.MarkConnected(payload.DeviceId, connected: true);
        await SendSessionReadyAsync(socket, connection, cancellationToken);
    }

    private void ClaimSession(
        ConnectionSession connection,
        string sessionId,
        WebSocket socket,
        bool isPhysicalDevice)
    {
        connection.Claim(
            sessionId,
            sessions.Attach(sessionId, socket),
            isPhysicalDevice);
        foreach (var entry in _pendingClipboardTransfers.Where(entry => entry.Value.SourceDeviceId == sessionId && entry.Value.Generation != connection.Generation))
            _pendingClipboardTransfers.TryRemove(entry);
    }

    private async Task SendSessionReadyAsync(
        WebSocket socket,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        var ready = ProtocolEnvelope.Create(
            ProtocolMessageTypes.SessionReady,
            "pc-host",
            NextSequence(),
            new SessionReadyPayload(
                connection.SessionId!,
                $"{connection.SessionId}:{connection.Generation}",
                DateTimeOffset.UtcNow));
        await WebSocketSessionHub.SendAsync(socket, ready, cancellationToken);

        // Every address this PC answers on, so the phone can fall back to Tailscale off the LAN.
        await WebSocketSessionHub.SendAsync(socket, endpoints.CreateEnvelope(), cancellationToken);
    }

    private void HandleDeviceState(ProtocolEnvelope envelope, ConnectionSession connection)
    {
        if (!connection.IsPhysicalDevice)
        {
            return;
        }

        var state = envelope.ReadPayload<DeviceStatePayload>()
            ?? throw new InvalidOperationException("Missing device state payload.");
        devices.UpdateDeviceState(envelope.DeviceId, state);
    }

    private async Task HandleDeviceForgetAsync(
        WebSocket socket,
        ProtocolEnvelope envelope,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        if (!connection.IsPhysicalDevice || string.IsNullOrWhiteSpace(connection.SessionId))
        {
            throw new InvalidOperationException("Only an authenticated phone may forget its own pairing.");
        }

        var request = envelope.ReadPayload<DeviceForgetPayload>()
            ?? throw new InvalidOperationException("Missing device forget payload.");
        var revoked = devices.Revoke(connection.SessionId);
        await WebSocketSessionHub.SendAsync(
            socket,
            ProtocolEnvelope.Create(
                ProtocolMessageTypes.DeviceForgetConfirm,
                "pc-host",
                NextSequence(),
                new DeviceForgetConfirmPayload(
                    request.RequestId,
                    connection.SessionId,
                    revoked,
                    DateTimeOffset.UtcNow,
                    revoked ? null : "The paired device was already absent.")),
            cancellationToken);

        if (socket.State == WebSocketState.Open)
        {
            await socket.CloseOutputAsync(
                WebSocketCloseStatus.NormalClosure,
                "Device pairing forgotten.",
                cancellationToken);
        }
    }

    private static async Task HandleHeartbeatPingAsync(
        WebSocket socket,
        ProtocolEnvelope envelope,
        CancellationToken cancellationToken)
    {
        var payload = envelope.ReadPayload<HeartbeatPayload>()
            ?? new HeartbeatPayload(envelope.DeviceId, DateTimeOffset.UtcNow);
        var response = ProtocolEnvelope.Create(
            ProtocolMessageTypes.HeartbeatPong,
            "pc-host",
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            payload);
        await WebSocketSessionHub.SendAsync(socket, response, cancellationToken);
    }

    private async Task HandleClipboardItemAsync(
        ProtocolEnvelope envelope,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        if (!connection.IsPhysicalDevice)
        {
            throw new InvalidOperationException("Only an authenticated phone may publish clipboard items on WebSocket.");
        }

        var item = envelope.ReadPayload<ClipboardItemPayload>()
            ?? throw new InvalidOperationException("Missing clipboard payload.");
        item = item with
        {
            SourceDeviceId = connection.SessionId!,
            OriginDeviceId = string.IsNullOrWhiteSpace(item.OriginDeviceId)
                ? connection.SessionId
                : item.OriginDeviceId,
            ExpiresAt = item.ExpiresAt ?? DateTimeOffset.UtcNow.Add(ClipboardHub.ItemLifetime)
        };

        CompletedContentTransfer? completed = null;
        if (!string.IsNullOrWhiteSpace(item.TransferId)
            && !contentTransfers.TryGetCompleted(item.TransferId, out completed))
        {
            foreach (var entry in _pendingClipboardTransfers.Where(entry => entry.Value.SourceDeviceId == connection.SessionId || entry.Value.Item.ExpiresAt <= DateTimeOffset.UtcNow))
                _pendingClipboardTransfers.TryRemove(entry.Key, out _);
            _pendingClipboardTransfers[item.TransferId] = new PendingClipboardTransfer(item with { Text = null, Base64 = null }, connection.SessionId!, connection.Generation);
            return;
        }
        if (completed is not null && (completed.Start.SourceDeviceId != connection.SessionId ||
            completed.Start.ItemId != item.ItemId || completed.Start.ByteCount != item.ByteCount ||
            !string.Equals(completed.Start.ContentHash, item.ContentHash, StringComparison.OrdinalIgnoreCase)))
            throw new InvalidOperationException("Clipboard item did not match its authenticated content transfer.");
        if (item.ContentType == "text/plain")
        {
            foreach (var entry in _pendingClipboardTransfers.Where(entry => entry.Value.SourceDeviceId == connection.SessionId))
                _pendingClipboardTransfers.TryRemove(entry.Key, out _);
        }

        await RelayClipboardAsync(
            item,
            completed,
            cancellationToken);
    }

    private void HandleContentTransferStart(ProtocolEnvelope envelope, ConnectionSession connection)
    {
        if (!connection.IsPhysicalDevice)
        {
            throw new InvalidOperationException("Only an authenticated phone may start content transfers.");
        }

        var start = envelope.ReadPayload<ContentTransferStartPayload>()
            ?? throw new InvalidOperationException("Missing content transfer start payload.");
        start = start with { SourceDeviceId = connection.SessionId! };
        if (string.Equals(start.Purpose, "media", StringComparison.OrdinalIgnoreCase))
        {
            var mediaValidation = mediaContent.CanAcceptTransfer(connection.SessionId!, start);
            if (!mediaValidation.Accepted)
            {
                throw new InvalidOperationException(mediaValidation.Reason);
            }
        }

        var result = contentTransfers.Begin(start);
        if (!result.Accepted)
        {
            throw new InvalidOperationException(result.Reason);
        }
    }

    private void HandleContentTransferChunk(ProtocolEnvelope envelope, ConnectionSession connection)
    {
        var chunk = envelope.ReadPayload<ContentTransferChunkPayload>()
            ?? throw new InvalidOperationException("Missing content transfer chunk payload.");
        if (!contentTransfers.IsOwnedBy(chunk.TransferId, connection.SessionId!))
        {
            throw new InvalidOperationException("Transfer owner did not match the authenticated socket.");
        }

        var result = contentTransfers.Append(chunk);
        if (!result.Accepted)
        {
            throw new InvalidOperationException(result.Reason);
        }
    }

    private async Task HandleContentTransferCompleteAsync(
        ProtocolEnvelope envelope,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        var complete = envelope.ReadPayload<ContentTransferCompletePayload>()
            ?? throw new InvalidOperationException("Missing content transfer completion payload.");
        var result = contentTransfers.Complete(complete);
        if (!result.Accepted || result.Transfer is null)
        {
            throw new InvalidOperationException(result.Reason ?? "Transfer did not complete.");
        }

        if (!string.Equals(result.Transfer.Start.SourceDeviceId, connection.SessionId, StringComparison.Ordinal))
        {
            contentTransfers.Remove(complete.TransferId);
            throw new InvalidOperationException("Transfer owner did not match the authenticated socket.");
        }

        if (string.Equals(result.Transfer.Start.Purpose, "media", StringComparison.OrdinalIgnoreCase))
        {
            var completedMedia = mediaContent.CompleteTransfer(connection.SessionId!, result.Transfer);
            if (!completedMedia.Accepted)
            {
                contentTransfers.Remove(complete.TransferId);
                throw new InvalidOperationException(completedMedia.Reason);
            }

            return;
        }

        if (_pendingClipboardTransfers.TryRemove(complete.TransferId, out var pending))
        {
            if (!string.Equals(pending.SourceDeviceId, connection.SessionId, StringComparison.Ordinal)
                || pending.Generation != connection.Generation
                || !string.Equals(pending.Item.ItemId, result.Transfer.Start.ItemId, StringComparison.Ordinal)
                || !string.Equals(pending.Item.ContentHash, result.Transfer.Start.ContentHash, StringComparison.OrdinalIgnoreCase))
            {
                contentTransfers.Remove(complete.TransferId);
                throw new InvalidOperationException("Clipboard item metadata did not match its completed transfer.");
            }

            await RelayClipboardAsync(pending.Item, result.Transfer, cancellationToken);
        }
    }

    private async Task RelayClipboardAsync(ClipboardItemPayload item, CompletedContentTransfer? transfer, CancellationToken cancellationToken)
    {
        var result = await clipboardRelay.PublishAsync(item, transfer, cancellationToken);
        if (!result.Accepted) logger.LogInformation("Clipboard item ignored: {Reason}", result.Reason);
    }

    private async Task HandleClipboardAckAsync(
        ProtocolEnvelope envelope,
        ConnectionSession connection,
        CancellationToken cancellationToken)
    {
        var acknowledgement = envelope.ReadPayload<ClipboardAckPayload>()
            ?? throw new InvalidOperationException("Missing clipboard acknowledgement payload.");
        if (!clipboard.Acknowledge(connection.SessionId!, acknowledgement))
        {
            throw new InvalidOperationException("Clipboard acknowledgement did not match a pending delivery.");
        }

        var source = clipboard.GetReceipt(acknowledgement.ItemId)?.SourceDeviceId;
        if (!string.IsNullOrWhiteSpace(source)
            && !string.Equals(source, "pc-host", StringComparison.Ordinal)
            && !string.Equals(source, connection.SessionId, StringComparison.Ordinal))
        {
            await sessions.SendAsync(source, envelope with { DeviceId = "pc-host" }, cancellationToken);
        }
    }

    private async Task HandleLegacyMirrorRelayAsync(ProtocolEnvelope envelope, CancellationToken cancellationToken)
    {
        var signal = envelope.ReadPayload<MirrorSignalPayload>()
            ?? throw new InvalidOperationException("Missing mirror relay payload.");
        mirrors.Touch(signal.SessionId);
        if (!string.IsNullOrWhiteSpace(signal.TargetDeviceId))
        {
            await sessions.SendAsync(signal.TargetDeviceId, envelope, cancellationToken);
        }
    }

    private async Task HandleMirrorConfigureRelayAsync(ProtocolEnvelope envelope, CancellationToken cancellationToken)
    {
        var configure = envelope.ReadPayload<MirrorConfigurePayload>()
            ?? throw new InvalidOperationException("Missing mirror configure payload.");
        mirrors.Touch(configure.SessionId, "configuring");
        await sessions.SendAsync(configure.TargetDeviceId, envelope, cancellationToken);
    }

    private async Task HandleMirrorTelemetryRelayAsync(ProtocolEnvelope envelope, CancellationToken cancellationToken)
    {
        var telemetry = envelope.ReadPayload<MirrorTelemetryPayload>()
            ?? throw new InvalidOperationException("Missing mirror telemetry payload.");
        mirrors.Touch(telemetry.SessionId, "streaming");
        await sessions.SendAsync(telemetry.TargetDeviceId, envelope, cancellationToken);
    }

    private async Task HandleMirrorStateRelayAsync(ProtocolEnvelope envelope, CancellationToken cancellationToken)
    {
        var state = envelope.ReadPayload<MirrorStatePayload>()
            ?? throw new InvalidOperationException("Missing mirror state payload.");
        if (state.State is "stopped" or "failed" or "denied")
        {
            mirrors.StopSession(state.SessionId, state.State);
        }
        else
        {
            mirrors.Touch(state.SessionId, state.State);
        }

        await sessions.SendAsync(state.TargetDeviceId, envelope, cancellationToken);
    }

    /// <summary>Forwards a phone event as-is to the PC app's realtime sockets.</summary>
    private async Task RelayToPcAppsAsync(ProtocolEnvelope envelope, CancellationToken cancellationToken)
    {
        var tasks = sessions.AllSessionIds
            .Where(sessionId => sessionId.StartsWith("pc-", StringComparison.OrdinalIgnoreCase))
            .Select(sessionId => sessions.SendAsync(sessionId, envelope, cancellationToken));
        await Task.WhenAll(tasks);
    }

    private async Task HandleMirrorInputRelayAsync(ProtocolEnvelope envelope, CancellationToken cancellationToken)
    {
        var input = envelope.ReadPayload<MirrorInputPayload>()
            ?? throw new InvalidOperationException("Missing mirror input payload.");
        mirrors.Touch(input.SessionId, "streaming");
        if (!string.IsNullOrWhiteSpace(input.TargetDeviceId))
        {
            await sessions.SendAsync(input.TargetDeviceId, envelope, cancellationToken);
        }
    }

    private async Task SendErrorAsync(
        WebSocket socket,
        string code,
        string message,
        string? correlationId,
        CancellationToken cancellationToken)
    {
        var error = ProtocolEnvelope.Create(
            ProtocolMessageTypes.Error,
            "pc-host",
            NextSequence(),
            new ErrorPayload(code, message, correlationId));
        await WebSocketSessionHub.SendAsync(socket, error, cancellationToken);
    }

    private long NextSequence() => Interlocked.Increment(ref _hostSequence);

    private static bool IsAllowedLocalMessage(string messageType) =>
        messageType is ProtocolMessageTypes.DeviceState
            or ProtocolMessageTypes.MirrorSignal
            or ProtocolMessageTypes.MirrorStop
            or ProtocolMessageTypes.MirrorInput
            or ProtocolMessageTypes.MirrorConfigure
            or ProtocolMessageTypes.MirrorTelemetry
            or ProtocolMessageTypes.MirrorState
            or ProtocolMessageTypes.HeartbeatPing
            or ProtocolMessageTypes.HeartbeatPong;

    private static bool IsLoopback(IPAddress? address)
    {
        if (address is null)
        {
            return false;
        }

        return IPAddress.IsLoopback(address)
            || (address.IsIPv4MappedToIPv6 && IPAddress.IsLoopback(address.MapToIPv4()));
    }

    private static async Task<string?> ReceiveTextAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        var buffer = new byte[16 * 1024];
        using var stream = new MemoryStream();

        while (true)
        {
            var result = await socket.ReceiveAsync(buffer, cancellationToken);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                {
                    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", cancellationToken);
                }

                return null;
            }

            if (result.MessageType != WebSocketMessageType.Text)
            {
                throw new InvalidOperationException("Only text protocol messages are supported.");
            }

            stream.Write(buffer, 0, result.Count);
            if (stream.Length > MaxInboundMessageBytes)
            {
                await socket.CloseAsync(WebSocketCloseStatus.MessageTooBig, "Protocol message exceeded 12 MiB.", cancellationToken);
                return null;
            }

            if (result.EndOfMessage)
            {
                break;
            }
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private sealed class ConnectionSession(bool isLoopback)
    {
        public bool IsLoopback { get; } = isLoopback;
        public bool Authenticated { get; private set; }
        public bool IsPhysicalDevice { get; private set; }
        public string? SessionId { get; private set; }
        public long Generation { get; private set; }
        public SessionChallengePayload? Challenge { get; private set; }

        public void SetChallenge(SessionChallengePayload challenge) => Challenge = challenge;

        public void Claim(string sessionId, long generation, bool isPhysicalDevice)
        {
            SessionId = sessionId;
            Generation = generation;
            IsPhysicalDevice = isPhysicalDevice;
            Authenticated = true;
        }
    }

    private sealed record PendingClipboardTransfer(
        ClipboardItemPayload Item,
        string SourceDeviceId,
        long Generation);
}
