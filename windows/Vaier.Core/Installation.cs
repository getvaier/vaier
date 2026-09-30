namespace Vaier.Core;

/// <summary>What a copy of the app does on start, given where it runs from and what is installed.</summary>
public enum Setup { Run, Install, Update, OpenInstalled }

public static class Installation
{
    public static Setup Decide(string runningFrom, string installDir, string? installedVersion, string ownVersion)
    {
        if (SameFolder(runningFrom, installDir)) return Setup.Run;
        if (installedVersion is null) return Setup.Install;
        return Version.Parse(ownVersion) > Version.Parse(installedVersion) ? Setup.Update : Setup.OpenInstalled;
    }

    // Windows folders: case does not matter, and neither does a trailing separator.
    private static bool SameFolder(string a, string b) =>
        string.Equals(a.TrimEnd('\\', '/'), b.TrimEnd('\\', '/'), StringComparison.OrdinalIgnoreCase);
}
