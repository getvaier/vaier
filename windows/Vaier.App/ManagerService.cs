using System.ServiceProcess;
using Tunnel;
using Vaier.Core;

namespace Vaier.App;

/// <summary>
/// Notices, while the tunnel is up, that Vaier does not have this computer any more. It runs as its own
/// service because the tunnel service cannot ask: with every route through the tunnel, the question has
/// to go out after the tunnel is taken down — and the window may be closed for weeks.
/// <see cref="StandingWatch"/> owns every judgement; this class only keeps time and does as it is told.
/// </summary>
public class ManagerService : ServiceBase
{
    public const string Name = "Vaier";

    private static readonly TimeSpan Beat = TimeSpan.FromSeconds(60);

    private readonly CancellationTokenSource _stop = new();
    private readonly VaierClient _vaier = new();
    private long _connectedSince;
    private long _lastAsked;

    public ManagerService() => ServiceName = Name;

    /// <summary>Installs the manager once; a computer that joined before it existed gets it on the next open.</summary>
    public static void Ensure()
    {
        if (!Services.Exists(Name))
            Services.Install(Name, "Vaier", "Notices when Vaier has removed this computer",
                "/manager", unrestrictedSid: false, dependencies: null);
    }

    protected override void OnStart(string[] args) => _ = Watch();

    protected override void OnStop() => _stop.Cancel();

    private async Task Watch()
    {
        try
        {
            while (true)
            {
                await Task.Delay(Beat, _stop.Token);
                try
                {
                    await Look();
                }
                catch (Exception)
                {
                    // One bad beat must not end the watch; the next one looks again.
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task Look()
    {
        var membership = DeviceStore.Load();
        if (membership is null || !TunnelService.IsUp(membership.ConfigFile))
        {
            _connectedSince = 0;
            return;
        }

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (_connectedSince == 0) _connectedSince = now;
        var handshake = TunnelService.Peer(membership.ConfigFile)?.LastHandshake;
        var lastHandshake = handshake is { } at && at != default ? new DateTimeOffset(at).ToUnixTimeMilliseconds() : 0;
        if (!StandingWatch.WorthAsking(now, lastHandshake, _connectedSince, _lastAsked)) return;

        _lastAsked = now;
        TunnelService.Down(membership.ConfigFile);
        var steps = StandingWatch.AfterSilence(await _vaier.AskStanding(membership));
        if (steps.Reconnect)
        {
            TunnelService.Up(membership.ConfigFile);
            _connectedSince = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }
        if (steps.Forget)
        {
            DeviceStore.Forget();
            DeviceStore.LeaveNotice(steps.Notice!);
        }
    }
}
