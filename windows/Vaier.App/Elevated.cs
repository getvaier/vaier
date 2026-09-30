using System.ComponentModel;
using System.Diagnostics;
using System.Security.Principal;

namespace Vaier.App;

/// <summary>The app runs without admin; installing, uninstalling and repairing ask for it, one prompt each.</summary>
public static class Elevated
{
    public static bool Now => new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);

    /// <summary>Starts this exe again with admin; false when the person said no to the prompt.</summary>
    public static bool Run(string arguments)
    {
        try
        {
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!, arguments) { UseShellExecute = true, Verb = "runas" });
            return true;
        }
        catch (Win32Exception)
        {
            return false;
        }
    }
}
