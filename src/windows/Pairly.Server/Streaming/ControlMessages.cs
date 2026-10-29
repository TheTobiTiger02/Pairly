using System.Buffers.Binary;
using System.Text;

namespace Pairly.Server.Streaming;

public enum ControlMessageType
{
    Touch = 0x01,
    Scroll = 0x02,
    Key = 0x03,
    Text = 0x04,
    Navigate = 0x05,
    ClipboardSet = 0x06,
    ClipboardGet = 0x07,
    KeyframeRequest = 0x08,
    Rotate = 0x09,
    AppStart = 0x0A
}

public enum TouchAction
{
    Down = 0,
    Up = 1,
    Move = 2,
    Cancel = 3
}

public enum KeyAction
{
    Down = 0,
    Up = 1
}

public enum NavigationTarget
{
    Back = 0,
    Home = 1,
    Recents = 2,
    Power = 3,
    Notifications = 4
}

[Flags]
public enum PointerButtons
{
    None = 0,
    Primary = 1 << 0,
    Secondary = 1 << 1,
    Tertiary = 1 << 2
}

/// <summary>
/// Desktop twin of <c>dev.pairly.streamcore.ControlMessageCodec</c>, byte-identical by
/// contract.
///
/// Pointer positions are absolute device pixels paired with the screen size they were computed
/// against. That pairing is what makes a coordinate self-describing: after a rotation, a position
/// computed for the old orientation arrives with the old screen size and the phone discards it
/// instead of tapping somewhere arbitrary. The previous normalised 0..1 scheme could express
/// neither that nor a second pointer.
/// </summary>
public static class ControlMessages
{
    /// <summary>Pressure is a 0..65535 fixed-point fraction; 1.0 is full contact.</summary>
    public const int PressureScale = 65_535;

    /// <summary>Guards against a malformed length turning into a large allocation.</summary>
    public const int MaxTextBytes = 64 * 1024;

    public static byte[] Touch(
        TouchAction action,
        ulong pointerId,
        int x,
        int y,
        int screenWidth,
        int screenHeight,
        int pressure,
        PointerButtons buttons)
    {
        var bytes = new byte[1 + 1 + 8 + 4 + 4 + 2 + 2 + 2 + 4];
        bytes[0] = (byte)ControlMessageType.Touch;
        bytes[1] = (byte)action;
        BinaryPrimitives.WriteUInt64BigEndian(bytes.AsSpan(2, 8), pointerId);
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(10, 4), x);
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(14, 4), y);
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(18, 2), (ushort)screenWidth);
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(20, 2), (ushort)screenHeight);
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(22, 2), (ushort)pressure);
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(24, 4), (int)buttons);
        return bytes;
    }

    public static byte[] Scroll(
        int x,
        int y,
        int screenWidth,
        int screenHeight,
        int horizontal,
        int vertical,
        PointerButtons buttons)
    {
        var bytes = new byte[1 + 4 + 4 + 2 + 2 + 2 + 2 + 4];
        bytes[0] = (byte)ControlMessageType.Scroll;
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(1, 4), x);
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(5, 4), y);
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(9, 2), (ushort)screenWidth);
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(11, 2), (ushort)screenHeight);
        BinaryPrimitives.WriteInt16BigEndian(bytes.AsSpan(13, 2), (short)horizontal);
        BinaryPrimitives.WriteInt16BigEndian(bytes.AsSpan(15, 2), (short)vertical);
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(17, 4), (int)buttons);
        return bytes;
    }

    public static byte[] Key(KeyAction action, int keyCode, int repeatCount, int metaState)
    {
        var bytes = new byte[1 + 1 + 4 + 4 + 4];
        bytes[0] = (byte)ControlMessageType.Key;
        bytes[1] = (byte)action;
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(2, 4), keyCode);
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(6, 4), repeatCount);
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(10, 4), metaState);
        return bytes;
    }

    public static byte[] Text(string value)
    {
        var utf8 = EncodeText(value);
        var bytes = new byte[1 + 4 + utf8.Length];
        bytes[0] = (byte)ControlMessageType.Text;
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(1, 4), utf8.Length);
        utf8.CopyTo(bytes.AsSpan(5));
        return bytes;
    }

    public static byte[] Navigate(NavigationTarget target) =>
        new[] { (byte)ControlMessageType.Navigate, (byte)target };

    public static byte[] ClipboardSet(string value, bool paste)
    {
        var utf8 = EncodeText(value);
        var bytes = new byte[1 + 1 + 4 + utf8.Length];
        bytes[0] = (byte)ControlMessageType.ClipboardSet;
        bytes[1] = paste ? (byte)1 : (byte)0;
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(2, 4), utf8.Length);
        utf8.CopyTo(bytes.AsSpan(6));
        return bytes;
    }

    public static byte[] ClipboardGet() => new[] { (byte)ControlMessageType.ClipboardGet };

    public static byte[] KeyframeRequest() => new[] { (byte)ControlMessageType.KeyframeRequest };

    public static byte[] Rotate(int orientation) =>
        new[] { (byte)ControlMessageType.Rotate, (byte)orientation };

    public static byte[] AppStart(string packageName, int displayId)
    {
        var utf8 = EncodeText(packageName);
        var bytes = new byte[1 + 4 + utf8.Length + 4];
        bytes[0] = (byte)ControlMessageType.AppStart;
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(1, 4), utf8.Length);
        utf8.CopyTo(bytes.AsSpan(5));
        BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(5 + utf8.Length, 4), displayId);
        return bytes;
    }

    private static byte[] EncodeText(string value)
    {
        var utf8 = Encoding.UTF8.GetBytes(value);
        if (utf8.Length > MaxTextBytes)
        {
            throw new ArgumentException($"Text exceeds {MaxTextBytes} bytes.", nameof(value));
        }

        return utf8;
    }
}
