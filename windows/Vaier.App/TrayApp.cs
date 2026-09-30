using Vaier.Core;

namespace Vaier.App;

/// <summary>
/// The tray icon, started with each sign-in: lit while connected, dim otherwise, and the way back into the
/// window. It reads the manager service's status — a local call, not the network — every few seconds.
/// </summary>
public class TrayApp : ApplicationContext
{
    private readonly NotifyIcon _icon = new();
    private readonly Icon _lit = Theme.AppIcon();
    private readonly Icon _dim = Theme.AppIcon("vaier-off.ico");
    private readonly ToolStripMenuItem _toggle = new("Connect") { Enabled = false };
    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 3000 };
    private readonly EventWaitHandle _showRequested;
    private MainForm? _window;
    private DeviceStatus? _status;
    private string? _lastNotified;

    public const string ShowEvent = @"Local\VaierShowWindow";

    public TrayApp(bool showWindow)
    {
        var menu = new ContextMenuStrip { Renderer = new ToolStripProfessionalRenderer(new Theme.MenuColours()), ShowImageMargin = false, Font = Theme.Ui(10) };
        menu.Items.Add(new ToolStripMenuItem("Open Vaier", null, (_, _) => ShowWindow()) { ForeColor = Theme.Text, Font = Theme.Ui(10, FontStyle.Bold) });
        _toggle.ForeColor = Theme.Text;
        _toggle.Click += async (_, _) => await Toggle();
        menu.Items.Add(_toggle);
        _icon.ContextMenuStrip = menu;
        _icon.Icon = _dim;
        _icon.Text = "Vaier";
        _icon.Visible = true;
        _icon.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };

        // A second start (Start menu, the setup handing over) asks this one to show its window, and exits.
        _showRequested = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEvent);
        ThreadPool.RegisterWaitForSingleObject(_showRequested, (_, _) => _icon.ContextMenuStrip.BeginInvoke(ShowWindow), null, -1, false);

        _tick.Tick += async (_, _) => await Refresh();
        _tick.Start();
        _ = Refresh();
        if (showWindow) ShowWindow();
    }

    private void ShowWindow()
    {
        if (_window is null || _window.IsDisposed)
        {
            _window = new MainForm();
            _window.FormClosed += (_, _) => _window = null;
            _window.Show();
        }
        if (_window.WindowState == FormWindowState.Minimized) _window.WindowState = FormWindowState.Normal;
        _window.Activate();
    }

    private async Task Toggle()
    {
        if (_status?.Member is null) return;
        _toggle.Enabled = false;
        await Pipe.Ask(_status.Up ? "disconnect" : "connect");
        await Refresh();
    }

    private async Task Refresh()
    {
        _status = (await Pipe.Ask("status"))?.Status;
        var look = TrayLook.Of(_status);
        _icon.Icon = look.Lit ? _lit : _dim;
        _icon.Text = look.Tooltip.Length > 127 ? look.Tooltip[..127] : look.Tooltip;
        _toggle.Text = _status?.Up == true ? "Disconnect" : "Connect";
        _toggle.Enabled = _status?.Member is not null;

        // A removal found with nobody looking is said once, here; the window says it again when opened.
        if (_status?.Notice is { } notice && notice != _lastNotified && _window is null)
            _icon.ShowBalloonTip(10_000, "Vaier", notice, ToolTipIcon.Info);
        _lastNotified = _status?.Notice;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _icon.Visible = false;
            _icon.Dispose();
            _showRequested.Dispose();
        }
        base.Dispose(disposing);
    }
}
