using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.IO;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Pairly.Core;
using Pairly.Protocol;
using Pairly.Server;
using Pairly.Server.Streaming;

var tests = new (string Name, Func<Task> Run)[]
{
    ("protocol round-trips clipboard payload", RunSync(ProtocolRoundTripsClipboardPayload)),
    ("Windows strings have a German translation for every key", RunSync(WindowsStringsHaveGermanTranslations)),
    ("protocol rejects unsupported versions", RunSync(ProtocolRejectsUnsupportedVersions)),
    ("protocol round-trips release-ready optional payloads", RunSync(ProtocolRoundTripsReleaseReadyPayloads)),
    ("pairing ticket carries active host identity", RunSync(PairingTicketCarriesActiveHostIdentity)),
    ("UDP discovery uses Android-compatible field names", RunSync(UdpDiscoveryUsesAndroidCompatibleFieldNames)),
    ("pairing rejects bad code and accepts ticket", RunSync(PairingRejectsBadCodeAndAcceptsTicket)),
    ("pairing distinguishes rejected and expired codes", RunSync(PairingDistinguishesRejectedAndExpiredCodes)),
    ("trusted device reconnects without pairing ticket", RunSync(TrustedDeviceReconnectsWithoutPairingTicket)),
    ("bad trusted reconnect does not erase pairing", RunSync(BadTrustedReconnectDoesNotErasePairing)),
    ("device registry persists trusted devices offline", RunSync(DeviceRegistryPersistsTrustedDevicesOffline)),
    ("device registry restores trust store backup", RunSync(DeviceRegistryRestoresTrustStoreBackup)),
    ("device registry repairs trust store from backup", RunSync(DeviceRegistryRepairsTrustStoreFromBackup)),
    ("device registry persists revocation", RunSync(DeviceRegistryPersistsRevocation)),
    ("device registry stores permission state", RunSync(DeviceRegistryStoresPermissionState)),
    ("device registry serializes checksummed concurrent trust", RunSync(DeviceRegistrySerializesChecksummedConcurrentTrust)),
    ("revoked device cannot return through backup repair", RunSync(RevokedDeviceCannotReturnThroughBackupRepair)),
    ("challenge proof authenticates without sending trust key", RunSync(ChallengeProofAuthenticatesWithoutSendingTrustKey)),
    ("host identity and protected certificate survive recovery", RunSync(HostIdentityAndProtectedCertificateSurviveRecovery)),
    ("mirror start payload carries audio settings", RunSync(MirrorStartPayloadCarriesAudioSettings)),
    ("clipboard relays without echo and prevents loops", RunSync(ClipboardRelaysWithoutEchoAndPreventsLoops)),
    ("clipboard fans out to every connected peer", RunSync(ClipboardFansOutToEveryConnectedPeer)),
    ("clipboard sequences and acknowledges every delivery", RunSync(ClipboardSequencesAndAcknowledgesEveryDelivery)),
    ("clipboard validates PNG dimensions and hashes", RunSync(ClipboardValidatesPngDimensionsAndHashes)),
    ("chunked clipboard transfer verifies and enforces memory cap", RunSync(ChunkedClipboardTransferVerifiesAndEnforcesMemoryCap)),
    ("clipboard retries survive history rollover and reject changed origin", ClipboardRetryReceiptsSurviveHistory),
    ("clipboard delivery leases reject local and session replacement", RunSync(ClipboardDeliveryLeases)),
    ("Windows background capture prevents text and image echoes", WindowsClipboardBackgroundRegression),
    ("media catalog stores newest page order", RunSync(MediaCatalogStoresNewestPageOrder)),
    ("media cursor survives deletion and rejects snapshot mismatch", RunSync(MediaCursorSurvivesDeletionAndRejectsSnapshotMismatch)),
    ("thumbnail cache returns cached bytes", RunSync(ThumbnailCacheReturnsCachedBytes)),
    ("notification store scopes items per device", RunSync(NotificationStoreScopesItemsPerDevice)),
    ("notification payload carries controls metadata", RunSync(NotificationPayloadCarriesControlsMetadata)),
    ("notification snapshots reconcile removals", RunSync(NotificationSnapshotsReconcileRemovals)),
    ("sms store scopes threads per device", RunSync(SmsStoreScopesThreadsPerDevice)),
    ("permission mapper distinguishes blocked requests", RunSync(PermissionMapperDistinguishesBlockedRequests)),
    ("embedded host starts and stops", EmbeddedHostStartsAndStops),
    ("host preflight detects every required port conflict", RunSync(HostPreflightDetectsEveryRequiredPortConflict)),
    ("secure host terminates pinned WSS", SecureHostTerminatesPinnedWss),
    ("desktop heartbeat identity and immediate phone image ingress", PhoneImageIngressRelaysImmediately),
    ("session hub routes clipboard only to real devices", RunSync(SessionHubRoutesClipboardOnlyToRealDevices)),
    ("session hub keeps newer reconnect when old socket closes", RunSync(SessionHubKeepsNewerReconnectWhenOldSocketCloses)),
    ("session hub bounds slow-device writer queue", SessionHubBoundsSlowDeviceWriterQueue),
    ("mirror registry keeps one active session per device", RunSync(MirrorRegistryKeepsOneActiveSessionPerDevice)),
    ("mirror registry enforces global session and lease", RunSync(MirrorRegistryEnforcesGlobalSessionAndLease)),
    ("media content broker completes pending response", MediaContentBrokerCompletesPendingResponse),
    ("media broker streams verified chunks beyond clipboard limit", MediaBrokerStreamsVerifiedChunksBeyondClipboardLimit),
    ("command result broker completes and times out", CommandResultBrokerCompletesAndTimesOut),
    ("backoff policy matches the Android reconnect ladder", RunSync(BackoffPolicyMatchesAndroidLadder)),
    ("diagnostics log bounds history and counts reconnect reasons", RunSync(DiagnosticsLogBoundsHistoryAndCountsReasons)),
    ("session hub keeps control work ahead of telemetry", SessionHubKeepsControlWorkAheadOfTelemetry),
    ("annex-b reader splits one access unit per picture", RunSync(AnnexBReaderSplitsOneAccessUnitPerPicture)),
    ("annex-b reader extracts leading parameter sets", RunSync(AnnexBReaderExtractsLeadingParameterSets)),
    ("stream framing matches the Android wire format", RunSync(StreamFramingMatchesAndroidWireFormat)),
    ("control messages match the Android wire format", RunSync(ControlMessagesMatchAndroidWireFormat)),
    ("mirror quality policy matches the Android policy", RunSync(MirrorQualityPolicyMatchesAndroidPolicy)),
    ("stream tickets are single use and stream bound", RunSync(StreamTicketsAreSingleUseAndStreamBound)),
    ("stream listener admits a pinned socket and reads records", StreamListenerAdmitsPinnedSocketAndReadsRecords),
    ("stream listener refuses an unusable ticket", StreamListenerRefusesUnusableTicket),
    ("mirror stream hub pairs concurrent streams with their own format", MirrorStreamHubPairsConcurrentStreams),
    ("device registry sweeps abandoned temp files", RunSync(DeviceRegistrySweepsAbandonedTempFiles))
};

var failed = 0;
foreach (var test in tests)
{
    try
    {
        await test.Run();
        Console.WriteLine($"PASS {test.Name}");
    }
    catch (Exception ex)
    {
        failed++;
        Console.WriteLine($"FAIL {test.Name}");
        Console.WriteLine(ex);
    }
}

if (failed > 0)
{
    Console.WriteLine($"{failed} test(s) failed.");
    Environment.Exit(1);
}

Console.WriteLine($"{tests.Length} test(s) passed.");

static Func<Task> RunSync(Action action) => () =>
{
    action();
    return Task.CompletedTask;
};

static void ProtocolRoundTripsClipboardPayload()
{
    var payload = new ClipboardItemPayload(
        "clip-1",
        "phone-1",
        "text/plain",
        "hello",
        null,
        5,
        Ephemeral: true);
    var envelope = ProtocolEnvelope.Create(
        ProtocolMessageTypes.ClipboardItem,
        "phone-1",
        7,
        payload,
        DateTimeOffset.Parse("2026-07-02T12:00:00Z"),
        "abc");

    var json = JsonProtocolSerializer.Serialize(envelope);
    var roundTripped = JsonProtocolSerializer.Deserialize(json);
    var actualPayload = roundTripped.ReadPayload<ClipboardItemPayload>();

    AssertEqual(ProtocolMessageTypes.ClipboardItem, roundTripped.Type);
    AssertEqual(7L, roundTripped.Sequence);
    AssertEqual("hello", actualPayload?.Text);
    AssertTrue(ProtocolValidator.Validate(roundTripped).IsValid, "Expected envelope to validate.");
}

static void ProtocolRejectsUnsupportedVersions()
{
    var envelope = ProtocolEnvelope.Create(
        ProtocolMessageTypes.DeviceState,
        "phone-1",
        1,
        new DeviceStatePayload("Phone", true, new Dictionary<string, string>())) with
    {
        Version = 99
    };

    var result = ProtocolValidator.Validate(envelope);
    AssertFalse(result.IsValid, "Expected unsupported version to be rejected.");
    AssertTrue(result.Error?.Contains("Unsupported protocol version", StringComparison.Ordinal) == true, "Expected version error.");
}

static void ProtocolRoundTripsReleaseReadyPayloads()
{
    var expiresAt = DateTimeOffset.Parse("2026-07-15T12:10:00Z");
    var clipboard = new ClipboardItemPayload(
        "clip-v2",
        "phone-a",
        "image/png",
        null,
        "clipboard.png",
        64,
        true,
        RelaySequence: 42,
        ContentHash: new string('a', 64),
        ExpiresAt: expiresAt,
        TransferId: "transfer-a",
        MimeType: "image/png",
        OriginDeviceId: "phone-a");
    var clipboardRoundTrip = JsonProtocolSerializer.Deserialize(JsonProtocolSerializer.Serialize(
        ProtocolEnvelope.Create(ProtocolMessageTypes.ClipboardItem, "pc-host", 42, clipboard)))
        .ReadPayload<ClipboardItemPayload>();
    AssertEqual(42L, clipboardRoundTrip?.RelaySequence);
    AssertEqual("transfer-a", clipboardRoundTrip?.TransferId);

    var configure = new MirrorConfigurePayload(
        "mirror-a",
        "phone-a:mirror:mirror-a",
        1080,
        1920,
        60,
        8000,
        1000,
        "maintain-framerate",
        "h264",
        true,
        true);
    var configureRoundTrip = JsonProtocolSerializer.Deserialize(JsonProtocolSerializer.Serialize(
        ProtocolEnvelope.Create(ProtocolMessageTypes.MirrorConfigure, "pc-mirror", 1, configure)))
        .ReadPayload<MirrorConfigurePayload>();
    AssertEqual("h264", configureRoundTrip?.PreferredCodec);
    AssertTrue(configureRoundTrip?.RequestIceRestart == true, "Expected typed ICE restart setting.");

    AssertTrue(ProtocolMessageTypes.IsKnown(ProtocolMessageTypes.SessionChallenge), "Expected session challenge to be known.");
    AssertTrue(ProtocolMessageTypes.IsKnown(ProtocolMessageTypes.ContentTransferChunk), "Expected transfer chunk to be known.");
    AssertTrue(ProtocolMessageTypes.IsKnown(ProtocolMessageTypes.NotificationSnapshotEnd), "Expected snapshot end to be known.");
}

static void PairingTicketCarriesActiveHostIdentity()
{
    var payload = new PairingTicketResponsePayload(
        "123456",
        DateTimeOffset.Parse("2026-07-16T12:10:00Z"),
        "host-v2",
        2,
        7818,
        new string('a', 64));
    var json = JsonSerializer.Serialize(payload, new JsonSerializerOptions(JsonSerializerDefaults.Web));
    var roundTrip = JsonSerializer.Deserialize<PairingTicketResponsePayload>(
        json,
        new JsonSerializerOptions(JsonSerializerDefaults.Web));

    AssertEqual("host-v2", roundTrip?.HostId);
    AssertEqual(2, roundTrip?.ProtocolVersion);
    AssertEqual(7818, roundTrip?.WssPort);
    AssertEqual(new string('a', 64), roundTrip?.CertificateFingerprint);
}

static void UdpDiscoveryUsesAndroidCompatibleFieldNames()
{
    var json = PairlyServer.SerializeDiscoveryResponse(new DiscoveryResponsePayload(
        "host-v2",
        "Test PC",
        2,
        7816,
        7818,
        new string('b', 64)));
    using var document = JsonDocument.Parse(json);
    var root = document.RootElement;

    AssertEqual("host-v2", root.GetProperty("hostId").GetString());
    AssertEqual("Test PC", root.GetProperty("pcName").GetString());
    AssertEqual(2, root.GetProperty("protocolVersion").GetInt32());
    AssertEqual(7818, root.GetProperty("wssPort").GetInt32());
    AssertEqual(new string('b', 64), root.GetProperty("certificateFingerprint").GetString());
    AssertFalse(root.TryGetProperty("HostId", out _), "Discovery must not emit PascalCase fields.");
}

static void MirrorStartPayloadCarriesAudioSettings()
{
    var payload = new MirrorStartPayload(
        "mirror-session",
        "pc-session",
        1280,
        720,
        60,
        "low-latency",
        2500,
        150,
        "maintain-framerate",
        true,
        true,
        "screen",
        48000,
        2);
    var envelope = ProtocolEnvelope.Create(
        ProtocolMessageTypes.MirrorStart,
        "pc-host",
        1,
        payload);

    var roundTripped = JsonProtocolSerializer.Deserialize(JsonProtocolSerializer.Serialize(envelope))
        .ReadPayload<MirrorStartPayload>();

    AssertTrue(roundTripped?.AudioEnabled == true, "Expected audio enabled to round-trip.");
    AssertEqual("screen", roundTripped?.AudioMode);
    AssertEqual(48000, roundTripped?.AudioSampleRate);
    AssertEqual(2, roundTripped?.AudioChannels);
}

static void PairingRejectsBadCodeAndAcceptsTicket()
{
    var registry = new DeviceRegistry();
    var rejected = registry.ConfirmPairing(new PairHelloPayload(
        "123456",
        "phone-1",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));
    AssertFalse(rejected.Accepted, "Unexpectedly accepted a bad pairing code.");

    var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
    var accepted = registry.ConfirmPairing(new PairHelloPayload(
        ticket.Code,
        "phone-1",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));

    AssertTrue(accepted.Accepted, "Expected pairing ticket to be accepted.");
    AssertTrue(registry.Authenticate("phone-1", accepted.TrustKey), "Expected trust key to authenticate.");
}

static void PairingDistinguishesRejectedAndExpiredCodes()
{
    var registry = new DeviceRegistry();
    var rejected = registry.ConfirmPairing(new PairHelloPayload(
        "000000",
        "phone-rejected",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));
    AssertEqual("pairing_code_rejected", rejected.ErrorCode);

    var expiredTicket = registry.OpenPairingTicket(TimeSpan.FromMilliseconds(-1));
    var expired = registry.ConfirmPairing(new PairHelloPayload(
        expiredTicket.Code,
        "phone-expired",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));
    AssertEqual("pairing_code_expired", expired.ErrorCode);
}

static void HostPreflightDetectsEveryRequiredPortConflict()
{
    using (var tcp7816 = new TcpListener(IPAddress.Loopback, HostIdentity.LanPort))
    {
        tcp7816.Server.ExclusiveAddressUse = true;
        tcp7816.Start();
        AssertHostConflict(HostIdentity.LanPort, "TCP");
    }

    using (var udp7817 = new UdpClient(AddressFamily.InterNetwork))
    {
        udp7817.ExclusiveAddressUse = true;
        udp7817.Client.Bind(new IPEndPoint(IPAddress.Any, 7817));
        AssertHostConflict(7817, "UDP");
    }

    using (var tcp7818 = new TcpListener(IPAddress.Any, HostIdentity.SecureLanPort))
    {
        tcp7818.Server.ExclusiveAddressUse = true;
        tcp7818.Start();
        AssertHostConflict(HostIdentity.SecureLanPort, "TCP");
    }

    HostPortPreflight.VerifyRequiredEndpointsAvailable();
}

static void AssertHostConflict(int expectedPort, string expectedTransport)
{
    try
    {
        HostPortPreflight.VerifyRequiredEndpointsAvailable();
        throw new InvalidOperationException($"Expected a conflict on {expectedTransport} port {expectedPort}.");
    }
    catch (HostStartupException ex)
    {
        AssertEqual(expectedPort, ex.Port);
        AssertEqual(expectedTransport, ex.Transport);
    }
}

static void TrustedDeviceReconnectsWithoutPairingTicket()
{
    var registry = new DeviceRegistry();
    var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
    var accepted = registry.ConfirmPairing(new PairHelloPayload(
        ticket.Code,
        "phone-1",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));

    var reconnect = registry.ConfirmPairing(new PairHelloPayload(
        string.Empty,
        "phone-1",
        "Phone renamed",
        "android",
        new DeviceCapabilitiesPayload(true, false, true, false, true),
        accepted.TrustKey));

    var device = registry.GetDevice("phone-1");
    AssertTrue(reconnect.Accepted, "Expected saved trust key to reconnect.");
    AssertEqual(accepted.TrustKey, reconnect.TrustKey);
    AssertEqual("Phone renamed", device?.DisplayName);
    AssertTrue(device?.Connected == true, "Expected trusted reconnect to mark device online.");
}

static void BadTrustedReconnectDoesNotErasePairing()
{
    var registry = new DeviceRegistry();
    var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
    var accepted = registry.ConfirmPairing(new PairHelloPayload(
        ticket.Code,
        "phone-1",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));

    var rejected = registry.ConfirmPairing(new PairHelloPayload(
        string.Empty,
        "phone-1",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true),
        "wrong-trust-key"));

    AssertFalse(rejected.Accepted, "Expected bad trust key to be rejected.");
    AssertTrue(registry.Authenticate("phone-1", accepted.TrustKey), "Expected original pairing to remain valid.");
}

static void DeviceRegistryPersistsTrustedDevicesOffline()
{
    var path = TempDeviceStorePath();
    try
    {
        var registry = new DeviceRegistry(path);
        var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
        var accepted = registry.ConfirmPairing(new PairHelloPayload(
            ticket.Code,
            "phone-1",
            "Phone",
            "android",
            new DeviceCapabilitiesPayload(true, true, true, true, true)));

        var reloaded = new DeviceRegistry(path);
        var device = reloaded.GetDevice("phone-1");

        AssertTrue(device is not null, "Expected trusted device to reload from disk.");
        AssertFalse(device?.Connected == true, "Expected persisted device to reload offline.");
        AssertTrue(reloaded.Authenticate("phone-1", accepted.TrustKey), "Expected reloaded trust key to authenticate.");
    }
    finally
    {
        TryDelete(path);
        TryDelete($"{path}.bak");
    }
}

static void DeviceRegistryRestoresTrustStoreBackup()
{
    var path = TempDeviceStorePath();
    try
    {
        var registry = new DeviceRegistry(path);
        var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
        var accepted = registry.ConfirmPairing(new PairHelloPayload(
            ticket.Code,
            "phone-1",
            "Phone",
            "android",
            new DeviceCapabilitiesPayload(true, true, true, true, true)));

        File.WriteAllText(path, string.Empty);
        var reloaded = new DeviceRegistry(path);

        AssertTrue(reloaded.GetPersistenceStatus().LoadedFromBackup, "Expected damaged primary trust store to restore from backup.");
        AssertTrue(reloaded.Authenticate("phone-1", accepted.TrustKey), "Expected backup-restored trust key to authenticate.");
    }
    finally
    {
        TryDelete(path);
        TryDelete($"{path}.bak");
    }
}

static void DeviceRegistryRepairsTrustStoreFromBackup()
{
    var path = TempDeviceStorePath();
    try
    {
        var registry = new DeviceRegistry(path);
        var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
        var accepted = registry.ConfirmPairing(new PairHelloPayload(
            ticket.Code,
            "phone-1",
            "Phone",
            "android",
            new DeviceCapabilitiesPayload(true, true, true, true, true)));

        File.WriteAllText(path, "not json");
        var damaged = new DeviceRegistry(path);
        AssertTrue(damaged.RepairPersistenceFromBackup(), "Expected trust-store repair to restore backup.");
        AssertTrue(damaged.Authenticate("phone-1", accepted.TrustKey), "Expected repaired trust key to authenticate.");
    }
    finally
    {
        TryDelete(path);
        TryDelete($"{path}.bak");
    }
}

static void DeviceRegistryPersistsRevocation()
{
    var path = TempDeviceStorePath();
    try
    {
        var registry = new DeviceRegistry(path);
        var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
        registry.ConfirmPairing(new PairHelloPayload(
            ticket.Code,
            "phone-1",
            "Phone",
            "android",
            new DeviceCapabilitiesPayload(true, true, true, true, true)));

        AssertTrue(registry.Revoke("phone-1"), "Expected revocation to remove a paired device.");
        var reloaded = new DeviceRegistry(path);
        AssertTrue(reloaded.GetDevice("phone-1") is null, "Expected revoked device to stay removed after reload.");
    }
    finally
    {
        TryDelete(path);
        TryDelete($"{path}.bak");
    }
}

static void DeviceRegistryStoresPermissionState()
{
    var registry = new DeviceRegistry();
    var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
    registry.ConfirmPairing(new PairHelloPayload(
        ticket.Code,
        "phone-1",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));

    registry.UpdateDeviceState(
        "phone-1",
        new DeviceStatePayload(
            "Phone",
            true,
            new Dictionary<string, string>
            {
                ["photos"] = "granted",
                ["sms"] = "needs_user_action"
            }));

    var device = registry.GetDevice("phone-1");
    AssertEqual("granted", device?.Permissions?["photos"]);
    AssertEqual("needs_user_action", device?.Permissions?["sms"]);
}

static void DeviceRegistrySerializesChecksummedConcurrentTrust()
{
    var path = TempDeviceStorePath();
    try
    {
        var registry = new DeviceRegistry(path);
        var tickets = Enumerable.Range(0, 12)
            .Select(_ => registry.OpenPairingTicket(TimeSpan.FromMinutes(1)))
            .ToArray();
        var trustKeys = new ConcurrentBag<string>();
        Parallel.For(0, tickets.Length, index =>
        {
            var accepted = registry.ConfirmPairing(new PairHelloPayload(
                tickets[index].Code,
                $"phone-{index}",
                $"Phone {index}",
                "android",
                new DeviceCapabilitiesPayload(true, true, true, true, true)));
            AssertTrue(accepted.Accepted, "Expected concurrent pairing to succeed.");
            trustKeys.Add(accepted.TrustKey);
        });

        var json = File.ReadAllText(path);
        using var parsed = JsonDocument.Parse(json);
        AssertEqual(2, parsed.RootElement.GetProperty("schemaVersion").GetInt32());
        AssertTrue(parsed.RootElement.GetProperty("revision").GetInt64() >= tickets.Length, "Expected monotonic trust-store revision.");
        AssertEqual(64, parsed.RootElement.GetProperty("checksum").GetString()?.Length ?? 0);
        foreach (var trustKey in trustKeys)
        {
            AssertFalse(json.Contains(trustKey, StringComparison.Ordinal), "Trust key must not be persisted in clear text.");
        }

        var reloaded = new DeviceRegistry(path);
        AssertEqual(tickets.Length, reloaded.GetDevices().Count);
        AssertTrue(reloaded.GetPersistenceStatus().Healthy, "Expected checksummed snapshots to reload cleanly.");
    }
    finally
    {
        TryDelete(path);
        TryDelete($"{path}.bak");
    }
}

static void RevokedDeviceCannotReturnThroughBackupRepair()
{
    var path = TempDeviceStorePath();
    try
    {
        var registry = new DeviceRegistry(path);
        var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
        registry.ConfirmPairing(new PairHelloPayload(
            ticket.Code,
            "phone-revoked",
            "Forgotten phone",
            "android",
            new DeviceCapabilitiesPayload(true, true, true, true, true)));
        AssertTrue(registry.Revoke("phone-revoked"), "Expected device revocation.");

        File.WriteAllText(path, "corrupt primary");
        var recovered = new DeviceRegistry(path);
        AssertTrue(recovered.GetPersistenceStatus().LoadedFromBackup, "Expected redundant snapshot recovery.");
        AssertTrue(recovered.GetDevice("phone-revoked") is null, "Backup recovery must honor the revocation tombstone.");
        AssertTrue(recovered.RepairPersistenceFromBackup(), "Expected explicit repair to preserve the valid tombstone snapshot.");
        AssertTrue(recovered.GetDevice("phone-revoked") is null, "Manual repair must not resurrect a forgotten phone.");
        AssertTrue(recovered.GetPersistenceStatus().RevocationCount >= 1, "Expected persisted revocation metadata.");
    }
    finally
    {
        TryDelete(path);
        TryDelete($"{path}.bak");
    }
}

static void ChallengeProofAuthenticatesWithoutSendingTrustKey()
{
    var registry = new DeviceRegistry();
    var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
    var accepted = registry.ConfirmPairing(new PairHelloPayload(
        ticket.Code,
        "phone-proof",
        "Phone",
        "android",
        new DeviceCapabilitiesPayload(true, true, true, true, true)));
    var proof = DeviceRegistry.ComputeChallengeProof(
        accepted.TrustKey,
        "challenge-a",
        "nonce-a",
        "phone-proof",
        "host-a");

    AssertTrue(
        registry.AuthenticateProof("phone-proof", "challenge-a", "nonce-a", "host-a", proof),
        "Expected HMAC challenge proof to authenticate.");
    AssertFalse(
        registry.AuthenticateProof("phone-proof", "challenge-a", "nonce-b", "host-a", proof),
        "Expected changed nonce to invalidate the proof.");
}

static void HostIdentityAndProtectedCertificateSurviveRecovery()
{
    var directory = Path.Combine(Path.GetTempPath(), $"pairly-identity-{Guid.NewGuid():N}");
    try
    {
        string hostId;
        string fingerprint;
        using (var first = HostIdentity.LoadOrCreate(directory))
        {
            hostId = first.HostId;
            fingerprint = first.CertificateFingerprint;
            AssertTrue(first.Certificate.NotAfter >= DateTime.UtcNow.AddYears(29), "Expected long-lived pinned certificate identity.");
        }

        var identityPath = Path.Combine(directory, "host-identity.json");
        var certificatePath = Path.Combine(directory, "host-certificate.pfx");
        using (var document = JsonDocument.Parse(File.ReadAllText(identityPath)))
        {
            var protectedPassword = document.RootElement.GetProperty("protectedCertificatePassword").GetString();
            AssertTrue(protectedPassword?.StartsWith("dpapi:", StringComparison.Ordinal) == true,
                "Expected certificate password to use Windows DPAPI.");
            var password = SecretProtector.Unprotect(protectedPassword!);
            using var certificate = X509CertificateLoader.LoadPkcs12FromFile(
                certificatePath,
                password,
                X509KeyStorageFlags.Exportable);
            var loadedFingerprint = Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant();
            AssertEqual(fingerprint, loadedFingerprint);
        }

        File.Delete(identityPath);
        File.Delete(certificatePath);
        using (var recovered = HostIdentity.LoadOrCreate(directory))
        {
            AssertEqual(hostId, recovered.HostId);
            AssertEqual(fingerprint, recovered.CertificateFingerprint);
        }

        var ids = new ConcurrentBag<string>();
        Parallel.For(0, 4, _ =>
        {
            using var identity = HostIdentity.LoadOrCreate(directory);
            ids.Add(identity.HostId);
        });
        AssertTrue(ids.All(id => id == hostId), "Concurrent loads must serialize around the same identity snapshots.");
    }
    finally
    {
        TryDeleteDirectory(directory);
    }
}

static void ClipboardRelaysWithoutEchoAndPreventsLoops()
{
    var clipboard = new ClipboardHub();
    var item = new ClipboardItemPayload(
        "clip-1",
        "phone-1",
        "text/plain",
        "sync me",
        null,
        7,
        Ephemeral: true);

    var first = clipboard.Publish(item, new[] { "pc-host", "phone-1", "tablet-1" });
    AssertTrue(first.Accepted, "Expected first clipboard item to be accepted.");
    AssertSequence(new[] { "pc-host", "tablet-1" }, first.RecipientDeviceIds);

    var duplicate = clipboard.Publish(item, new[] { "pc-host", "phone-1", "tablet-1" });
    AssertFalse(duplicate.Accepted, "Expected duplicate clipboard item to be rejected.");
    var keyboardCopy = clipboard.Publish(item with { ItemId = "keyboard-copy", SourceDeviceId = "tablet-1" }, ["pc-host", "phone-1", "tablet-1"]);
    AssertFalse(keyboardCopy.Accepted, "An OEM's simultaneous copy on another device must not fan out unchanged content.");
    AssertEqual("keyboard-copy", keyboardCopy.Item!.ItemId);
    AssertEqual("tablet-1", keyboardCopy.Item.SourceDeviceId);
    AssertEqual(first.Item!.RelaySequence, keyboardCopy.Item.RelaySequence);
    AssertEqual(first.Item.ExpiresAt, keyboardCopy.Item.ExpiresAt);
    AssertEqual(0, keyboardCopy.RecipientDeviceIds.Count);
    var keyboardRetry = clipboard.Publish(item with { ItemId = "keyboard-copy", SourceDeviceId = "tablet-1" }, []);
    AssertEqual(keyboardCopy.Item.RelaySequence, keyboardRetry.Item!.RelaySequence);
    AssertFalse(clipboard.Publish(item with { ItemId = "keyboard-copy", SourceDeviceId = "phone-1" }, []).Accepted,
        "Coalescing must preserve retry origin authorization.");
    clipboard.Publish(item with { ItemId = "intervening", Text = "different" }, []);
    AssertTrue(clipboard.Publish(item with { ItemId = "copied-again", SourceDeviceId = "tablet-1" }, []).Accepted,
        "Copying previous content after a different item must publish a new item.");
}

static void ClipboardFansOutToEveryConnectedPeer()
{
    var clipboard = new ClipboardHub();
    var item = new ClipboardItemPayload(
        "clip-2",
        "phone-1",
        "text/plain",
        "sync everyone",
        null,
        13,
        Ephemeral: true);

    var result = clipboard.Publish(item, new[] { "phone-3", "phone-1", "phone-2", "pc-host" });

    AssertTrue(result.Accepted, "Expected clipboard item to be accepted.");
    AssertSequence(new[] { "pc-host", "phone-2", "phone-3" }, result.RecipientDeviceIds);
}

static void ClipboardSequencesAndAcknowledgesEveryDelivery()
{
    var clipboard = new ClipboardHub();
    var first = clipboard.Publish(
        new ClipboardItemPayload("ordered-1", "phone-a", "text/plain", "first", null, 5, true),
        new[] { "phone-a", "phone-b", "pc-host" });
    var second = clipboard.Publish(
        new ClipboardItemPayload("ordered-2", "phone-b", "text/plain", "second", null, 6, true),
        new[] { "phone-a", "phone-b", "pc-host" });

    AssertTrue(first.Accepted && second.Accepted, "Expected ordered publications.");
    AssertEqual(1L, first.Item?.RelaySequence);
    AssertEqual(2L, second.Item?.RelaySequence);
    AssertTrue(clipboard.Acknowledge(
        "phone-b",
        new ClipboardAckPayload("ordered-1", 1, "applied", AppliedAt: DateTimeOffset.UtcNow)),
        "Expected matching phone acknowledgement.");
    AssertTrue(clipboard.Acknowledge(
        "pc-host",
        new ClipboardAckPayload("ordered-1", 1, "unsupported", "Clipboard image disabled.")),
        "Expected matching PC acknowledgement.");
    AssertFalse(clipboard.Acknowledge(
        "phone-b",
        new ClipboardAckPayload("ordered-1", 99, "applied")),
        "Expected stale relay sequence to be rejected.");
    AssertSequence(
        new[] { "unsupported", "applied" },
        clipboard.GetDeliveries("ordered-1").Select(item => item.Status).ToArray());
}

static void ClipboardValidatesPngDimensionsAndHashes()
{
    var clipboard = new ClipboardHub();
    var png = TestPng(1080, 1920, 256);
    var hash = Convert.ToHexString(SHA256.HashData(png)).ToLowerInvariant();
    var accepted = clipboard.Publish(
        new ClipboardItemPayload(
            "png-good",
            "phone-a",
            "image/png",
            null,
            "clipboard.png",
            png.Length,
            true,
            ContentHash: hash,
            Base64: Convert.ToBase64String(png)),
        new[] { "phone-b" });
    AssertTrue(accepted.Accepted, "Expected valid bounded PNG clipboard item.");
    AssertEqual(png.Length, accepted.Item?.ByteCount);

    var oversized = TestPng(4097, 100, 64);
    var rejectedDimensions = clipboard.Publish(
        new ClipboardItemPayload(
            "png-large",
            "phone-a",
            "image/png",
            null,
            null,
            oversized.Length,
            true,
            Base64: Convert.ToBase64String(oversized)),
        new[] { "phone-b" });
    AssertFalse(rejectedDimensions.Accepted, "Expected PNG long edge limit.");

    var rejectedHash = clipboard.Publish(
        new ClipboardItemPayload(
            "png-hash",
            "phone-a",
            "image/png",
            null,
            null,
            png.Length,
            true,
            ContentHash: new string('0', 64),
            Base64: Convert.ToBase64String(png)),
        new[] { "phone-b" });
    AssertFalse(rejectedHash.Accepted, "Expected PNG content hash verification.");
}

static void ChunkedClipboardTransferVerifiesAndEnforcesMemoryCap()
{
    var store = new ContentTransferStore();
    var png = TestPng(1440, 3120, 600_000);
    var hash = Convert.ToHexString(SHA256.HashData(png)).ToLowerInvariant();
    const int chunkSize = ContentTransferStore.MaxChunkBytes;
    var chunkCount = (int)Math.Ceiling(png.Length / (double)chunkSize);
    var start = new ContentTransferStartPayload(
        "transfer-good",
        "item-good",
        "phone-a",
        "image/png",
        "clipboard.png",
        png.Length,
        chunkSize,
        chunkCount,
        hash,
        DateTimeOffset.UtcNow.Add(ContentTransferStore.MaxLifetime).AddSeconds(5));
    AssertTrue(store.Begin(start).Accepted, "Phone clock skew must not delay clipboard images.");
    AssertTrue(store.Begin(start).Accepted, "The original transfer request must remain retryable.");
    AssertFalse(store.Begin(start with { ExpiresAt = start.ExpiresAt.AddSeconds(1) }).Accepted, "A retry cannot renew its deadline.");
    AssertFalse(store.Begin(start with { TransferId = "expired-copy", ExpiresAt = DateTimeOffset.UtcNow.AddSeconds(-1) }).Accepted, "Expired clipboard content must still be rejected.");
    AssertFalse(store.Begin(start with { TransferId = "media-skew", Purpose = "media" }).Accepted, "Clipboard clock normalization must not change media transfer policy.");
    for (var index = 0; index < chunkCount; index++)
    {
        var offset = index * chunkSize;
        var bytes = png.AsSpan(offset, Math.Min(chunkSize, png.Length - offset)).ToArray();
        AssertTrue(store.Append(new ContentTransferChunkPayload(
            start.TransferId,
            index,
            Convert.ToBase64String(bytes),
            Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant())).Accepted,
            "Expected verified transfer chunk.");
    }

    var completed = store.Complete(new ContentTransferCompletePayload(start.TransferId, hash));
    AssertTrue(completed.Accepted && completed.Transfer is not null, "Expected completed SHA-256 transfer.");
    AssertEqual((long)png.Length, store.BufferedBytes);
    AssertTrue(store.TryGetCompletedByItemId("item-good", out var byItem), "Expected item-addressable ephemeral bytes.");
    AssertEqual(chunkCount, byItem.Chunks.Count);
    AssertTrue(store.Begin(start).Accepted, "Retry must reuse the original reservation.");
    AssertTrue(store.Append(new ContentTransferChunkPayload(start.TransferId, 0, Convert.ToBase64String(byItem.Chunks[0]),
        Convert.ToHexString(SHA256.HashData(byItem.Chunks[0])).ToLowerInvariant())).Accepted, "Verified retry chunks remain idempotent after completion.");
    AssertFalse(store.Complete(new ContentTransferCompletePayload(start.TransferId, new string('0', 64))).Accepted,
        "An already completed transfer still verifies a retried completion hash.");
    AssertEqual((long)png.Length, store.BufferedBytes);

    var capped = new ContentTransferStore();
    for (var index = 0; index < 8; index++)
    {
        var reservation = new ContentTransferStartPayload(
            $"cap-{index}",
            $"cap-item-{index}",
            "phone-a",
            "image/png",
            null,
            ContentTransferStore.MaxTransferBytes,
            ContentTransferStore.MaxChunkBytes,
            32,
            new string('a', 64),
            DateTimeOffset.UtcNow.AddMinutes(9));
        AssertTrue(capped.Begin(reservation).Accepted, "Expected reservation within 64 MiB cap.");
    }

    var overflow = capped.Begin(new ContentTransferStartPayload(
        "cap-overflow",
        "cap-overflow-item",
        "phone-a",
        "image/png",
        null,
        1,
        1,
        1,
        new string('b', 64),
        DateTimeOffset.UtcNow.AddMinutes(9)));
    AssertFalse(overflow.Accepted, "Expected aggregate transfer memory cap.");
    AssertEqual(ContentTransferStore.MaxBufferedBytes, capped.BufferedBytes);
}

static async Task ClipboardRetryReceiptsSurviveHistory()
{
    var hub = new ClipboardHub();
    using var sessions = new WebSocketSessionHub();
    sessions.Attach("phone-a", new BlockingWebSocket());
    sessions.Attach("phone-b", new BlockingWebSocket());
    sessions.Attach("pc-app-test", new BlockingWebSocket());
    using var relay = new ClipboardRelay(hub, sessions);
    var original = new ClipboardItemPayload("retry-original", "phone-a", "text/plain", "original", null, 8, true,
        ExpiresAt: DateTimeOffset.UtcNow.AddMinutes(5));
    var first = await relay.PublishAsync(original, null, CancellationToken.None);
    for (var index = 0; index < 60; index++)
        await relay.PublishAsync(original with { ItemId = "later-" + index, Text = "later" }, null, CancellationToken.None);
    AssertFalse(hub.GetRecent(50).Any(item => item.ItemId == original.ItemId), "Original must have left recent history.");
    var retry = await relay.PublishAsync(original, null, CancellationToken.None);
    AssertTrue(retry.Accepted, "The same copy must receive publication confirmation after rollover.");
    AssertEqual(first.Item!.RelaySequence, retry.Item!.RelaySequence);
    AssertEqual(first.Item.ExpiresAt, retry.Item.ExpiresAt);
    AssertEqual(0, retry.RecipientDeviceIds.Count);
    AssertFalse((await relay.PublishAsync(original with { SourceDeviceId = "phone-b" }, null, CancellationToken.None)).Accepted,
        "Another source must not reuse a receipt.");
    AssertFalse((await relay.PublishAsync(original with { Text = "changed" }, null, CancellationToken.None)).Accepted,
        "Retry identity must not authorize changed content.");
    AssertFalse(hub.Publish(original with { ItemId = "expired", ExpiresAt = DateTimeOffset.UtcNow.AddSeconds(-1) }, []).Accepted,
        "An expired unsent copy must never be relayed.");
}

static void ClipboardDeliveryLeases()
{
    var ordering = new ClipboardOrdering();
    var image = ordering.Receive(1)!.Value;
    var text = ordering.Receive(2)!.Value;
    AssertFalse(ordering.CanApply(image), "A delayed image must not overwrite newer text.");
    AssertTrue(ordering.CanApply(text), "Newest text can apply.");
    ordering.LocalChanged();
    AssertFalse(ordering.CanApply(text), "A local copy supersedes delayed remote work.");
    var previous = ordering.Receive(3)!.Value;
    ordering.NewSession();
    AssertFalse(ordering.CanApply(previous), "A replaced session invalidates delayed images.");
    AssertTrue(ordering.Receive(1).HasValue, "A new session may restart relay numbering.");
    AssertFalse(ordering.Receive(1).HasValue, "Duplicate delivery must not apply twice.");
}

static Task WindowsClipboardBackgroundRegression() => RunOnStaAsync(async () =>
{
    var original = System.Windows.Clipboard.GetDataObject();
    var saved = new System.Windows.DataObject();
    if (original is not null)
        foreach (var format in original.GetFormats(false))
            try { if (original.GetData(format, false) is { } value) saved.SetData(format, value); } catch { }
    using var handler = new ClipboardTestHttpHandler();
    using var client = new HttpClient(handler) { BaseAddress = new Uri("http://clipboard.test/") };
    using var sync = new Pairly.Windows.WindowsClipboardSync(System.Windows.Threading.Dispatcher.CurrentDispatcher, client);
    try
    {
        sync.Start();
        await Task.Delay(100);
        AssertEqual(0, handler.Copies.Count); // Establishing a baseline must not publish.
        var text = "remote-text-" + Guid.NewGuid();
        await sync.ReceiveAsync(TestRemoteText("remote-text", text, 1));
        await Task.Delay(100);
        AssertEqual(text, System.Windows.Clipboard.GetText());
        AssertEqual(0, handler.Copies.Count); // WM_CLIPBOARDUPDATE for an application is not a new copy.
        await WriteClipboardAsync(() => System.Windows.Clipboard.SetText(text));
        await Task.Delay(200);
        AssertEqual(0, handler.Copies.Count); // A cross-device bridge echoing the phone item back is not a new copy.
        var image = System.Windows.Media.Imaging.BitmapSource.Create(2, 2, 96, 96,
            System.Windows.Media.PixelFormats.Bgra32, null, new byte[16], 8);
        image.Freeze();
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder();
        encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(image));
        using var pngBuffer = new MemoryStream();
        encoder.Save(pngBuffer);
        var png = pngBuffer.ToArray();
        var imageItem = new ClipboardItemPayload("remote-image", "phone-test", "image/png", null, "test.png", png.Length, true,
            RelaySequence: 2, ContentHash: Convert.ToHexString(SHA256.HashData(png)).ToLowerInvariant(),
            ExpiresAt: DateTimeOffset.UtcNow.AddMinutes(5), Base64: Convert.ToBase64String(png));
        await sync.ReceiveAsync(imageItem);
        await Task.Delay(100);
        AssertTrue(System.Windows.Clipboard.ContainsImage(), "Remote image must apply on the STA dispatcher.");
        AssertEqual(0, handler.Copies.Count);
        handler.Failures = 1;
        System.Windows.Clipboard.SetText("local-retry");
        await WaitForClipboardAsync(() => handler.Copies.Count == 2);
        var attempts = handler.Copies.ToArray();
        AssertEqual(attempts[0].ItemId, attempts[1].ItemId);
        AssertEqual(attempts[0].ExpiresAt, attempts[1].ExpiresAt);
        sync.NewSession();
        var delayed = sync.ReceiveAsync(imageItem with { ItemId = "delayed", RelaySequence = 1, Base64 = null });
        await WaitForClipboardAsync(() => handler.ImageRequested);
        await sync.ReceiveAsync(TestRemoteText("new-text", "newer than image", 2));
        handler.Image.TrySetResult(png);
        await delayed;
        AssertEqual("newer than image", System.Windows.Clipboard.GetText());
        sync.NewSession();
        handler.Image = new(TaskCreationOptions.RunContinuationsAsynchronously);
        handler.ImageRequested = false;
        var replaced = sync.ReceiveAsync(imageItem with { ItemId = "old-session", RelaySequence = 1, Base64 = null });
        await WaitForClipboardAsync(() => handler.ImageRequested);
        sync.NewSession();
        await sync.ReceiveAsync(TestRemoteText("replacement", "new session", 1));
        handler.Image.TrySetResult(png);
        await replaced;
        AssertEqual("new session", System.Windows.Clipboard.GetText());
        var published = handler.Copies.Count;
        await WriteClipboardAsync(() => System.Windows.Clipboard.SetText("local-final"));
        await WaitForClipboardAsync(() => handler.Copies.Count == published + 1);
        await WriteClipboardAsync(() => System.Windows.Clipboard.SetText("local-final"));
        await Task.Delay(200);
        AssertEqual(published + 1, handler.Copies.Count); // Re-setting just-published content must not loop back to the phone.
    }
    finally { sync.Dispose(); await WriteClipboardAsync(() => System.Windows.Clipboard.SetDataObject(saved, true)); }
});

// Windows clipboard history briefly holds the clipboard after each write, so back-to-back test writes retry.
static async Task WriteClipboardAsync(Action write)
{
    for (var attempt = 0; ; attempt++)
    {
        try { write(); return; }
        catch (System.Runtime.InteropServices.COMException) when (attempt < 20) { await Task.Delay(100); }
    }
}

static ClipboardItemPayload TestRemoteText(string id, string text, long sequence) => new(id, "phone-test", "text/plain", text,
    null, System.Text.Encoding.UTF8.GetByteCount(text), true, RelaySequence: sequence,
    ContentHash: Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(text))).ToLowerInvariant(),
    ExpiresAt: DateTimeOffset.UtcNow.AddMinutes(5));

static async Task WaitForClipboardAsync(Func<bool> condition)
{
    var deadline = DateTime.UtcNow.AddSeconds(5);
    while (!condition()) { if (DateTime.UtcNow >= deadline) throw new TimeoutException("Clipboard test timed out."); await Task.Delay(20); }
}

static Task RunOnStaAsync(Func<Task> test)
{
    var result = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    var thread = new Thread(() => {
        var dispatcher = System.Windows.Threading.Dispatcher.CurrentDispatcher;
        SynchronizationContext.SetSynchronizationContext(new System.Windows.Threading.DispatcherSynchronizationContext(dispatcher));
        dispatcher.BeginInvoke(new Action(async () => {
            try { await test(); result.TrySetResult(); } catch (Exception failure) { result.TrySetException(failure); }
            finally { dispatcher.BeginInvokeShutdown(System.Windows.Threading.DispatcherPriority.Background); }
        }));
        System.Windows.Threading.Dispatcher.Run();
    }) { IsBackground = true };
    thread.SetApartmentState(ApartmentState.STA);
    thread.Start();
    return result.Task.WaitAsync(TimeSpan.FromSeconds(20));
}

static void MediaCatalogStoresNewestPageOrder()
{
    var catalog = new MediaCatalog();
    catalog.UpsertPage(new MediaIndexPagePayload(
        "phone-1",
        null,
        new[]
        {
            new MediaItemPayload("older", "old.jpg", "image/jpeg", 10, 10, 10, DateTimeOffset.Parse("2026-01-01T00:00:00Z")),
            new MediaItemPayload("newer", "new.jpg", "image/jpeg", 20, 20, 20, DateTimeOffset.Parse("2026-07-02T00:00:00Z"))
        }));

    var page = catalog.GetPage("phone-1", pageSize: 1);
    AssertEqual("newer", page.Items.Single().MediaId);
}

static void MediaCursorSurvivesDeletionAndRejectsSnapshotMismatch()
{
    var catalog = new MediaCatalog();
    var day = DateTimeOffset.Parse("2026-07-15T00:00:00Z");
    catalog.UpsertPage(new MediaIndexPagePayload(
        "phone-media",
        null,
        new[]
        {
            new MediaItemPayload("a", "a.jpg", "image/jpeg", 1, 10, 10, day.AddHours(3), "camera", "Camera", 90),
            new MediaItemPayload("b", "b.jpg", "image/jpeg", 1, 10, 10, day.AddHours(2), "camera", "Camera"),
            new MediaItemPayload("c", "c.jpg", "image/jpeg", 1, 10, 10, day.AddHours(1), "camera", "Camera")
        },
        SnapshotId: "snapshot-1",
        SnapshotStart: true,
        SnapshotComplete: true));

    AssertTrue(catalog.TryGetPage(
        "phone-media", 2, null, "camera", null, out var first, out _),
        "Expected first stable cursor page.");
    AssertSequence(new[] { "a", "b" }, first.Items.Select(item => item.MediaId).ToArray());
    AssertTrue(!string.IsNullOrWhiteSpace(first.Cursor), "Expected continuation cursor.");

    catalog.RemoveItem("phone-media", "b");
    catalog.UpsertPage(new MediaIndexPagePayload(
        "phone-media",
        null,
        new[]
        {
            new MediaItemPayload("new", "new.jpg", "image/jpeg", 1, 10, 10, day.AddHours(4), "camera", "Camera")
        },
        SnapshotId: "snapshot-1"));
    AssertTrue(catalog.TryGetPage(
        "phone-media", 2, first.Cursor, "camera", null, out var second, out _),
        "Deleted cursor item must still page from its encoded sort tuple.");
    AssertSequence(new[] { "c" }, second.Items.Select(item => item.MediaId).ToArray());

    catalog.UpsertPage(new MediaIndexPagePayload(
        "phone-media",
        null,
        new[] { new MediaItemPayload("z", "z.jpg", "image/jpeg", 1, 10, 10, day) },
        SnapshotId: "snapshot-2",
        SnapshotStart: true,
        SnapshotComplete: true));
    AssertFalse(catalog.TryGetPage(
        "phone-media", 2, first.Cursor, "camera", null, out _, out var error),
        "Expected old snapshot cursor rejection.");
    AssertTrue(error?.Contains("different snapshot", StringComparison.OrdinalIgnoreCase) == true,
        "Expected deterministic cursor error.");
}

static void ThumbnailCacheReturnsCachedBytes()
{
    var directory = Path.Combine(Path.GetTempPath(), $"pairly-thumbs-{Guid.NewGuid():N}");
    try
    {
        var cache = new MediaThumbnailCache(directory);
        var item = new MediaItemPayload(
            "media-1",
            "photo.jpg",
            "image/jpeg",
            123,
            1920,
            1080,
            DateTimeOffset.Parse("2026-07-07T12:00:00Z"));
        var expected = new byte[] { 4, 5, 6 };

        cache.Store("phone-1", item, expected);

        AssertTrue(cache.TryGet("phone-1", item, out var actual), "Expected thumbnail cache hit.");
        AssertSequence(expected, actual);
    }
    finally
    {
        TryDeleteDirectory(directory);
    }
}

static void NotificationStoreScopesItemsPerDevice()
{
    var store = new NotificationStore();
    var postedAt = DateTimeOffset.Parse("2026-07-02T00:00:00Z");
    store.Upsert("phone-a", new NotificationPayload("n-1", "pkg", "App", "A", "One", true, postedAt));
    store.Upsert("phone-b", new NotificationPayload("n-1", "pkg", "App", "B", "Two", false, postedAt));

    AssertEqual("A", store.GetActive("phone-a").Single().Title);
    AssertEqual("B", store.GetActive("phone-b").Single().Title);
    AssertTrue(store.Remove("phone-a", "n-1"), "Expected phone-a notification removal.");
    AssertEqual(0, store.GetActive("phone-a").Count);
    AssertEqual(1, store.GetActive("phone-b").Count);
}

static void NotificationPayloadCarriesControlsMetadata()
{
    var payload = new NotificationPayload(
        "n-1",
        "com.example",
        "Example",
        "Title",
        "Body",
        true,
        DateTimeOffset.Parse("2026-07-07T12:00:00Z"),
        Clearable: true,
        Category: "msg",
        GroupKey: "group-1");
    var envelope = ProtocolEnvelope.Create(
        ProtocolMessageTypes.NotificationUpsert,
        "phone-1",
        1,
        payload);

    var roundTripped = JsonProtocolSerializer.Deserialize(JsonProtocolSerializer.Serialize(envelope))
        .ReadPayload<NotificationPayload>();

    AssertTrue(roundTripped?.Clearable == true, "Expected clearable metadata to round-trip.");
    AssertEqual("msg", roundTripped?.Category);
    AssertEqual("group-1", roundTripped?.GroupKey);
}

static void NotificationSnapshotsReconcileRemovals()
{
    var store = new NotificationStore();
    var postedAt = DateTimeOffset.Parse("2026-07-15T12:00:00Z");
    store.Upsert("phone-a", new NotificationPayload("old", "pkg", "App", "Old", "Gone", false, postedAt));
    store.Upsert("phone-a", new NotificationPayload("keep", "pkg", "App", "Keep", "Current", true, postedAt));

    var snapshot = new NotificationSnapshotPayload("snapshot-a", postedAt.AddMinutes(1));
    store.BeginSnapshot("phone-a", snapshot);
    store.Upsert("phone-a", new NotificationPayload(
        "keep",
        "pkg",
        "App",
        "Keep updated",
        "Current",
        true,
        postedAt.AddMinutes(1),
        GroupKey: "conversation",
        IsOngoing: false,
        ChannelId: "messages",
        ConversationTitle: "Family",
        TextLines: new[] { "One", "Two" },
        Actions: new[] { new NotificationActionDescriptorPayload("reply", "Reply", AllowsReply: true) },
        SnapshotId: "snapshot-a"));
    store.Upsert("phone-a", new NotificationPayload(
        "new",
        "pkg",
        "App",
        "New",
        "Arrived",
        false,
        postedAt.AddMinutes(2),
        SnapshotId: "snapshot-a"));

    AssertEqual(1, store.EndSnapshot("phone-a", snapshot));
    AssertSequence(
        new[] { "new", "keep" },
        store.GetActive("phone-a").Select(item => item.NotificationId).ToArray());
    AssertEqual("messages", store.GetActive("phone-a").Last().ChannelId);
    AssertEqual(0, store.EndSnapshot(
        "phone-a",
        new NotificationSnapshotPayload("wrong", postedAt)));
}

static void SmsStoreScopesThreadsPerDevice()
{
    var store = new SmsStore();
    store.UpsertThreads("phone-a", new SmsThreadPagePayload(null, new[]
    {
        new SmsThreadPayload("thread-1", "Alice", "+1", "A", DateTimeOffset.Parse("2026-07-02T00:00:00Z"), 0)
    }));
    store.UpsertThreads("phone-b", new SmsThreadPagePayload(null, new[]
    {
        new SmsThreadPayload("thread-1", "Bob", "+2", "B", DateTimeOffset.Parse("2026-07-03T00:00:00Z"), 0)
    }));
    store.UpsertMessages("phone-a", new SmsMessagePagePayload("thread-1", null, new[]
    {
        new SmsMessagePayload("msg-1", "thread-1", "+1", "hello a", false, DateTimeOffset.Parse("2026-07-02T00:00:00Z"))
    }));
    store.UpsertMessages("phone-b", new SmsMessagePagePayload("thread-1", null, new[]
    {
        new SmsMessagePayload("msg-1", "thread-1", "+2", "hello b", false, DateTimeOffset.Parse("2026-07-03T00:00:00Z"))
    }));

    AssertEqual("Alice", store.GetThreads("phone-a").Single().DisplayName);
    AssertEqual("Bob", store.GetThreads("phone-b").Single().DisplayName);
    AssertEqual("hello a", store.GetMessages("phone-a", "thread-1").Single().Body);
    AssertEqual("hello b", store.GetMessages("phone-b", "thread-1").Single().Body);
}

static void PermissionMapperDistinguishesBlockedRequests()
{
    AssertEqual(PermissionState.NotAvailable, PermissionStateMapper.FromAndroidGrant(false, false, false));
    AssertEqual(PermissionState.Granted, PermissionStateMapper.FromAndroidGrant(true, true, false));
    AssertEqual(PermissionState.Denied, PermissionStateMapper.FromAndroidGrant(true, false, true));
    AssertEqual(PermissionState.NeedsUserAction, PermissionStateMapper.FromAndroidGrant(true, false, false));
}

static async Task EmbeddedHostStartsAndStops()
{
    var directory = Path.Combine(Path.GetTempPath(), $"pairly-host-{Guid.NewGuid():N}");
    var server = new PairlyServer();
    try
    {
        await server.StartAsync(
            "http://127.0.0.1:0",
            CancellationToken.None,
            directory);
        AssertTrue(server.IsRunning, "Expected embedded server to be running.");

        await server.StopAsync();
        AssertFalse(server.IsRunning, "Expected embedded server to stop.");
    }
    finally
    {
        await server.StopAsync();
        TryDeleteDirectory(directory);
    }
}

static async Task SecureHostTerminatesPinnedWss()
{
    var directory = Path.Combine(Path.GetTempPath(), $"pairly-secure-host-{Guid.NewGuid():N}");
    var app = PairlyServer.BuildSecureForTesting(
        "http://127.0.0.1:0",
        "https://127.0.0.1:0",
        directory);
    try
    {
        await app.StartAsync();
        var identity = app.Services.GetRequiredService<HostIdentity>();
        var httpsUrl = app.Urls.Single(url => url.StartsWith("https://", StringComparison.OrdinalIgnoreCase));
        using var socket = new ClientWebSocket();
        socket.Options.Proxy = null;
        socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
        {
            if (certificate is null)
            {
                return false;
            }

            var fingerprint = Convert.ToHexString(SHA256.HashData(certificate.GetRawCertData())).ToLowerInvariant();
            return string.Equals(fingerprint, identity.CertificateFingerprint, StringComparison.Ordinal);
        };
        var websocketUrl = new Uri($"wss://{new Uri(httpsUrl).Authority}/ws/v1/session");
        await socket.ConnectAsync(websocketUrl, CancellationToken.None);

        var buffer = new byte[4096];
        using var message = new MemoryStream();
        WebSocketReceiveResult received;
        do
        {
            received = await socket.ReceiveAsync(buffer, CancellationToken.None);
            message.Write(buffer, 0, received.Count);
        } while (!received.EndOfMessage);

        var challenge = JsonProtocolSerializer.Deserialize(System.Text.Encoding.UTF8.GetString(message.ToArray()));
        AssertEqual(ProtocolMessageTypes.SessionChallenge, challenge.Type);
        AssertEqual(identity.HostId, challenge.ReadPayload<SessionChallengePayload>()?.HostId);
        AssertTrue(PairlyServer.DefaultHttpUrl.StartsWith("http://127.0.0.1", StringComparison.Ordinal),
            "Management HTTP must bind loopback only.");
        await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "test complete", CancellationToken.None);
    }
    finally
    {
        await app.StopAsync();
        await app.DisposeAsync();
        TryDeleteDirectory(directory);
    }
}

static async Task PhoneImageIngressRelaysImmediately()
{
    var directory = Path.Combine(Path.GetTempPath(), $"pairly-clipboard-host-{Guid.NewGuid():N}");
    var app = PairlyServer.BuildSecureForTesting("http://127.0.0.1:0", "https://127.0.0.1:0", directory);
    using var source = new ClientWebSocket();
    using var peer = new ClientWebSocket();
    using var desktop = new ClientWebSocket();
    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(10));
    try
    {
        await app.StartAsync(deadline.Token);
        var address = app.Urls.Single(url => url.StartsWith("http://", StringComparison.Ordinal));
        var endpoint = new Uri($"ws://{new Uri(address).Authority}/ws/v1/session");
        var registry = app.Services.GetRequiredService<DeviceRegistry>();
        desktop.Options.Proxy = null;
        await desktop.ConnectAsync(new Uri($"{endpoint}?role=pc-app"), deadline.Token);
        var desktopReady = await ReceiveAsync(desktop);
        AssertEqual(ProtocolMessageTypes.SessionReady, desktopReady.Type);
        var desktopId = desktopReady.ReadPayload<SessionReadyPayload>()!.DeviceId;
        AssertTrue(desktopId.StartsWith("pc-app-", StringComparison.Ordinal), "Desktop must know its assigned session identity.");
        AssertEqual(ProtocolMessageTypes.HostEndpoints, (await ReceiveAsync(desktop)).Type);
        AssertEqual(ProtocolMessageTypes.SessionChallenge, (await ReceiveAsync(desktop)).Type);
        await SendAsync(desktop, ProtocolMessageTypes.HeartbeatPing, desktopId, new HeartbeatPayload(desktopId, DateTimeOffset.UtcNow));
        AssertEqual(ProtocolMessageTypes.HeartbeatPong, (await ReceiveAsync(desktop)).Type);
        await PairAsync(source, "phone-image-source");
        await PairAsync(peer, "phone-image-peer");
        var bytes = TestPng(96, 72, 512);
        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        // A real phone's clock can run ahead of the host by a few seconds.
        var expiry = DateTimeOffset.UtcNow.AddMinutes(10).AddSeconds(5);
        var item = new ClipboardItemPayload("phone-gallery-copy", "phone-image-source", "image/png", null,
            "clipboard.png", bytes.Length, true, ContentHash: hash, ExpiresAt: expiry, TransferId: "gallery-transfer");
        var start = new ContentTransferStartPayload("gallery-transfer", item.ItemId, item.SourceDeviceId, "image/png",
            item.FileName, bytes.Length, ContentTransferStore.MaxChunkBytes, 1, hash, expiry, "clipboard");
        await SendAsync(source, ProtocolMessageTypes.ClipboardItem, item.SourceDeviceId, item);
        await SendAsync(source, ProtocolMessageTypes.ContentTransferStart, item.SourceDeviceId, start);
        await SendAsync(source, ProtocolMessageTypes.ContentTransferChunk, item.SourceDeviceId,
            new ContentTransferChunkPayload(start.TransferId, 0, Convert.ToBase64String(bytes), hash));
        await SendAsync(source, ProtocolMessageTypes.ContentTransferComplete, item.SourceDeviceId,
            new ContentTransferCompletePayload(start.TransferId, hash));
        var confirmation = await ReceiveAsync(source);
        AssertEqual(ProtocolMessageTypes.ClipboardItem, confirmation.Type);
        AssertEqual(item.ItemId, confirmation.ReadPayload<ClipboardItemPayload>()?.ItemId);
        AssertEqual(item.ItemId, (await ReceiveAsync(desktop)).ReadPayload<ClipboardItemPayload>()?.ItemId);
        foreach (var expected in new[] { ProtocolMessageTypes.ClipboardItem, ProtocolMessageTypes.ContentTransferStart,
                     ProtocolMessageTypes.ContentTransferChunk, ProtocolMessageTypes.ContentTransferComplete })
            AssertEqual(expected, (await ReceiveAsync(peer)).Type);
        AssertEqual(1, app.Services.GetRequiredService<ClipboardHub>().GetRecent().Count);
        var published = app.Services.GetRequiredService<ClipboardHub>().GetRecent().Single();
        AssertTrue(app.Services.GetRequiredService<ContentTransferStore>().TryGetCompleted(start.TransferId, out var completed), "Verified image must remain available.");
        AssertTrue(completed.Start.ExpiresAt <= DateTimeOffset.UtcNow.Add(ContentTransferStore.MaxLifetime), "Host must bound image lifetime even when the phone clock is ahead.");
        AssertEqual(completed.Start.ExpiresAt, published.ExpiresAt);
        await SendAsync(source, ProtocolMessageTypes.ClipboardItem, item.SourceDeviceId, item);
        var retry = (await ReceiveAsync(source)).ReadPayload<ClipboardItemPayload>()!;
        AssertEqual(published.ItemId, retry.ItemId);
        AssertEqual(published.RelaySequence, retry.RelaySequence);
        AssertEqual(published.ExpiresAt, retry.ExpiresAt);

        async Task PairAsync(ClientWebSocket socket, string deviceId)
        {
            socket.Options.Proxy = null;
            await socket.ConnectAsync(endpoint, deadline.Token);
            AssertEqual(ProtocolMessageTypes.SessionChallenge, (await ReceiveAsync(socket)).Type);
            var ticket = registry.OpenPairingTicket(TimeSpan.FromMinutes(1));
            await SendAsync(socket, ProtocolMessageTypes.PairHello, deviceId,
                new PairHelloPayload(ticket.Code, deviceId, deviceId, "android", new(true, true, true, true, true)));
            AssertTrue((await ReceiveAsync(socket)).ReadPayload<PairConfirmPayload>()?.Accepted == true, "Pairing must succeed.");
            AssertEqual(ProtocolMessageTypes.SessionReady, (await ReceiveAsync(socket)).Type);
            AssertEqual(ProtocolMessageTypes.HostEndpoints, (await ReceiveAsync(socket)).Type);
        }
        async Task SendAsync<T>(ClientWebSocket socket, string type, string deviceId, T payload) =>
            await socket.SendAsync(JsonSerializer.SerializeToUtf8Bytes(ProtocolEnvelope.Create(type, deviceId, 1, payload),
                JsonProtocolSerializer.Options), WebSocketMessageType.Text, true, deadline.Token);
        async Task<ProtocolEnvelope> ReceiveAsync(ClientWebSocket socket)
        {
            var buffer = new byte[16 * 1024];
            using var message = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await socket.ReceiveAsync(buffer, deadline.Token);
                message.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);
            var envelope = JsonProtocolSerializer.Deserialize(System.Text.Encoding.UTF8.GetString(message.ToArray()));
            if (envelope.Type == ProtocolMessageTypes.Error)
                throw new InvalidOperationException($"Host rejected clipboard ingress: {envelope.ReadPayload<ErrorPayload>()}");
            return envelope;
        }
    }
    finally
    {
        source.Abort();
        peer.Abort();
        desktop.Abort();
        await app.StopAsync();
        await app.DisposeAsync();
        TryDeleteDirectory(directory);
    }
}

static void SessionHubRoutesClipboardOnlyToRealDevices()
{
    var sessions = new WebSocketSessionHub();
    sessions.Attach("phone-a", new System.Net.WebSockets.ClientWebSocket());
    sessions.Attach("phone-b", new System.Net.WebSockets.ClientWebSocket());
    sessions.Attach("pc-mirror-123", new System.Net.WebSockets.ClientWebSocket());
    sessions.Attach("phone-a:mirror:123", new System.Net.WebSockets.ClientWebSocket());

    AssertSequence(new[] { "phone-a", "phone-b" }, sessions.ConnectedDeviceIds);
    AssertTrue(sessions.HasSession("pc-mirror-123"), "Expected transient mirror session to remain addressable.");
}

static void SessionHubKeepsNewerReconnectWhenOldSocketCloses()
{
    var sessions = new WebSocketSessionHub();
    var oldSocket = new System.Net.WebSockets.ClientWebSocket();
    var newSocket = new System.Net.WebSockets.ClientWebSocket();

    sessions.Attach("phone-a", oldSocket);
    sessions.Attach("phone-a", newSocket);
    sessions.Detach("phone-a", oldSocket);

    AssertTrue(sessions.HasSession("phone-a"), "Expected closing old socket not to detach newer reconnect.");
    sessions.Detach("phone-a", newSocket);
    AssertFalse(sessions.HasSession("phone-a"), "Expected closing active socket to detach the device.");
}

static async Task SessionHubBoundsSlowDeviceWriterQueue()
{
    using var sessions = new WebSocketSessionHub();
    var oldSocket = new BlockingWebSocket();
    var oldGeneration = sessions.Attach("phone-slow", oldSocket);
    var envelope = ProtocolEnvelope.Create(
        ProtocolMessageTypes.DeviceSyncRequest,
        "pc-host",
        1,
        new DeviceSyncRequestPayload(Array.Empty<string>()));
    AssertTrue(await sessions.SendAsync("phone-slow", envelope, CancellationToken.None), "Expected first queued send.");
    await oldSocket.SendStarted.WaitAsync(TimeSpan.FromSeconds(1));

    var rejected = 0;
    for (var index = 0; index < WebSocketSessionHub.QueueCapacity + 25; index++)
    {
        if (!await sessions.SendAsync("phone-slow", envelope with { Sequence = index + 2 }, CancellationToken.None))
        {
            rejected++;
        }
    }

    AssertTrue(rejected > 0, "Expected bounded writer queue to reject excess work for a slow phone.");
    AssertTrue(sessions.GetStatuses().Single().DroppedMessages >= rejected, "Expected honest queue drop diagnostics.");

    var replacement = new BlockingWebSocket();
    var newGeneration = sessions.Attach("phone-slow", replacement);
    AssertTrue(newGeneration > oldGeneration, "Expected monotonically increasing session generation.");
    AssertFalse(sessions.Detach("phone-slow", oldSocket), "Old socket closure must not detach replacement generation.");
    AssertTrue(sessions.IsCurrent("phone-slow", replacement, newGeneration), "Expected replacement generation ownership.");
    oldSocket.Release();
    replacement.Release();
}

static void MirrorRegistryKeepsOneActiveSessionPerDevice()
{
    var mirrors = new MirrorSessionRegistry();
    var prepared = mirrors.Prepare("phone-a");
    var active = mirrors.Start("phone-a", prepared.SessionId);
    var secondPrepare = mirrors.Prepare("phone-a");

    AssertEqual(prepared.SessionId, active?.SessionId);
    AssertEqual(active?.SessionId, secondPrepare.SessionId);

    var wrongDevice = mirrors.Start("phone-b", prepared.SessionId);
    AssertTrue(wrongDevice is null, "Expected mirror start to reject mismatched device/session.");

    var stopped = mirrors.Stop("phone-a");
    AssertEqual(prepared.SessionId, stopped?.SessionId);
    AssertFalse(stopped?.Active == true, "Expected stopped mirror session to be inactive.");
}

static void MirrorRegistryEnforcesGlobalSessionAndLease()
{
    var mirrors = new MirrorSessionRegistry();
    var preparedA = mirrors.Prepare("phone-a");
    var activeA = mirrors.StartExclusive("phone-a", preparedA.SessionId);
    AssertTrue(activeA.Session?.Active == true, "Expected first active mirror.");

    var preparedB = mirrors.Prepare("phone-b");
    var activeB = mirrors.StartExclusive("phone-b", preparedB.SessionId);
    AssertTrue(activeB.Session?.Active == true, "Expected second selected mirror.");
    AssertEqual(1, activeB.SupersededSessions.Count);
    AssertEqual("phone-a", activeB.SupersededSessions.Single().DeviceId);
    AssertFalse(mirrors.Get(preparedA.SessionId)?.Active == true, "Starting phone B must stop phone A globally.");
    AssertEqual("superseded", mirrors.Get(preparedA.SessionId)?.State);

    var expiresAt = activeB.Session?.LeaseExpiresAt
        ?? throw new InvalidOperationException("Expected mirror lease expiration.");
    var expired = mirrors.PruneExpired(expiresAt.AddMilliseconds(1));
    AssertTrue(expired.Any(item => item.SessionId == preparedB.SessionId), "Expected deterministic lease cleanup.");
    AssertTrue(mirrors.Get(preparedB.SessionId) is null, "Expired mirror session must be removed.");
}

static async Task MediaContentBrokerCompletesPendingResponse()
{
    var broker = new MediaContentBroker();
    var wait = broker.WaitForAsync(
        "req-1",
        "phone-a",
        "media-1",
        "thumb",
        TimeSpan.FromSeconds(1),
        CancellationToken.None);
    var completed = broker.AcceptResponse("phone-a", new MediaContentResponsePayload(
        "req-1",
        "media-1",
        "thumb",
        "image/jpeg",
        "photo.jpg",
        Convert.ToBase64String(new byte[] { 1, 2, 3 }),
        3,
        null));

    var delivery = await wait;
    AssertTrue(completed.Accepted, "Expected media response to complete a pending request.");
    AssertEqual("media-1", delivery?.Response.MediaId);
    AssertEqual(3L, delivery?.Response.ByteCount);
    AssertTrue(delivery?.Transfer is null, "Expected legacy Base64 response to remain a v1 fallback.");
}

static async Task MediaBrokerStreamsVerifiedChunksBeyondClipboardLimit()
{
    var broker = new MediaContentBroker();
    var store = new ContentTransferStore();
    const string requestId = "media-request-2";
    const string transferId = "media-transfer-2";
    const string deviceId = "phone-a";
    var bytes = new byte[ContentTransferStore.MaxTransferBytes + 17];
    for (var index = 0; index < bytes.Length; index++)
    {
        bytes[index] = (byte)(index % 251);
    }

    var contentHash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    var wait = broker.WaitForAsync(
        requestId,
        deviceId,
        "media-2",
        "content",
        TimeSpan.FromSeconds(5),
        CancellationToken.None);
    var metadata = new MediaContentResponsePayload(
        requestId,
        "media-2",
        "content",
        "image/jpeg",
        "photo.jpg",
        Base64: null,
        bytes.LongLength,
        Error: null,
        transferId,
        contentHash);
    AssertTrue(
        broker.AcceptResponse(deviceId, metadata).Accepted,
        "Expected chunked response metadata to be accepted.");

    var chunkSize = ContentTransferStore.MaxChunkBytes;
    var chunkCount = (int)Math.Ceiling(bytes.Length / (double)chunkSize);
    var start = new ContentTransferStartPayload(
        transferId,
        requestId,
        deviceId,
        "image/jpeg",
        "photo.jpg",
        bytes.LongLength,
        chunkSize,
        chunkCount,
        contentHash,
        DateTimeOffset.UtcNow.AddMinutes(5),
        Purpose: "media");
    AssertTrue(broker.CanAcceptTransfer(deviceId, start).Accepted, "Expected media transfer metadata to match.");
    AssertTrue(store.Begin(start).Accepted, "Expected media original larger than clipboard's 8 MiB limit to begin.");

    for (var index = 0; index < chunkCount; index++)
    {
        var offset = index * chunkSize;
        var count = Math.Min(chunkSize, bytes.Length - offset);
        var chunkBytes = bytes.AsSpan(offset, count).ToArray();
        AssertTrue(store.Append(new ContentTransferChunkPayload(
            transferId,
            index,
            Convert.ToBase64String(chunkBytes),
            Convert.ToHexString(SHA256.HashData(chunkBytes)).ToLowerInvariant())).Accepted,
            $"Expected media chunk {index} to be accepted.");
    }

    var completed = store.Complete(new ContentTransferCompletePayload(transferId, contentHash));
    AssertTrue(completed.Accepted && completed.Transfer is not null, "Expected verified media transfer completion.");
    AssertTrue(
        broker.CompleteTransfer(deviceId, completed.Transfer!).Accepted,
        "Expected completed chunks to release the pending media response.");

    var delivery = await wait;
    AssertEqual(transferId, delivery?.Transfer?.Start.TransferId);
    AssertEqual(bytes.LongLength, delivery?.Transfer?.Chunks.Sum(chunk => (long)chunk.Length));
    AssertTrue(delivery?.Response.Base64 is null, "Expected v2 media delivery not to materialize whole-file Base64.");
}

static async Task CommandResultBrokerCompletesAndTimesOut()
{
    var broker = new CommandResultBroker();
    var wait = broker.WaitForAsync("cmd-1", TimeSpan.FromSeconds(1), CancellationToken.None);
    AssertTrue(
        broker.Complete(new CommandResultPayload("cmd-1", "sms", true, "SMS sent.")),
        "Expected command result to complete.");
    var result = await wait;
    AssertTrue(result?.Success == true, "Expected successful command result.");

    var timeout = await broker.WaitForAsync("cmd-2", TimeSpan.FromMilliseconds(20), CancellationToken.None);
    AssertTrue(timeout is null, "Expected command result wait to time out.");
}

// Golden vectors shared with dev.pairly.streamcore. Both sides assert the same strings,
// so an encoder edited on one side alone breaks that side's build instead of producing a stream
// the other end silently misreads.
static void DeviceRegistrySweepsAbandonedTempFiles()
{
    var directory = Path.Combine(Path.GetTempPath(), "Pairly", $"registry-sweep-{Guid.NewGuid():N}");
    Directory.CreateDirectory(directory);
    try
    {
        var store = Path.Combine(directory, "devices.json");
        var stale = $"{store}.{Guid.NewGuid():N}.tmp";
        var fresh = $"{store}.{Guid.NewGuid():N}.tmp";
        var unrelated = Path.Combine(directory, "other.json.abc.tmp");
        foreach (var file in new[] { stale, fresh, unrelated })
        {
            File.WriteAllText(file, "{}");
        }

        File.SetLastWriteTimeUtc(stale, DateTime.UtcNow - TimeSpan.FromHours(3));
        File.SetLastWriteTimeUtc(unrelated, DateTime.UtcNow - TimeSpan.FromHours(3));

        _ = new DeviceRegistry(store);

        AssertFalse(File.Exists(stale), "An abandoned temp file must be removed on start.");
        AssertTrue(File.Exists(fresh), "A temp file that may still be mid-write must be left alone.");
        AssertTrue(File.Exists(unrelated), "Only this store's temp files may be touched.");
    }
    finally
    {
        TryDeleteDirectory(directory);
    }
}

static void StreamTicketsAreSingleUseAndStreamBound()
{
    var clock = new TestTimeProvider(DateTimeOffset.UnixEpoch);
    var store = new StreamTicketStore(clock);

    var ticket = store.Issue("phone-a", "session-1", 42UL, StreamKind.Video);
    AssertEqual(StreamFraming.TicketBytes, ticket.Value.Length);
    AssertEqual(1, store.Count);

    // A ticket minted for one stream must not open another, even inside its lifetime.
    AssertTrue(store.Redeem(ticket.Value, StreamKind.Control, 42UL) is null, "Kind must be bound.");
    AssertTrue(store.Redeem(ticket.Value, StreamKind.Video, 7UL) is null, "Stream id must be bound.");

    var reissued = store.Issue("phone-a", "session-1", 42UL, StreamKind.Video);
    var redeemed = store.Redeem(reissued.Value, StreamKind.Video, 42UL);
    AssertTrue(redeemed is not null, "A matching ticket must be redeemable.");
    AssertEqual("phone-a", redeemed!.DeviceId);

    // Single use: a replayed ticket is worthless.
    AssertTrue(store.Redeem(reissued.Value, StreamKind.Video, 42UL) is null, "A ticket must not be reusable.");

    var expiring = store.Issue("phone-b", "session-2", 1UL, StreamKind.Audio);
    clock.Advance(StreamTicketStore.Lifetime + TimeSpan.FromSeconds(1));
    AssertTrue(store.Redeem(expiring.Value, StreamKind.Audio, 1UL) is null, "An expired ticket must be refused.");

    var revocable = store.Issue("phone-c", "session-3", 2UL, StreamKind.Video);
    AssertEqual(1, store.RevokeDevice("phone-c"));
    AssertTrue(store.Redeem(revocable.Value, StreamKind.Video, 2UL) is null, "A revoked ticket must be refused.");
}

// The phone screen and app windows stream at the same time; each socket must meet its own format,
// whichever of the two arrives first.
static async Task MirrorStreamHubPairsConcurrentStreams()
{
    var (listener, tickets, identity, storage) = BuildStreamListener();
    var hub = new MirrorStreamHub(new DiagnosticsLog());
    var published = new System.Collections.Concurrent.ConcurrentDictionary<ulong, int>();
    var both = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    hub.VideoStreamReady += (connection, format) =>
    {
        AssertEqual(connection.StreamId, format.StreamId);
        published[format.StreamId] = format.Width;
        if (published.Count == 2)
        {
            both.TrySetResult();
        }
    };

    var accepted = new System.Collections.Concurrent.ConcurrentDictionary<ulong, TaskCompletionSource>();
    listener.ConnectionAccepted += (connection, _) =>
    {
        hub.AttachVideoStream(connection);
        accepted.GetOrAdd(connection.StreamId, _ => new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously)).TrySetResult();
        return Task.CompletedTask;
    };

    var clients = new List<TcpClient>();
    try
    {
        await listener.StartAsync(CancellationToken.None);

        // Stream 20's format is known before its socket; stream 30's socket comes first and waits.
        hub.AnnounceFormat("phone-a", new MirrorVideoFormatPayload("s", 20UL, "h264", 720, 1600, 0, 0));
        foreach (var streamId in new[] { 20UL, 30UL })
        {
            var ticket = tickets.Issue("phone-a", "s", streamId, StreamKind.Video);
            var client = new TcpClient();
            clients.Add(client);
            await client.ConnectAsync(IPAddress.Loopback, StreamListener.Port);
            await client.GetStream().WriteAsync(StreamFraming.EncodeHandshake(StreamKind.Video, ticket.Value, streamId));
            await accepted.GetOrAdd(streamId, _ => new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously))
                .Task.WaitAsync(TimeSpan.FromSeconds(5));
        }

        AssertFalse(published.ContainsKey(30UL), "Stream 30 must wait for its own format, not borrow stream 20's.");
        hub.AnnounceFormat("phone-a", new MirrorVideoFormatPayload("s", 30UL, "h264", 1080, 1920, 0, 25));

        await both.Task.WaitAsync(TimeSpan.FromSeconds(5));
        AssertEqual(720, published[20UL]);
        AssertEqual(1080, published[30UL]);
    }
    finally
    {
        clients.ForEach(client => client.Dispose());
        await listener.StopAsync(CancellationToken.None);
        identity.Dispose();
        TryDeleteDirectory(storage);
    }
}

static async Task StreamListenerAdmitsPinnedSocketAndReadsRecords()
{
    var (listener, tickets, identity, storage) = BuildStreamListener();
    var accepted = new TaskCompletionSource<StreamConnection>(TaskCreationOptions.RunContinuationsAsynchronously);

    try
    {
        listener.ConnectionAccepted += (connection, _) =>
        {
            accepted.TrySetResult(connection);
            return Task.CompletedTask;
        };

        await listener.StartAsync(CancellationToken.None);

        var ticket = tickets.Issue("phone-a", "session-1", 9UL, StreamKind.Video);
        var payload = new byte[] { 0x00, 0x00, 0x00, 0x01, 0x65, 0xAA, 0xBB };

        using var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, StreamListener.Port);

        // Loopback is the ADB tunnel shape: plaintext, because it is already inside the
        // wireless-debugging trust boundary.
        var stream = client.GetStream();
        await stream.WriteAsync(StreamFraming.EncodeHandshake(StreamKind.Video, ticket.Value, 9UL));
        await stream.WriteAsync(StreamFraming.EncodeHeader(StreamRecordFlags.Keyframe, 0, 1_000L, payload.Length));
        await stream.WriteAsync(payload);
        await stream.FlushAsync();

        var connection = await accepted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        AssertEqual("phone-a", connection.DeviceId);
        AssertEqual(9UL, connection.StreamId);
        AssertEqual(StreamKind.Video, connection.Kind);
        AssertFalse(connection.IsEncrypted, "A loopback tunnel is not wrapped in TLS.");

        var record = await connection.ReadRecordAsync(CancellationToken.None);
        AssertTrue(record is not null, "Expected one record.");
        AssertTrue(record!.Header.IsKeyframe, "Keyframe flag must survive the socket.");
        AssertEqual(1_000L, record.Header.PresentationTimeUs);
        AssertSequence(payload, record.Payload);

        client.Close();
        AssertTrue(await connection.ReadRecordAsync(CancellationToken.None) is null, "A closed socket ends the stream.");
        await connection.DisposeAsync();
    }
    finally
    {
        await listener.StopAsync(CancellationToken.None);
        identity.Dispose();
        TryDeleteDirectory(storage);
    }
}

static async Task StreamListenerRefusesUnusableTicket()
{
    var (listener, tickets, identity, storage) = BuildStreamListener();
    var accepted = 0;

    try
    {
        listener.ConnectionAccepted += (connection, _) =>
        {
            Interlocked.Increment(ref accepted);
            return connection.DisposeAsync().AsTask();
        };

        await listener.StartAsync(CancellationToken.None);

        using (var forged = new TcpClient())
        {
            await forged.ConnectAsync(IPAddress.Loopback, StreamListener.Port);
            var stream = forged.GetStream();
            await stream.WriteAsync(StreamFraming.EncodeHandshake(StreamKind.Video, new byte[StreamFraming.TicketBytes], 1UL));
            await stream.FlushAsync();

            // The host drops the socket rather than answering, so the read completes empty.
            var response = new byte[1];
            var read = await stream.ReadAsync(response).AsTask().WaitAsync(TimeSpan.FromSeconds(5));
            AssertEqual(0, read);
        }

        // A ticket minted for video must not admit a control socket either.
        var ticket = tickets.Issue("phone-a", "session-1", 3UL, StreamKind.Video);
        using (var mismatched = new TcpClient())
        {
            await mismatched.ConnectAsync(IPAddress.Loopback, StreamListener.Port);
            var stream = mismatched.GetStream();
            await stream.WriteAsync(StreamFraming.EncodeHandshake(StreamKind.Control, ticket.Value, 3UL));
            await stream.FlushAsync();

            var response = new byte[1];
            var read = await stream.ReadAsync(response).AsTask().WaitAsync(TimeSpan.FromSeconds(5));
            AssertEqual(0, read);
        }

        AssertEqual(0, Volatile.Read(ref accepted));
    }
    finally
    {
        await listener.StopAsync(CancellationToken.None);
        identity.Dispose();
        TryDeleteDirectory(storage);
    }
}

static (StreamListener Listener, StreamTicketStore Tickets, HostIdentity Identity, string Storage) BuildStreamListener()
{
    var storage = Path.Combine(Path.GetTempPath(), "Pairly", $"stream-test-{Guid.NewGuid():N}");
    var identity = HostIdentity.LoadOrCreate(storage);
    var tickets = new StreamTicketStore();
    var listener = new StreamListener(
        identity,
        tickets,
        new DiagnosticsLog(),
        Microsoft.Extensions.Logging.Abstractions.NullLogger<StreamListener>.Instance);

    return (listener, tickets, identity, storage);
}

static void StreamFramingMatchesAndroidWireFormat()
{
    var ticket = new byte[StreamFraming.TicketBytes];
    for (var index = 0; index < ticket.Length; index++)
    {
        ticket[index] = (byte)index;
    }

    var handshake = StreamFraming.EncodeHandshake(StreamKind.Video, ticket, 0x0123456789ABCDEFUL);
    AssertEqual(StreamFraming.HandshakeBytes, handshake.Length);
    AssertEqual(
        "534C4B3101010000000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F0123456789ABCDEF",
        Convert.ToHexString(handshake));

    var decodedHandshake = StreamFraming.DecodeHandshake(handshake);
    AssertEqual(StreamKind.Video, decodedHandshake.Kind);
    AssertEqual(StreamFraming.Version, decodedHandshake.Version);
    AssertEqual(0x0123456789ABCDEFUL, decodedHandshake.StreamId);
    AssertSequence(ticket, decodedHandshake.Ticket);

    var header = StreamFraming.EncodeHeader(
        StreamRecordFlags.CodecConfig | StreamRecordFlags.Keyframe,
        0xBEEF,
        1_234_567_890L,
        4096);
    AssertEqual(StreamFraming.HeaderBytes, header.Length);
    AssertEqual("0300BEEF00000000499602D200001000", Convert.ToHexString(header));

    var decodedHeader = StreamFraming.DecodeHeader(header);
    AssertTrue(decodedHeader.IsCodecConfig, "Codec config flag must survive the round trip.");
    AssertTrue(decodedHeader.IsKeyframe, "Keyframe flag must survive the round trip.");
    AssertFalse(decodedHeader.IsFormatChange, "Format change flag was not set.");
    AssertEqual(0xBEEF, decodedHeader.Sequence);
    AssertEqual(1_234_567_890L, decodedHeader.PresentationTimeUs);
    AssertEqual(4096, decodedHeader.Length);

    var foreign = StreamFraming.EncodeHandshake(StreamKind.Control, ticket, 1UL);
    foreign[0] = 0x00;
    AssertThrows<InvalidDataException>(() => StreamFraming.DecodeHandshake(foreign));

    AssertThrows<ArgumentOutOfRangeException>(
        () => StreamFraming.EncodeHeader(StreamRecordFlags.None, 0, 0, StreamFraming.MaxPayloadBytes + 1));
}

static void ControlMessagesMatchAndroidWireFormat()
{
    AssertEqual(
        "0102000000000000000700000064FFFFFFCE04380960FFFF00000002",
        Convert.ToHexString(ControlMessages.Touch(
            TouchAction.Move,
            7UL,
            100,
            -50,
            1080,
            2400,
            ControlMessages.PressureScale,
            PointerButtons.Secondary)));

    AssertEqual(
        "0200000280000001E004380960FFFD000500000000",
        Convert.ToHexString(ControlMessages.Scroll(640, 480, 1080, 2400, -3, 5, PointerButtons.None)));

    AssertEqual("0300000000420000000200001000", Convert.ToHexString(ControlMessages.Key(KeyAction.Down, 66, 2, 4096)));
    AssertEqual("040000000668C3A96C6C6F", Convert.ToHexString(ControlMessages.Text("h\u00e9llo")));
    AssertEqual("0502", Convert.ToHexString(ControlMessages.Navigate(NavigationTarget.Recents)));
    AssertEqual("0601000000026F6B", Convert.ToHexString(ControlMessages.ClipboardSet("ok", paste: true)));
    AssertEqual("07", Convert.ToHexString(ControlMessages.ClipboardGet()));
    AssertEqual("08", Convert.ToHexString(ControlMessages.KeyframeRequest()));
    AssertEqual("0901", Convert.ToHexString(ControlMessages.Rotate(1)));
    AssertEqual("0A00000005636F6D2E7800000003", Convert.ToHexString(ControlMessages.AppStart("com.x", 3)));

    AssertThrows<ArgumentException>(
        () => ControlMessages.Text(new string('x', ControlMessages.MaxTextBytes + 1)));
}

static void MirrorQualityPolicyMatchesAndroidPolicy()
{
    var expected = new (MirrorProfile Profile, int Bitrate, MirrorDegradationPreference Degradation)[]
    {
        (MirrorProfile.Auto, 9_953_280, MirrorDegradationPreference.MaintainFramerate),
        (MirrorProfile.Balanced, 7_962_624, MirrorDegradationPreference.MaintainFramerate),
        (MirrorProfile.Sharpness, 13_934_592, MirrorDegradationPreference.MaintainResolution),
        (MirrorProfile.LowLatency, 5_971_968, MirrorDegradationPreference.MaintainFramerate)
    };

    foreach (var (profile, bitrate, degradation) in expected)
    {
        var settings = MirrorQualityPolicy.Resolve(profile, 1440, 3200, 1080, 1920, 60);
        AssertEqual(864, settings.Width);
        AssertEqual(1920, settings.Height);
        AssertEqual(60, settings.FrameRate);
        AssertEqual(bitrate, settings.BitrateBps);
        AssertEqual(degradation, settings.Degradation);
        AssertEqual(MirrorQualityPolicy.KeyframeIntervalSeconds, settings.KeyframeIntervalSeconds);
    }

    // Height-bound fit: the width follows the aspect rather than the bound. Getting this backwards
    // is how a mirror ends up stretched.
    var (fittedWidth, fittedHeight) = MirrorQualityPolicy.FitCaptureSize(1440, 3200, 1080, 1920);
    AssertEqual(864, fittedWidth);
    AssertEqual(1920, fittedHeight);
    AssertTrue(Math.Abs((1440.0 / 3200.0) - ((double)fittedWidth / fittedHeight)) < 0.01, "Aspect ratio must survive the fit.");

    var (oddWidth, oddHeight) = MirrorQualityPolicy.FitCaptureSize(1001, 2001, 999, 1999);
    AssertEqual(0, oddWidth % 2);
    AssertEqual(0, oddHeight % 2);

    var native = MirrorQualityPolicy.Resolve(MirrorProfile.Auto, 1440, 3201, 0, 0, 0);
    AssertEqual(1440, native.Width);
    AssertEqual(3200, native.Height);
    AssertEqual(MirrorQualityPolicy.MaxBitrateBps(MirrorProfile.Auto), native.BitrateBps);
    AssertEqual(MirrorQualityPolicy.DefaultFrameRate, native.FrameRate);

    var floor = MirrorQualityPolicy.Resolve(MirrorProfile.LowLatency, 640, 480, 0, 0, 10);
    AssertEqual(MirrorQualityPolicy.MinBitrateBps, floor.BitrateBps);

    AssertEqual(MirrorQualityPolicy.MaxFrameRate, MirrorQualityPolicy.Resolve(MirrorProfile.Auto, 720, 1280, 0, 0, 500).FrameRate);
    AssertEqual(MirrorQualityPolicy.MinFrameRate, MirrorQualityPolicy.Resolve(MirrorProfile.Auto, 720, 1280, 0, 0, 1).FrameRate);

    foreach (var profile in Enum.GetValues<MirrorProfile>())
    {
        AssertEqual(profile, MirrorQualityPolicy.ParseProfile(profile.ToWireName()));
    }

    AssertEqual(MirrorProfile.Auto, MirrorQualityPolicy.ParseProfile("nonsense"));
    AssertEqual(MirrorProfile.Auto, MirrorQualityPolicy.ParseProfile(null));
}

static void AssertThrows<TException>(Action action) where TException : Exception
{
    try
    {
        action();
    }
    catch (TException)
    {
        return;
    }

    throw new InvalidOperationException($"Expected {typeof(TException).Name}.");
}

static void AnnexBReaderSplitsOneAccessUnitPerPicture()
{
    // MediaCodec emits bare slice NALs with no delimiter between frames, so boundaries have to
    // come from first_mb_in_slice. The 0x80 byte after the NAL header encodes ue(v) = 0.
    var stream = new List<byte>();
    AppendNal(stream, 0x67, 0x42, 0xC0, 0x29);       // SPS
    AppendNal(stream, 0x68, 0xCE, 0x01);             // PPS
    AppendNal(stream, 0x65, 0x80, 0x11, 0x22);       // IDR slice, first_mb_in_slice = 0
    AppendNal(stream, 0x65, 0x40, 0x33);             // second slice of the SAME picture
    AppendNal(stream, 0x41, 0x80, 0x44);             // non-IDR slice, new picture
    AppendNal(stream, 0x06, 0x05, 0x10);             // SEI, belongs to the NEXT picture
    AppendNal(stream, 0x41, 0x80, 0x55);             // non-IDR slice, new picture

    var units = Pairly.Media.AnnexBReader.Split(stream.ToArray());
    AssertEqual(3, units.Count);

    AssertTrue(units[0].IsKeyframe, "First access unit carries the IDR slice.");
    AssertTrue(units[0].HasParameterSets, "First access unit carries SPS and PPS.");
    AssertFalse(units[1].IsKeyframe, "Second access unit is a predicted picture.");

    // The SEI preceding the third picture must belong to it, not to the picture before.
    AssertEqual(stream.Count, units[0].Length + units[1].Length + units[2].Length);
    AssertTrue(units[2].Length > units[1].Length, "The trailing SEI belongs to the access unit it precedes.");

    var reassembled = new List<byte>();
    foreach (var unit in units)
    {
        reassembled.AddRange(stream.GetRange(unit.Offset, unit.Length));
    }

    AssertSequence(stream, reassembled);
}

static void AnnexBReaderExtractsLeadingParameterSets()
{
    var stream = new List<byte>();
    AppendNal(stream, 0x67, 0x42, 0xC0, 0x29);
    AppendNal(stream, 0x68, 0xCE, 0x01);
    var parameterSetBytes = stream.Count;
    AppendNal(stream, 0x65, 0x80, 0x11);

    var parameterSets = Pairly.Media.AnnexBReader.ExtractParameterSets(stream.ToArray());
    AssertEqual(parameterSetBytes, parameterSets.Length);
    AssertSequence(stream.GetRange(0, parameterSetBytes), parameterSets);

    AssertEqual(0, Pairly.Media.AnnexBReader.ExtractParameterSets(Array.Empty<byte>()).Length);
}

static void AppendNal(List<byte> stream, params byte[] payload)
{
    stream.AddRange(new byte[] { 0x00, 0x00, 0x00, 0x01 });
    stream.AddRange(payload);
}

static void BackoffPolicyMatchesAndroidLadder()
{
    // Pinned against dev.pairly.android.network.ReconnectPolicy. BackoffParityTest.kt
    // asserts the same numbers from the other side, so neither ladder can drift alone.
    var expected = new[] { 1_000, 2_000, 5_000, 10_000, 30_000 };
    for (var attempt = 0; attempt < expected.Length; attempt++)
    {
        AssertEqual(expected[attempt], BackoffPolicy.CeilingMilliseconds(attempt));
    }

    AssertEqual(expected.Length - 1, BackoffPolicy.MaxAttemptIndex);
    AssertEqual(expected[0], BackoffPolicy.CeilingMilliseconds(-5));
    AssertEqual(expected[^1], BackoffPolicy.CeilingMilliseconds(99));
    AssertEqual(BackoffPolicy.MaxAttemptIndex, BackoffPolicy.NextAttempt(BackoffPolicy.MaxAttemptIndex));

    var random = new Random(7);
    for (var attempt = 0; attempt <= BackoffPolicy.MaxAttemptIndex; attempt++)
    {
        for (var iteration = 0; iteration < 200; iteration++)
        {
            var delay = BackoffPolicy.FullJitterDelayMilliseconds(attempt, random);
            AssertTrue(
                delay >= BackoffPolicy.FloorMilliseconds && delay <= BackoffPolicy.CeilingMilliseconds(attempt),
                $"Backoff delay {delay} left the attempt {attempt} window.");
        }
    }
}

static void DiagnosticsLogBoundsHistoryAndCountsReasons()
{
    var log = new DiagnosticsLog(capacity: 4);
    for (var index = 0; index < 10; index++)
    {
        log.Record(DiagnosticsCategory.Transport, $"event-{index}");
    }

    var entries = log.Snapshot();
    AssertEqual(4, entries.Count);
    AssertEqual("event-6", entries[0].Event);
    AssertEqual("event-9", entries[^1].Event);

    log.Record(DiagnosticsCategory.Mirror, "mirror-started");
    AssertEqual(1, log.Snapshot(category: DiagnosticsCategory.Mirror).Count);

    AssertTrue(log.ConnectedFor is null, "A fresh log must not claim an active connection.");
    log.MarkConnected();
    AssertTrue(log.ConnectedFor is not null, "MarkConnected must start the uptime clock.");

    log.MarkDisconnected("heartbeat-timeout");
    log.MarkConnected();
    log.MarkDisconnected("heartbeat-timeout");
    log.MarkConnected();
    log.MarkDisconnected("host-closed");

    var reasons = log.ReconnectReasons();
    AssertEqual(2, reasons.Count);
    AssertEqual("heartbeat-timeout", reasons[0].Key);
    AssertEqual(2, reasons[0].Value);
    AssertTrue(log.ConnectedFor is null, "MarkDisconnected must stop the uptime clock.");
}

static async Task SessionHubKeepsControlWorkAheadOfTelemetry()
{
    using var sessions = new WebSocketSessionHub();
    var socket = new BlockingWebSocket();
    sessions.Attach("phone-busy", socket);

    var telemetry = ProtocolEnvelope.Create(
        ProtocolMessageTypes.MirrorTelemetry,
        "pc-host",
        1,
        new { sessionId = "s1" });
    AssertTrue(
        WebSocketSessionHub.ClassifyPriority(ProtocolMessageTypes.MirrorTelemetry) == SendPriority.Telemetry,
        "Mirror telemetry must be classified as droppable.");
    AssertTrue(
        WebSocketSessionHub.ClassifyPriority(ProtocolMessageTypes.MirrorInput) == SendPriority.Control,
        "Mirror input must never be classified as droppable.");

    // Park the writer on the first send, then flood telemetry well past its queue.
    await sessions.SendAsync("phone-busy", telemetry, CancellationToken.None);
    await socket.SendStarted.WaitAsync(TimeSpan.FromSeconds(1));

    for (var index = 0; index < WebSocketSessionHub.TelemetryQueueCapacity * 3; index++)
    {
        await sessions.SendAsync("phone-busy", telemetry with { Sequence = index + 2 }, CancellationToken.None);
    }

    var control = ProtocolEnvelope.Create(
        ProtocolMessageTypes.MirrorInput,
        "pc-host",
        9_999,
        new { sessionId = "s1" });
    AssertTrue(
        await sessions.SendAsync("phone-busy", control, CancellationToken.None),
        "A telemetry flood must not be able to evict a control message.");

    var status = sessions.GetStatuses().Single();
    AssertEqual(0L, status.DroppedMessages);
    AssertTrue(status.DroppedTelemetry > 0, "Expected the telemetry queue to shed stale samples.");
    AssertTrue(
        status.QueuedTelemetry <= WebSocketSessionHub.TelemetryQueueCapacity,
        "Telemetry backlog must stay bounded.");

    socket.Release();
    sessions.Detach("phone-busy", socket);
}

static void AssertEqual<T>(T expected, T actual)
{
    if (!EqualityComparer<T>.Default.Equals(expected, actual))
    {
        throw new InvalidOperationException($"Expected '{expected}', got '{actual}'.");
    }
}

static void AssertTrue(bool condition, string message)
{
    if (!condition)
    {
        throw new InvalidOperationException(message);
    }
}

static void AssertFalse(bool condition, string message) => AssertTrue(!condition, message);

static void AssertSequence<T>(IReadOnlyList<T> expected, IReadOnlyList<T> actual)
{
    if (expected.Count != actual.Count)
    {
        throw new InvalidOperationException($"Expected {expected.Count} item(s), got {actual.Count}.");
    }

    for (var i = 0; i < expected.Count; i++)
    {
        AssertEqual(expected[i], actual[i]);
    }
}

static string TempDeviceStorePath() =>
    Path.Combine(Path.GetTempPath(), $"pairly-devices-{Guid.NewGuid():N}.json");

static byte[] TestPng(int width, int height, int length)
{
    var bytes = new byte[Math.Max(33, length)];
    new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }.CopyTo(bytes, 0);
    BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(8, 4), 13);
    "IHDR"u8.CopyTo(bytes.AsSpan(12, 4));
    BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(16, 4), checked((uint)width));
    BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(20, 4), checked((uint)height));
    bytes[24] = 8;
    bytes[25] = 6;
    return bytes;
}

static void TryDelete(string path)
{
    try
    {
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }
    catch (IOException)
    {
    }
}

static void TryDeleteDirectory(string path)
{
    try
    {
        if (Directory.Exists(path))
        {
            Directory.Delete(path, recursive: true);
        }
    }
    catch (IOException)
    {
    }
}

static void WindowsStringsHaveGermanTranslations()
{
    // Both resource files must define the same keys with the same {0} placeholders.
    var folder = FindRepositoryFile(Path.Combine("src", "windows", "Pairly.Windows", "Localization"));
    static Dictionary<string, string> Read(string path) => System.Xml.Linq.XDocument.Load(path).Root!
        .Elements("data").ToDictionary(e => (string)e.Attribute("name")!, e => (string?)e.Element("value") ?? string.Empty);
    var english = Read(Path.Combine(folder, "Strings.resx"));
    var german = Read(Path.Combine(folder, "Strings.de.resx"));
    var missing = english.Keys.Except(german.Keys).ToArray();
    var extra = german.Keys.Except(english.Keys).ToArray();
    if (missing.Length > 0 || extra.Length > 0)
    {
        throw new InvalidOperationException($"German strings missing: {string.Join(", ", missing)}; without English: {string.Join(", ", extra)}");
    }
    static string Placeholders(string text) => string.Join(",", System.Text.RegularExpressions.Regex.Matches(text, @"\{\d+(:[^}]*)?\}").Select(m => m.Value).Order());
    var mismatched = english.Where(pair => Placeholders(pair.Value) != Placeholders(german[pair.Key])).Select(pair => pair.Key).ToArray();
    if (mismatched.Length > 0)
    {
        throw new InvalidOperationException($"Placeholders differ: {string.Join(", ", mismatched)}");
    }
}

static string FindRepositoryFile(string relative)
{
    for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
    {
        var candidate = Path.Combine(directory.FullName, relative);
        if (Directory.Exists(candidate) || File.Exists(candidate))
        {
            return candidate;
        }
    }
    throw new DirectoryNotFoundException(relative);
}

sealed class TestTimeProvider(DateTimeOffset start) : TimeProvider
{
    private DateTimeOffset _now = start;

    public override DateTimeOffset GetUtcNow() => _now;

    public void Advance(TimeSpan delta) => _now += delta;
}

sealed class ClipboardTestHttpHandler : HttpMessageHandler
{
    public ConcurrentQueue<HostClipboardRequest> Copies { get; } = new();
    public int Failures;
    public bool ImageRequested;
    public TaskCompletionSource<byte[]> Image = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private long _sequence;
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        if (request.Method == HttpMethod.Get) {
            ImageRequested = true;
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(await Image.Task.WaitAsync(cancellationToken)) };
        }
        if (request.RequestUri!.AbsolutePath == "/v1/clipboard") {
            var copy = JsonSerializer.Deserialize<HostClipboardRequest>(await request.Content!.ReadAsStringAsync(cancellationToken), new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
            Copies.Enqueue(copy);
            if (Failures-- > 0) throw new HttpRequestException("Simulated network loss.");
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(new { relaySequence = ++_sequence })) };
        }
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{}") };
    }
}

sealed class BlockingWebSocket : WebSocket
{
    private readonly TaskCompletionSource _sendStarted = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly TaskCompletionSource _release = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private WebSocketState _state = WebSocketState.Open;

    public Task SendStarted => _sendStarted.Task;
    public override WebSocketCloseStatus? CloseStatus => null;
    public override string? CloseStatusDescription => null;
    public override WebSocketState State => _state;
    public override string? SubProtocol => null;

    public void Release() => _release.TrySetResult();

    public override void Abort()
    {
        _state = WebSocketState.Aborted;
        _release.TrySetResult();
    }

    public override Task CloseAsync(
        WebSocketCloseStatus closeStatus,
        string? statusDescription,
        CancellationToken cancellationToken)
    {
        _state = WebSocketState.Closed;
        _release.TrySetResult();
        return Task.CompletedTask;
    }

    public override Task CloseOutputAsync(
        WebSocketCloseStatus closeStatus,
        string? statusDescription,
        CancellationToken cancellationToken) => CloseAsync(closeStatus, statusDescription, cancellationToken);

    public override void Dispose()
    {
        _state = WebSocketState.Closed;
        _release.TrySetResult();
    }

    public override Task<WebSocketReceiveResult> ReceiveAsync(
        ArraySegment<byte> buffer,
        CancellationToken cancellationToken) =>
        Task.FromResult(new WebSocketReceiveResult(0, WebSocketMessageType.Close, true));

    public override Task SendAsync(
        ArraySegment<byte> buffer,
        WebSocketMessageType messageType,
        bool endOfMessage,
        CancellationToken cancellationToken)
    {
        _sendStarted.TrySetResult();
        return _release.Task.WaitAsync(cancellationToken);
    }
}

