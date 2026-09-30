using System.Runtime.InteropServices;

namespace Tunnel;

/// <summary>
/// The service that carries this computer's tunnel, run by tunnel.dll as SYSTEM. It starts with Windows,
/// so the tunnel stays up with the window closed. Shaped after WireGuard's embeddable-dll-service demo (MIT).
/// </summary>
public static class TunnelService
{
    [DllImport("tunnel.dll", EntryPoint = "WireGuardTunnelService", CallingConvention = CallingConvention.Cdecl)]
    public static extern bool Run([MarshalAs(UnmanagedType.LPWStr)] string configFile);

    public static string NameOf(string configFile) => $"WireGuardTunnel${AdapterOf(configFile)}";

    public static string AdapterOf(string configFile) => Path.GetFileNameWithoutExtension(configFile);

    public static void Up(string configFile)
    {
        Services.Remove(NameOf(configFile));
        // Without an unrestricted SID the tunnel cannot set its own firewall rules.
        Services.Install(NameOf(configFile), "Vaier tunnel", "Keeps this computer in the Vaier fleet",
            $"/service \"{configFile}\"", unrestrictedSid: true, dependencies: "Nsi\0TcpIp\0");
    }

    public static void Down(string configFile) => Services.Remove(NameOf(configFile));

    public static bool IsUp(string configFile) => Services.IsRunning(NameOf(configFile));

    /// <summary>The tunnel's peer as the driver sees it right now, or null when the adapter is not there.</summary>
    public static Driver.Adapter.Peer? Peer(string configFile)
    {
        try
        {
            return new Driver.Adapter(AdapterOf(configFile)).GetConfiguration().Peers.FirstOrDefault();
        }
        catch (Exception)
        {
            return null;
        }
    }
}
