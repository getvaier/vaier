using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;

namespace Vaier.App;

/// <summary>This computer's membership: the Vaier address and the tunnel config holding its private key.</summary>
public record Membership(string Address, string Name, string ConfigFile);

/// <summary>
/// Keeps the membership in a folder only SYSTEM and Administrators can read — the private key lives in
/// the config, as it does under WireGuard's own Data folder.
/// </summary>
public static class DeviceStore
{
    private static readonly string Folder =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Vaier");

    public static readonly string ConfigFile = Path.Combine(Folder, "Vaier.conf");
    private static readonly string AddressFile = Path.Combine(Folder, "vaier.json");

    public static Membership? Load()
    {
        if (!File.Exists(ConfigFile) || !File.Exists(AddressFile)) return null;
        var saved = JsonDocument.Parse(File.ReadAllText(AddressFile)).RootElement;
        return new Membership(saved.GetProperty("address").GetString()!, saved.GetProperty("name").GetString()!, ConfigFile);
    }

    public static Membership Save(string address, string name, string configText)
    {
        LockedFolder();
        File.WriteAllText(ConfigFile, configText.Replace("\n", "\r\n"));
        File.WriteAllText(AddressFile, JsonSerializer.Serialize(new { address, name }));
        return new Membership(address, name, ConfigFile);
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
