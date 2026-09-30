using System.ServiceProcess;
using Tunnel;

namespace Vaier.App;

/// <summary>
/// The one privileged process the app keeps running, as SYSTEM. It answers the window and the tray over the
/// pipe, and watches the tunnel for a removal nobody will be told about: with every route through the
/// tunnel, that question can only go out once the tunnel is down, so it cannot live in the tunnel service.
/// </summary>
public class ManagerService : ServiceBase
{
    public const string Name = "Vaier";

    private static readonly TimeSpan Beat = TimeSpan.FromSeconds(60);

    private readonly CancellationTokenSource _stop = new();
    private readonly Device _device = new();

    public ManagerService() => ServiceName = Name;

    /// <summary>Installs the manager, pointed at the installed exe, once.</summary>
    public static void Ensure()
    {
        if (!Services.Exists(Name))
            Services.Install(Name, "Vaier", "Keeps this computer in the Vaier fleet and answers the Vaier app",
                "/manager", unrestrictedSid: false, dependencies: null);
    }

    protected override void OnStart(string[] args)
    {
        _ = Pipe.Serve(_device, _stop.Token);
        _ = Watch();
    }

    protected override void OnStop() => _stop.Cancel();

    private async Task Watch()
    {
        try
        {
            await Quietly(_device.KeepAsWanted);
            await Quietly(_device.CheckOnStart);
            while (true)
            {
                await Task.Delay(Beat, _stop.Token);
                await Quietly(_device.KeepAsWanted);
                await Quietly(_device.Look);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    // One bad beat must not end the watch; the next one looks again.
    private static async Task Quietly(Func<Task> beat)
    {
        try
        {
            await beat();
        }
        catch (Exception)
        {
        }
    }
}
