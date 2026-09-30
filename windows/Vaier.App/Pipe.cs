using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;
using Vaier.Core;

namespace Vaier.App;

public record PipeRequest(string Op, string? Address = null, string? Name = null);

public record PipeAnswer(DeviceStatus? Status = null, string? Error = null);

/// <summary>
/// How the window and the tray reach the manager service: one JSON line in, one out, per connection. Only
/// SYSTEM, Administrators and people signed in at this computer may open it.
/// </summary>
public static class Pipe
{
    private const string Name = "Vaier";

    public static async Task Serve(Device device, CancellationToken stop)
    {
        var security = new PipeSecurity();
        foreach (var (who, rights) in new[]
                 {
                     (WellKnownSidType.LocalSystemSid, PipeAccessRights.FullControl),
                     (WellKnownSidType.BuiltinAdministratorsSid, PipeAccessRights.FullControl),
                     (WellKnownSidType.InteractiveSid, PipeAccessRights.ReadWrite),
                 })
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(who, null), rights, AccessControlType.Allow));

        while (!stop.IsCancellationRequested)
        {
            var server = NamedPipeServerStreamAcl.Create(Name, PipeDirection.InOut, NamedPipeServerStream.MaxAllowedServerInstances,
                PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 0, 0, security);
            await server.WaitForConnectionAsync(stop);
            _ = Answer(device, server);
        }
    }

    private static async Task Answer(Device device, NamedPipeServerStream server)
    {
        await using var _ = server;
        try
        {
            using var reader = new StreamReader(server, leaveOpen: true);
            var request = JsonSerializer.Deserialize<PipeRequest>(await reader.ReadLineAsync() ?? "{}")!;
            string? error = null;
            switch (request.Op)
            {
                case "connect": await device.Connect(); break;
                case "disconnect": await device.Disconnect(); break;
                case "join": error = await device.Join(request.Address!, request.Name!); break;
                case "cancel-join": device.CancelJoin(); break;
                case "leave": await device.Leave(); break;
                case "take-notice": device.TakeNotice(); break;
            }
            await Write(server, new PipeAnswer(device.Status(), error));
        }
        catch (Exception e)
        {
            await Write(server, new PipeAnswer(Error: e.Message));
        }
    }

    private static async Task Write(Stream server, PipeAnswer answer)
    {
        await using var writer = new StreamWriter(server, leaveOpen: true);
        await writer.WriteLineAsync(JsonSerializer.Serialize(answer));
    }

    /// <summary>Asks the manager service; null when it is not there to ask.</summary>
    public static async Task<PipeAnswer?> Ask(string op, string? address = null, string? name = null)
    {
        try
        {
            await using var client = new NamedPipeClientStream(".", Name, PipeDirection.InOut, PipeOptions.Asynchronous);
            await client.ConnectAsync(2000);
            await using (var writer = new StreamWriter(client, leaveOpen: true))
                await writer.WriteLineAsync(JsonSerializer.Serialize(new PipeRequest(op, address, name)));
            using var reader = new StreamReader(client);
            return JsonSerializer.Deserialize<PipeAnswer>(await reader.ReadLineAsync() ?? "null");
        }
        catch (Exception e) when (e is IOException or TimeoutException or UnauthorizedAccessException or JsonException)
        {
            return null;
        }
    }
}
