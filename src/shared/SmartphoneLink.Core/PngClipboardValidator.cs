using System.Buffers.Binary;

namespace SmartphoneLink.Core;

public static class PngClipboardValidator
{
    public const int MaxLongEdge = 4096;
    private static ReadOnlySpan<byte> Signature => new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 };

    public static bool TryValidate(byte[] bytes, out string? error) =>
        TryValidateHeader(bytes, out error);

    public static bool TryValidateChunks(IReadOnlyList<byte[]> chunks, out string? error)
    {
        Span<byte> header = stackalloc byte[24];
        var written = 0;
        foreach (var chunk in chunks)
        {
            var copyCount = Math.Min(chunk.Length, header.Length - written);
            chunk.AsSpan(0, copyCount).CopyTo(header[written..]);
            written += copyCount;
            if (written == header.Length)
            {
                break;
            }
        }

        return TryValidateHeader(header[..written], out error);
    }

    private static bool TryValidateHeader(ReadOnlySpan<byte> bytes, out string? error)
    {
        if (bytes.Length < 24
            || !bytes[..8].SequenceEqual(Signature)
            || BinaryPrimitives.ReadUInt32BigEndian(bytes.Slice(8, 4)) != 13
            || !bytes.Slice(12, 4).SequenceEqual("IHDR"u8))
        {
            error = "Clipboard image was not a valid PNG with an IHDR header.";
            return false;
        }

        var width = BinaryPrimitives.ReadUInt32BigEndian(bytes.Slice(16, 4));
        var height = BinaryPrimitives.ReadUInt32BigEndian(bytes.Slice(20, 4));
        if (width == 0 || height == 0 || width > MaxLongEdge || height > MaxLongEdge)
        {
            error = $"Clipboard PNG dimensions must be between 1 and {MaxLongEdge} pixels per edge.";
            return false;
        }

        error = null;
        return true;
    }
}
