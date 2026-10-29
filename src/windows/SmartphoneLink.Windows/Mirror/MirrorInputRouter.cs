using System.Threading.Channels;
using SmartphoneLink.Media;
using SmartphoneLink.Server.Streaming;

namespace SmartphoneLink.Windows.Mirror;

/// <summary>
/// Turns mouse and keyboard input over the mirror surface into control messages for the phone.
///
/// Positions are converted to video pixels through the same letterbox rectangle the presenter
/// draws into, so a click lands exactly where the phone's pixel appears. Messages are written by a
/// single background writer so the UI thread never blocks on the network, and a queue that backs
/// up drops stale pointer moves rather than delaying the next tap behind them.
/// </summary>
public sealed class MirrorInputRouter : IDisposable
{
    private const int MaxQueuedMessages = 256;

    private readonly MirrorSurfaceHost _surface;
    private readonly StreamConnection _connection;
    private readonly Func<string?> _readClipboard;
    private readonly Channel<byte[]> _outbound = Channel.CreateBounded<byte[]>(
        new BoundedChannelOptions(MaxQueuedMessages)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = true
        });
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _writer;

    private int _videoWidth;
    private int _videoHeight;
    private bool _pointerDown;
    private SurfaceButtons _downButton;

    public MirrorInputRouter(
        MirrorSurfaceHost surface,
        StreamConnection connection,
        int videoWidth,
        int videoHeight,
        Func<string?> readClipboard)
    {
        _surface = surface;
        _connection = connection;
        _readClipboard = readClipboard;
        _videoWidth = videoWidth;
        _videoHeight = videoHeight;

        _surface.PointerInput += OnPointer;
        _surface.WheelInput += OnWheel;
        _surface.KeyInput += OnKey;
        _surface.CharacterInput += OnCharacter;

        _writer = Task.Run(WriteLoopAsync);
    }

    public long MessagesSent { get; private set; }

    /// <summary>Raw surface events seen, mapped or not. Diagnoses "input does nothing".</summary>
    public long EventsSeen { get; private set; }

    public long EventsUnmapped { get; private set; }

    public string? LastError { get; private set; }

    public void SetVideoSize(int width, int height)
    {
        _videoWidth = width;
        _videoHeight = height;
    }

    /// <summary>Sends a navigation key; used by the window's Back / Home / Recents buttons.</summary>
    public void Navigate(NavigationTarget target) => Enqueue(ControlMessages.Navigate(target));

    public void RequestKeyframe() => Enqueue(ControlMessages.KeyframeRequest());

    private void OnPointer(SurfacePointerEvent input)
    {
        EventsSeen++;
        var mapped = Map(input.X, input.Y, clampOutside: input.Action != SurfacePointerAction.Down);
        if (mapped is null)
        {
            EventsUnmapped++;
            return;
        }

        var (x, y) = mapped.Value;

        switch (input.Action)
        {
            case SurfacePointerAction.Down:
                if (_pointerDown)
                {
                    return;
                }

                _pointerDown = true;
                _downButton = input.Buttons;
                Enqueue(Touch(TouchAction.Down, x, y, input.Buttons));
                break;

            case SurfacePointerAction.Move:
                if (_pointerDown)
                {
                    Enqueue(Touch(TouchAction.Move, x, y, _downButton));
                }

                break;

            case SurfacePointerAction.Up:
                if (_pointerDown && input.Buttons == _downButton)
                {
                    _pointerDown = false;
                    Enqueue(Touch(TouchAction.Up, x, y, _downButton));
                }

                break;
        }
    }

    private byte[] Touch(TouchAction action, int x, int y, SurfaceButtons buttons) =>
        ControlMessages.Touch(
            action,
            0,
            x,
            y,
            _videoWidth,
            _videoHeight,
            action == TouchAction.Up ? 0 : ControlMessages.PressureScale,
            buttons.HasFlag(SurfaceButtons.Right) ? PointerButtons.Secondary : PointerButtons.Primary);

    private void OnWheel(SurfaceWheelEvent input)
    {
        var mapped = Map(input.X, input.Y, clampOutside: true);
        if (mapped is null)
        {
            return;
        }

        var (x, y) = mapped.Value;
        Enqueue(ControlMessages.Scroll(x, y, _videoWidth, _videoHeight, input.HorizontalNotches, input.VerticalNotches, PointerButtons.None));
    }

    private void OnKey(SurfaceKeyEvent input)
    {
        if (input.IsDown && input.Control && input.VirtualKey == KeyMap.VirtualKeyV)
        {
            // Ctrl+V pastes the PC clipboard into the focused field on the phone.
            var text = _readClipboard();
            if (!string.IsNullOrEmpty(text))
            {
                Enqueue(ControlMessages.ClipboardSet(text, paste: true));
            }

            return;
        }

        if (input.VirtualKey == KeyMap.VirtualKeyEscape)
        {
            // Escape as Back is what every phone-mirroring tool converged on: Android has no
            // Escape that means anything outside a hardware keyboard.
            if (input.IsDown)
            {
                Navigate(NavigationTarget.Back);
            }

            return;
        }

        var keyCode = KeyMap.ToAndroid(input.VirtualKey, input.Control);
        if (keyCode is null)
        {
            return;
        }

        Enqueue(ControlMessages.Key(
            input.IsDown ? KeyAction.Down : KeyAction.Up,
            keyCode.Value,
            Math.Max(0, input.RepeatCount - 1),
            KeyMap.MetaState(input.Control, input.Shift, input.Alt)));
    }

    private void OnCharacter(char character)
    {
        Enqueue(ControlMessages.Text(character.ToString()));
    }

    /// <summary>
    /// Surface pixels to video pixels through the presenter's letterbox. A press outside the video
    /// is ignored; moves and releases are clamped to the edge, because a drag that leaves the
    /// picture is still the same drag.
    /// </summary>
    private (int X, int Y)? Map(int surfaceX, int surfaceY, bool clampOutside)
    {
        if (_videoWidth <= 0 || _videoHeight <= 0)
        {
            return null;
        }

        var (surfaceWidth, surfaceHeight) = _surface.PixelSize;
        var fit = NV12Presenter.FitRect(surfaceWidth, surfaceHeight, _videoWidth, _videoHeight);

        var relativeX = (surfaceX - fit.X) / (double)fit.Width;
        var relativeY = (surfaceY - fit.Y) / (double)fit.Height;

        var outside = relativeX < 0 || relativeX > 1 || relativeY < 0 || relativeY > 1;
        if (outside && !clampOutside)
        {
            return null;
        }

        var x = (int)Math.Round(Math.Clamp(relativeX, 0, 1) * (_videoWidth - 1));
        var y = (int)Math.Round(Math.Clamp(relativeY, 0, 1) * (_videoHeight - 1));
        return (x, y);
    }

    private void Enqueue(byte[] message)
    {
        if (!_cts.IsCancellationRequested)
        {
            _outbound.Writer.TryWrite(message);
        }
    }

    private async Task WriteLoopAsync()
    {
        try
        {
            await foreach (var message in _outbound.Reader.ReadAllAsync(_cts.Token))
            {
                await _connection.WriteAsync(message, _cts.Token);

                // Flush per message: each one is a user action, and batching would add exactly the
                // latency that makes remote input feel remote.
                await _connection.FlushAsync(_cts.Token);
                MessagesSent++;
            }
        }
        catch (OperationCanceledException)
        {
            // Normal teardown.
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
        }
    }

    public void Dispose()
    {
        _surface.PointerInput -= OnPointer;
        _surface.WheelInput -= OnWheel;
        _surface.KeyInput -= OnKey;
        _surface.CharacterInput -= OnCharacter;

        _cts.Cancel();
        _outbound.Writer.TryComplete();
        try
        {
            _writer.Wait(TimeSpan.FromSeconds(1));
        }
        catch (AggregateException)
        {
            // The writer's own failure is already in LastError.
        }

        _connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
        _cts.Dispose();
    }
}
