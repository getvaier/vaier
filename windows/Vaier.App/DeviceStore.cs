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
    private static readonly string ConnectedFile = Path.Combine(Folder, "connected");

    /// <summary>
    /// Whether the person wants the tunnel on. The manager keeps the tunnel matching it, so an update, a
    /// repair or a reboot never has to remember the switch itself.
    /// </summary>
    public static bool WantsConnected
    {
        get => File.Exists(ConnectedFile);
        set
        {
            LockedFolder();
            if (value) File.WriteAllText(ConnectedFile, "");
            else File.Delete(ConnectedFile);
        }
    }

    /// <summary>The Vaier host the setup was served from, kept at install; null for a setup handed over by hand.</summary>
    public static string? StampedHost() =>
        File.Exists(Installer.StampFile) ? VaierAddress.Normalise(File.ReadAllText(Installer.StampFile)) : null;

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
        File.Delete(ConnectedFile);
    }

    /// <summary>Uninstalling: the key, the tunnel's own log, everything Vaier kept on this computer.</summary>
    public static void Wipe()
    {
        if (Directory.Exists(Folder)) Directory.Delete(Folder, recursive: true);
    }

    /// <summary>Kept for whoever opens the window next — a removal is usually found with nobody looking.</summary>
    public static void LeaveNotice(string notice)
    {
        LockedFolder();
        File.WriteAllText(NoticeFile, notice);
    }

    public static string? PeekNotice() => File.Exists(NoticeFile) ? File.ReadAllText(NoticeFile) : null;

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
