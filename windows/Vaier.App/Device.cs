using Tunnel;
using Vaier.Core;

namespace Vaier.App;

/// <summary>
/// Everything that needs SYSTEM: joining, the key, the tunnel, leaving, and noticing a removal. It lives in
/// the manager service; the window and the tray only ask it over the pipe, so neither needs admin.
/// </summary>
public class Device
{
    private readonly VaierClient _vaier = new();
    private readonly SemaphoreSlim _one = new(1, 1);
    private PendingJoin? _pending;
    private long _connectedSince;
    private long _lastAsked;

    private record PendingJoin(Pending Shown, string Ticket, Keypair Keypair, CancellationTokenSource Cancel);

    public DeviceStatus Status()
    {
        var membership = DeviceStore.Load();
        var up = membership is not null && TunnelService.IsUp(membership.ConfigFile);
        var peer = up ? TunnelService.Peer(membership!.ConfigFile) : null;
        var handshake = peer is { LastHandshake: var at } && at != default ? new DateTimeOffset(at).ToUnixTimeMilliseconds() : 0;
        var member = membership is null ? null
            : new Member(membership.Name, membership.Address, membership.Saved.TunnelAddress);
        return new DeviceStatus(member, up, handshake, peer?.RxBytes ?? 0, peer?.TxBytes ?? 0,
            _pending?.Shown, DeviceStore.PeekNotice());
    }

    public void TakeNotice() => DeviceStore.TakeNotice();

    public Task Connect() => Only(() => Act(m =>
    {
        DeviceStore.WantsConnected = true;
        TunnelService.Up(m.ConfigFile);
        _ = Greet(m);
    }));

    /// <summary>The goodbye goes through the tunnel, so it is said before the teardown.</summary>
    public Task Disconnect() => Only(async () =>
    {
        if (DeviceStore.Load() is not { } membership) return;
        DeviceStore.WantsConnected = false;
        if (TunnelService.IsUp(membership.ConfigFile)) await _vaier.SayGoodbye(membership);
        TunnelService.Down(membership.ConfigFile);
    });

    /// <summary>Puts the tunnel back when the person wants it on and it is not — after an update, say.</summary>
    public Task KeepAsWanted() => Only(() => Act(m =>
    {
        if (!DeviceStore.WantsConnected || TunnelService.IsUp(m.ConfigFile)) return;
        TunnelService.Up(m.ConfigFile);
        _ = Greet(m);
    }));

    /// <summary>Asks to join; the answer is waited for here, so closing the window does not lose it.</summary>
    public async Task<string?> Join(string address, string name)
    {
        await _one.WaitAsync();
        try
        {
            if (DeviceStore.Load() is not null) return "This computer is already in Vaier.";
            _pending?.Cancel.Cancel();
            var keypair = Keypair.Generate();
            var outcome = await _vaier.AskToJoin(address, name, keypair.Public);
            if (outcome is JoinOutcome.Turned turned) return turned.Reason;

            var answer = ((JoinOutcome.Waiting)outcome).Answer;
            var shown = new Pending(answer.Code, address,
                DateTimeOffset.UtcNow.AddSeconds(answer.ExpiresInSeconds).ToUnixTimeMilliseconds());
            var pending = _pending = new PendingJoin(shown, answer.Ticket, keypair, new CancellationTokenSource());
            _ = Wait(pending);
            return null;
        }
        finally
        {
            _one.Release();
        }
    }

    public void CancelJoin()
    {
        _pending?.Cancel.Cancel();
        _pending = null;
    }

    /// <summary>The tunnel goes down before the request: Vaier removes the peer before it answers.</summary>
    public Task Leave() => Only(async () =>
    {
        if (DeviceStore.Load() is not { } membership) return;
        var wasUp = TunnelService.IsUp(membership.ConfigFile);
        TunnelService.Down(membership.ConfigFile);
        Follow(membership, Leaving.After(await _vaier.Leave(membership), wasUp));
    });

    /// <summary>Started with the tunnel off, nothing is watching — so ask once, over ordinary internet.</summary>
    public Task CheckOnStart() => Only(async () =>
    {
        if (DeviceStore.Load() is not { } membership || TunnelService.IsUp(membership.ConfigFile)) return;
        Follow(membership, StandingWatch.AfterOpening(await _vaier.AskStanding(membership)));
    });

    /// <summary>One beat of the watch. <see cref="StandingWatch"/> owns every judgement.</summary>
    public Task Look() => Only(async () =>
    {
        var membership = DeviceStore.Load();
        if (membership is null || !TunnelService.IsUp(membership.ConfigFile))
        {
            _connectedSince = 0;
            return;
        }
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (_connectedSince == 0) _connectedSince = now;
        if (!StandingWatch.WorthAsking(now, Status().LastHandshakeMillis, _connectedSince, _lastAsked)) return;

        _lastAsked = now;
        TunnelService.Down(membership.ConfigFile);
        Follow(membership, StandingWatch.AfterSilence(await _vaier.AskStanding(membership)));
        if (TunnelService.IsUp(membership.ConfigFile)) _connectedSince = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    });

    private async Task Wait(PendingJoin pending)
    {
        string? notice = "That join code ran out. Ask again.";
        try
        {
            while (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() < pending.Shown.ExpiresAtMillis)
            {
                var (verdict, payload) = await _vaier.AwaitVerdict(pending.Shown.Address, pending.Ticket, pending.Cancel.Token);
                if (verdict == Verdict.Approved)
                {
                    var enrolment = EnrolmentPayload.Parse(payload, pending.Keypair.Public, pending.Keypair.Private);
                    var membership = DeviceStore.Save(pending.Shown.Address, enrolment.PeerName, enrolment.ConfigText);
                    DeviceStore.WantsConnected = true;
                    TunnelService.Up(membership.ConfigFile);
                    notice = null;
                    return;
                }
                if (verdict == Verdict.Refused)
                {
                    notice = "Vaier turned this computer away.";
                    return;
                }
                if (verdict == Verdict.Gone) return;
                await Task.Delay(TimeSpan.FromSeconds(3), pending.Cancel.Token);
            }
        }
        catch (OperationCanceledException)
        {
            notice = null;
        }
        catch (EnrolmentException e)
        {
            notice = e.Message;
        }
        catch (Exception e)
        {
            notice = $"This computer was let in, but its tunnel would not start: {e.Message}";
        }
        finally
        {
            if (_pending == pending) _pending = null;
            if (notice is not null) DeviceStore.LeaveNotice(notice);
        }
    }

    // Says hello only once the tunnel has shaken hands; before that it would leave by the ordinary route.
    private async Task Greet(Membership membership)
    {
        try
        {
            for (var waited = 0; TunnelService.Peer(membership.ConfigFile)?.LastHandshake is not { } at || at == default; waited += 250)
            {
                if (waited >= 5000) return;
                await Task.Delay(250);
            }
            await _vaier.SayHello(membership);
        }
        catch (Exception)
        {
        }
    }

    private static void Follow(Membership membership, Steps steps)
    {
        if (steps.Reconnect) TunnelService.Up(membership.ConfigFile);
        if (steps.Forget) DeviceStore.Forget();
        if (steps.Notice is not null) DeviceStore.LeaveNotice(steps.Notice);
    }

    private static Task Act(Action<Membership> act)
    {
        if (DeviceStore.Load() is { } membership) act(membership);
        return Task.CompletedTask;
    }

    // Joining, leaving, switching and the watch never overlap: each assumes the tunnel stays as it left it.
    private async Task Only(Func<Task> act)
    {
        await _one.WaitAsync();
        try
        {
            await act();
        }
        finally
        {
            _one.Release();
        }
    }
}
