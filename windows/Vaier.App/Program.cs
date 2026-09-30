using System.ServiceProcess;
using Tunnel;

namespace Vaier.App;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // The service control manager starts this same exe twice over: once to carry the tunnel, once to watch it.
        switch (args)
        {
            case ["/service", var configFile]:
                TunnelService.Run(configFile);
                return;
            case ["/manager"]:
                ServiceBase.Run(new ManagerService());
                return;
        }

        using var single = new Mutex(true, @"Global\VaierWindowsApp", out var first);
        if (!first) return;

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
