using System.Text;

namespace Vaier.Core;

/// <summary>One message off a server-sent event stream.</summary>
public record ServerEvent(string Name, string Data);

/// <summary>
/// The line-by-line half of a server-sent event stream, kept away from the socket so a test can read
/// it as plain text. Fed one line at a time, it returns an event on the blank line that ends one.
/// </summary>
public class EventStream
{
    private string _name = "";
    private readonly StringBuilder _data = new();
    private bool _dataSeen;
    private bool _started;

    public ServerEvent? Accept(string rawLine)
    {
        var line = rawLine.TrimEnd('\r');
        if (line.Length == 0) return Finish();
        if (line.StartsWith(':')) return null;

        var colon = line.IndexOf(':');
        var field = colon < 0 ? line : line[..colon];
        var value = colon < 0 ? "" : line[(colon + 1)..];
        if (value.StartsWith(' ')) value = value[1..];

        switch (field)
        {
            case "event":
                _name = value;
                break;
            case "data":
                if (_dataSeen) _data.Append('\n');
                _data.Append(value);
                _dataSeen = true;
                break;
            default:
                return null; // retry, id and anything else this app has no use for
        }
        _started = true;
        return null;
    }

    private ServerEvent? Finish()
    {
        if (!_started) return null;
        var serverEvent = new ServerEvent(_name, _data.ToString());
        _name = "";
        _data.Clear();
        _dataSeen = false;
        _started = false;
        return serverEvent;
    }
}
