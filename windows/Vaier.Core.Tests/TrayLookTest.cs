using Xunit;

namespace Vaier.Core.Tests;

public class TrayLookTest
{
    private static readonly Member Laptop = new("Dell laptop", "vaier.example.com", "10.13.13.9/32");

    public static TheoryData<DeviceStatus?, bool, string> Statuses => new()
    {
        { null, false, "Vaier — its service is not running" },
        { new DeviceStatus(null, false, 0, 0, 0, null, null), false, "Vaier — not joined" },
        { new DeviceStatus(null, false, 0, 0, 0, new Pending("4821", "vaier.example.com", 0), null), false, "Vaier — waiting to join, code 4821" },
        { new DeviceStatus(Laptop, true, 0, 0, 0, null, null), true, "Vaier — Dell laptop is connected" },
        { new DeviceStatus(Laptop, false, 0, 0, 0, null, null), false, "Vaier — Dell laptop is not connected" },
    };

    [Theory]
    [MemberData(nameof(Statuses))]
    public void The_tray_lights_only_while_connected_and_says_where_this_computer_stands(DeviceStatus? status, bool lit, string tooltip)
    {
        Assert.Equal(new TrayLook(lit, tooltip), TrayLook.Of(status));
    }
}
