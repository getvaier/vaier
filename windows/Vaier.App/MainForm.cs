using System.Diagnostics;
using Tunnel;
using Vaier.Core;

namespace Vaier.App;

/// <summary>The Android app's three screens: ask to join, the join code while waiting, and the connection once in.</summary>
public class MainForm : Form
{
    private readonly VaierClient _vaier = new();
    private readonly string? _stampedHost = DeviceStore.StampedHost();
    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 1000 };
    private Membership? _membership;
    private CancellationTokenSource? _waiting;
    private string? _notice;
    private Action? _onTick;

    public MainForm()
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
        _tick.Tick += (_, _) => _onTick?.Invoke();
        _tick.Start();

        _membership = DeviceStore.Load();
        _notice = DeviceStore.TakeNotice();
        if (_membership is null)
        {
            ShowSetup();
            return;
        }
        Attempt(ManagerService.Ensure);
        ShowHome();
        _ = CheckStandingOnOpening(_membership);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Theme.DarkTitleBar(this);
    }

    private void Screen(Control content)
    {
        _onTick = null;
        SuspendLayout();
        Controls.Clear();
        Controls.Add(content);
        ResumeLayout();
    }

    // --- Ask to join ---

    private void ShowSetup()
    {
        var stack = Theme.Stack();
        stack.Controls.Add(Theme.Label("Join Vaier", Theme.Ui(18, FontStyle.Bold), Theme.Text));
        stack.Controls.Add(Theme.Label("This computer asks to join. Whoever runs Vaier says yes, and you are in.", Theme.Ui(11), Theme.Text));

        // A download served by Vaier carries its server's name, so there is nothing to type.
        var (addressField, address) = Theme.Field("Vaier address", "vaier.example.com");
        if (_stampedHost is null) stack.Controls.Add(addressField);
        var (nameField, name) = Theme.Field("Name this computer", "");
        name.Text = Environment.MachineName;
        stack.Controls.Add(nameField);

        var join = Theme.Primary("Ask to join");
        var notice = Theme.Label(_notice ?? "", Theme.Ui(10), Theme.Error);
        stack.Controls.Add(join);
        stack.Controls.Add(notice);
        AcceptButton = join;

        join.Click += async (_, _) =>
        {
            var host = _stampedHost ?? VaierAddress.Normalise(address.Text);
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
            var keypair = Keypair.Generate();
            var outcome = await _vaier.AskToJoin(host, name.Text.Trim(), keypair.Public);
            if (outcome is JoinOutcome.Waiting waiting)
            {
                _notice = null;
                ShowWaiting(host, waiting.Answer, keypair);
                return;
            }
            notice.Text = ((JoinOutcome.Turned)outcome).Reason;
            join.Enabled = true;
            join.Text = "Ask to join";
        };
        Screen(stack);
    }

    // --- Waiting on the join code ---

    /// <summary>The code is the whole screen: someone reading it out across a room should never hunt for it.</summary>
    private void ShowWaiting(string host, JoinAnswer answer, Keypair keypair)
    {
        var deadline = DateTime.UtcNow.AddSeconds(answer.ExpiresInSeconds);
        var grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(28, 24, 28, 24), BackColor = Theme.Panel };
        grid.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        for (var i = 0; i < 4; i++) grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        grid.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        for (var i = 0; i < 3; i++) grid.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        Label Centred(string text, Font font, Color colour) =>
            new() { Text = text, Font = font, ForeColor = colour, AutoSize = true, Anchor = AnchorStyles.None, TextAlign = ContentAlignment.MiddleCenter, MaximumSize = new Size(360, 0), Margin = new Padding(0, 6, 0, 6) };

        var code = Centred(string.Join(" ", answer.Code.ToCharArray()), Theme.Mono(44, FontStyle.Bold), Theme.Amber);
        var countdown = Centred("", Theme.Ui(9.5f), Theme.TextDim);
        var notice = Centred("", Theme.Ui(10), Theme.Error);
        var approveHere = Theme.Primary("I run Vaier — approve it here");
        var cancel = Theme.Quiet("Cancel");

        grid.Controls.Add(new Panel { Height = 1 }, 0, 0);
        grid.Controls.Add(code, 0, 1);
        grid.Controls.Add(Centred("Read this code out to whoever runs Vaier.", Theme.Ui(11.5f), Theme.Text), 0, 2);
        grid.Controls.Add(Centred("The moment they say yes, this computer is in. Leave this window open.", Theme.Ui(10), Theme.TextDim), 0, 3);
        grid.Controls.Add(countdown, 0, 4);
        grid.Controls.Add(notice, 0, 6);
        grid.Controls.Add(approveHere, 0, 7);
        grid.Controls.Add(cancel, 0, 8);
        Screen(grid);

        _onTick = () =>
        {
            var left = deadline - DateTime.UtcNow;
            countdown.Text = left > TimeSpan.Zero ? $"{left:m\\:ss} left" : "";
        };
        approveHere.Click += (_, _) => Open($"https://{host}/explorer.html?approve={answer.Code}");
        cancel.Click += (_, _) => _waiting?.Cancel();

        _ = Wait(host, answer.Ticket, keypair, deadline);
    }

    private async Task Wait(string host, string ticket, Keypair keypair, DateTime deadline)
    {
        using var cancel = _waiting = new CancellationTokenSource();
        try
        {
            while (DateTime.UtcNow < deadline)
            {
                var (verdict, payload) = await _vaier.AwaitVerdict(host, ticket, cancel.Token);
                switch (verdict)
                {
                    case Verdict.Approved:
                        var enrolment = EnrolmentPayload.Parse(payload, keypair.Public, keypair.Private);
                        _membership = DeviceStore.Save(host, enrolment.PeerName, enrolment.ConfigText);
                        TunnelService.Up(_membership.ConfigFile);
                        Attempt(ManagerService.Ensure);
                        ShowHome();
                        return;
                    case Verdict.Refused:
                        _notice = "Vaier turned this computer away.";
                        ShowSetup();
                        return;
                    case Verdict.Gone:
                        _notice = "That join code ran out. Ask again.";
                        ShowSetup();
                        return;
                    case Verdict.Lost:
                        await Task.Delay(TimeSpan.FromSeconds(3), cancel.Token);
                        break;
                }
            }
            _notice = "That join code ran out. Ask again.";
        }
        catch (OperationCanceledException)
        {
            _notice = null;
        }
        catch (EnrolmentException e)
        {
            _notice = e.Message;
        }
        catch (Exception e)
        {
            _notice = $"This computer was let in, but its tunnel would not start: {e.Message}";
        }
        finally
        {
            _waiting = null;
        }
        if (_membership is null) ShowSetup();
    }

    // --- In the fleet ---

    private void ShowHome()
    {
        var membership = _membership!;
        var stack = Theme.Stack();

        var top = new TableLayoutPanel { ColumnCount = 2, Size = new Size(360, 36), Margin = new Padding(0, 0, 0, 12) };
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        top.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var name = Theme.Label(membership.Name, Theme.Ui(12), Theme.TextDim);
        name.Anchor = AnchorStyles.Left;
        var more = new Button { Text = "⋯", FlatStyle = FlatStyle.Flat, ForeColor = Theme.Text, BackColor = Theme.Panel, Size = new Size(36, 32), Font = Theme.Ui(12), Cursor = Cursors.Hand };
        more.FlatAppearance.BorderSize = 0;
        var menu = new ContextMenuStrip { Renderer = new ToolStripProfessionalRenderer(new Theme.MenuColours()), ShowImageMargin = false, Font = Theme.Ui(10.5f) };
        menu.Items.Add(new ToolStripMenuItem("Leave Vaier", null, (_, _) => ConfirmLeave()) { ForeColor = Theme.Text });
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
        Field("Vaier address").Text = membership.Address;
        Field("This computer's address").Text = Attempt(() => membership.Saved.TunnelAddress) ?? "—";
        var handshake = Field("Last handshake");
        var received = Field("Received");
        var sent = Field("Sent");
        stack.Controls.Add(details);
        Screen(stack);

        var busy = false;
        connected.Click += (_, _) =>
        {
            if (busy) return;
            busy = true;
            connected.Enabled = false;
            var turnOn = connected.Checked;
            notice.Text = "";
            Task.Run(() => Attempt(() => { if (turnOn) TunnelService.Up(membership.ConfigFile); else TunnelService.Down(membership.ConfigFile); }))
                .ContinueWith(_ => BeginInvoke(() => { busy = false; connected.Enabled = true; }));
        };

        _onTick = () =>
        {
            if (!busy) connected.Checked = Attempt(() => TunnelService.IsUp(membership.ConfigFile));
            state.Text = connected.Checked ? "Connected" : "Not connected";
            meaning.Text = connected.Checked ? "Everything this computer does online goes through Vaier." : "Turn it on to use Vaier.";
            var peer = connected.Checked ? TunnelService.Peer(membership.ConfigFile) : null;
            handshake.Text = Since(peer?.LastHandshake);
            received.Text = peer is null ? "—" : Bytes(peer.RxBytes);
            sent.Text = peer is null ? "—" : Bytes(peer.TxBytes);
        };
        _onTick();
    }

    private void ConfirmLeave()
    {
        using var ask = new LeaveDialog();
        if (ask.ShowDialog(this) == DialogResult.OK) _ = LeaveVaier();
    }

    /// <summary>The tunnel goes down before the request: Vaier removes the peer before it answers.</summary>
    private async Task LeaveVaier()
    {
        var membership = _membership!;
        var wasConnected = Attempt(() => TunnelService.IsUp(membership.ConfigFile));
        await Task.Run(() => Attempt(() => TunnelService.Down(membership.ConfigFile)));
        var steps = Leaving.After(await _vaier.Leave(membership), wasConnected);
        if (steps.Reconnect) await Task.Run(() => Attempt(() => TunnelService.Up(membership.ConfigFile)));
        _notice = steps.Notice;
        if (steps.Forget)
        {
            DeviceStore.Forget();
            _membership = null;
            ShowSetup();
            return;
        }
        ShowHome();
    }

    /// <summary>Opened with the tunnel off, nothing is watching — so ask once, over ordinary internet.</summary>
    private async Task CheckStandingOnOpening(Membership membership)
    {
        if (Attempt(() => TunnelService.IsUp(membership.ConfigFile))) return;
        var steps = StandingWatch.AfterOpening(await _vaier.AskStanding(membership));
        if (!steps.Forget || _membership != membership) return;
        DeviceStore.Forget();
        _membership = null;
        _notice = steps.Notice;
        ShowSetup();
    }

    private static void Open(string url) => Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

    private static void Attempt(Action act) => Attempt(() => { act(); return true; });

    private static T? Attempt<T>(Func<T> read)
    {
        try
        {
            return read();
        }
        catch (Exception)
        {
            return default;
        }
    }

    private static string Since(DateTime? at)
    {
        if (at is not { } when || when == default) return "never";
        var seconds = (long)(DateTime.UtcNow - when).TotalSeconds;
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
