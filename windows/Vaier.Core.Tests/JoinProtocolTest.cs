using System.Text.Json;
using Xunit;

namespace Vaier.Core.Tests;

public class JoinProtocolTest
{
    [Fact]
    public void Asking_to_join_sends_name_key_and_platform_and_reads_back_code_and_ticket()
    {
        var sent = JsonDocument.Parse(JoinProtocol.JoinRequest("Dell laptop", "pub=")).RootElement;
        Assert.Equal("Dell laptop", sent.GetProperty("name").GetString());
        Assert.Equal("pub=", sent.GetProperty("publicKey").GetString());
        Assert.Equal("windows", sent.GetProperty("platform").GetString());

        Assert.Equal(new JoinAnswer("4821", "t0k3n", 600),
            JoinProtocol.ReadJoinAnswer("""{"code":"4821","ticket":"t0k3n","expiresInSeconds":600}"""));
    }

    [Fact]
    public void Leaving_and_asking_about_standing_prove_who_is_asking_with_both_keys()
    {
        var sent = JsonDocument.Parse(JoinProtocol.Proof("pub=", "psk=")).RootElement;

        Assert.Equal("pub=", sent.GetProperty("publicKey").GetString());
        Assert.Equal("psk=", sent.GetProperty("presharedKey").GetString());
    }

    [Theory]
    [InlineData("<html>")]
    [InlineData("""{"code":"4821"}""")]
    public void An_answer_that_is_not_a_join_answer_is_refused(string body)
    {
        Assert.Throws<EnrolmentException>(() => JoinProtocol.ReadJoinAnswer(body));
    }
}
