namespace Pairly.Server;

public sealed record HostClipboardRequest(
    string? Text,
    string? ContentType,
    string? FileName,
    long? ByteCount,
    string? Base64 = null,
    string? ContentHash = null,
    string? ItemId = null,
    DateTimeOffset? ExpiresAt = null);
