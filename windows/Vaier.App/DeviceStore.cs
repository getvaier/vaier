using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;
using Vaier.Core;

namespace Vaier.App;

/// <summary>This computer's membership: the Vaier address, its name, and the tunnel config holding its private key.</summary>
public record Membership(string Address, string Name, string ConfigFile)
{
    public SavedConfig Saved => SavedConfig.Read(File.ReadAllText(ConfigFile));
}

/// <summary>
/// Keeps the membership in a folder only SYSTEM and Administrators can read — the private key lives in the
/// config, as it does under WireGuard's own Data folder. The manager service reads the same folder.
/// </summary>
public static class DeviceStore
{
    private static readonly string Folder =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Vaier");

    public static readonly string ConfigFile = Path.Combine(Folder, "Vaier.conf");
    private static readonly string MembershipFile = Path.Combine(Folder, "vaier.json");
    private static readonly string NoticeFile = Path.Combine(Folder, "notice.txt");

    /// <summary>The Vaier host this download was served from, written beside the exe; null for a build handed over by hand.</summary>
    public static string? StampedHost()
    {
        var stamp = Path.Combine(AppContext.BaseDirectory, "stamped-host.txt");
        return File.Exists(stamp) ? VaierAddress.Normalise(File.ReadAllText(stamp)) : null;
    }

    public static Membership? Load()
    {
        if (!File.Exists(ConfigFile) || !File.Exists(MembershipFile)) return null;
        var saved = JsonDocument.Parse(File.ReadAllText(MembershipFile)).RootElement;
        return new Membership(saved.GetProperty("address").GetString()!, saved.GetProperty("name").GetString()!, ConfigFile);
    }

    public static Membership Save(string address, string name, string configText)
    {
        LockedFolder();
        File.WriteAllText(ConfigFile, configText.Replace("\n", "\r\n"));
        File.WriteAllText(MembershipFile, JsonSerializer.Serialize(new { address, name }));
        return new Membership(address, name, ConfigFile);
    }

    /// <summary>Drops the membership, and with it the private key. Joining again starts from nothing.</summary>
    public static void Forget()
    {
        File.Delete(ConfigFile);
        File.Delete(MembershipFile);
    }

    /// <summary>Kept for whoever opens the window next — a removal is usually found with nobody looking.</summary>
    public static void LeaveNotice(string notice)
    {
        LockedFolder();
        File.WriteAllText(NoticeFile, notice);
    }

    public static string? TakeNotice()
    {
        if (!File.Exists(NoticeFile)) return null;
        var notice = File.ReadAllText(NoticeFile);
        File.Delete(NoticeFile);
        return notice;
    }

    private static void LockedFolder()
    {
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        foreach (var who in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(who, null),
                FileSystemRights.FullControl, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(Folder).Create(security);
        new DirectoryInfo(Folder).SetAccessControl(security);
    }
}
