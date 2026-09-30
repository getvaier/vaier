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

        ApplicationConfiguration.Initialize();

        // A copy handing over to the installed one may still be closing, so the installed one waits for it.
        using var single = new Mutex(false, @"Global\VaierWindowsApp");
        try
        {
            if (!single.WaitOne(TimeSpan.FromSeconds(args is ["/installed", ..] ? 10 : 2)))
            {
                MessageBox.Show("Vaier is already open. Close it first, then try again.", "Vaier",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
        }
        catch (AbandonedMutexException)
        {
        }

        switch (args)
        {
            case ["/uninstall"]:
                Application.Run(new MainForm(MainForm.Opening.Uninstall));
                return;
            case ["/installed", .. var rest]:
                Installer.Finish(reconnect: rest is ["reconnect"]);
                break;
        }

        var setup = Installer.Decide();
        if (setup == Setup.OpenInstalled)
        {
            single.ReleaseMutex();
            Process.Start(Installer.Exe);
            return;
        }
        Application.Run(new MainForm(setup == Setup.Run ? MainForm.Opening.App : MainForm.Opening.Install));
    }
}
