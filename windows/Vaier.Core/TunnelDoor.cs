namespace Vaier.Core;

/// <summary>Vaier's tunnel-only door: reachable only through WireGuard, so plain HTTP is enough.</summary>
public static class TunnelDoor
{
    public static readonly Uri Address = new("http://172.20.0.251:8090");
}
