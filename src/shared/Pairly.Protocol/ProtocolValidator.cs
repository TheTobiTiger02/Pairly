namespace Pairly.Protocol;

public sealed record ProtocolValidationResult(bool IsValid, string? Error)
{
    public static ProtocolValidationResult Valid { get; } = new(true, null);

    public static ProtocolValidationResult Invalid(string error) => new(false, error);
}

public static class ProtocolValidator
{
    public static ProtocolValidationResult Validate(ProtocolEnvelope envelope, bool requireKnownType = true)
    {
        if (envelope.Version != ProtocolEnvelope.CurrentVersion)
        {
            return ProtocolValidationResult.Invalid(
                $"Unsupported protocol version {envelope.Version}; expected {ProtocolEnvelope.CurrentVersion}.");
        }

        if (string.IsNullOrWhiteSpace(envelope.Id))
        {
            return ProtocolValidationResult.Invalid("Envelope id is required.");
        }

        if (string.IsNullOrWhiteSpace(envelope.Type))
        {
            return ProtocolValidationResult.Invalid("Envelope type is required.");
        }

        if (requireKnownType && !ProtocolMessageTypes.IsKnown(envelope.Type))
        {
            return ProtocolValidationResult.Invalid($"Unknown message type '{envelope.Type}'.");
        }

        if (envelope.Sequence < 0)
        {
            return ProtocolValidationResult.Invalid("Envelope sequence cannot be negative.");
        }

        return ProtocolValidationResult.Valid;
    }
}

