using Tunnel;
using Vaier.Core;

namespace Vaier.App;

/// <summary>Three screens: ask to join, show the join code while waiting, and the tunnel once this computer is in.</summary>
public class MainForm : Form
{
    private readonly VaierClient _client = new();
    private readonly Panel _setup = Screen();
    private readonly Panel _waiting = Screen();
    private readonly Panel _member = Screen();

    private readonly TextBox _address = Field();
    private readonly TextBox _deviceName = Field();
    private readonly Button _join = Action("Ask to join");
    private readonly Label _setupError = Note(Color.Firebrick);

    private readonly Label _code = new() { AutoSize = true, Font = new Font("Segoe UI", 40, FontStyle.Bold) };
    private readonly Label _countdown = Note(SystemColors.GrayText);
    private readonly Button _cancel = Action("Cancel");
    private CancellationTokenSource? _waitingCancel;
    private DateTime _deadline;

    private readonly Label _memberName = new() { AutoSize = true, Font = new Font("Segoe UI", 16, FontStyle.Bold) };
    private readonly Label _memberAddress = Note(SystemColors.GrayText);
    private readonly Label _status = Note(SystemColors.ControlText);
    private readonly Button _toggle = Action("Connect");
    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 1000 };
    private Membership? _membership;

    public MainForm()
    {
        Text = "Vaier";
        Font = new Font("Segoe UI", 10);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        ClientSize = new Size(420, 320);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.White;

        Stack(_setup, Heading("Join Vaier"), Note(SystemColors.GrayText, "This computer asks to join. You let it in from Vaier's fleet page."),
            Note(SystemColors.ControlText, "Vaier address"), _address,
            Note(SystemColors.ControlText, "Name this computer"), _deviceName, _join, _setupError);
        Stack(_waiting, Note(SystemColors.ControlText, "Your join code"), _code,
            Note(SystemColors.GrayText, "Approve it on Vaier's fleet page, under Waiting to join."), _countdown, _cancel);
        Stack(_member, _memberName, _memberAddress, _status, _toggle);
        Controls.AddRange([_setup, _waiting, _member]);

        _deviceName.Text = Environment.MachineName;
        _join.Click += async (_, _) => await Join();
        _cancel.Click += (_, _) => _waitingCancel?.Cancel();
        _toggle.Click += (_, _) => Toggle();
        _tick.Tick += (_, _) => UpdateStatus();
        AcceptButton = _join;

        _membership = DeviceStore.Load();
        Show(_membership is null ? _setup : _member);
        _tick.Start();
    }

    private async Task Join()
    {
        _setupError.Text = "";
        var address = VaierAddress.Normalise(_address.Text);
        if (address is null)
        {
            _setupError.Text = "That does not look like an address. Try vaier.example.com.";
            return;
        }
        var name = _deviceName.Text.Trim();
        if (name.Length == 0)
        {
            _setupError.Text = "Give this computer a name.";
            return;
        }

        _join.Enabled = false;
        var keypair = Keypair.Generate();
        var outcome = await _client.AskToJoin(address, name, keypair.Public);
        _join.Enabled = true;
        if (outcome is JoinOutcome.Turned turned)
        {
            _setupError.Text = turned.Reason;
            return;
        }

        var answer = ((JoinOutcome.Waiting)outcome).Answer;
        _code.Text = answer.Code;
        _deadline = DateTime.UtcNow.AddSeconds(answer.ExpiresInSeconds);
        Show(_waiting);
        _setupError.Text = await AwaitApproval(address, answer.Ticket, keypair);
        if (_membership is null) Show(_setup);
    }

    /// <summary>Waits for the operator's decision, reconnecting when the stream drops. Returns why it ended, if not in.</summary>
    private async Task<string> AwaitApproval(string address, string ticket, Keypair keypair)
    {
        using var cancel = _waitingCancel = new CancellationTokenSource();
        try
        {
            while (DateTime.UtcNow < _deadline)
            {
                var (verdict, payload) = await _client.AwaitVerdict(address, ticket, cancel.Token);
                switch (verdict)
                {
                    case Verdict.Approved:
                        var enrolment = EnrolmentPayload.Parse(payload, keypair.Public, keypair.Private);
                        _membership = DeviceStore.Save(address, enrolment.PeerName, enrolment.ConfigText);
                        TunnelService.Add(_membership.ConfigFile);
                        Show(_member);
                        return "";
                    case Verdict.Refused:
                        return "Vaier turned this computer away.";
                    case Verdict.Gone:
                        return "That join code ran out. Ask again.";
                    case Verdict.Lost:
                        await Task.Delay(TimeSpan.FromSeconds(3), cancel.Token);
                        break;
                }
            }
            return "That join code ran out. Ask again.";
        }
        catch (OperationCanceledException)
        {
            return "";
        }
        catch (EnrolmentException e)
        {
            return e.Message;
        }
        catch (Exception e)
        {
            return $"This computer was let in, but its tunnel would not start: {e.Message}";
        }
        finally
        {
            _waitingCancel = null;
        }
    }

    private void Toggle()
    {
        if (_membership is null) return;
        _toggle.Enabled = false;
        try
        {
            if (TunnelService.IsRunning(_membership.ConfigFile)) TunnelService.Remove(_membership.ConfigFile);
            else TunnelService.Add(_membership.ConfigFile);
        }
        catch (Exception e)
        {
            MessageBox.Show(this, e.Message, "Vaier", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        _toggle.Enabled = true;
        UpdateStatus();
    }

    private void UpdateStatus()
    {
        if (_waiting.Visible)
        {
            var left = _deadline - DateTime.UtcNow;
            _countdown.Text = left > TimeSpan.Zero ? $"Waiting · {left:m\\:ss} left" : "";
        }
        if (_member.Visible && _membership is not null)
        {
            var running = TunnelService.IsRunning(_membership.ConfigFile);
            _toggle.Text = running ? "Disconnect" : "Connect";
            _status.Text = running ? $"Connected{Handshake()}" : "Disconnected";
        }
    }

    private string Handshake()
    {
        try
        {
            var peer = new Driver.Adapter(Path.GetFileNameWithoutExtension(_membership!.ConfigFile)).GetConfiguration().Peers.FirstOrDefault();
            if (peer is null || peer.LastHandshake == default) return " · waiting for a handshake";
            var ago = DateTime.UtcNow - peer.LastHandshake;
            return $" · last handshake {(int)ago.TotalSeconds} s ago";
        }
        catch
        {
            return "";
        }
    }

    private void Show(Panel screen)
    {
        foreach (var panel in new[] { _setup, _waiting, _member }) panel.Visible = panel == screen;
        if (screen == _member && _membership is not null)
        {
            _memberName.Text = _membership.Name;
            _memberAddress.Text = $"In {_membership.Address}'s fleet";
        }
        UpdateStatus();
    }


    private static Panel Screen() => new() { Dock = DockStyle.Fill, Padding = new Padding(24), Visible = false };
    private static TextBox Field() => new() { Width = 360 };
    private static Button Action(string text) => new() { Text = text, AutoSize = true, Padding = new Padding(8, 2, 8, 2), Margin = new Padding(0, 12, 0, 0) };
    private static Label Heading(string text) => new() { Text = text, AutoSize = true, Font = new Font("Segoe UI", 16, FontStyle.Bold) };
    private static Label Note(Color colour, string text = "") => new() { Text = text, AutoSize = true, MaximumSize = new Size(360, 0), ForeColor = colour, Margin = new Padding(0, 6, 0, 0) };

    private static void Stack(Panel screen, params Control[] controls)
    {
        var flow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false };
        flow.Controls.AddRange(controls);
        screen.Controls.Add(flow);
    }
}
