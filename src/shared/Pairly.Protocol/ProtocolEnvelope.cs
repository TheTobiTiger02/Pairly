using System.Text.Json;
using System.Text.Json.Serialization;

namespace Pairly.Protocol;

public sealed record ProtocolEnvelope
{
    public const int CurrentVersion = 1;

    [JsonPropertyName("v")]
    public int Version { get; init; } = CurrentVersion;

    [JsonPropertyName("id")]
    public string Id { get; init; } = Guid.NewGuid().ToString("N");

    [JsonPropertyName("type")]
    public string Type { get; init; } = ProtocolMessageTypes.Error;

    [JsonPropertyName("deviceId")]
    public string DeviceId { get; init; } = string.Empty;

    [JsonPropertyName("seq")]
    public long Sequence { get; init; }

    [JsonPropertyName("timestamp")]
    public DateTimeOffset Timestamp { get; init; } = DateTimeOffset.UtcNow;

    [JsonPropertyName("payload")]
    public JsonElement Payload { get; init; } = JsonSerializer.SerializeToElement(new Dictionary<string, object>());

    public static ProtocolEnvelope Create<TPayload>(
        string type,
        string deviceId,
        long sequence,
        TPayload payload,
        DateTimeOffset? timestamp = null,
        string? id = null)
    {
        return new ProtocolEnvelope
        {
            Id = string.IsNullOrWhiteSpace(id) ? Guid.NewGuid().ToString("N") : id,
            Type = type,
            DeviceId = deviceId,
            Sequence = sequence,
            Timestamp = timestamp ?? DateTimeOffset.UtcNow,
            Payload = JsonSerializer.SerializeToElement(payload, JsonProtocolSerializer.Options)
        };
    }

    public TPayload? ReadPayload<TPayload>() =>
        Payload.Deserialize<TPayload>(JsonProtocolSerializer.Options);
}

