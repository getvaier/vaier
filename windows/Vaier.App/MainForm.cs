using System.Diagnostics;
using Vaier.Core;

namespace Vaier.App;

/// <summary>
/// The Android app's screens, following what the manager service reports: ask to join, the join code while
/// waiting, and the connection once in. It runs without admin; everything privileged goes over the pipe.
/// Opened by the setup or by Apps &amp; features instead, it installs, updates or uninstalls.
/// </summary>
public class MainForm : Form
{
    public enum Opening { App, Install, Uninstall }

    private enum View { None, NoService, Setup, Waiting, Home }

    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 1000 };
    private DeviceStatus? _status;
    private View _shown;
    private string? _shownCode;
    private string? _notice;
    private Action? _onStatus;

    public MainForm(Opening opening = Opening.App)
    {
        Text = "Vaier";
        Icon = Theme.AppIcon();
        BackColor = Theme.Panel;
        ForeColor = Theme.Text;
        Font = Theme.Ui(10.5f);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        ClientSize = new Size(420, 600);
        StartPosition = FormStartPosition.CenterScreen;

        if (opening == Opening.Install)
        {
            ShowInstall();
            return;
        }
        if (opening == Opening.Uninstall)
        {
            ShowUninstall();
            return;
        }
        _tick.Tick += async (_, _) => await Follow();
        _tick.Start();
        _ = Follow();
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Theme.DarkTitleBar(this);
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        _tick.Stop();
        base.OnFormClosed(e);
    }

    private void Screen(Control content)
    {
        _onStatus = null;
        SuspendLayout();
        Controls.Clear();
        Controls.Add(content);
        ResumeLayout();
    }

    /// <summary>Reads the manager's status and moves to the screen it calls for; a screen already on show only updates.</summary>
    private async Task Follow()
    {
        _status = (await Pipe.Ask("status"))?.Status;
        if (_status?.Notice is { } notice)
        {
            _notice = notice;
            await Pipe.Ask("take-notice");
        }

        var wanted = _status switch
        {
            null => View.NoService,
            { Member: not null } => View.Home,
            { Pending: not null } => View.Waiting,
            _ => View.Setup,
        };
        if (wanted != _shown || (wanted == View.Waiting && _status!.Pending!.Code != _shownCode))
        {
            _shown = wanted;
            switch (wanted)
            {
                case View.NoService: ShowNoService(); break;
                case View.Setup: ShowSetup(); break;
                case View.Waiting: ShowWaiting(_status!.Pending!); break;
                case View.Home: ShowHome(); break;
            }
        }
        _onStatus?.Invoke();
    }

    // --- The service is missing ---

    private void ShowNoService()
    {
        var stack = Theme.Stack();
        stack.Controls.Add(Theme.Label("Vaier is not running", Theme.Ui(18, FontStyle.Bold), Theme.Text));
        stack.Controls.Add(Theme.Label("The service that keeps this computer in Vaier is not answering. Repair starts it again.", Theme.Ui(11), Theme.Text));
        var repair = Theme.Primary("Repair");
        repair.Click += (_, _) => Elevated.Run("/repair");
        stack.Controls.Add(repair);
        Screen(stack);
    }

    // --- Install, update, uninstall (run elevated, in a process of their own) ---

    private void ShowInstall()
    {
        var installed = Installer.InstalledVersion();
        var stack = Theme.Stack();
        stack.Controls.Add(Theme.Label(installed is null ? "Install Vaier" : "Update Vaier", Theme.Ui(18, FontStyle.Bold), Theme.Text));
        stack.Controls.Add(Theme.Label(installed is null
            ? "Vaier moves into Program Files and the Start menu, sits in the tray, and keeps this computer in your fleet — after a restart too."
            : $"{installed} → {Installer.OwnVersion}. This computer stays in the fleet, and its connection comes back on its own.",
            Theme.Ui(11), Theme.Text));
        var go = Theme.Primary(installed is null ? "Install" : "Update");
        var notice = Theme.Label("", Theme.Ui(10), Theme.Error);
        stack.Controls.Add(go);
        stack.Controls.Add(notice);
        AcceptButton = go;
        go.Click += async (_, _) =>
        {
            go.Enabled = false;
            go.Text = installed is null ? "Installing…" : "Updating…";
            try
            {
                await Task.Run(Installer.InstallOrUpdate);
                Close();
            }
            catch (Exception e)
            {
                notice.Text = $"Vaier could not be installed: {e.Message}";
                go.Enabled = true;
                go.Text = installed is null ? "Install" : "Update";
            }
        };
        Screen(stack);
    }

    /// <summary>Uninstalling leaves the fleet first, so no machine is left behind that nothing answers for.</summary>
    private void ShowUninstall()
    {
        var stack = Theme.Stack();
        stack.Controls.Add(Theme.Label("Uninstall Vaier?", Theme.Ui(18, FontStyle.Bold), Theme.Text));
        stack.Controls.Add(Theme.Label("This computer leaves Vaier, and the app is removed. To come back you'll need to install it, join again and be approved.",
            Theme.Ui(11), Theme.Text));
        var go = Theme.Primary("Uninstall");
        var cancel = Theme.Quiet("Cancel");
        var notice = Theme.Label("", Theme.Ui(10), Theme.Error);
        stack.Controls.AddRange([go, cancel, notice]);
        var leftAnyway = false;

        cancel.Click += (_, _) => Close();
        go.Click += async (_, _) =>
        {
            go.Enabled = cancel.Enabled = false;
            go.Text = "Uninstalling…";
            if (!leftAnyway && (await Pipe.Ask("leave"))?.Status?.Member is not null)
            {
                notice.Text = "Vaier couldn't be reached, so this computer is still listed in the fleet. "
                    + "Uninstall anyway, and remove it on the fleet page later?";
                go.Text = "Uninstall anyway";
                leftAnyway = true;
                go.Enabled = cancel.Enabled = true;
                return;
            }
            try
            {
                await Task.Run(Installer.Remove);
                ShowUninstalled();
            }
            catch (Exception e)
            {
                notice.Text = $"Vaier could not be removed completely: {e.Message}";
                go.Enabled = cancel.Enabled = true;
                go.Text = "Uninstall";
            }
        };
        Screen(stack);
    }

    private void ShowUninstalled()
    {
        var stack = Theme.Stack();
        stack.Controls.Add(Theme.Label("Vaier is uninstalled", Theme.Ui(18, FontStyle.Bold), Theme.Text));
        stack.Controls.Add(Theme.Label("Nothing of it is left on this computer.", Theme.Ui(11), Theme.TextDim));
        var close = Theme.Primary("Close");
        close.Click += (_, _) => Close();
        stack.Controls.Add(close);
        AcceptButton = close;
        Screen(stack);
    }

    // --- Ask to join ---

    private void ShowSetup()
    {
        var stamped = DeviceStore.StampedHost();
        var stack = Theme.Stack();
        stack.Controls.Add(Theme.Label("Join Vaier", Theme.Ui(18, FontStyle.Bold), Theme.Text));
        stack.Controls.Add(Theme.Label("This computer asks to join. Whoever runs Vaier says yes, and you are in.", Theme.Ui(11), Theme.Text));

        // A setup served by Vaier carries its server's name, so there is nothing to type.
        var (addressField, address) = Theme.Field("Vaier address", "vaier.example.com");
        if (stamped is null) stack.Controls.Add(addressField);
        var (nameField, name) = Theme.Field("Name this computer", "");
        name.Text = Environment.MachineName;
        stack.Controls.Add(nameField);

        var join = Theme.Primary("Ask to join");
        var notice = Theme.Label(_notice ?? "", Theme.Ui(10), Theme.Error);
        _notice = null;
        stack.Controls.Add(join);
        stack.Controls.Add(notice);
        AcceptButton = join;

        join.Click += async (_, _) =>
        {
            var host = stamped ?? VaierAddress.Normalise(address.Text);
            if (host is null)
            {
                notice.Text = "That does not look like an address. Try vaier.example.com.";
                return;
            }
            if (name.Text.Trim().Length == 0)
            {
                notice.Text = "Give this computer a name.";
                return;
            }
            join.Enabled = false;
            join.Text = "Asking…";
            var answer = await Pipe.Ask("join", host, name.Text.Trim());
            if (answer?.Error is null && answer is not null)
            {
                await Follow();
                return;
            }
            notice.Text = answer?.Error ?? "Vaier's service is not answering.";
            join.Enabled = true;
            join.Text = "Ask to join";
        };
        Screen(stack);
    }

    // --- Waiting on the join code ---

    /// <summary>The code is the whole screen: someone reading it out across a room should never hunt for it.</summary>
    private void ShowWaiting(Pending pending)
    {
        _shownCode = pending.Code;
        var grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(28, 24, 28, 24), BackColor = Theme.Panel };
        grid.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        for (var i = 0; i < 4; i++) grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        grid.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        for (var i = 0; i < 2; i++) grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        Label Centred(string text, Font font, Color colour) =>
            new() { Text = text, Font = font, ForeColor = colour, AutoSize = true, Anchor = AnchorStyles.None, TextAlign = ContentAlignment.MiddleCenter, MaximumSize = new Size(360, 0), Margin = new Padding(0, 6, 0, 6) };

        var countdown = Centred("", Theme.Ui(9.5f), Theme.TextDim);
        var approveHere = Theme.Primary("I run Vaier — approve it here");
        var cancel = Theme.Quiet("Cancel");

        grid.Controls.Add(new Panel { Height = 1 }, 0, 0);
        grid.Controls.Add(Centred(string.Join(" ", pending.Code.ToCharArray()), Theme.Mono(44, FontStyle.Bold), Theme.Amber), 0, 1);
        grid.Controls.Add(Centred("Read this code out to whoever runs Vaier.", Theme.Ui(11.5f), Theme.Text), 0, 2);
        grid.Controls.Add(Centred("The moment they say yes, this computer is in. You can close this window.", Theme.Ui(10), Theme.TextDim), 0, 3);
        grid.Controls.Add(countdown, 0, 4);
        grid.Controls.Add(approveHere, 0, 6);
        grid.Controls.Add(cancel, 0, 7);
        Screen(grid);

        _onStatus = () =>
        {
            var left = TimeSpan.FromMilliseconds(pending.ExpiresAtMillis - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            countdown.Text = left > TimeSpan.Zero ? $"{left:m\\:ss} left" : "";
        };
        approveHere.Click += (_, _) => Open($"https://{pending.Address}/explorer.html?approve={pending.Code}");
        cancel.Click += async (_, _) =>
        {
            await Pipe.Ask("cancel-join");
            await Follow();
        };
    }

    // --- In the fleet ---

    private void ShowHome()
    {
        var member = _status!.Member!;
        var stack = Theme.Stack();

        var top = new TableLayoutPanel { ColumnCount = 2, Size = new Size(360, 36), Margin = new Padding(0, 0, 0, 12) };
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        top.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var name = Theme.Label(member.Name, Theme.Ui(12), Theme.TextDim);
        name.Anchor = AnchorStyles.Left;
        var more = new Button { Text = "⋯", FlatStyle = FlatStyle.Flat, ForeColor = Theme.Text, BackColor = Theme.Panel, Size = new Size(36, 32), Font = Theme.Ui(12), Cursor = Cursors.Hand };
        more.FlatAppearance.BorderSize = 0;
        var menu = new ContextMenuStrip { Renderer = new ToolStripProfessionalRenderer(new Theme.MenuColours()), ShowImageMargin = false, Font = Theme.Ui(10.5f) };
        menu.Items.Add(new ToolStripMenuItem("Leave Vaier", null, (_, _) => ConfirmLeave()) { ForeColor = Theme.Text });
        menu.Items.Add(new ToolStripMenuItem("Uninstall Vaier…", null, (_, _) => Elevated.Run("/uninstall")) { ForeColor = Theme.Text });
        more.Click += (_, _) => menu.Show(more, new Point(more.Width - menu.Width, more.Height));
        top.Controls.Add(name, 0, 0);
        top.Controls.Add(more, 1, 0);
        stack.Controls.Add(top);

        var card = new Theme.RoundedCard { Size = new Size(360, 112), Margin = new Padding(0, 0, 0, 16) };
        var state = new Label { Font = Theme.Ui(16), ForeColor = Theme.Text, BackColor = Theme.Card, AutoSize = true, Location = new Point(20, 22) };
        var meaning = new Label { Font = Theme.Ui(10), ForeColor = Theme.TextDim, BackColor = Theme.Card, AutoSize = true, MaximumSize = new Size(250, 0), Location = new Point(20, 56) };
        var connected = new Theme.Switch { Location = new Point(360 - 20 - 52, 40) };
        card.Controls.AddRange([state, meaning, connected]);
        stack.Controls.Add(card);

        var notice = Theme.Label(_notice ?? "", Theme.Ui(10), Theme.Error);
        _notice = null;
        stack.Controls.Add(notice);

        var details = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Visible = false, Margin = new Padding(0) };
        var fold = new Button { Text = "Details  ▾", FlatStyle = FlatStyle.Flat, ForeColor = Theme.Amber, BackColor = Theme.Panel, AutoSize = true, Font = Theme.Ui(10, FontStyle.Bold), Cursor = Cursors.Hand, Margin = new Padding(0, 8, 0, 8) };
        fold.FlatAppearance.BorderSize = 0;
        fold.Click += (_, _) =>
        {
            details.Visible = !details.Visible;
            fold.Text = details.Visible ? "Details  ▴" : "Details  ▾";
        };
        stack.Controls.Add(fold);
        Label Field(string label)
        {
            details.Controls.Add(Theme.Label(label, Theme.Ui(9), Theme.TextDim).With(l => l.Margin = new Padding(0, 0, 0, 2)));
            var value = Theme.Label("—", Theme.Mono(10.5f), Theme.Text);
            details.Controls.Add(value);
            return value;
        }
        Field("Vaier address").Text = member.Address;
        Field("This computer's address").Text = member.TunnelAddress;
        var handshake = Field("Last handshake");
        var received = Field("Received");
        var sent = Field("Sent");
        stack.Controls.Add(details);
        Screen(stack);

        var busy = false;
        connected.Click += async (_, _) =>
        {
            if (busy) return;
            busy = true;
            connected.Enabled = false;
            notice.Text = "";
            await Pipe.Ask(connected.Checked ? "connect" : "disconnect");
            busy = false;
            connected.Enabled = true;
            await Follow();
        };

        _onStatus = () =>
        {
            if (_status?.Member is null) return;
            if (!busy) connected.Checked = _status.Up;
            state.Text = connected.Checked ? "Connected" : "Not connected";
            meaning.Text = connected.Checked ? "Everything this computer does online goes through Vaier." : "Turn it on to use Vaier.";
            handshake.Text = _status.Up ? Since(_status.LastHandshakeMillis) : "—";
            received.Text = _status.Up ? Bytes(_status.Received) : "—";
            sent.Text = _status.Up ? Bytes(_status.Sent) : "—";
            if (_notice is not null)
            {
                notice.Text = _notice;
                _notice = null;
            }
        };
    }

    private void ConfirmLeave()
    {
        using var ask = new LeaveDialog();
        if (ask.ShowDialog(this) != DialogResult.OK) return;
        _ = Pipe.Ask("leave").ContinueWith(_ => BeginInvoke(async () => await Follow()));
    }

    private static void Open(string url) => Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

    private static string Since(long epochMillis)
    {
        if (epochMillis <= 0) return "never";
        var seconds = (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - epochMillis) / 1000;
        return seconds switch
        {
            < 60 => $"{seconds} s ago",
            < 3600 => $"{seconds / 60} min ago",
            _ => $"{seconds / 3600} h ago",
        };
    }

    private static string Bytes(ulong count) => count switch
    {
        < 1024 => $"{count} B",
        < 1024 * 1024 => $"{count / 1024.0:0.0} kB",
        < 1024UL * 1024 * 1024 => $"{count / (1024.0 * 1024):0.0} MB",
        _ => $"{count / (1024.0 * 1024 * 1024):0.00} GB",
    };
}

/// <summary>"Leave Vaier?" in the app's own colours rather than a grey system box.</summary>
public class LeaveDialog : Form
{
    public LeaveDialog()
    {
        Text = "Leave Vaier?";
        Icon = Theme.AppIcon();
        BackColor = Theme.Panel;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.CenterParent;
        ClientSize = new Size(380, 190);

        var stack = Theme.Stack();
        stack.Padding = new Padding(24, 20, 24, 16);
        stack.Controls.Add(Theme.Label("Leave Vaier?", Theme.Ui(14, FontStyle.Bold), Theme.Text));
        stack.Controls.Add(Theme.Label("This computer will be removed from Vaier. To come back you'll need to join again and be approved.", Theme.Ui(10), Theme.TextDim).With(l => l.MaximumSize = new Size(330, 0)));
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, Size = new Size(330, 44), Margin = new Padding(0) };
        var leave = new Button { Text = "Leave", DialogResult = DialogResult.OK, FlatStyle = FlatStyle.Flat, ForeColor = Theme.Amber, BackColor = Theme.Panel, Size = new Size(90, 36), Font = Theme.Ui(10.5f, FontStyle.Bold) };
        var stay = new Button { Text = "Stay", DialogResult = DialogResult.Cancel, FlatStyle = FlatStyle.Flat, ForeColor = Theme.Amber, BackColor = Theme.Panel, Size = new Size(90, 36), Font = Theme.Ui(10.5f) };
        leave.FlatAppearance.BorderSize = stay.FlatAppearance.BorderSize = 0;
        buttons.Controls.AddRange([leave, stay]);
        stack.Controls.Add(buttons);
        Controls.Add(stack);
        AcceptButton = stay;
        CancelButton = stay;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Theme.DarkTitleBar(this);
    }
}

internal static class ControlExtensions
{
    public static T With<T>(this T control, Action<T> change) where T : Control
    {
        change(control);
        return control;
    }
}
