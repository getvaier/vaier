using System.Text;
using System.Text.Json;

namespace Vaier.Core;

/// <summary>What Vaier sent down this device's own stream once it was approved, after we know it is ours.</summary>
public record Enrolment(string PeerId, string PeerName, string TunnelAddress, string ConfigText);

/// <summary>What this computer's saved config says about it: the keys that prove it, and its tunnel address.</summary>
public record SavedConfig(string PublicKey, string PresharedKey, string TunnelAddress)
{
    public static SavedConfig Read(string configText)
    {
        var lines = EnrolmentPayload.Lines(configText);
        return new SavedConfig(EnrolmentPayload.Text(EnrolmentPayload.MetadataIn(lines), "publicKey"),
            EnrolmentPayload.ValueOf(lines, "PresharedKey"), EnrolmentPayload.ValueOf(lines, "Address"));
    }
}

/// <summary>
/// Reads the <c>approved</c> event's data: base64url of a WireGuard config that Vaier deliberately left
/// without a private key. This app supplies the private half, which never leaves the computer.
/// </summary>
public static class EnrolmentPayload
{
    private const string Marker = "# VAIER:";

    public static Enrolment Parse(string encoded, string publicKey, string privateKey)
    {
        if (string.IsNullOrWhiteSpace(encoded))
            throw new EnrolmentException("Vaier approved this computer but sent nothing with it.");

        var lines = Lines(Decode(encoded.Trim()));
        var metadata = MetadataIn(lines);

        if (Text(metadata, "publicKey") != publicKey)
            throw new EnrolmentException("That was meant for a different device. Start again.");
        if (lines.Any(line => line.TrimStart().StartsWith("PrivateKey", StringComparison.OrdinalIgnoreCase)))
            throw new EnrolmentException("That carries a private key. Vaier never sends one, so it is refused.");

        var section = lines.FindIndex(line => line.Trim().Equals("[Interface]", StringComparison.OrdinalIgnoreCase));
        if (section < 0) throw new EnrolmentException("What Vaier sent has no [Interface] section.");
        lines.Insert(section + 1, $"PrivateKey = {privateKey}");

        return new Enrolment(Text(metadata, "id"), Text(metadata, "name"), ValueOf(lines, "Address"),
            string.Join('\n', lines));
    }

    private static string Decode(string encoded)
    {
        var base64 = encoded.Replace('-', '+').Replace('_', '/');
        base64 = base64.PadRight(base64.Length + (4 - base64.Length % 4) % 4, '=');
        try
        {
            return Encoding.UTF8.GetString(Convert.FromBase64String(base64));
        }
        catch (FormatException)
        {
            throw new EnrolmentException("What Vaier sent back is damaged. Try joining again.");
        }
    }

    internal static List<string> Lines(string text) => text.Split('\n').Select(line => line.TrimEnd('\r')).ToList();

    internal static JsonElement MetadataIn(List<string> lines)
    {
        var line = lines.FirstOrDefault(l => l.TrimStart().StartsWith(Marker))
            ?? throw new EnrolmentException("That answer is not from Vaier, so it is refused.");
        try
        {
            return JsonDocument.Parse(line.TrimStart()[Marker.Length..]).RootElement;
        }
        catch (JsonException)
        {
            throw new EnrolmentException("That answer is not from Vaier, so it is refused.");
        }
    }

    internal static string Text(JsonElement metadata, string field) =>
        metadata.TryGetProperty(field, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()!
            : "";

    internal static string ValueOf(List<string> lines, string key) =>
        lines.Where(line => line.Contains('='))
            .FirstOrDefault(line => line[..line.IndexOf('=')].Trim().Equals(key, StringComparison.OrdinalIgnoreCase))
            is { } found ? found[(found.IndexOf('=') + 1)..].Trim() : "";
}
