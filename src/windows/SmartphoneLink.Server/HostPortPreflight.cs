using System.Net;
using System.Net.Sockets;

namespace SmartphoneLink.Server;

public sealed class HostStartupException : InvalidOperationException
{
    public HostStartupException(int port, string transport, Exception innerException)
        : base(
            $"Another Pairly host or application is already using {transport} port {port}. " +
            "Close every other Pairly window, including old Debug builds, and try again.",
            innerException)
    {
        Port = port;
        Transport = transport;
    }

    public int Port { get; }

    public string Transport { get; }
}

public static class HostPortPreflight
{
    public static void VerifyRequiredEndpointsAvailable()
    {
        var leases = new List<IDisposable>();
        try
        {
            leases.Add(BindTcp(IPAddress.Loopback, HostIdentity.LanPort));
            leases.Add(BindUdp(IPAddress.Any, 7817));
            leases.Add(BindTcp(IPAddress.Any, HostIdentity.SecureLanPort));
            leases.Add(BindTcp(IPAddress.Any, Streaming.StreamListener.Port));
        }
        finally
        {
            for (var index = leases.Count - 1; index >= 0; index--)
            {
                leases[index].Dispose();
            }
        }
    }

    private static TcpListener BindTcp(IPAddress address, int port)
    {
        var listener = new TcpListener(address, port);
        try
        {
            listener.Server.ExclusiveAddressUse = true;
            listener.Start();
            return listener;
        }
        catch (Exception ex) when (ex is SocketException or InvalidOperationException)
        {
            listener.Stop();
            throw new HostStartupException(port, "TCP", ex);
        }
    }

    private static UdpClient BindUdp(IPAddress address, int port)
    {
        var client = new UdpClient(address.AddressFamily);
        try
        {
            client.ExclusiveAddressUse = true;
            client.Client.Bind(new IPEndPoint(address, port));
            return client;
        }
        catch (Exception ex) when (ex is SocketException or InvalidOperationException)
        {
            client.Dispose();
            throw new HostStartupException(port, "UDP", ex);
        }
    }
}
