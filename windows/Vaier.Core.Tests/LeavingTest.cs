using Xunit;

namespace Vaier.Core.Tests;

public class LeavingTest
{
    [Theory]
    [InlineData(LeaveOutcome.Removed, true, true, false, false)]
    [InlineData(LeaveOutcome.Removed, false, true, false, false)]
    [InlineData(LeaveOutcome.Unreachable, true, false, true, true)]
    [InlineData(LeaveOutcome.Unreachable, false, false, false, true)]
    public void A_failure_puts_back_only_what_leaving_took_down(
        LeaveOutcome outcome, bool wasConnected, bool forget, bool reconnect, bool saysWhy)
    {
        var steps = Leaving.After(outcome, wasConnected);

        Assert.Equal((forget, reconnect, saysWhy), (steps.Forget, steps.Reconnect, steps.Notice is not null));
    }
}
