using System.Net;
using System.Text;
using Vaier.Core;

namespace Vaier.App;

/// <summary>How the ask to join went: waiting with a code, or turned away with a reason.</summary>
public abstract record JoinOutcome
{
    public record Waiting(JoinAnswer Answer) : JoinOutcome;
    public record Turned(string Reason) : JoinOutcome;
}

public enum Verdict { Approved, Refused, Gone, Lost }

/// <summary>
/// The only place this app talks to Vaier. Every route it uses is anonymous: a computer that has not
/// been let in yet has no session and is never asked to sign in for one.
/// </summary>
public class VaierClient
{
    private const string Unreachable = "Vaier could not be reached. Check the address and the internet connection.";

    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };

    public async Task<JoinOutcome> AskToJoin(string address, string deviceName, string publicKey)
    {
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            using var response = await Http.PostAsync($"https://{address}/vpn/enrolments",
                new StringContent(JoinProtocol.JoinRequest(deviceName, publicKey), Encoding.UTF8, "application/json"),
                timeout.Token);
            return response.StatusCode switch
            {
                HttpStatusCode.OK => new JoinOutcome.Waiting(JoinProtocol.ReadJoinAnswer(await response.Content.ReadAsStringAsync())),
                HttpStatusCode.BadRequest => new JoinOutcome.Turned("Vaier would not take that name. Try a different one."),
                HttpStatusCode.Conflict => new JoinOutcome.Turned("Too many devices are waiting to join right now. Try again in a few minutes."),
                HttpStatusCode.TooManyRequests => new JoinOutcome.Turned("That was too many tries at once. Wait a minute, then try again."),
                _ => new JoinOutcome.Turned("Vaier could not take this request. Try again in a moment."),
            };
        }
        catch (EnrolmentException e)
        {
            return new JoinOutcome.Turned(e.Message);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            return new JoinOutcome.Turned(Unreachable);
        }
    }

    /// <summary>404 is a removal too: whatever this computer was, Vaier does not have it any more.</summary>
    public async Task<LeaveOutcome> Leave(Membership membership)
    {
        using var response = await Prove(membership, "leave");
        return response?.StatusCode is HttpStatusCode.NoContent or HttpStatusCode.NotFound
            ? LeaveOutcome.Removed
            : LeaveOutcome.Unreachable;
    }

    /// <summary>
    /// Whether this computer is still one of Vaier's devices. Must go over ordinary internet: a removed
    /// computer's tunnel is a black hole, so an answer down it never comes.
    /// </summary>
    public async Task<Standing> AskStanding(Membership membership)
    {
        using var response = await Prove(membership, "standing");
        return response?.StatusCode switch
        {
            HttpStatusCode.NoContent => Standing.Member,
            HttpStatusCode.NotFound => Standing.Removed,
            _ => Standing.Unreachable,
        };
    }

    /// <summary>Tells Vaier, through the tunnel, that it just connected. A few tries, since the first can race the tunnel.</summary>
    public Task SayHello(Membership membership) => Tell(membership, "hello", TimeSpan.FromSeconds(3), attempts: 3);

    /// <summary>Tells Vaier, through the tunnel, that it is going down on purpose. One try, never more.</summary>
    public Task SayGoodbye(Membership membership) => Tell(membership, "goodbye", TimeSpan.FromSeconds(2), attempts: 1);

    // Never over the open internet, and never on a pooled connection: one opened through an earlier tunnel is dead.
    private static async Task Tell(Membership membership, string route, TimeSpan patience, int attempts)
    {
        var saved = membership.Saved;
        for (var attempt = 1; attempt <= attempts; attempt++)
        {
            try
            {
                using var timeout = new CancellationTokenSource(patience);
                using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(TunnelDoor.Address, $"/vpn/peers/{route}"))
                {
                    Content = new StringContent(JoinProtocol.Proof(saved.PublicKey, saved.PresharedKey), Encoding.UTF8, "application/json"),
                };
                request.Headers.ConnectionClose = true;
                using var _ = await Http.SendAsync(request, timeout.Token);
                return;
            }
            catch (Exception)
            {
                if (attempt < attempts) await Task.Delay(TimeSpan.FromSeconds(1));
            }
        }
    }

    private static Task<HttpResponseMessage?> Prove(Membership membership, string route) =>
        Prove(new Uri($"https://{membership.Address}/vpn/peers/{route}"), membership, TimeSpan.FromSeconds(20));

    private static async Task<HttpResponseMessage?> Prove(Uri to, Membership membership, TimeSpan patience)
    {
        var saved = membership.Saved;
        try
        {
            using var timeout = new CancellationTokenSource(patience);
            return await Http.PostAsync(to,
                new StringContent(JoinProtocol.Proof(saved.PublicKey, saved.PresharedKey), Encoding.UTF8, "application/json"),
                timeout.Token);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            return null;
        }
    }

    /// <summary>Holds this computer's own stream open until Vaier decides, the connection drops, or the caller cancels.</summary>
    public async Task<(Verdict Verdict, string Payload)> AwaitVerdict(string address, string ticket, CancellationToken cancel)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get,
                $"https://{address}/vpn/enrolments/{Uri.EscapeDataString(ticket)}/events");
            request.Headers.Accept.ParseAdd("text/event-stream");
            using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            if (response.StatusCode == HttpStatusCode.Gone) return (Verdict.Gone, "");
            if (response.StatusCode != HttpStatusCode.OK) return (Verdict.Lost, "");

            using var reader = new StreamReader(await response.Content.ReadAsStreamAsync(cancel));
            var stream = new EventStream();
            while (await reader.ReadLineAsync(cancel) is { } line)
            {
                switch (stream.Accept(line))
                {
                    case { Name: "approved" } approved: return (Verdict.Approved, approved.Data);
                    case { Name: "refused" }: return (Verdict.Refused, "");
                }
            }
            return (Verdict.Lost, "");
        }
        catch (Exception e) when (e is HttpRequestException or IOException || (e is OperationCanceledException && !cancel.IsCancellationRequested))
        {
            return (Verdict.Lost, "");
        }
    }
}
