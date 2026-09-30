using Xunit;

namespace Vaier.Core.Tests;

public class VaierAddressTest
{
    [Theory]
    [InlineData("vaier.example.com", "vaier.example.com")]
    [InlineData("  Vaier.Example.COM  ", "vaier.example.com")]
    [InlineData("vaier.example .com", "vaier.example.com")]
    [InlineData("https://vaier.example.com/explorer.html?x=1", "vaier.example.com")]
    [InlineData("vaier.example.com.", "vaier.example.com")]
    [InlineData("vaier.example.com:8443", "vaier.example.com:8443")]
    [InlineData("nas", "nas")]
    [InlineData("", null)]
    [InlineData("https://", null)]
    [InlineData("not a host!", null)]
    public void A_typed_address_normalises_to_its_bare_host(string typed, string? expected)
    {
        Assert.Equal(expected, VaierAddress.Normalise(typed));
    }
}
