using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using SmartphoneLink.Core;
using SmartphoneLink.Protocol;
using SmartphoneLink.Server.Streaming;

namespace SmartphoneLink.Server.Adb;

/// <summary>What to stream: the phone screen, or one app on its own virtual display.</summary>
public sealed record AdbStreamRequest(
    int MaxSize = 0,
    int MaxFps = 60,
    string Profile = "auto",
    string Codec = "h264",
    string? LaunchPackage = null,
    int AppWidth = 1080,
    int AppHeight = 1920,
    int AppDensityDpi = 420);

/// <summary>
/// A running ADB-mode server. Disposing it ends the server: closing the shell kills the process,
/// and the process also exits by itself when the PC closes the video socket.
/// </summary>
public sealed class AdbStreamSession : IDisposable
{
    private readonly Process _process;

    internal AdbStreamSession(Process process, ulong videoStreamId)
    {
        _process = process;
        VideoStreamId = videoStreamId;
    }

    /// <summary>Control is <c>VideoStreamId + 1</c>, like in app mode.</summary>
    public ulong VideoStreamId { get; }

    public bool HasExited => _process.HasExited;

    public void Dispose()
    {
        try
        {
            if (!_process.HasExited)
            {
                _process.Kill(entireProcessTree: true);
            }
        }
        catch (InvalidOperationException)
        {
            // Already gone.
        }

        _process.Dispose();
    }
}

/// <summary>
/// ADB mode: mirrors the phone, or streams one app in its own window, through wireless debugging
/// instead of the installed app. No screen-capture prompt, exact input (real injected events,
/// multi-touch, every key), and it keeps working with the screen off.
///
/// The server APK is pushed under a content-hashed name, so a new build is deployed exactly once
/// and an old copy can never answer. <c>adb reverse</c> maps the phone's 127.0.0.1:7819 onto this
/// PC's stream listener, so the server's sockets arrive as loopback and are admitted with the same
/// single-use tickets as app mode.
/// </summary>
public sealed class AdbMirrorLauncher(
    StreamTicketStore tickets,
    MirrorStreamHub streams,
    DiagnosticsLog diagnostics)
{
    private const string ServerClass = "dev.smartphonelink.server.Server";
    private static readonly TimeSpan CommandTimeout = TimeSpan.FromSeconds(20);

    private string? _serverHash;

    /// <summary>Path of adb.exe: bundled next to the app, else the Android SDK, else PATH.</summary>
    public static string? FindAdb()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "adb", "adb.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe"),
            Environment.GetEnvironmentVariable("ANDROID_HOME") is { } home ? Path.Combine(home, "platform-tools", "adb.exe") : null
        };
        return candidates.FirstOrDefault(path => path is not null && File.Exists(path))
            ?? (Environment.GetEnvironmentVariable("PATH") ?? string.Empty)
                .Split(Path.PathSeparator)
                .Select(folder => Path.Combine(folder, "adb.exe"))
                .FirstOrDefault(File.Exists);
    }

    public static string ServerPath => Path.Combine(AppContext.BaseDirectory, "adb", "smartphonelink-server.jar");

    public bool IsAvailable => FindAdb() is not null && File.Exists(ServerPath);

    /// <summary>
    /// The ADB serial of the phone with this model name (as the phone reports it when pairing,
    /// e.g. "SM-G991B"), or null when it is not connected over ADB right now.
    /// </summary>
    public async Task<string?> FindSerialAsync(string model, CancellationToken cancellationToken)
    {
        var (exit, output) = await RunAdbAsync(["devices", "-l"], cancellationToken);
        if (exit != 0)
        {
            return null;
        }

        var wanted = model.Replace('-', '_').Replace(' ', '_');
        return output.Split('\n')
            .Select(line => line.Trim())
            .Where(line => line.Contains(" device ", StringComparison.Ordinal))
            .Where(line => line.Contains($"model:{wanted}", StringComparison.OrdinalIgnoreCase))
            .Select(line => line.Split(' ', 2)[0])
            // The mDNS name survives the port change Samsung makes every time wireless debugging
            // is toggled; prefer it over ip:port.
            .OrderByDescending(serial => serial.Contains("._adb-tls-connect", StringComparison.Ordinal))
            .FirstOrDefault();
    }

    public async Task<AdbStreamSession> StartAsync(
        string deviceId,
        string serial,
        AdbStreamRequest request,
        CancellationToken cancellationToken)
    {
        var remoteJar = await DeployServerAsync(serial, cancellationToken);

        var (reverseExit, reverseOutput) = await RunAdbAsync(
            ["-s", serial, "reverse", $"tcp:{StreamListener.Port}", $"tcp:{StreamListener.Port}"],
            cancellationToken);
        if (reverseExit != 0)
        {
            throw new InvalidOperationException($"adb reverse failed: {reverseOutput.Trim()}");
        }

        var sessionId = $"adb-{Guid.NewGuid():N}";
        var videoStreamId = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() | (1UL << 61);
        var video = tickets.Issue(deviceId, sessionId, videoStreamId, StreamKind.Video);
        var control = tickets.Issue(deviceId, sessionId, videoStreamId + 1, StreamKind.Control);

        var arguments = new List<string>
        {
            $"port={StreamListener.Port}",
            $"videoTicket={Convert.ToBase64String(video.Value)}",
            $"videoStreamId={videoStreamId}",
            $"controlTicket={Convert.ToBase64String(control.Value)}",
            $"controlStreamId={videoStreamId + 1}",
            $"maxSize={request.MaxSize}",
            $"maxFps={request.MaxFps}",
            $"profile={request.Profile}",
            $"codec={request.Codec}"
        };
        if (request.LaunchPackage is { } package)
        {
            if (!IsPackageName(package))
            {
                throw new ArgumentException("Not an Android package name.", nameof(request));
            }

            arguments.Add($"newDisplay={request.AppWidth}x{request.AppHeight}/{request.AppDensityDpi}");
            arguments.Add($"launch={package}");
        }

        // Options travel in an environment variable, not argv: Samsung's encoder copies the command
        // line into a fixed buffer and aborts when it is long, and argv would expose the tickets
        // in `ps`. None of the values contain spaces or quotes.
        var command = $"SLK_ARGS='{string.Join(' ', arguments)}' CLASSPATH={remoteJar} app_process / {ServerClass}";
        var process = StartAdb(["-s", serial, "shell", command]);

        process.OutputDataReceived += (_, e) => OnServerLine(deviceId, sessionId, videoStreamId, e.Data);
        process.ErrorDataReceived += (_, e) =>
        {
            if (e.Data is { Length: > 0 } line)
            {
                diagnostics.Record(
                    DiagnosticsCategory.Mirror,
                    "adb.server",
                    line,
                    deviceId,
                    line.StartsWith("W ", StringComparison.Ordinal) ? DiagnosticsSeverity.Warning : DiagnosticsSeverity.Info);
            }
        };
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        diagnostics.Record(
            DiagnosticsCategory.Mirror,
            "adb.server.started",
            request.LaunchPackage is null ? "phone screen" : $"app {request.LaunchPackage}",
            deviceId);
        return new AdbStreamSession(process, videoStreamId);
    }

    private void OnServerLine(string deviceId, string sessionId, ulong videoStreamId, string? line)
    {
        if (line is null || !line.StartsWith("SLK_FORMAT ", StringComparison.Ordinal))
        {
            return;
        }

        // SLK_FORMAT codec width height rotation displayId
        var parts = line.Split(' ');
        if (parts.Length < 6
            || !int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out var width)
            || !int.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out var height)
            || !int.TryParse(parts[4], NumberStyles.Integer, CultureInfo.InvariantCulture, out var rotation)
            || !int.TryParse(parts[5], NumberStyles.Integer, CultureInfo.InvariantCulture, out var displayId))
        {
            return;
        }

        streams.AnnounceFormat(
            deviceId,
            new MirrorVideoFormatPayload(sessionId, videoStreamId, parts[1], width, height, rotation, displayId));
    }

    private async Task<string> DeployServerAsync(string serial, CancellationToken cancellationToken)
    {
        if (!File.Exists(ServerPath))
        {
            throw new FileNotFoundException("The ADB server component is missing from this installation.", ServerPath);
        }

        _serverHash ??= Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(ServerPath, cancellationToken)))[..12].ToLowerInvariant();
        var remote = $"/data/local/tmp/smartphonelink-server-{_serverHash}.jar";

        var (_, present) = await RunAdbAsync(["-s", serial, "shell", $"test -f {remote} && echo present"], cancellationToken);
        if (!present.Contains("present", StringComparison.Ordinal))
        {
            // Old versions are removed so /data/local/tmp does not collect one jar per update.
            await RunAdbAsync(["-s", serial, "shell", "rm -f /data/local/tmp/smartphonelink-server-*.jar"], cancellationToken);
            var (exit, output) = await RunAdbAsync(["-s", serial, "push", ServerPath, remote], cancellationToken);
            if (exit != 0)
            {
                throw new InvalidOperationException($"Could not copy the server to the phone: {output.Trim()}");
            }
        }

        return remote;
    }

    private static bool IsPackageName(string value) =>
        value.Length is > 0 and < 256
        && value.All(ch => char.IsAsciiLetterOrDigit(ch) || ch is '.' or '_')
        && value.Contains('.');

    private static async Task<(int ExitCode, string Output)> RunAdbAsync(IReadOnlyList<string> arguments, CancellationToken cancellationToken)
    {
        using var process = StartAdb(arguments);
        var output = process.StandardOutput.ReadToEndAsync(cancellationToken);
        var error = process.StandardError.ReadToEndAsync(cancellationToken);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(CommandTimeout);
        try
        {
            await process.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            process.Kill(entireProcessTree: true);
            throw new TimeoutException($"adb {string.Join(' ', arguments.Take(3))} did not finish.");
        }

        return (process.ExitCode, await output + await error);
    }

    private static Process StartAdb(IReadOnlyList<string> arguments)
    {
        var adb = FindAdb() ?? throw new FileNotFoundException("adb.exe was not found.");
        var info = new ProcessStartInfo(adb)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var argument in arguments)
        {
            info.ArgumentList.Add(argument);
        }

        return Process.Start(info) ?? throw new InvalidOperationException("adb could not be started.");
    }
}
