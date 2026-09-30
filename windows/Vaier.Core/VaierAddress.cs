using System.Text.RegularExpressions;

namespace Vaier.Core;

/// <summary>The Vaier server's address, as a person types it and as this app must store it.</summary>
public static partial class VaierAddress
{
    [GeneratedRegex(@"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:\d{1,5})?$")]
    private static partial Regex Host();

    /// <summary>The bare host — no scheme, no path, lower case — or null if what was typed cannot be one.</summary>
    public static string? Normalise(string typed)
    {
        var host = new string(typed.Where(c => !char.IsWhiteSpace(c)).ToArray()).ToLowerInvariant();
        foreach (var scheme in new[] { "https://", "http://" })
            if (host.StartsWith(scheme)) host = host[scheme.Length..];
        var slash = host.IndexOf('/');
        if (slash >= 0) host = host[..slash];
        host = host.TrimEnd('.');
        return Host().IsMatch(host) ? host : null;
    }
}
