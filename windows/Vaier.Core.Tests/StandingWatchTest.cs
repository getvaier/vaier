using Xunit;

namespace Vaier.Core.Tests;

public class StandingWatchTest
{
    private const long Now = 100_000_000;
    private const long Minute = 60_000;

    [Theory]
    [InlineData(Now - Minute, 0, 0, false)]                         // in touch a minute ago
    [InlineData(Now - 4 * Minute, 0, 0, true)]                      // silent for four minutes
    [InlineData(0, Now - 2 * Minute, 0, false)]                     // never in touch, up for two minutes
    [InlineData(0, Now - 4 * Minute, 0, true)]                      // never in touch, up for four minutes
    [InlineData(Now - 10 * Minute, 0, Now - Minute, false)]         // asked a minute ago
    [InlineData(Now - 10 * Minute, 0, Now - 6 * Minute, true)]      // asked six minutes ago
    public void Only_a_silence_of_three_minutes_is_worth_asking_about_and_never_twice_in_five(
        long lastHandshake, long connectedSince, long lastAsked, bool expected)
    {
        Assert.Equal(expected, StandingWatch.WorthAsking(Now, lastHandshake, connectedSince, lastAsked));
    }

    [Theory]
    [InlineData(Standing.Removed, true, false, true)]
    [InlineData(Standing.Member, false, true, false)]
    [InlineData(Standing.Unreachable, false, true, false)]
    public void After_a_silence_whatever_happens_what_was_taken_down_goes_back_unless_removed(
        Standing outcome, bool forget, bool reconnect, bool saysWhy)
    {
        var steps = StandingWatch.AfterSilence(outcome);

        Assert.Equal((forget, reconnect, saysWhy), (steps.Forget, steps.Reconnect, steps.Notice is not null));
    }

    [Theory]
    [InlineData(Standing.Removed, true, true)]
    [InlineData(Standing.Member, false, false)]
    [InlineData(Standing.Unreachable, false, false)]
    public void Opening_with_the_connection_off_never_connects_it(Standing outcome, bool forget, bool saysWhy)
    {
        var steps = StandingWatch.AfterOpening(outcome);

        Assert.Equal((forget, false, saysWhy), (steps.Forget, steps.Reconnect, steps.Notice is not null));
    }
}
