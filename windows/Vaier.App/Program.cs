using Tunnel;

namespace Vaier.App;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // The service control manager starts this same exe to carry the tunnel as SYSTEM.
        if (args is ["/service", var configFile])
        {
            TunnelService.Run(configFile);
            return;
        }

        using var single = new Mutex(true, @"Global\VaierWindowsApp", out var first);
        if (!first) return;

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
