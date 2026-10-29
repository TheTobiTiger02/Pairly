namespace SmartphoneLink.Protocol;

public sealed record DiscoveryResponsePayload(
    string HostId,
    string PcName,
    int ProtocolVersion,
    int Port,
    int WssPort,
    string CertificateFingerprint,
    IReadOnlyList<HostEndpoint>? Endpoints = null);

public sealed record PairingTicketResponsePayload(
    string Code,
    DateTimeOffset ExpiresAt,
    string HostId,
    int ProtocolVersion,
    int WssPort,
    string CertificateFingerprint);

public sealed record PairHelloPayload(
    string PairingCode,
    string DeviceId,
    string DisplayName,
    string Platform,
    DeviceCapabilitiesPayload Capabilities,
    string? TrustKey = null,
    string? HostId = null,
    int? ProtocolVersion = null);

public sealed record PairConfirmPayload(
    bool Accepted,
    string DeviceId,
    string TrustKey,
    string? Reason,
    string? HostId = null,
    string? PcName = null,
    int? ProtocolVersion = null,
    int? SecurePort = null,
    string? CertificateFingerprint = null,
    string? ErrorCode = null);

public sealed record SessionChallengePayload(
    string ChallengeId,
    string Nonce,
    string HostId,
    DateTimeOffset ExpiresAt,
    int ProtocolVersion = 2);

public sealed record SessionAuthenticatePayload(
    string ChallengeId,
    string DeviceId,
    string Proof,
    int ProtocolVersion = 2);

public sealed record SessionReadyPayload(
    string DeviceId,
    string SessionId,
    DateTimeOffset AuthenticatedAt,
    int HeartbeatSeconds = 20,
    int StaleAfterSeconds = 60);

public sealed record DeviceCapabilitiesPayload(
    bool Clipboard,
    bool Photos,
    bool Notifications,
    bool Sms,
    bool Mirroring);

public sealed record DeviceStatePayload(
    string DisplayName,
    bool Connected,
    IReadOnlyDictionary<string, string> Permissions,
    int? BatteryLevel = null,
    bool? IsCharging = null);

public sealed record DeviceForgetPayload(string RequestId);

public sealed record DeviceForgetConfirmPayload(
    string RequestId,
    string DeviceId,
    bool Revoked,
    DateTimeOffset CompletedAt,
    string? Error = null);

public sealed record DeviceSyncRequestPayload(
    IReadOnlyList<string> Features);

public sealed record ClipboardItemPayload(
    string ItemId,
    string SourceDeviceId,
    string ContentType,
    string? Text,
    string? FileName,
    long ByteCount,
    bool Ephemeral,
    long? RelaySequence = null,
    string? ContentHash = null,
    DateTimeOffset? ExpiresAt = null,
    string? TransferId = null,
    string? Base64 = null,
    string? MimeType = null,
    string? OriginDeviceId = null);

public sealed record ClipboardAckPayload(
    string ItemId,
    long RelaySequence,
    string Status,
    string? Message = null,
    DateTimeOffset? AppliedAt = null);

public sealed record ContentTransferStartPayload(
    string TransferId,
    string ItemId,
    string SourceDeviceId,
    string ContentType,
    string? FileName,
    long ByteCount,
    int ChunkSize,
    int ChunkCount,
    string ContentHash,
    DateTimeOffset ExpiresAt,
    string? Purpose = null);

public sealed record ContentTransferChunkPayload(
    string TransferId,
    int ChunkIndex,
    string Base64,
    string? ChunkHash = null);

public sealed record ContentTransferCompletePayload(
    string TransferId,
    string ContentHash);

public sealed record MediaIndexRequestPayload(
    string? PageToken,
    int PageSize,
    string[] MimeTypes,
    string? Cursor = null,
    string? AlbumId = null,
    string? Search = null,
    string? SnapshotId = null);

public sealed record MediaItemPayload(
    string MediaId,
    string FileName,
    string MimeType,
    long ByteCount,
    int Width,
    int Height,
    DateTimeOffset TakenAt,
    string? AlbumId = null,
    string? AlbumName = null,
    int OrientationDegrees = 0,
    DateTimeOffset? ModifiedAt = null,
    string? DateSource = null);

public sealed record MediaIndexPagePayload(
    string DeviceId,
    string? NextPageToken,
    IReadOnlyList<MediaItemPayload> Items,
    string? Cursor = null,
    string? SnapshotId = null,
    bool SnapshotStart = false,
    bool SnapshotComplete = false);

public sealed record MediaContentRequestPayload(
    string RequestId,
    string MediaId,
    string Kind,
    string? TransferMode = null,
    int? MaxChunkSize = null,
    int? ProtocolVersion = null);

public sealed record MediaContentResponsePayload(
    string RequestId,
    string MediaId,
    string Kind,
    string? ContentType,
    string? FileName,
    string? Base64,
    long ByteCount,
    string? Error,
    string? TransferId = null,
    string? ContentHash = null);

public sealed record NotificationPayload(
    string NotificationId,
    string PackageName,
    string AppName,
    string Title,
    string Text,
    bool CanReply,
    DateTimeOffset PostedAt,
    string? AppIconBase64 = null,
    string? LargeIconBase64 = null,
    string? PictureBase64 = null,
    bool Clearable = false,
    string? Category = null,
    string? GroupKey = null,
    bool IsGroupSummary = false,
    bool IsOngoing = false,
    string? ChannelId = null,
    string? ConversationTitle = null,
    IReadOnlyList<string>? TextLines = null,
    IReadOnlyList<NotificationActionDescriptorPayload>? Actions = null,
    string? SnapshotId = null);

public sealed record NotificationActionDescriptorPayload(
    string ActionId,
    string Title,
    bool AllowsReply = false,
    bool IsDestructive = false);

public sealed record NotificationRemovePayload(string NotificationId);

public sealed record NotificationSnapshotPayload(
    string SnapshotId,
    DateTimeOffset CapturedAt);

public sealed record NotificationActionPayload(
    string NotificationId,
    string ActionId,
    string? ReplyText,
    string? RequestId = null);

public sealed record SmsThreadPayload(
    string ThreadId,
    string DisplayName,
    string Address,
    string Snippet,
    DateTimeOffset UpdatedAt,
    int UnreadCount);

public sealed record SmsThreadPagePayload(
    string? NextPageToken,
    IReadOnlyList<SmsThreadPayload> Threads);

public sealed record SmsMessagePayload(
    string MessageId,
    string ThreadId,
    string Address,
    string Body,
    bool FromMe,
    DateTimeOffset SentAt);

public sealed record SmsMessagePagePayload(
    string ThreadId,
    string? NextPageToken,
    IReadOnlyList<SmsMessagePayload> Messages);

public sealed record SmsSendPayload(
    string Address,
    string Body,
    string? ThreadId,
    string? RequestId = null);

public sealed record MirrorSignalPayload(
    string SessionId,
    string TargetDeviceId,
    string SignalType,
    string Data);

public sealed record MirrorStartPayload(
    string SessionId,
    string PcSessionId,
    int MaxWidth,
    int MaxHeight,
    int MaxFps,
    string? Profile = null,
    int? MaxBitrateKbps = null,
    int? MinBitrateKbps = null,
    string? DegradationPreference = null,
    bool? Adaptive = null,
    bool? AudioEnabled = null,
    string? AudioMode = null,
    int? AudioSampleRate = null,
    int? AudioChannels = null,
    string? AspectMode = null,
    string? PreferredCodec = null,
    bool? PrioritizeLatestFrames = null,
    string? SourceType = null,
    int? LeaseSeconds = null);

public sealed record MirrorConfigurePayload(
    string SessionId,
    string TargetDeviceId,
    int? MaxWidth = null,
    int? MaxHeight = null,
    int? MaxFps = null,
    int? MaxBitrateKbps = null,
    int? MinBitrateKbps = null,
    string? DegradationPreference = null,
    string? PreferredCodec = null,
    bool? PrioritizeLatestFrames = null,
    bool? RequestIceRestart = null);

public sealed record MirrorTelemetryPayload(
    string SessionId,
    string TargetDeviceId,
    double? RoundTripTimeMs = null,
    double? PacketLossPercent = null,
    long? AvailableBitrateBps = null,
    double? EncodedFps = null,
    double? DecodedFps = null,
    int? FreezeCount = null,
    double? JitterBufferDelayMs = null,
    string? QualityLimitationReason = null,
    int? FrameWidth = null,
    int? FrameHeight = null,
    DateTimeOffset? SampledAt = null);

public sealed record MirrorStatePayload(
    string SessionId,
    string TargetDeviceId,
    string State,
    string? Reason = null,
    bool NeedsUserAction = false,
    DateTimeOffset? ChangedAt = null);

public sealed record CommandResultPayload(
    string RequestId,
    string Feature,
    bool Success,
    string Message);

public sealed record ErrorPayload(
    string Code,
    string Message,
    string? CorrelationId);

public sealed record MirrorInputPayload(
    string SessionId,
    string TargetDeviceId,
    string Action, // "down", "move", "up", "key", "nav", "scroll"
    double? X = null,
    double? Y = null,
    string? Key = null,
    int? KeyCode = null,
    double? DeltaY = null);

public sealed record CallRingingPayload(
    string SessionId,
    string Number,
    string? CallerName);

public sealed record CallActionPayload(
    string SessionId,
    string Action);

public sealed record StorageItemPayload(
    string Name,
    string Path,
    bool IsDirectory,
    long SizeBytes,
    long LastModifiedMs);

public sealed record StorageListRequestPayload(
    string RequestId,
    string? DirectoryPath);

public sealed record StorageListResponsePayload(
    string RequestId,
    string? CurrentPath,
    IReadOnlyList<StorageItemPayload> Items,
    string? Error);

public sealed record StorageFileRequestPayload(
    string RequestId,
    string FilePath);

public sealed record StorageFileResponsePayload(
    string RequestId,
    string FilePath,
    string? Base64,
    string? Error);

public sealed record StorageDeleteRequestPayload(
    string RequestId,
    string FilePath);

public sealed record HeartbeatPayload(
    string SessionId,
    DateTimeOffset SentAt);

/// <summary>
/// One app's media session on the phone. <see cref="State"/> is playing, paused, buffering,
/// stopped or none. The position is a snapshot taken at <see cref="PositionUpdatedAt"/>; while
/// playing, the PC extrapolates it with <see cref="PlaybackSpeed"/> rather than asking again.
/// </summary>
public sealed record MediaSessionInfo(
    string PackageName,
    string AppName,
    string State,
    string? Title,
    string? Artist,
    string? Album,
    long DurationMs,
    long PositionMs,
    DateTimeOffset PositionUpdatedAt,
    float PlaybackSpeed,
    bool CanPlayPause,
    bool CanNext,
    bool CanPrevious,
    bool CanSeek,
    string? ArtworkJpegBase64 = null);

/// <summary>Every active media session, most relevant first.</summary>
public sealed record MediaSessionStatePayload(
    IReadOnlyList<MediaSessionInfo> Sessions,
    DateTimeOffset UpdatedAt);

/// <summary>Action is play, pause, toggle, next, previous, stop or seek (with PositionMs).</summary>
public sealed record MediaSessionCommandPayload(
    string PackageName,
    string Action,
    long? PositionMs = null);

/// <summary>
/// Offers a file to the phone. The phone dials the stream port with <see cref="Ticket"/> and
/// <see cref="StreamId"/> (stream kind 5) and the PC writes exactly <see cref="ByteCount"/> bytes.
/// An empty <see cref="Host"/> means "the address you already reached me on".
/// </summary>
public sealed record FilePushStartPayload(
    string TransferId,
    ulong StreamId,
    string FileName,
    string MimeType,
    long ByteCount,
    string Host,
    int Port,
    string Ticket,
    bool UseTls);

/// <summary>Status is saved, failed or rejected.</summary>
public sealed record FilePushResultPayload(
    string TransferId,
    string Status,
    string? SavedName = null,
    string? Error = null);

public sealed record AppsListRequestPayload(string RequestId);

/// <summary>A launchable app. The icon is a small PNG so the list needs no second round trip.</summary>
public sealed record PhoneAppPayload(
    string PackageName,
    string Label,
    string? IconPngBase64 = null);

public sealed record AppsListResponsePayload(
    string RequestId,
    IReadOnlyList<PhoneAppPayload> Apps,
    string? Error = null);

public sealed record AppsLaunchPayload(string RequestId, string PackageName);

/// <summary>Status is launched or failed.</summary>
public sealed record AppsLaunchResultPayload(string RequestId, string Status, string? Error = null);

public sealed record HostEndpoint(
    string Host,
    int Port,
    string Kind,
    int Preference);

public sealed record HostEndpointsPayload(
    string HostId,
    IReadOnlyList<HostEndpoint> Endpoints,
    DateTimeOffset IssuedAt);

public sealed record DiagnosticsRequestPayload(
    string RequestId,
    int MaxEntries);

public sealed record DiagnosticsEntryPayload(
    long Sequence,
    DateTimeOffset Timestamp,
    string Category,
    string Severity,
    string Event,
    string? Detail);

public sealed record DiagnosticsSnapshotPayload(
    string RequestId,
    string DeviceId,
    long ConnectedForSeconds,
    int ReconnectCount,
    IReadOnlyList<DiagnosticsEntryPayload> Entries);

public sealed record StreamOpenPayload(
    string SessionId,
    ulong StreamId,
    string Kind,
    string Host,
    int Port,
    string Ticket,
    IReadOnlyList<string> SupportedCodecs,
    bool UseTls,
    // The input channel rides alongside the video. It has its own ticket and stream id so neither
    // socket can be mistaken for the other, and it needs no consent of its own.
    string? ControlTicket = null,
    ulong ControlStreamId = 0,
    // Phone audio: 48 kHz 16-bit stereo PCM records on a third socket. Optional - a phone that
    // cannot capture audio simply never dials it.
    string? AudioTicket = null,
    ulong AudioStreamId = 0);

public sealed record StreamReadyPayload(
    string SessionId,
    ulong StreamId,
    string Codec,
    int Width,
    int Height,
    int Rotation);

public sealed record StreamClosePayload(
    string SessionId,
    ulong StreamId,
    string Reason);

public sealed record MirrorVideoFormatPayload(
    string SessionId,
    ulong StreamId,
    string Codec,
    int Width,
    int Height,
    int Rotation,
    int DisplayId);

public sealed record MirrorKeyframeRequestPayload(
    string SessionId,
    ulong StreamId);

public sealed record MirrorQualityPayload(
    string SessionId,
    int MaxWidth,
    int MaxHeight,
    int MaxFps,
    int BitrateKbps,
    string Profile);
