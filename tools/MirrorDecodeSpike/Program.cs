using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Pairly.Media;
using Vortice.MediaFoundation;

namespace MirrorDecodeSpike;

/// <summary>
/// Spike harness for the milestone 2 mirror pipeline.
///
/// Feeds a captured H.264 Annex-B elementary stream through <see cref="VideoDecoder"/> and
/// <see cref="NV12Presenter"/> and reports what the real pipeline will do: which decoder Windows
/// picks, whether it is hardware and asynchronous, whether the decoded texture can be sampled
/// without a copy, and how long decode and present actually take per frame.
/// </summary>
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("usage: MirrorDecodeSpike <stream.h264> [width] [height] [--software] [--loops N]");
            return 2;
        }

        var path = args[0];
        if (!File.Exists(path))
        {
            Console.Error.WriteLine($"Stream not found: {path}");
            return 2;
        }

        var width = args.Length > 1 && int.TryParse(args[1], out var w) ? w : 1080;
        var height = args.Length > 2 && int.TryParse(args[2], out var h) ? h : 1920;
        var preferHardware = !args.Contains("--software");
        var loops = 1;
        var loopIndex = Array.IndexOf(args, "--loops");
        if (loopIndex >= 0 && loopIndex + 1 < args.Length && int.TryParse(args[loopIndex + 1], out var parsedLoops))
        {
            loops = Math.Max(1, parsedLoops);
        }

        var application = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
        var reportPath = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(path))!, "spike-report.txt");
        var autoExit = args.Contains("--exit");
        var window = new SpikeWindow(path, width, height, preferHardware, loops, reportPath, autoExit);
        application.Run(window);
        return window.ExitCode;
    }
}

internal sealed class SpikeWindow : Window
{
    private readonly string _streamPath;
    private readonly int _width;
    private readonly int _height;
    private readonly bool _preferHardware;
    private readonly int _loops;
    private readonly string _reportPath;
    private readonly bool _autoExit;

    private readonly SwapChainHost _surface = new();
    private readonly TextBlock _report = new()
    {
        Margin = new Thickness(10),
        FontFamily = new FontFamily("Consolas"),
        FontSize = 12,
        Foreground = Brushes.White,
        Background = new SolidColorBrush(Color.FromArgb(180, 0, 0, 0)),
        Padding = new Thickness(8),
        VerticalAlignment = VerticalAlignment.Top,
        HorizontalAlignment = HorizontalAlignment.Left,
        IsHitTestVisible = false
    };

    private DispatcherTimer? _progressTimer;
    private long _submitted;
    private bool _failed;
    private D3D11Context? _context;
    private VideoDecoder? _decoder;
    private NV12Presenter? _presenter;
    private CancellationTokenSource? _cts;

    public int ExitCode { get; private set; }

    public SpikeWindow(string streamPath, int width, int height, bool preferHardware, int loops, string reportPath, bool autoExit)
    {
        _reportPath = reportPath;
        _autoExit = autoExit;
        _streamPath = streamPath;
        _width = width;
        _height = height;
        _preferHardware = preferHardware;
        _loops = loops;

        Title = "Mirror decode spike";
        Width = 720;
        Height = 1040;
        Background = Brushes.Black;

        var root = new Grid();
        root.Children.Add(_surface);
        root.Children.Add(_report);
        Content = root;

        _surface.SurfaceResized += (w, h) => Dispatcher.BeginInvoke(() => _presenter?.Resize(w, h));
        Loaded += OnLoaded;
        Closed += OnClosed;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            _context = D3D11Context.Create();
        }
        catch (Exception ex)
        {
            Fail($"FAIL  Direct3D 11 device: {ex.Message}");
            return;
        }

        var stream = File.ReadAllBytes(_streamPath);
        var parameterSets = AnnexBReader.ExtractParameterSets(stream);
        var units = AnnexBReader.Split(stream);

        try
        {
            _decoder = VideoDecoder.Create(
                _context,
                VideoFormatGuids.H264,
                _width,
                _height,
                parameterSets,
                _preferHardware);
        }
        catch (Exception ex)
        {
            Fail($"FAIL  decoder: {ex}");
            return;
        }

        var dpi = VisualTreeHelper.GetDpi(this);
        _presenter = NV12Presenter.Create(
            _context,
            _surface.SurfaceHandle,
            (int)(_surface.ActualWidth * dpi.DpiScaleX),
            (int)(_surface.ActualHeight * dpi.DpiScaleY));

        var registered = VideoDecoder.EnumerateDecoders(VideoFormatGuids.H264)
            .Select(entry => $"{entry.Name}{(entry.IsHardware ? " [hw]" : string.Empty)}")
            .ToArray();

        Report(
            $"adapter     {_context.AdapterDescription}",
            $"registered  {string.Join(" | ", registered)}",
            $"decoder     {_decoder.Name}",
            $"hardware    {_decoder.IsHardware}",
            $"async MFT   {_decoder.IsAsync}",
            $"stream      {stream.Length:N0} bytes, {units.Count} access units, SPS/PPS {parameterSets.Length} bytes",
            "running...");

        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        var decoder = _decoder;
        var presenter = _presenter;

        // Present on whichever thread produced the frame: the swap chain is on a multithread
        // protected device, and hopping to the UI thread is exactly the latency this design avoids.
        var presentGate = new object();
        decoder.FrameDecoded += frame =>
        {
            try
            {
                lock (presentGate)
                {
                    presenter.Present(frame);
                }
            }
            finally
            {
                frame.Dispose();
            }
        };

        if (decoder.IsAsync)
        {
            var pump = new Thread(() => decoder.PumpAsync(token))
            {
                IsBackground = true,
                Name = "mft-events"
            };
            pump.Start();
        }

        var feeder = new Thread(() => Feed(stream, units, decoder, token))
        {
            IsBackground = true,
            Name = "au-feeder"
        };
        feeder.Start();

        _progressTimer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(500)
        };
        _progressTimer.Tick += (_, _) => ReportProgress();
        _progressTimer.Start();
    }

    private void Feed(byte[] stream, IReadOnlyList<AccessUnit> units, VideoDecoder decoder, CancellationToken token)
    {
        var wallClock = Stopwatch.StartNew();
        var submitted = 0L;

        try
        {
            for (var loop = 0; loop < _loops && !token.IsCancellationRequested; loop++)
            {
                foreach (var unit in units)
                {
                    if (token.IsCancellationRequested)
                    {
                        break;
                    }

                    // Feed as fast as the decoder accepts. This measures the pipeline's ceiling,
                    // not playback: real input arrives at capture rate.
                    decoder.Decode(
                        stream.AsSpan(unit.Offset, unit.Length),
                        submitted * 16_666,
                        unit.IsKeyframe);
                    submitted++;
                    Interlocked.Increment(ref _submitted);
                }
            }
        }
        catch (Exception ex)
        {
            Dispatcher.BeginInvoke(() => Fail($"FAIL  feed after {submitted} access units:", ex.ToString()));
            return;
        }

        wallClock.Stop();
        var elapsed = wallClock.Elapsed.TotalSeconds;
        Dispatcher.BeginInvoke(() => ReportFinal(submitted, elapsed));
    }

    private void ReportProgress()
    {
        if (_failed || _decoder is null || _presenter is null)
        {
            return;
        }

        var decode = _decoder.Statistics;
        var present = _presenter.Statistics;
        Report(
            $"adapter     {_context!.AdapterDescription}",
            $"decoder     {_decoder.Name}",
            $"hardware    {_decoder.IsHardware}   async {_decoder.IsAsync}   zero-copy {present.ZeroCopy}   provides-samples {_decoder.ProvidesSamples}",
            $"decoded     {decode.FramesDecoded}  dropped {decode.FramesDropped}  system-memory {decode.SystemMemoryFrames}",
            $"decode ms   avg {decode.AverageDecodeMs:F2}  last {decode.LastDecodeMs:F2}  peak {decode.PeakDecodeMs:F2}",
            $"present ms  avg {present.AveragePresentMs:F2}  last {present.LastPresentMs:F2}",
            $"submitted   {Interlocked.Read(ref _submitted)}");

        WriteReport(_report.Text.Split(Environment.NewLine));
    }

    private void ReportFinal(long submitted, double elapsedSeconds)
    {
        if (_decoder is null || _presenter is null)
        {
            return;
        }

        var decode = _decoder.Statistics;
        var present = _presenter.Statistics;
        var fps = elapsedSeconds > 0 ? decode.FramesDecoded / elapsedSeconds : 0;

        var registered = VideoDecoder.EnumerateDecoders(VideoFormatGuids.H264)
            .Select(entry => $"{entry.Name}{(entry.IsHardware ? " [hw]" : string.Empty)}")
            .ToArray();

        var lines = new[]
        {
            $"adapter     {_context!.AdapterDescription}",
            $"registered  {string.Join(" | ", registered)}",
            $"decoder     {_decoder.Name}",
            $"hardware    {_decoder.IsHardware}   async {_decoder.IsAsync}   zero-copy {present.ZeroCopy}   provides-samples {_decoder.ProvidesSamples}",
            $"submitted   {submitted} access units in {elapsedSeconds:F2}s",
            $"decoded     {decode.FramesDecoded}  dropped {decode.FramesDropped}  system-memory {decode.SystemMemoryFrames}",
            $"throughput  {fps:F1} fps",
            $"decode ms   avg {decode.AverageDecodeMs:F2}  peak {decode.PeakDecodeMs:F2}",
            $"present ms  avg {present.AveragePresentMs:F2}  last {present.LastPresentMs:F2}",
            "DONE"
        };

        _progressTimer?.Stop();
        SaveSnapshot();
        Report(lines);
        WriteReport(lines);

        if (_autoExit)
        {
            var closeTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            closeTimer.Tick += (_, _) =>
            {
                closeTimer.Stop();
                Close();
            };
            closeTimer.Start();
        }
    }

    /// <summary>Writes the last presented frame to disk so the colour conversion can be eyeballed.</summary>
    private void SaveSnapshot()
    {
        if (_presenter is null)
        {
            return;
        }

        try
        {
            var (pixels, width, height, stride) = _presenter.CaptureBackBuffer();
            var source = BitmapSource.Create(width, height, 96, 96, PixelFormats.Bgra32, null, pixels, stride);
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(source));

            var snapshotPath = Path.Combine(Path.GetDirectoryName(_reportPath)!, "spike-frame.png");
            using var file = File.Create(snapshotPath);
            encoder.Save(file);
        }
        catch (Exception ex)
        {
            Report($"snapshot failed: {ex.Message}");
        }
    }

    private void Fail(params string[] lines)
    {
        _failed = true;
        Report(lines);
        WriteReport(lines);
        ExitCode = 1;
        if (_autoExit) Close();
    }

    private void WriteReport(IEnumerable<string> lines)
    {
        try
        {
            File.WriteAllLines(_reportPath, lines);
        }
        catch (IOException)
        {
            // The on-screen report is the primary output; the file is a convenience.
        }
    }

    private void Report(params string[] lines) => _report.Text = string.Join(Environment.NewLine, lines);

    private void OnClosed(object? sender, EventArgs e)
    {
        _cts?.Cancel();
        _presenter?.Dispose();
        _decoder?.Dispose();
        _context?.Dispose();
    }
}
