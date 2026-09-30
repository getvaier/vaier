using System.Diagnostics;
using System.Reflection;
using Microsoft.Win32;
using Tunnel;
using Vaier.Core;

namespace Vaier.App;

/// <summary>
/// The app installs itself: VaierSetup.exe opened from a download writes itself into Program Files as Vaier.exe,
/// with the WireGuard DLLs it carries, registers in Apps &amp; features and the Start menu, and hands over to the
/// installed copy. Both services run the installed exe.
/// </summary>
public static class Installer
{
    public static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Vaier");

    public static string Exe => Path.Combine(Dir, "Vaier.exe");

    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Vaier";
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    private static readonly string[] Carried = ["tunnel.dll", "wireguard.dll", "wireguard-nt-LICENSE.txt"];

    public static string StampFile => Path.Combine(Dir, "stamped-host.txt");

    private static string Shortcut =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms), "Vaier.lnk");

    public static string OwnVersion => Plain(Assembly.GetExecutingAssembly()
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()!.InformationalVersion);

    // A half-removed folder is not an install: the next install repairs it rather than handing over to it.
    public static string? InstalledVersion() =>
        new[] { "Vaier.exe", "tunnel.dll", "wireguard.dll" }.All(f => File.Exists(Path.Combine(Dir, f)))
            ? Plain(FileVersionInfo.GetVersionInfo(Exe).ProductVersion ?? "0.0.0")
            : null;

    public static Setup Decide() => Installation.Decide(AppContext.BaseDirectory, Dir, InstalledVersion(), OwnVersion);

    public static bool RunningInstalled => Decide() == Setup.Run;

    /// <summary>Installs or updates, puts the tunnel back as it was, and starts the tray without admin.</summary>
    public static void InstallOrUpdate()
    {
        var membership = DeviceStore.Load();
        var wasUp = membership is not null && TunnelService.IsUp(membership.ConfigFile);

        // Both services and every signed-in tray run the installed exe; they must let go of it first.
        Services.Remove(ManagerService.Name);
        if (membership is not null) TunnelService.Down(membership.ConfigFile);
        StopTrays();

        Directory.CreateDirectory(Dir);
        WhenReleased(() => File.Copy(Environment.ProcessPath!, Exe, overwrite: true));
        foreach (var name in Carried)
        {
            using var carried = typeof(Installer).Assembly.GetManifestResourceStream(name)
                ?? throw new InvalidOperationException($"This setup does not carry {name}.");
            WhenReleased(() =>
            {
                using var target = File.Create(Path.Combine(Dir, name));
                carried.Position = 0;
                carried.CopyTo(target);
            });
        }
        // A setup handed over by hand has no stamp; an earlier install's stamp is then kept.
        using (var self = File.OpenRead(Environment.ProcessPath!))
            if (SetupStamp.Read(self) is { } host) File.WriteAllText(StampFile, host);
        Register();
        CreateShortcut();
        // The manager puts the tunnel back as it starts; it retries where this setup could not.
        if (membership is not null) DeviceStore.WantsConnected = wasUp;
        ManagerService.Ensure();
        // Started through Explorer, the tray runs as the signed-in person rather than with this setup's admin.
        Process.Start("explorer.exe", $"\"{Exe}\"");
    }

    /// <summary>The manager service went missing or stopped; put it back and start it.</summary>
    public static void Repair()
    {
        ManagerService.Ensure();
        Services.Start(ManagerService.Name);
    }

    /// <summary>Everything but leaving the fleet, which the caller has already done or decided against.</summary>
    public static void Remove()
    {
        var membership = DeviceStore.Load();
        if (membership is not null) TunnelService.Down(membership.ConfigFile);
        Services.Remove(ManagerService.Name);
        StopTrays();
        DeviceStore.Wipe();
        Registry.LocalMachine.DeleteSubKeyTree(UninstallKey, throwOnMissingSubKey: false);
        using (var run = Registry.LocalMachine.OpenSubKey(RunKey, writable: true)) run?.DeleteValue("Vaier", throwOnMissingValue: false);
        File.Delete(Shortcut);
        // Windows will not delete a running exe, so the folder goes once this process has really ended.
        var script = $"Wait-Process -Id {Environment.ProcessId} -ErrorAction SilentlyContinue; "
                     + $"Remove-Item -LiteralPath '{Dir}' -Recurse -Force";
        Process.Start(new ProcessStartInfo("powershell.exe", $"-NoProfile -NonInteractive -WindowStyle Hidden -Command \"{script}\"")
        {
            CreateNoWindow = true, UseShellExecute = false, WorkingDirectory = Path.GetTempPath(),
        });
    }

    /// <summary>The tray of everyone signed in holds the installed exe open; this setup is the one copy spared.</summary>
    private static void StopTrays()
    {
        foreach (var tray in Process.GetProcessesByName("Vaier").Where(p => p.Id != Environment.ProcessId))
        {
            try
            {
                tray.Kill();
                tray.WaitForExit(5000);
            }
            catch (Exception)
            {
                // Already gone, or a service stopping on its own.
            }
        }
    }

    private static void Register()
    {
        using (var run = Registry.LocalMachine.CreateSubKey(RunKey)) run.SetValue("Vaier", $"\"{Exe}\" /tray");
        using var key = Registry.LocalMachine.CreateSubKey(UninstallKey);
        key.SetValue("DisplayName", "Vaier");
        key.SetValue("DisplayVersion", OwnVersion);
        key.SetValue("Publisher", "Vaier");
        key.SetValue("DisplayIcon", Exe);
        key.SetValue("InstallLocation", Dir);
        key.SetValue("UninstallString", $"\"{Exe}\" /uninstall");
        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
        key.SetValue("EstimatedSize", (int)(Directory.GetFiles(Dir).Sum(f => new FileInfo(f).Length) / 1024), RegistryValueKind.DWord);
    }

    private static void CreateShortcut()
    {
        dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell")!)!;
        var link = shell.CreateShortcut(Shortcut);
        link.TargetPath = Exe;
        link.WorkingDirectory = Dir;
        link.IconLocation = Exe + ",0";
        link.Description = "Keeps this computer in the Vaier fleet";
        link.Save();
    }

    // A stopped service's process can hold its files for a moment after it reports stopped.
    private static void WhenReleased(Action write)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                write();
                return;
            }
            catch (IOException) when (attempt < 20)
            {
                Thread.Sleep(500);
            }
        }
    }

    private static string Plain(string version) => version.Split('+')[0];
}
