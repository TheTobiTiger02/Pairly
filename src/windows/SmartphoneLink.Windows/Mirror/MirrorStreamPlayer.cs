using System.Diagnostics;
using SmartphoneLink.Media;
using SmartphoneLink.Server.Streaming;
using Vortice.MediaFoundation;

namespace SmartphoneLink.Windows.Mirror;

public sealed record MirrorPlaybackStatistics(
    long FramesDecoded,
    long FramesDropped,
    double AverageDecodeMs,
    double AveragePresentMs,
    double MegabitsPerSecond,
    bool ZeroCopy,
    string DecoderName);

/// <summary>
/// Drives one mirror stream: socket to decoder to screen.
///
/// Runs on its own thread and presents from that thread rather than marshalling to the UI thread.
/// The swap chain is on a multithread-protected device, and a Dispatcher hop per frame would add
/// exactly the latency this pipeline exists to avoid.
/// </summary>
public sealed class MirrorStreamPlayer : IDisposable
{
    private readonly D3D11Context _context;
    private readonly StreamConnection _connection;
    private readonly MirrorVideoFormat _format;
    private readonly NV12Presenter _presenter;
    private readonly CancellationTokenSource _cts = new();
    private readonly object _presentGate = new();

    private VideoDecoder? _decoder;
    private Thread? _pumpThread;
    private Thread? _asyncEventThread;
    private long _startedTimestamp;

    private MirrorStreamPlayer(
        D3D11Context context,
        StreamConnection connection,
        MirrorVideoFormat format,
        NV12Presenter presenter)
    {
        _context = context;
        _connection = connection;
        _format = format;
        _presenter = presenter;
    }

    public event Action<string>? Failed;

    public MirrorVideoFormat Format => _format;

    public MirrorPlaybackStatistics Statistics
    {
        get
        {
            var decode = _decoder?.Statistics ?? new DecoderStatistics(0, 0, 0, 0, 0);
            var present = _presenter.Statistics;
            var elapsed = _startedTimestamp == 0
                ? TimeSpan.Zero
                : Stopwatch.GetElapsedTime(_startedTimestamp);

            var megabits = elapsed.TotalSeconds > 0
                ? _connection.BytesRead * 8.0 / 1_000_000.0 / elapsed.TotalSeconds
                : 0;

            return new MirrorPlaybackStatistics(
                decode.FramesDecoded,
                decode.FramesDropped,
                decode.AverageDecodeMs,
                present.AveragePresentMs,
                megabits,
                present.ZeroCopy,
                _decoder?.Name ?? "starting");
        }
    }

    public static MirrorStreamPlayer Create(
        StreamConnection connection,
        MirrorVideoFormat format,
        nint surfaceHandle,
        int surfaceWidth,
        int surfaceHeight)
    {
        var context = D3D11Context.Create();
        try
        {
            var presenter = NV12Presenter.Create(context, surfaceHandle, surfaceWidth, surfaceHeight);
            presenter.SetVideoSize(format.Width, format.Height);
            return new MirrorStreamPlayer(context, connection, format, presenter);
        }
        catch
        {
            context.Dispose();
            throw;
        }
    }

    public void Start()
    {
        _startedTimestamp = Stopwatch.GetTimestamp();
        _pumpThread = new Thread(Pump)
        {
            IsBackground = true,
            Name = "mirror-stream"
        };
        _pumpThread.Start();
    }

    public void Resize(int width, int height)
    {
        lock (_presentGate)
        {
            _presenter.Resize(width, height);
        }
    }

    private void Pump()
    {
        try
        {
            PumpCore();
        }
        catch (OperationCanceledException)
        {
            // Normal teardown.
        }
        catch (Exception ex)
        {
            Failed?.Invoke(ex.Message);
        }
    }

    private void PumpCore()
    {
        var token = _cts.Token;

        while (!token.IsCancellationRequested)
        {
            var record = _connection.ReadRecordAsync(token).GetAwaiter().GetResult();
            if (record is null)
            {
                Failed?.Invoke("The phone closed the mirror stream.");
                return;
            }

            if (record.Header.IsCodecConfig)
            {
                // SPS/PPS. The first config record builds the decoder (as the sequence header on
                // its input type), and every config record is also fed in-band: measured on a real
                // phone, the Microsoft H.264 decoder produced no output at all from the sequence
                // header alone, because MediaCodec sends parameter sets out of band while the
                // spike's test file happened to carry them inline.
                EnsureDecoder(record.Payload);
                _decoder!.Decode(record.Payload, record.Header.PresentationTimeUs, isKeyframe: false);
                continue;
            }

            var decoder = _decoder;
            if (decoder is null)
            {
                // Frames before the parameter sets cannot be decoded. Skipping them is correct:
                // the encoder emits a keyframe right after the config record.
                continue;
            }

            decoder.Decode(record.Payload, record.Header.PresentationTimeUs, record.Header.IsKeyframe);
        }
    }

    private void EnsureDecoder(byte[] parameterSets)
    {
        if (_decoder is not null)
        {
            return;
        }

        var codec = string.Equals(_format.Codec, "h265", StringComparison.OrdinalIgnoreCase)
            ? VideoFormatGuids.H265
            : VideoFormatGuids.H264;

        var decoder = VideoDecoder.Create(
            _context,
            codec,
            _format.Width,
            _format.Height,
            parameterSets);

        decoder.FrameDecoded += OnFrameDecoded;
        _decoder = decoder;

        if (decoder.IsAsync)
        {
            _asyncEventThread = new Thread(() => decoder.PumpAsync(_cts.Token))
            {
                IsBackground = true,
                Name = "mirror-mft-events"
            };
            _asyncEventThread.Start();
        }
    }

    private void OnFrameDecoded(DecodedFrame frame)
    {
        try
        {
            lock (_presentGate)
            {
                _presenter.Present(frame);
            }
        }
        catch (Exception ex)
        {
            Failed?.Invoke($"Presenting a frame failed: {ex.Message}");
        }
        finally
        {
            frame.Dispose();
        }
    }

    public void Dispose()
    {
        _cts.Cancel();

        // The pump blocks on a socket read, so closing the connection is what actually unblocks it.
        _connection.DisposeAsync().AsTask().GetAwaiter().GetResult();

        _pumpThread?.Join(TimeSpan.FromSeconds(2));
        _asyncEventThread?.Join(TimeSpan.FromSeconds(2));

        if (_decoder is not null)
        {
            _decoder.FrameDecoded -= OnFrameDecoded;
            _decoder.Dispose();
            _decoder = null;
        }

        lock (_presentGate)
        {
            _presenter.Dispose();
        }

        _context.Dispose();
        _cts.Dispose();
    }
}
