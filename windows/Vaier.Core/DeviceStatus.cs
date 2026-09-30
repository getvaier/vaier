namespace Vaier.Core;

/// <summary>This computer's place in a fleet, as the manager service reports it over its pipe.</summary>
public record Member(string Name, string Address, string TunnelAddress);

/// <summary>A join asked and not yet answered.</summary>
public record Pending(string Code, string Address, long ExpiresAtMillis);

/// <summary>Everything the window and the tray show, read from the one process allowed to know it.</summary>
public record DeviceStatus(Member? Member, bool Up, long LastHandshakeMillis, ulong Received, ulong Sent,
                           Pending? Pending, string? Notice);

/// <summary>What the tray icon looks like: lit only while connected, and a line saying where things stand.</summary>
public record TrayLook(bool Lit, string Tooltip)
{
    /// <param name="status">null when the manager service could not be reached.</param>
    public static TrayLook Of(DeviceStatus? status) => status switch
    {
        null => new(false, "Vaier — its service is not running"),
        { Member: { } m, Up: true } => new(true, $"Vaier — {m.Name} is connected"),
        { Member: { } m } => new(false, $"Vaier — {m.Name} is not connected"),
        { Pending: { } p } => new(false, $"Vaier — waiting to join, code {p.Code}"),
        _ => new(false, "Vaier — not joined"),
    };
}
