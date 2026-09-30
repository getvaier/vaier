using System.Text;
using Xunit;

namespace Vaier.Core.Tests;

public class SetupStampTest
{
    private static readonly byte[] Exe = Encoding.ASCII.GetBytes("MZ...the app itself...");

    private static byte[] Stamped(byte[] exe, string host, int? claimedLength = null)
    {
        var bytes = Encoding.UTF8.GetBytes(host);
        return [.. exe, .. bytes, .. BitConverter.GetBytes(claimedLength ?? bytes.Length), .. Encoding.ASCII.GetBytes("VAIERHOST1")];
    }

    public static TheoryData<byte[], string?> Downloads => new()
    {
        { Stamped(Exe, "vaier.example.com"), "vaier.example.com" },   // served by Vaier
        { Exe, null },                                                // built by hand, never served
        { Stamped(Exe, "vaier.example.com", claimedLength: 999_999), null }, // a length pointing past the file
        { Stamped(Exe, "not a host!"), null },                        // a trailer, but no host in it
        { Stamped([], "vaier.example.com")[1..], null },              // too short to hold a trailer
    };

    [Theory]
    [MemberData(nameof(Downloads))]
    public void The_host_Vaier_appended_to_the_setup_is_read_back_and_nothing_else_is(byte[] file, string? expected)
    {
        Assert.Equal(expected, SetupStamp.Read(new MemoryStream(file)));
    }
}
