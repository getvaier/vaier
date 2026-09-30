namespace Vaier.Core;

/// <summary>How Vaier answered when this computer asked whether it is still one of its devices.</summary>
public enum Standing { Member, Removed, Unreachable }

/// <summary>
/// When a connected computer should ask Vaier whether it is still wanted. A removed peer is never told —
/// Vaier just stops answering its handshakes — so silence is the only signal, and asking means taking the
/// tunnel down to go out over ordinary internet. Hence the two guards: quiet for three minutes, and never
/// more than one interruption in five.
/// </summary>
public static class StandingWatch
{
    public const long SilenceMillis = 3 * 60_000L;
    public const long PatienceMillis = 5 * 60_000L;

    public const string Notice = "Vaier removed this computer. Join again to reconnect.";

    public static bool WorthAsking(long now, long lastHandshakeMillis, long connectedSinceMillis, long lastAskedMillis)
    {
        if (now - lastAskedMillis < PatienceMillis) return false;
        // Never in touch: judged from when it came up, so one removed before it was switched on still finds out.
        var quietSince = lastHandshakeMillis > 0 ? lastHandshakeMillis : connectedSinceMillis;
        return now - quietSince > SilenceMillis;
    }

    public static Steps AfterSilence(Standing outcome) => outcome == Standing.Removed
        ? new Steps(Forget: true, Reconnect: false, Notice: Notice)
        : new Steps(Forget: false, Reconnect: true, Notice: null);

    /// <summary>Opening the app with the tunnel off took nothing down, so nothing is put back.</summary>
    public static Steps AfterOpening(Standing outcome) => outcome == Standing.Removed
        ? new Steps(Forget: true, Reconnect: false, Notice: Notice)
        : new Steps(Forget: false, Reconnect: false, Notice: null);
}
