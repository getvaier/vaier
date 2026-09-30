using System.Diagnostics;
using System.ServiceProcess;
using Tunnel;
using Vaier.Core;

namespace Vaier.App;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // The service control manager starts this same exe twice over: once to carry the tunnel, once to manage it.
        switch (args)
        {
            case ["/service", var configFile]:
                TunnelService.Run(configFile);
                return;
            case ["/manager"]:
                ServiceBase.Run(new ManagerService());
                return;
        }

        ApplicationConfiguration.Initialize();
        switch (args)
        {
            case ["/uninstall"]:
                if (Elevated.Now) Application.Run(new MainForm(MainForm.Opening.Uninstall));
                else Elevated.Run("/uninstall");
                return;
            case ["/repair"]:
                if (Elevated.Now) Installer.Repair();
                return;
        }

        switch (Installer.Decide())
        {
            case Setup.Install or Setup.Update:
                if (Elevated.Now) Application.Run(new MainForm(MainForm.Opening.Install));
                else Elevated.Run("");
                return;
            case Setup.OpenInstalled:
                Process.Start(Installer.Exe);
                return;
        }

        // The installed copy is the tray. A second start only asks the running one to show its window.
        using var single = new Mutex(true, @"Local\VaierTray", out var first);
        if (!first)
        {
            if (EventWaitHandle.TryOpenExisting(TrayApp.ShowEvent, out var show)) show.Set();
            return;
        }
        Application.Run(new TrayApp(showWindow: args is not ["/tray"]));
    }
}
