using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace Vaier.App;

/// <summary>The Android app's palette — Vaier commits to one warm dark look, so this app does too.</summary>
public static class Theme
{
    public static readonly Color Amber = ColorTranslator.FromHtml("#D9A05B");
    public static readonly Color Panel = ColorTranslator.FromHtml("#191510");
    public static readonly Color Card = ColorTranslator.FromHtml("#201A13");
    public static readonly Color Text = ColorTranslator.FromHtml("#EAE0D2");
    public static readonly Color TextDim = ColorTranslator.FromHtml("#7A6A53");
    public static readonly Color Error = ColorTranslator.FromHtml("#E5877A");
    public static readonly Color Line = ColorTranslator.FromHtml("#3A3024");

    public static Font Ui(float size, FontStyle style = FontStyle.Regular) => new("Segoe UI", size, style);
    public static Font Mono(float size, FontStyle style = FontStyle.Regular) => new("Cascadia Mono", size, style);

    public static Icon AppIcon(string name = "vaier.ico")
    {
        using var stream = typeof(Theme).Assembly.GetManifestResourceStream(name)!;
        return new Icon(stream);
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>A dark title bar, so the window's frame matches what is inside it.</summary>
    public static void DarkTitleBar(Form form)
    {
        var on = 1;
        DwmSetWindowAttribute(form.Handle, 20, ref on, sizeof(int));
    }

    public static Label Label(string text, Font font, Color colour) =>
        new() { Text = text, Font = font, ForeColor = colour, AutoSize = true, MaximumSize = new Size(360, 0), Margin = new Padding(0, 0, 0, 12) };

    public static Button Primary(string text) => Styled(new Button { Text = text, BackColor = Amber, ForeColor = Panel, Font = Ui(10.5f, FontStyle.Bold) }, 360);

    public static Button Quiet(string text) => Styled(new Button { Text = text, BackColor = Panel, ForeColor = Amber, Font = Ui(10.5f) }, 360);

    private static Button Styled(Button button, int width)
    {
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderSize = 0;
        button.FlatAppearance.MouseOverBackColor = ControlPaint.Light(button.BackColor, 0.15f);
        button.Size = new Size(width, 44);
        button.Cursor = Cursors.Hand;
        button.Margin = new Padding(0, 4, 0, 4);
        return button;
    }

    /// <summary>A labelled text field on the card colour, with a thin border the stock TextBox cannot draw in this palette.</summary>
    public static (Control Field, TextBox Box) Field(string label, string placeholder)
    {
        var box = new TextBox
        {
            BorderStyle = BorderStyle.None, BackColor = Card, ForeColor = Text, Font = Ui(11),
            PlaceholderText = placeholder, Dock = DockStyle.Fill,
        };
        var frame = new Panel { BackColor = Card, Padding = new Padding(12, 10, 12, 8), Size = new Size(360, 40) };
        frame.Controls.Add(box);
        frame.Paint += (_, e) => e.Graphics.DrawRectangle(new Pen(box.Focused ? Amber : Line), 0, 0, frame.Width - 1, frame.Height - 1);
        box.GotFocus += (_, _) => frame.Invalidate();
        box.LostFocus += (_, _) => frame.Invalidate();

        var column = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Margin = new Padding(0, 0, 0, 12) };
        column.Controls.Add(new Label { Text = label, Font = Ui(9), ForeColor = TextDim, AutoSize = true, Margin = new Padding(0, 0, 0, 4) });
        column.Controls.Add(frame);
        return (column, box);
    }

    /// <summary>A vertical stack that fills its screen with the app's padding.</summary>
    public static FlowLayoutPanel Stack() => new()
    {
        Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false,
        Padding = new Padding(28, 24, 28, 24), BackColor = Panel, AutoScroll = true,
    };

    /// <summary>A rounded card, as the connection sits on in the Android app.</summary>
    public class RoundedCard : Panel
    {
        public RoundedCard()
        {
            DoubleBuffered = true;
            BackColor = Panel;
            Padding = new Padding(20);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var path = Rounded(new Rectangle(0, 0, Width - 1, Height - 1), 14);
            using var fill = new SolidBrush(Card);
            e.Graphics.FillPath(fill, path);
        }
    }

    /// <summary>The connection switch: amber when on, a quiet track when off.</summary>
    public class Switch : CheckBox
    {
        public Switch()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
            Size = new Size(52, 30);
            Cursor = Cursors.Hand;
            AutoSize = false;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Card);
            using var track = Rounded(new Rectangle(1, 3, Width - 3, Height - 7), (Height - 7) / 2);
            using var trackFill = new SolidBrush(Checked ? Amber : Line);
            g.FillPath(trackFill, track);
            var knob = Height - 13;
            var x = Checked ? Width - knob - 6 : 5;
            using var knobFill = new SolidBrush(Checked ? Panel : TextDim);
            g.FillEllipse(knobFill, x, 6, knob, knob);
            if (!Enabled)
            {
                using var veil = new SolidBrush(Color.FromArgb(120, Card));
                g.FillRectangle(veil, ClientRectangle);
            }
        }
    }

    private static GraphicsPath Rounded(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        var d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    /// <summary>Menus in the palette, instead of the system's light grey.</summary>
    public class MenuColours : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => Card;
        public override Color MenuItemSelected => Line;
        public override Color MenuItemBorder => Line;
        public override Color MenuBorder => Line;
        public override Color ImageMarginGradientBegin => Card;
        public override Color ImageMarginGradientMiddle => Card;
        public override Color ImageMarginGradientEnd => Card;
    }
}
