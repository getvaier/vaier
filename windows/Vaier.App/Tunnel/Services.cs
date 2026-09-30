using System.ComponentModel;
using System.Runtime.InteropServices;
using Vaier.App;

namespace Tunnel;

/// <summary>The few service control manager calls the app needs, for both the tunnel and the manager service.</summary>
public static class Services
{
    private const int ServiceDoesNotExist = 0x424;
    private const int ServiceAlreadyRunning = 0x420;
    private const int ServiceMarkedForDelete = 0x430;

    public static void Install(string name, string displayName, string description, string arguments,
                               bool unrestrictedSid, string? dependencies)
    {
        // Services always run the installed copy, even when the setup in Downloads is the one creating them.
        var pathAndArgs = $"\"{Installer.Exe}\" {arguments}";
        WithScm(scm =>
        {
            // A service just removed stays "marked for deletion" for a moment; its name frees up once it has gone.
            var service = IntPtr.Zero;
            for (var attempt = 1; service == IntPtr.Zero; attempt++)
            {
                service = Win32.CreateService(scm, name, displayName, Win32.ServiceAccessRights.AllAccess,
                    Win32.ServiceType.Win32OwnProcess, Win32.ServiceStartType.Auto, Win32.ServiceError.Normal,
                    pathAndArgs, null, IntPtr.Zero, dependencies, null, null);
                if (service != IntPtr.Zero) break;
                var error = Marshal.GetLastWin32Error();
                if (error != ServiceMarkedForDelete || attempt == 20) throw new Win32Exception(error);
                Thread.Sleep(500);
            }
            try
            {
                if (unrestrictedSid)
                {
                    var sidType = Win32.ServiceSidType.Unrestricted;
                    if (!Win32.ChangeServiceConfig2(service, Win32.ServiceConfigType.SidInfo, ref sidType))
                        throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                var about = new Win32.ServiceDescription { lpDescription = description };
                if (!Win32.ChangeServiceConfig2(service, Win32.ServiceConfigType.Description, ref about))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            finally
            {
                Win32.CloseServiceHandle(service);
            }
        });
        Start(name);
    }

    public static bool Exists(string name) => Open(name, Win32.ServiceAccessRights.QueryStatus, _ => true);

    public static bool IsRunning(string name) => Open(name, Win32.ServiceAccessRights.QueryStatus, service =>
    {
        var status = new Win32.ServiceStatus();
        return Win32.QueryServiceStatus(service, status) && status.dwCurrentState == Win32.ServiceState.Running;
    });

    public static void Start(string name) => Open(name, Win32.ServiceAccessRights.AllAccess, service =>
    {
        if (!Win32.StartService(service, 0, null) && Marshal.GetLastWin32Error() != ServiceAlreadyRunning)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        return true;
    });

    /// <summary>Stops and deletes the service, waiting until it has stopped so a new one can take its name.</summary>
    public static void Remove(string name) => Open(name, Win32.ServiceAccessRights.AllAccess, service =>
    {
        var status = new Win32.ServiceStatus();
        Win32.ControlService(service, Win32.ServiceControl.Stop, status);
        for (var i = 0; i < 60 && Win32.QueryServiceStatus(service, status)
                        && status.dwCurrentState != Win32.ServiceState.Stopped; ++i)
            Thread.Sleep(500);
        if (!Win32.DeleteService(service) && Marshal.GetLastWin32Error() != ServiceMarkedForDelete)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        return true;
    });

    /// <summary>Runs <paramref name="use"/> on the named service; false, without running it, when there is none.</summary>
    private static bool Open(string name, Win32.ServiceAccessRights rights, Func<IntPtr, bool> use)
    {
        var result = false;
        WithScm(scm =>
        {
            var service = Win32.OpenService(scm, name, rights);
            if (service == IntPtr.Zero)
            {
                if (Marshal.GetLastWin32Error() != ServiceDoesNotExist) throw new Win32Exception(Marshal.GetLastWin32Error());
                return;
            }
            try
            {
                result = use(service);
            }
            finally
            {
                Win32.CloseServiceHandle(service);
            }
        });
        return result;
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
