using System.Text.Json;

namespace SmartphoneLink.Protocol;

public static class JsonProtocolSerializer
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = false
    };

    public static string Serialize(ProtocolEnvelope envelope) =>
        JsonSerializer.Serialize(envelope, Options);

    public static ProtocolEnvelope Deserialize(string json) =>
        JsonSerializer.Deserialize<ProtocolEnvelope>(json, Options)
        ?? throw new JsonException("Protocol envelope was empty.");
}

