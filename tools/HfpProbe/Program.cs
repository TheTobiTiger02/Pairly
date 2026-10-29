using Windows.ApplicationModel.Calls;
using Windows.Devices.Enumeration;

// Spike: can Pairly be the PC's Bluetooth hands-free for a phone?
//   hfpprobe            read-only: list phones, ask for access
//   hfpprobe connect X  register as the phone-line app for phone X and connect its HFP link
Console.Out.Flush();
var log = new StreamWriter(Path.Combine(Path.GetTempPath(), "slk-hfpprobe.log"), append: false) { AutoFlush = true };
void Say(string m) { Console.WriteLine(m); log.WriteLine($"{DateTime.Now:HH:mm:ss.fff} {m}"); }
var mode = args.Length >= 2 ? args[0] : null;
var target = args.Length >= 2 ? args[1] : null;
Say($"package identity: {HasIdentity()}");

Say("finding devices");
var devices = await DeviceInformation.FindAllAsync(PhoneLineTransportDevice.GetDeviceSelector(PhoneLineTransport.Bluetooth));
foreach (var info in devices)
{
    Say($"requesting access for {info.Name}");
    var device = PhoneLineTransportDevice.FromId(info.Id);
    var access = await device.RequestAccessAsync();
    Say($"- {info.Name}: access {access}, registered {device.IsRegistered()}");

    if (target is null || !info.Name.Contains(target, StringComparison.OrdinalIgnoreCase) || access != DeviceAccessStatus.Allowed)
    {
        continue;
    }

    if (mode == "connect")
    {
        device.RegisterApp();
        Say($"  after RegisterApp: registered {device.IsRegistered()}");
        var connected = await device.ConnectAsync();
        Say($"  RegisterApp done, ConnectAsync -> {connected}, audio routing: {device.AudioRoutingStatus}");
        device.AudioRoutingStatusChanged += (d, _) => Say($"  audio routing -> {d.AudioRoutingStatus}");
        Say("  watching audio routing for 20 s; call the phone now");
        await Task.Delay(TimeSpan.FromSeconds(20));
    }
    else if (mode == "undo")
    {
        device.UnregisterApp();
        Say($"  unregistered: registered {device.IsRegistered()}");
    }
}

log.Dispose();

static bool HasIdentity()
{
    try { return Windows.ApplicationModel.Package.Current is not null; }
    catch { return false; }
}
