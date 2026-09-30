using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Tunnel;

/// <summary>
/// The one Windows service that carries this computer's tunnel, run by tunnel.dll as SYSTEM. It starts
/// with Windows, so the tunnel stays up with this window closed — as it does in the WireGuard app.
/// Shaped after WireGuard's embeddable-dll-service demo (MIT).
/// </summary>
public static class TunnelService
{
    [DllImport("tunnel.dll", EntryPoint = "WireGuardTunnelService", CallingConvention = CallingConvention.Cdecl)]
    public static extern bool Run([MarshalAs(UnmanagedType.LPWStr)] string configFile);

    private const int ServiceDoesNotExist = 0x424;
    private const int ServiceMarkedForDelete = 0x430;

    private static string ShortName(string configFile) => $"WireGuardTunnel${Path.GetFileNameWithoutExtension(configFile)}";

    public static void Add(string configFile)
    {
        Remove(configFile);
        var exe = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule!.FileName;
        var pathAndArgs = $"\"{exe}\" /service \"{configFile}\"";

        WithScm(scm =>
        {
            var service = Win32.CreateService(scm, ShortName(configFile), "Vaier tunnel",
                Win32.ServiceAccessRights.AllAccess, Win32.ServiceType.Win32OwnProcess,
                Win32.ServiceStartType.Auto, Win32.ServiceError.Normal, pathAndArgs, null, IntPtr.Zero,
                "Nsi\0TcpIp\0", null, null);
            if (service == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            try
            {
                // Without an unrestricted SID the tunnel cannot set its own firewall rules.
                var sidType = Win32.ServiceSidType.Unrestricted;
                if (!Win32.ChangeServiceConfig2(service, Win32.ServiceConfigType.SidInfo, ref sidType))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                var description = new Win32.ServiceDescription { lpDescription = "Keeps this computer in the Vaier fleet" };
                if (!Win32.ChangeServiceConfig2(service, Win32.ServiceConfigType.Description, ref description))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                if (!Win32.StartService(service, 0, null))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            finally
            {
                Win32.CloseServiceHandle(service);
            }
        });
    }

    /// <summary>Stops and deletes the service, waiting until it is gone so a new one can take its name.</summary>
    public static void Remove(string configFile)
    {
        WithScm(scm =>
        {
            var service = Win32.OpenService(scm, ShortName(configFile), Win32.ServiceAccessRights.AllAccess);
            if (service == IntPtr.Zero) return;
            try
            {
                var status = new Win32.ServiceStatus();
                Win32.ControlService(service, Win32.ServiceControl.Stop, status);
                for (var i = 0; i < 60 && Win32.QueryServiceStatus(service, status)
                                && status.dwCurrentState != Win32.ServiceState.Stopped; ++i)
                    Thread.Sleep(500);
                if (!Win32.DeleteService(service) && Marshal.GetLastWin32Error() != ServiceMarkedForDelete)
                    throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            finally
            {
                Win32.CloseServiceHandle(service);
            }
        });
    }

    public static bool IsRunning(string configFile)
    {
        var running = false;
        WithScm(scm =>
        {
            var service = Win32.OpenService(scm, ShortName(configFile), Win32.ServiceAccessRights.QueryStatus);
            if (service == IntPtr.Zero)
            {
                if (Marshal.GetLastWin32Error() != ServiceDoesNotExist) throw new Win32Exception(Marshal.GetLastWin32Error());
                return;
            }
            try
            {
                var status = new Win32.ServiceStatus();
                running = Win32.QueryServiceStatus(service, status) && status.dwCurrentState == Win32.ServiceState.Running;
            }
            finally
            {
                Win32.CloseServiceHandle(service);
            }
        });
        return running;
    }

    private static void WithScm(Action<IntPtr> use)
    {
        var scm = Win32.OpenSCManager(null, null, Win32.ScmAccessRights.AllAccess);
        if (scm == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        try
        {
            use(scm);
        }
        finally
        {
            Win32.CloseServiceHandle(scm);
        }
    }
}
