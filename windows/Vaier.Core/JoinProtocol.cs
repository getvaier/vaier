using System.Text.Json;
using System.Text.Json.Nodes;

namespace Vaier.Core;

/// <summary>What Vaier answers when this device asks to join: the code to show, and the ticket to wait on.</summary>
public record JoinAnswer(string Code, string Ticket, long ExpiresInSeconds);

/// <summary>A refusal a person can read, because the only place it can be shown is the screen.</summary>
public class EnrolmentException(string message) : Exception(message);

/// <summary>The bodies this app sends to Vaier and the answer it reads back. The private key is never in any of them.</summary>
public static class JoinProtocol
{
    public static string JoinRequest(string name, string publicKey) =>
        new JsonObject { ["name"] = name, ["publicKey"] = publicKey, ["platform"] = "windows" }.ToJsonString();

    public static JoinAnswer ReadJoinAnswer(string body)
    {
        try
        {
            var answer = JsonDocument.Parse(body).RootElement;
            return new JoinAnswer(
                answer.GetProperty("code").GetString()!,
                answer.GetProperty("ticket").GetString()!,
                answer.GetProperty("expiresInSeconds").GetInt64());
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
        {
            throw new EnrolmentException("Vaier answered with something this computer could not read.");
        }
    }
}
