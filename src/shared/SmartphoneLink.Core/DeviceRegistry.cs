using System.Collections.Concurrent;
using System.ComponentModel;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Core;

public sealed class DeviceRegistry
{
    private const int CurrentSchemaVersion = 2;

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private static readonly JsonSerializerOptions CanonicalJsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = false
    };

    private readonly ConcurrentDictionary<string, LinkedDevice> _devices = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, long> _deviceRevisions = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, RevocationTombstone> _revocations = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, PairingTicket> _tickets = new(StringComparer.Ordinal);
    private readonly object _persistenceGate = new();
    private readonly string? _storagePath;
    private readonly string? _backupPath;
    private string? _lastLoadError;
    private string? _lastSaveError;
    private string? _lastChecksum;
    private bool _loadedFromBackup;
    private long _revision;
    private DateTimeOffset? _lastLoadedAt;
    private DateTimeOffset? _lastSavedAt;

    public static string DefaultStoragePath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SmartphoneLink",
        "devices.json");

    public DeviceRegistry()
        : this(null)
    {
    }

    public DeviceRegistry(string? storagePath)
    {
        _storagePath = string.IsNullOrWhiteSpace(storagePath) ? null : storagePath;
        _backupPath = _storagePath is null ? null : $"{_storagePath}.bak";
        RemoveStaleTempFiles(_storagePath);
        RemoveStaleTempFiles(_backupPath);
        LoadPersistedDevices();
    }

    /// <summary>
    /// Age after which an atomic-write temp file is certainly abandoned. A live write finishes in
    /// milliseconds; anything this old belongs to a process that died mid-write, where the
    /// <c>finally</c> that normally deletes it never ran.
    /// </summary>
    internal static readonly TimeSpan StaleTempFileAge = TimeSpan.FromHours(1);

    /// <summary>
    /// Deletes leftover <c>{path}.{guid}.tmp</c> files. They are never resumable - the real file is
    /// only ever replaced by a completed temp - so the only question is whether one might still be
    /// in use, which the age threshold answers.
    /// </summary>
    internal static int RemoveStaleTempFiles(string? path, DateTime? nowUtc = null)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return 0;
        }

        var directory = Path.GetDirectoryName(path);
        if (string.IsNullOrWhiteSpace(directory) || !Directory.Exists(directory))
        {
            return 0;
        }

        var cutoff = (nowUtc ?? DateTime.UtcNow) - StaleTempFileAge;
        var removed = 0;
        foreach (var candidate in Directory.EnumerateFiles(directory, $"{Path.GetFileName(path)}.*.tmp"))
        {
            try
            {
                if (File.GetLastWriteTimeUtc(candidate) < cutoff)
                {
                    File.Delete(candidate);
                    removed++;
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Best effort; a locked orphan is retried on the next start.
            }
        }

        return removed;
    }

    public PairingTicket OpenPairingTicket(TimeSpan? ttl = null)
    {
        var code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
        var trustKey = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        var ticket = new PairingTicket(code, trustKey, DateTimeOffset.UtcNow.Add(ttl ?? TimeSpan.FromMinutes(5)));
        _tickets[code] = ticket;
        return ticket;
    }

    public PairingResult ConfirmPairing(PairHelloPayload hello)
    {
        if (!string.IsNullOrWhiteSpace(hello.TrustKey))
        {
            return ConfirmTrustedDevice(hello);
        }

        if (!_tickets.TryRemove(hello.PairingCode, out var ticket))
        {
            return new PairingResult(
                false,
                hello.DeviceId,
                string.Empty,
                "The pairing code was rejected. Create a new code on the active Windows app.",
                "pairing_code_rejected");
        }

        if (ticket.ExpiresAt <= DateTimeOffset.UtcNow)
        {
            return new PairingResult(
                false,
                hello.DeviceId,
                string.Empty,
                "The pairing code expired. Create a new code on the active Windows app.",
                "pairing_code_expired");
        }

        var deviceId = string.IsNullOrWhiteSpace(hello.DeviceId)
            ? Guid.NewGuid().ToString("N")
            : hello.DeviceId;

        lock (_persistenceGate)
        {
            var now = DateTimeOffset.UtcNow;
            var nextRevision = _revision + 1;
            var device = new LinkedDevice(
                deviceId,
                hello.DisplayName,
                hello.Platform,
                ticket.TrustKey,
                hello.Capabilities,
                now,
                now,
                Connected: true,
                Permissions: DefaultPermissionState());

            // A new pairing ticket is explicit user consent, so it is the only
            // operation that may supersede a previous Forget tombstone.
            _revocations.TryRemove(deviceId, out _);
            _devices[deviceId] = device;
            _deviceRevisions[deviceId] = nextRevision;
            SavePersistedDevicesLocked();
        }

        return new PairingResult(true, deviceId, ticket.TrustKey, null);
    }

    public bool Authenticate(string deviceId, string trustKey)
    {
        if (!_devices.TryGetValue(deviceId, out var device)
            || string.IsNullOrWhiteSpace(trustKey)
            || device.TrustKey.Length != trustKey.Length)
        {
            return false;
        }

        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(device.TrustKey),
            Encoding.UTF8.GetBytes(trustKey));
    }

    public bool AuthenticateProof(
        string deviceId,
        string challengeId,
        string nonce,
        string hostId,
        string proof)
    {
        if (!_devices.TryGetValue(deviceId, out var device) || string.IsNullOrWhiteSpace(proof))
        {
            return false;
        }

        var expected = ComputeChallengeProof(device.TrustKey, challengeId, nonce, deviceId, hostId);
        if (expected.Length != proof.Length)
        {
            return false;
        }

        return CryptographicOperations.FixedTimeEquals(
            Encoding.ASCII.GetBytes(expected),
            Encoding.ASCII.GetBytes(proof.ToLowerInvariant()));
    }

    public static string ComputeChallengeProof(
        string trustKey,
        string challengeId,
        string nonce,
        string deviceId,
        string hostId)
    {
        byte[] key;
        try
        {
            key = Convert.FromHexString(trustKey);
        }
        catch (FormatException)
        {
            key = Encoding.UTF8.GetBytes(trustKey);
        }

        using var hmac = new HMACSHA256(key);
        var message = Encoding.UTF8.GetBytes($"{challengeId}:{nonce}:{deviceId}:{hostId}");
        return Convert.ToHexString(hmac.ComputeHash(message)).ToLowerInvariant();
    }

    public IReadOnlyList<LinkedDevice> GetDevices() =>
        _devices.Values.OrderBy(device => device.DisplayName, StringComparer.OrdinalIgnoreCase).ToArray();

    public LinkedDevice? GetDevice(string deviceId) =>
        _devices.TryGetValue(deviceId, out var device) ? device : null;

    public DeviceRegistryPersistenceStatus GetPersistenceStatus() => new(
        _storagePath,
        _backupPath,
        _devices.Values.Count(device => !string.IsNullOrWhiteSpace(device.TrustKey)),
        _loadedFromBackup,
        _lastLoadError,
        _lastSaveError,
        _lastLoadedAt,
        _lastSavedAt,
        CurrentSchemaVersion,
        Interlocked.Read(ref _revision),
        _revocations.Count,
        _lastChecksum);

    public bool RepairPersistenceFromBackup()
    {
        if (_storagePath is null || _backupPath is null || !File.Exists(_backupPath))
        {
            _lastLoadError = "No trust-store backup is available.";
            return false;
        }

        lock (_persistenceGate)
        {
            try
            {
                var backup = ReadSnapshot(_backupPath);
                var mergedRevocations = backup.Document.Revocations
                    .Concat(_revocations.Values)
                    .GroupBy(item => item.DeviceId, StringComparer.Ordinal)
                    .Select(group => group.OrderByDescending(item => item.Revision).First())
                    .ToArray();
                ApplySnapshot(backup.Document, mergedRevocations);
                _revision = Math.Max(_revision, backup.Document.Revision);
                SavePersistedDevicesLocked();
                _loadedFromBackup = true;
                _lastLoadError = null;
                _lastLoadedAt = DateTimeOffset.UtcNow;
                return string.IsNullOrWhiteSpace(_lastSaveError);
            }
            catch (Exception ex) when (IsPersistenceException(ex))
            {
                _lastLoadError = $"Backup restore failed: {ex.Message}";
                return false;
            }
        }
    }

    public void MarkConnected(string deviceId, bool connected)
    {
        if (!_devices.TryGetValue(deviceId, out var existing))
        {
            return;
        }

        _devices[deviceId] = existing with
        {
            Connected = connected,
            LastSeenAt = DateTimeOffset.UtcNow
        };
    }

    public void UpdateDeviceState(string deviceId, DeviceStatePayload state)
    {
        if (string.IsNullOrWhiteSpace(deviceId) || !_devices.TryGetValue(deviceId, out var existing))
        {
            return;
        }

        lock (_persistenceGate)
        {
            _devices[deviceId] = existing with
            {
                DisplayName = string.IsNullOrWhiteSpace(state.DisplayName) ? existing.DisplayName : state.DisplayName,
                Connected = state.Connected,
                LastSeenAt = DateTimeOffset.UtcNow,
                Permissions = NormalizePermissions(state.Permissions),
                BatteryLevel = state.BatteryLevel,
                IsCharging = state.IsCharging
            };
            _deviceRevisions[deviceId] = _revision + 1;
            SavePersistedDevicesLocked();
        }
    }

    public bool Revoke(string deviceId)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            return false;
        }

        lock (_persistenceGate)
        {
            var removed = _devices.TryRemove(deviceId, out _);
            _deviceRevisions.TryRemove(deviceId, out _);
            _revocations[deviceId] = new RevocationTombstone(
                deviceId,
                DateTimeOffset.UtcNow,
                _revision + 1);
            SavePersistedDevicesLocked();
            return removed;
        }
    }

    private PairingResult ConfirmTrustedDevice(PairHelloPayload hello)
    {
        if (string.IsNullOrWhiteSpace(hello.DeviceId))
        {
            return new PairingResult(false, hello.DeviceId, string.Empty, "Trusted reconnect requires a device id.", "authentication_rejected");
        }

        if (!Authenticate(hello.DeviceId, hello.TrustKey ?? string.Empty)
            || !_devices.TryGetValue(hello.DeviceId, out var existing))
        {
            return new PairingResult(false, hello.DeviceId, string.Empty, "Trust key was not recognized.", "authentication_rejected");
        }

        lock (_persistenceGate)
        {
            var now = DateTimeOffset.UtcNow;
            var updated = existing with
            {
                DisplayName = string.IsNullOrWhiteSpace(hello.DisplayName) ? existing.DisplayName : hello.DisplayName,
                Platform = string.IsNullOrWhiteSpace(hello.Platform) ? existing.Platform : hello.Platform,
                Capabilities = hello.Capabilities,
                LastSeenAt = now,
                Connected = true
            };

            _devices[hello.DeviceId] = updated;
            _deviceRevisions[hello.DeviceId] = _revision + 1;
            SavePersistedDevicesLocked();
        }

        return new PairingResult(true, hello.DeviceId, existing.TrustKey, null);
    }

    private void LoadPersistedDevices()
    {
        if (_storagePath is null)
        {
            return;
        }

        lock (_persistenceGate)
        {
            _loadedFromBackup = false;
            var snapshots = new List<TrustStoreSnapshot>();
            var errors = new List<string>();

            TryReadCandidate(_storagePath, snapshots, errors);
            if (_backupPath is not null)
            {
                TryReadCandidate(_backupPath, snapshots, errors);
            }

            if (snapshots.Count == 0)
            {
                _lastLoadError = errors.Count == 0 ? null : string.Join(" ", errors);
                _lastLoadedAt = DateTimeOffset.UtcNow;
                return;
            }

            var selected = snapshots
                .OrderByDescending(snapshot => snapshot.Document.Revision)
                .ThenBy(snapshot => string.Equals(snapshot.Path, _storagePath, StringComparison.OrdinalIgnoreCase) ? 0 : 1)
                .First();
            var mergedRevocations = snapshots
                .SelectMany(snapshot => snapshot.Document.Revocations)
                .GroupBy(item => item.DeviceId, StringComparer.Ordinal)
                .Select(group => group.OrderByDescending(item => item.Revision).First())
                .ToArray();

            ApplySnapshot(selected.Document, mergedRevocations);
            _revision = snapshots.Max(snapshot => snapshot.Document.Revision);
            _lastChecksum = selected.Document.Checksum;
            _loadedFromBackup = !string.Equals(selected.Path, _storagePath, StringComparison.OrdinalIgnoreCase);
            _lastLoadError = errors.Count == 0 ? null : string.Join(" ", errors);
            _lastLoadedAt = DateTimeOffset.UtcNow;

            // Repair a missing/corrupt peer snapshot immediately. v1 arrays are
            // also migrated to the checksummed, DPAPI-protected schema here.
            if (_loadedFromBackup || errors.Count > 0 || selected.Document.SchemaVersion < CurrentSchemaVersion)
            {
                SavePersistedDevicesLocked();
            }
        }
    }

    private void TryReadCandidate(
        string path,
        ICollection<TrustStoreSnapshot> snapshots,
        ICollection<string> errors)
    {
        if (!File.Exists(path))
        {
            return;
        }

        try
        {
            snapshots.Add(ReadSnapshot(path));
        }
        catch (Exception ex) when (IsPersistenceException(ex))
        {
            errors.Add($"{Path.GetFileName(path)} could not be loaded: {ex.Message}");
        }
    }

    private void SavePersistedDevicesLocked()
    {
        if (_storagePath is null || _backupPath is null)
        {
            return;
        }

        try
        {
            var document = BuildDocument(_revision + 1);
            var json = JsonSerializer.Serialize(document, JsonOptions);

            // Write the redundant snapshot first. If the process is interrupted,
            // startup still selects the valid document with the greatest revision.
            AtomicWrite(_backupPath, json);
            AtomicWrite(_storagePath, json);

            _revision = document.Revision;
            _lastChecksum = document.Checksum;
            _lastSaveError = null;
            _lastSavedAt = DateTimeOffset.UtcNow;
        }
        catch (Exception ex) when (IsPersistenceException(ex))
        {
            _lastSaveError = $"Trust store save failed: {ex.Message}";
        }
    }

    private TrustStoreDocument BuildDocument(long revision)
    {
        var devices = _devices.Values
            .Where(device => !string.IsNullOrWhiteSpace(device.TrustKey))
            .OrderBy(device => device.DeviceId, StringComparer.Ordinal)
            .Select(device => new PersistedDevice(
                device.DeviceId,
                device.DisplayName,
                device.Platform,
                SecretProtector.Protect(device.TrustKey),
                device.Capabilities,
                device.PairedAt,
                device.LastSeenAt,
                NormalizePermissions(device.Permissions),
                device.BatteryLevel,
                device.IsCharging,
                _deviceRevisions.TryGetValue(device.DeviceId, out var deviceRevision)
                    ? Math.Min(deviceRevision, revision)
                    : revision))
            .ToArray();
        var revocations = _revocations.Values
            .OrderBy(item => item.DeviceId, StringComparer.Ordinal)
            .ToArray();
        var content = new TrustStoreContent(CurrentSchemaVersion, revision, devices, revocations);
        var checksum = ComputeChecksum(content);
        return new TrustStoreDocument(
            content.SchemaVersion,
            content.Revision,
            checksum,
            content.Devices,
            content.Revocations);
    }

    private static TrustStoreSnapshot ReadSnapshot(string path)
    {
        var json = File.ReadAllText(path);
        if (string.IsNullOrWhiteSpace(json))
        {
            throw new JsonException("Trust store was empty.");
        }

        using var parsed = JsonDocument.Parse(json);
        if (parsed.RootElement.ValueKind == JsonValueKind.Array)
        {
            var legacy = JsonSerializer.Deserialize<LegacyLinkedDevice[]>(json, JsonOptions)
                ?? Array.Empty<LegacyLinkedDevice>();
            var legacyDevices = legacy
                .Where(device => !string.IsNullOrWhiteSpace(device.TrustKey))
                .Select(device => new PersistedDevice(
                    device.DeviceId,
                    device.DisplayName,
                    device.Platform,
                    device.TrustKey,
                    device.Capabilities,
                    device.PairedAt,
                    device.LastSeenAt,
                    device.Permissions,
                    device.BatteryLevel,
                    device.IsCharging,
                    Revision: 0))
                .ToArray();
            var content = new TrustStoreContent(1, 0, legacyDevices, Array.Empty<RevocationTombstone>());
            return new TrustStoreSnapshot(
                path,
                new TrustStoreDocument(1, 0, ComputeChecksum(content), legacyDevices, content.Revocations));
        }

        var document = JsonSerializer.Deserialize<TrustStoreDocument>(json, JsonOptions)
            ?? throw new JsonException("Trust store document was missing.");
        if (document.SchemaVersion is < 1 or > CurrentSchemaVersion)
        {
            throw new JsonException($"Unsupported trust store schema {document.SchemaVersion}.");
        }

        var contentToVerify = new TrustStoreContent(
            document.SchemaVersion,
            document.Revision,
            document.Devices,
            document.Revocations);
        var expectedChecksum = ComputeChecksum(contentToVerify);
        if (!string.Equals(expectedChecksum, document.Checksum, StringComparison.OrdinalIgnoreCase))
        {
            throw new JsonException("Trust store checksum did not match its contents.");
        }

        // Validate every protected value while selecting a snapshot. A damaged
        // DPAPI blob therefore falls back to the other redundant snapshot.
        foreach (var device in document.Devices)
        {
            _ = SecretProtector.Unprotect(device.ProtectedTrustKey);
        }

        return new TrustStoreSnapshot(path, document);
    }

    private void ApplySnapshot(
        TrustStoreDocument document,
        IReadOnlyCollection<RevocationTombstone> revocations)
    {
        _devices.Clear();
        _deviceRevisions.Clear();
        _revocations.Clear();

        foreach (var revocation in revocations)
        {
            _revocations[revocation.DeviceId] = revocation;
        }

        foreach (var persisted in document.Devices)
        {
            if (string.IsNullOrWhiteSpace(persisted.ProtectedTrustKey))
            {
                continue;
            }

            if (_revocations.TryGetValue(persisted.DeviceId, out var tombstone)
                && tombstone.Revision >= persisted.Revision)
            {
                continue;
            }

            var device = new LinkedDevice(
                persisted.DeviceId,
                persisted.DisplayName,
                persisted.Platform,
                SecretProtector.Unprotect(persisted.ProtectedTrustKey),
                persisted.Capabilities,
                persisted.PairedAt,
                persisted.LastSeenAt,
                Connected: false,
                NormalizePermissions(persisted.Permissions),
                persisted.BatteryLevel,
                persisted.IsCharging);
            _devices[device.DeviceId] = device;
            _deviceRevisions[device.DeviceId] = persisted.Revision;
        }
    }

    private static string ComputeChecksum(TrustStoreContent content)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(content, CanonicalJsonOptions);
        return Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    }

    private static void AtomicWrite(string path, string contents)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var tempPath = $"{path}.{Guid.NewGuid():N}.tmp";
        try
        {
            using (var stream = new FileStream(
                       tempPath,
                       FileMode.CreateNew,
                       FileAccess.Write,
                       FileShare.None,
                       16 * 1024,
                       FileOptions.WriteThrough))
            using (var writer = new StreamWriter(stream, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false)))
            {
                writer.Write(contents);
                writer.Flush();
                stream.Flush(flushToDisk: true);
            }

            File.Move(tempPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }
        }
    }

    private static bool IsPersistenceException(Exception ex) =>
        ex is IOException
            or JsonException
            or UnauthorizedAccessException
            or CryptographicException
            or FormatException
            or Win32Exception
            or PlatformNotSupportedException;

    private static IReadOnlyDictionary<string, string> NormalizePermissions(IReadOnlyDictionary<string, string>? permissions) =>
        permissions is null || permissions.Count == 0
            ? DefaultPermissionState()
            : new SortedDictionary<string, string>(
                permissions.ToDictionary(item => item.Key, item => item.Value, StringComparer.OrdinalIgnoreCase),
                StringComparer.OrdinalIgnoreCase);

    private static IReadOnlyDictionary<string, string> DefaultPermissionState() => new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["clipboard"] = "granted",
        ["photos"] = "needs_user_action",
        ["notifications"] = "needs_user_action",
        ["sms"] = "needs_user_action",
        ["mirroring"] = "needs_user_action"
    };

    private sealed record TrustStoreSnapshot(string Path, TrustStoreDocument Document);

    private sealed record TrustStoreDocument(
        int SchemaVersion,
        long Revision,
        string Checksum,
        IReadOnlyList<PersistedDevice> Devices,
        IReadOnlyList<RevocationTombstone> Revocations);

    private sealed record TrustStoreContent(
        int SchemaVersion,
        long Revision,
        IReadOnlyList<PersistedDevice> Devices,
        IReadOnlyList<RevocationTombstone> Revocations);

    private sealed record PersistedDevice(
        string DeviceId,
        string DisplayName,
        string Platform,
        string ProtectedTrustKey,
        DeviceCapabilitiesPayload Capabilities,
        DateTimeOffset PairedAt,
        DateTimeOffset LastSeenAt,
        IReadOnlyDictionary<string, string>? Permissions,
        int? BatteryLevel,
        bool? IsCharging,
        long Revision);

    private sealed record LegacyLinkedDevice(
        string DeviceId,
        string DisplayName,
        string Platform,
        string TrustKey,
        DeviceCapabilitiesPayload Capabilities,
        DateTimeOffset PairedAt,
        DateTimeOffset LastSeenAt,
        bool Connected,
        IReadOnlyDictionary<string, string>? Permissions = null,
        int? BatteryLevel = null,
        bool? IsCharging = null);

    private sealed record RevocationTombstone(
        string DeviceId,
        DateTimeOffset RevokedAt,
        long Revision);
}
