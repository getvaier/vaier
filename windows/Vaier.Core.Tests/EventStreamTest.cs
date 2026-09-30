using Xunit;

namespace Vaier.Core.Tests;

public class EventStreamTest
{
    private static List<ServerEvent> Read(params string[] lines)
    {
        var stream = new EventStream();
        return lines.Select(stream.Accept).OfType<ServerEvent>().ToList();
    }

    [Fact]
    public void Events_complete_on_the_blank_line_and_follow_one_another()
    {
        Assert.Equal(
            [new ServerEvent("approved", "abc"), new ServerEvent("refused", "")],
            Read(": keepalive", "retry: 3000", "event: approved", "data: abc", "",
                 "", "event: refused", "data:", ""));
    }

    [Theory]
    [InlineData("data: one", "data: two", "one\ntwo")]
    [InlineData("data:tight", "data:  spaced", "tight\n spaced")]
    [InlineData("data: crlf\r", "data: end\r", "crlf\nend")]
    public void Data_lines_join_with_newlines_and_lose_only_their_framing(string first, string second, string expected)
    {
        Assert.Equal([new ServerEvent("", expected)], Read(first, second, ""));
    }
}
