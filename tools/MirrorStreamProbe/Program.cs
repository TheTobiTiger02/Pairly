using System.Diagnostics;
using System.Net.Http.Json;
using System.Text.Json;
using Pairly.Core;
using Pairly.Media;
using Pairly.Server;
using Pairly.Server.Streaming;

namespace MirrorStreamProbe;

/// <summary>
/// Headless end-to-end probe for the app-mode mirror.
///
/// Runs the real embedded host, pairs a phone, starts a mirror, and captures the elementary stream
/// the phone produces. It exercises everything except decode and present - pairing, `stream.open`,
/// the admission ticket, the listener, the encoder, the socket and the framing - and leaves a real
/// device-encoded `.h264` file behind so the decoder can be validated against it separately.
///
/// Not shipped; this is a harness.
/// </summary>
internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        var seconds = ArgInt(args, "--seconds", 15);
        var outputPath = ArgString(args, "--out", Path.Combine(Path.GetTempPath(), "mirror-probe.h264"));
        var profile = ArgString(args, "--profile", "auto");
        var maxWidth = ArgInt(args, "--max-width", 0);
        var maxHeight = ArgInt(args, "--max-height", 0);
        var maxFps = ArgInt(args, "--fps", 60);
        var pairOnly = args.Contains("--pair-only");
        var waitSeconds = ArgInt(args, "--wait", 20);
        var alwaysTicket = args.Contains("--ticket");
        var excluded = new HashSet<string>(
            ArgString(args, "--exclude", string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries),
            StringComparer.Ordinal);

        var server = new PairlyServer();
        try
        {
            await server.StartAsync();
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"host failed to start: {ex.Message}");
            return 1;
        }

        using var http = new HttpClient { BaseAddress = new Uri(PairlyServer.DefaultHttpUrl) };

        var identity = await http.GetFromJsonAsync<JsonElement>("/");
        Console.WriteLine($"hostId       {identity.GetProperty("hostId").GetString()}");
        Console.WriteLine($"pcName       {identity.GetProperty("pcName").GetString()}");
        Console.WriteLine($"securePort   {identity.GetProperty("securePort").GetInt32()}");
        Console.WriteLine($"fingerprint  {identity.GetProperty("certificateFingerprint").GetString()}");

        var devices = await http.GetFromJsonAsync<JsonElement>("/v1/devices");
        var paired = devices.EnumerateArray().ToArray();
        Console.WriteLine($"paired       {paired.Length} device(s)");

        if (pairOnly || alwaysTicket || paired.Length == 0)
        {
            var ticket = await http.PostAsync("/v1/pairing-ticket", content: null);
            var ticketBody = await ticket.Content.ReadFromJsonAsync<JsonElement>();
            Console.WriteLine($"PAIRING CODE {ticketBody.GetProperty("code").GetString()}");
            Console.WriteLine($"expires      {ticketBody.GetProperty("expiresAt").GetString()}");
        }

        if (pairOnly)
        {
            Console.WriteLine("waiting for the phone to pair...");
            var pairedDeviceId = await WaitForDeviceAsync(http, TimeSpan.FromSeconds(seconds));
            Console.WriteLine(pairedDeviceId is null ? "RESULT no device paired" : $"RESULT paired {pairedDeviceId}");
            await server.StopAsync();
            return pairedDeviceId is null ? 1 : 0;
        }

        Console.WriteLine($"waiting up to {waitSeconds}s for a phone to connect...");
        var deviceId = await WaitForConnectedDeviceAsync(http, TimeSpan.FromSeconds(waitSeconds), excluded);
        if (deviceId is null)
        {
            Console.WriteLine("RESULT no connected device");
            await server.StopAsync();
            return 1;
        }

        Console.WriteLine($"device       {deviceId}");

        var streams = server.MirrorStreams!;
        var capture = new StreamCapture(outputPath);
        streams.VideoStreamReady += (connection, format) =>
        {
            Console.WriteLine($"STREAM       {format.Codec} {format.Width}x{format.Height} rot {format.Rotation} " +
                              $"encrypted={connection.IsEncrypted} from {connection.RemoteEndPoint}");
            _ = Task.Run(() => capture.RunAsync(connection));
        };

        var startUrl = $"/v1/mirror/{Uri.EscapeDataString(deviceId)}/start"
            + $"?maxWidth={maxWidth}&maxHeight={maxHeight}&maxFps={maxFps}&profile={profile}&preferredCodec=h264";

        var start = await http.PostAsync(startUrl, content: null);
        Console.WriteLine($"start        {(int)start.StatusCode} {start.ReasonPhrase}");
        if (!start.IsSuccessStatusCode)
        {
            Console.WriteLine(await start.Content.ReadAsStringAsync());
            await server.StopAsync();
            return 1;
        }

        Console.WriteLine($"capturing for {seconds}s (allow screen capture on the phone if it asks)...");
        var deadline = Stopwatch.GetTimestamp() + (long)(seconds * Stopwatch.Frequency);
        while (Stopwatch.GetTimestamp() < deadline)
        {
            await Task.Delay(1000);
            Console.WriteLine($"  {capture.Summary()}");
        }

        await http.PostAsync($"/v1/mirror/{Uri.EscapeDataString(deviceId)}/stop", content: null);
        await Task.Delay(500);
        capture.Complete();

        Console.WriteLine();
        Console.WriteLine(capture.FinalReport(outputPath));

        var log = server.Diagnostics.Snapshot(40, DiagnosticsCategory.Mirror);
        Console.WriteLine("--- host mirror log ---");
        foreach (var entry in log)
        {
            Console.WriteLine($"  {entry.Timestamp.ToLocalTime():HH:mm:ss} {entry.Event} {entry.Detail}");
        }

        await server.StopAsync();
        return capture.Records > 0 ? 0 : 1;
    }

    private static async Task<string?> WaitForDeviceAsync(HttpClient http, TimeSpan timeout)
    {
        var deadline = DateTimeOffset.UtcNow + timeout;
        while (DateTimeOffset.UtcNow < deadline)
        {
            var devices = await http.GetFromJsonAsync<JsonElement>("/v1/devices");
            foreach (var device in devices.EnumerateArray())
            {
                return device.GetProperty("deviceId").GetString();
            }

            await Task.Delay(500);
        }

        return null;
    }

    private static async Task<string?> WaitForConnectedDeviceAsync(HttpClient http, TimeSpan timeout, IReadOnlySet<string> excluded)
    {
        var deadline = DateTimeOffset.UtcNow + timeout;
        while (DateTimeOffset.UtcNow < deadline)
        {
            var sessions = await http.GetFromJsonAsync<JsonElement>("/v1/diagnostics/sessions");
            foreach (var session in sessions.EnumerateArray())
            {
                var id = session.GetProperty("sessionId").GetString();
                if (!string.IsNullOrWhiteSpace(id)
                    && !id.StartsWith("pc-", StringComparison.OrdinalIgnoreCase)
                    && !excluded.Contains(id))
                {
                    return id;
                }
            }

            await Task.Delay(500);
        }

        return null;
    }

    private static int ArgInt(string[] args, string name, int fallback)
    {
        var index = Array.IndexOf(args, name);
        return index >= 0 && index + 1 < args.Length && int.TryParse(args[index + 1], out var value) ? value : fallback;
    }

    private static string ArgString(string[] args, string name, string fallback)
    {
        var index = Array.IndexOf(args, name);
        return index >= 0 && index + 1 < args.Length ? args[index + 1] : fallback;
    }
}

/// <summary>Writes an accepted stream straight to disk and counts what went past.</summary>
internal sealed class StreamCapture
{
    private readonly FileStream _file;
    private readonly Stopwatch _clock = new();

    public StreamCapture(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        _file = File.Create(path);
    }

    public long Records { get; private set; }

    public long Keyframes { get; private set; }

    public long ConfigRecords { get; private set; }

    public long Bytes { get; private set; }

    public int FirstSequence { get; private set; } = -1;

    public int LastSequence { get; private set; } = -1;

    public long SequenceGaps { get; private set; }

    public string? Error { get; private set; }

    public async Task RunAsync(StreamConnection connection)
    {
        _clock.Start();
        try
        {
            while (true)
            {
                var record = await connection.ReadRecordAsync(CancellationToken.None);
                if (record is null)
                {
                    return;
                }

                lock (_file)
                {
                    _file.Write(record.Payload);
                    Records++;
                    Bytes += record.Payload.Length;

                    if (record.Header.IsKeyframe)
                    {
                        Keyframes++;
                    }

                    if (record.Header.IsCodecConfig)
                    {
                        ConfigRecords++;
                    }

                    if (FirstSequence < 0)
                    {
                        FirstSequence = record.Header.Sequence;
                    }
                    else if (record.Header.Sequence != ((LastSequence + 1) & 0xFFFF))
                    {
                        SequenceGaps++;
                    }

                    LastSequence = record.Header.Sequence;
                }
            }
        }
        catch (Exception ex)
        {
            Error = ex.Message;
        }
        finally
        {
            await connection.DisposeAsync();
        }
    }

    public string Summary()
    {
        lock (_file)
        {
            var seconds = _clock.Elapsed.TotalSeconds;
            var fps = seconds > 0 ? Records / seconds : 0;
            var mbps = seconds > 0 ? Bytes * 8.0 / 1_000_000.0 / seconds : 0;
            return $"records {Records}, keyframes {Keyframes}, {fps:F1}/s, {mbps:F2} Mb/s";
        }
    }

    public void Complete()
    {
        lock (_file)
        {
            _file.Flush();
            _file.Dispose();
        }
    }

    public string FinalReport(string path)
    {
        var seconds = _clock.Elapsed.TotalSeconds;
        var lines = new List<string>
        {
            $"RESULT       {(Records > 0 ? "stream received" : "NO STREAM")}",
            $"records      {Records} ({ConfigRecords} codec config, {Keyframes} keyframes)",
            $"bytes        {Bytes:N0} over {seconds:F1}s",
            $"rate         {(seconds > 0 ? Records / seconds : 0):F1} records/s, {(seconds > 0 ? Bytes * 8.0 / 1_000_000.0 / seconds : 0):F2} Mb/s",
            $"sequence     {FirstSequence}..{LastSequence}, {SequenceGaps} gap(s)",
            $"file         {path}"
        };

        if (Error is not null)
        {
            lines.Add($"error        {Error}");
        }

        if (Records > 0 && File.Exists(path))
        {
            var bytes = File.ReadAllBytes(path);
            var units = AnnexBReader.Split(bytes);
            var parameterSets = AnnexBReader.ExtractParameterSets(bytes);
            lines.Add($"annex-b      {units.Count} access units, SPS/PPS {parameterSets.Length} bytes");
            lines.Add($"keyframe AUs {units.Count(unit => unit.IsKeyframe)}");
        }

        return string.Join(Environment.NewLine, lines);
    }
}
