using Pairly.Protocol;
using System.Text.Json.Serialization;

namespace Pairly.Core;

public sealed record PairingTicket(
    string Code,
    string TrustKey,
    DateTimeOffset ExpiresAt);

public sealed record PairingResult(
    bool Accepted,
    string DeviceId,
    string TrustKey,
    string? Reason,
    string? ErrorCode = null)
{
    public PairConfirmPayload ToPayload(
        string? hostId = null,
        string? pcName = null,
        int? protocolVersion = null,
        int? securePort = null,
        string? certificateFingerprint = null) =>
        new(
            Accepted,
            DeviceId,
            TrustKey,
            Reason,
            hostId,
            pcName,
            protocolVersion,
            securePort,
            certificateFingerprint,
            ErrorCode);
}

public sealed record LinkedDevice(
    string DeviceId,
    string DisplayName,
    string Platform,
    [property: JsonIgnore]
    string TrustKey,
    DeviceCapabilitiesPayload Capabilities,
    DateTimeOffset PairedAt,
    DateTimeOffset LastSeenAt,
    bool Connected,
    IReadOnlyDictionary<string, string>? Permissions = null,
    int? BatteryLevel = null,
    bool? IsCharging = null);

public sealed record DeviceRegistryPersistenceStatus(
    string? StoragePath,
    string? BackupPath,
    int PersistedDeviceCount,
    bool LoadedFromBackup,
    string? LastLoadError,
    string? LastSaveError,
    DateTimeOffset? LastLoadedAt,
    DateTimeOffset? LastSavedAt,
    int SchemaVersion = 2,
    long Revision = 0,
    int RevocationCount = 0,
    string? Checksum = null)
{
    public bool Healthy => string.IsNullOrWhiteSpace(LastLoadError) && string.IsNullOrWhiteSpace(LastSaveError);
}
