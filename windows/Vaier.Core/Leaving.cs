namespace Vaier.Core;

/// <summary>How Vaier answered a request to leave. Removed covers "already gone", which is the same here.</summary>
public enum LeaveOutcome { Removed, Unreachable }

/// <summary>What this computer does about an answer from Vaier.</summary>
public record Steps(bool Forget, bool Reconnect, string? Notice);

/// <summary>
/// The tunnel goes down <b>before</b> the leave request is sent: Vaier removes the peer before it answers,
/// so an answer coming back through that tunnel would never arrive. The price is that a failure has to put
/// back exactly what was taken down, and only that.
/// </summary>
public static class Leaving
{
    private const string Unreachable =
        "Vaier couldn't be reached, so this computer is still in it. Check the connection and try again.";

    public static Steps After(LeaveOutcome outcome, bool wasConnected) => outcome switch
    {
        LeaveOutcome.Removed => new Steps(Forget: true, Reconnect: false, Notice: null),
        _ => new Steps(Forget: false, Reconnect: wasConnected, Notice: Unreachable),
    };
}
