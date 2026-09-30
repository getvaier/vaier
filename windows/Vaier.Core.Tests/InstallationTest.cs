using Xunit;

namespace Vaier.Core.Tests;

public class InstallationTest
{
    private const string Installed = @"C:\Program Files\Vaier";
    private const string Downloads = @"C:\Users\geir\Downloads\Vaier";

    [Theory]
    [InlineData(Installed, "0.3.0", "0.3.0", Setup.Run)]           // the installed copy just runs
    [InlineData(Downloads, null, "0.3.0", Setup.Install)]          // nothing installed yet
    [InlineData(Downloads, "0.2.0", "0.3.0", Setup.Update)]        // a newer download updates in place
    [InlineData(Downloads, "0.3.0", "0.3.0", Setup.OpenInstalled)] // the same version is already there
    [InlineData(Downloads, "0.4.0", "0.3.0", Setup.OpenInstalled)] // an older download never downgrades
    [InlineData(Installed + @"\", "0.3.0", "0.3.0", Setup.Run)]     // a trailing slash is the same folder
    [InlineData(@"c:\program files\vaier", "0.3.0", "0.3.0", Setup.Run)] // so is another casing
    public void A_copy_run_from_elsewhere_installs_updates_or_hands_over_to_the_installed_one(
        string runningFrom, string? installedVersion, string ownVersion, Setup expected)
    {
        Assert.Equal(expected, Installation.Decide(runningFrom, Installed, installedVersion, ownVersion));
    }
}
