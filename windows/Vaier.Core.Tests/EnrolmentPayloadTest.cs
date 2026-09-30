using System.Text;
using Xunit;

namespace Vaier.Core.Tests;

public class EnrolmentPayloadTest
{
    private const string OurPublicKey = "eA1uZ0nOhTn2Yz0kQvVYcWkZ0m7l7pQ5rXcSbW8xTiE=";
    private const string OurPrivateKey = "cM1hZ0nOhTn2Yz0kQvVYcWkZ0m7l7pQ5rXcSbW8xTkA=";

    private static string Conf(string publicKey = OurPublicKey) => $$"""
        # VAIER: {"peerType":"MOBILE_CLIENT","name":"Dell laptop","publicKey":"{{publicKey}}","id":"9f2c"}
        [Interface]
        Address = 10.13.13.7/32
        DNS = 172.20.0.53
        [Peer]
        PublicKey = HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=
        PresharedKey = FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE=
        Endpoint = vaier.example.com:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
        """;

    // Base64url, unpadded — the bytes of "é" (0xC3 0xA9) put the URL alphabet to work.
    private static string Encoded(string text) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes(text)).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static Enrolment Parse(string text) => EnrolmentPayload.Parse(Encoded(text), OurPublicKey, OurPrivateKey);

    [Fact]
    public void An_approval_becomes_a_config_carrying_our_private_key_and_everything_Vaier_sent()
    {
        var enrolment = Parse(Conf().Replace("Dell laptop", "Dell laptopé"));

        Assert.Equal("Dell laptopé", enrolment.PeerName);
        Assert.Equal("10.13.13.7/32", enrolment.TunnelAddress);

        var lines = enrolment.ConfigText.Split('\n');
        Assert.Equal($"PrivateKey = {OurPrivateKey}", lines[Array.IndexOf(lines, "[Interface]") + 1]);
        Assert.Single(lines, line => line.StartsWith("PrivateKey"));
        foreach (var line in Conf().Split('\n').Skip(1))
            Assert.Contains(line, lines);
    }

    public static TheoryData<string> Refused => new()
    {
        Conf(publicKey: "someone+else="),
        Conf().Replace("[Interface]", "[Interface]\nPrivateKey = leaked="),
        Conf().Replace("# VAIER:", "#"),
        Conf().Replace("[Interface]", ""),
    };

    [Theory]
    [MemberData(nameof(Refused))]
    public void An_approval_that_is_not_ours_or_not_from_Vaier_is_refused(string conf)
    {
        Assert.Throws<EnrolmentException>(() => Parse(conf));
    }

    [Theory]
    [InlineData("")]
    [InlineData("!!not base64!!")]
    public void An_empty_or_damaged_approval_is_refused(string encoded)
    {
        Assert.Throws<EnrolmentException>(() => EnrolmentPayload.Parse(encoded, OurPublicKey, OurPrivateKey));
    }
}
