using System.Drawing;
using System.Windows.Forms;

namespace GlobalVPN;

enum VpnState { Idle, Connecting, Connected, Error }

public class MainForm : Form
{
    private static readonly (string Code, string Country, string City)[] Countries =
    [
        ("US", "United States", "New York"),
        ("US", "United States", "Los Angeles"),
        ("US", "United States", "Chicago"),
        ("GB", "United Kingdom", "London"),
        ("DE", "Germany", "Frankfurt"),
        ("FR", "France", "Paris"),
        ("NL", "Netherlands", "Amsterdam"),
        ("SG", "Singapore", "Singapore"),
        ("JP", "Japan", "Tokyo"),
        ("AU", "Australia", "Sydney"),
        ("CA", "Canada", "Toronto"),
        ("BR", "Brazil", "São Paulo"),
        ("IN", "India", "Mumbai"),
        ("KR", "South Korea", "Seoul"),
        ("HK", "Hong Kong", "Hong Kong"),
        ("SE", "Sweden", "Stockholm"),
        ("CH", "Switzerland", "Zurich"),
        ("ES", "Spain", "Madrid"),
        ("IT", "Italy", "Milan"),
        ("MX", "Mexico", "Mexico City"),
        ("ZA", "South Africa", "Johannesburg"),
        ("AE", "UAE", "Dubai"),
        ("TR", "Turkey", "Istanbul"),
        ("PL", "Poland", "Warsaw"),
        ("NZ", "New Zealand", "Auckland"),
        ("NO", "Norway", "Oslo"),
        ("FI", "Finland", "Helsinki"),
        ("PT", "Portugal", "Lisbon"),
        ("AR", "Argentina", "Buenos Aires"),
        ("ID", "Indonesia", "Jakarta"),
    ];

    private readonly Label _lblStatus;
    private readonly ListBox _lstCountries;
    private readonly Button _btnConnect;
    private readonly Label _lblLog;

    private VpnState _state = VpnState.Idle;
    private CancellationTokenSource? _cts;

    public MainForm()
    {
        Text = "GlobalVPN";
        Size = new Size(420, 600);
        MinimumSize = Size;
        MaximumSize = Size;
        MaximizeBox = false;
        BackColor = Color.FromArgb(26, 26, 46);
        StartPosition = FormStartPosition.CenterScreen;

        // Header
        var header = new Panel
        {
            Dock = DockStyle.Top, Height = 60,
            BackColor = Color.FromArgb(22, 33, 62),
        };
        header.Controls.Add(new Label
        {
            Text = "GlobalVPN",
            Font = new Font("Segoe UI", 16, FontStyle.Bold),
            ForeColor = Color.White,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
        });

        // Status
        _lblStatus = new Label
        {
            Text = "● Disconnected",
            Font = new Font("Segoe UI", 12),
            ForeColor = Color.FromArgb(231, 76, 60),
            BackColor = Color.Transparent,
            Location = new Point(24, 76),
            AutoSize = true,
        };

        // Location label
        var lblLocation = new Label
        {
            Text = "Select Location",
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            ForeColor = Color.FromArgb(170, 170, 204),
            BackColor = Color.Transparent,
            Location = new Point(24, 116),
            AutoSize = true,
        };

        // Country list
        _lstCountries = new ListBox
        {
            Location = new Point(24, 144),
            Size = new Size(372, 264),
            BackColor = Color.FromArgb(15, 52, 96),
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 10),
            BorderStyle = BorderStyle.None,
            DrawMode = DrawMode.OwnerDrawFixed,
            ItemHeight = 26,
        };
        _lstCountries.DrawItem += (_, e) =>
        {
            if (e.Index < 0) return;
            bool sel = (e.State & DrawItemState.Selected) != 0;
            e.Graphics.FillRectangle(
                new SolidBrush(sel ? Color.FromArgb(83, 52, 131) : Color.FromArgb(15, 52, 96)),
                e.Bounds);
            TextRenderer.DrawText(e.Graphics, _lstCountries.Items[e.Index]?.ToString(),
                e.Font, e.Bounds, Color.White,
                TextFormatFlags.VerticalCenter | TextFormatFlags.Left);
        };
        foreach (var (code, country, city) in Countries)
            _lstCountries.Items.Add($"  {Flag(code)}  {country} – {city}");
        _lstCountries.SelectedIndex = 0;

        // Connect button
        _btnConnect = new Button
        {
            Text = "Connect",
            Location = new Point(24, 428),
            Size = new Size(372, 46),
            Font = new Font("Segoe UI", 12, FontStyle.Bold),
            ForeColor = Color.White,
            BackColor = Color.FromArgb(83, 52, 131),
            FlatStyle = FlatStyle.Flat,
            Cursor = Cursors.Hand,
        };
        _btnConnect.FlatAppearance.BorderSize = 0;
        _btnConnect.FlatAppearance.MouseOverBackColor = Color.FromArgb(106, 74, 156);
        _btnConnect.Click += OnConnectClick;

        // Log label
        _lblLog = new Label
        {
            Text = WireGuardManager.FindWireGuard() == null
                ? "WireGuard will be installed automatically on first connect."
                : "",
            Font = new Font("Segoe UI", 9),
            ForeColor = Color.FromArgb(136, 136, 153),
            BackColor = Color.Transparent,
            Location = new Point(24, 488),
            Size = new Size(372, 60),
        };

        Controls.AddRange(new Control[]
        {
            header, _lblStatus, lblLocation, _lstCountries, _btnConnect, _lblLog,
        });
    }

    private async void OnConnectClick(object? sender, EventArgs e)
    {
        if (_state == VpnState.Connected)
            await DisconnectAsync();
        else
            await ConnectAsync();
    }

    private async Task ConnectAsync()
    {
        _cts = new CancellationTokenSource();
        SetState(VpnState.Connecting, "Starting…");

        await Task.Run(async () =>
        {
            try
            {
                await WireGuardManager.EnsureInstalledAsync(Log);
                Log("Registering with Cloudflare WARP…");
                var creds = await WarpClient.GetOrRegisterAsync();
                var config = WarpClient.BuildConfig(creds);
                await WireGuardManager.ConnectAsync(config, Log, _cts.Token);

                var country = _lstCountries.SelectedIndex >= 0
                    ? Countries[_lstCountries.SelectedIndex].Country : "";
                SetState(VpnState.Connected, $"Connected – {country}");
            }
            catch (OperationCanceledException)
            {
                SetState(VpnState.Idle, "");
            }
            catch (Exception ex)
            {
                SetState(VpnState.Error, ex.Message);
            }
        });
    }

    private async Task DisconnectAsync()
    {
        SetState(VpnState.Connecting, "Disconnecting…");
        try { await WireGuardManager.DisconnectAsync(); } catch { }
        SetState(VpnState.Idle, "");
    }

    private void SetState(VpnState state, string msg)
    {
        _state = state;
        Invoke(() =>
        {
            (_lblStatus.Text, _lblStatus.ForeColor, _btnConnect.Text, _btnConnect.Enabled) = state switch
            {
                VpnState.Idle       => ("● Disconnected", Color.FromArgb(231, 76, 60),  "Connect",        true),
                VpnState.Connecting => ("◌ Connecting…",  Color.FromArgb(243, 156, 18), "Connecting…", false),
                VpnState.Connected  => ("● Connected",    Color.FromArgb(46, 204, 113),  "Disconnect",     true),
                VpnState.Error      => ("✕ Error",         Color.FromArgb(231, 76, 60),  "Connect",        true),
                _                   => (_lblStatus.Text, _lblStatus.ForeColor, _btnConnect.Text, true),
            };
            _lblLog.Text = msg;
            _lblLog.ForeColor = state == VpnState.Error
                ? Color.FromArgb(231, 76, 60)
                : Color.FromArgb(136, 136, 153);
        });
    }

    private void Log(string msg)
    {
        if (InvokeRequired) Invoke(() => Log(msg));
        else
        {
            _lblLog.Text = msg;
            _lblLog.ForeColor = Color.FromArgb(136, 136, 153);
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _cts?.Cancel();
        base.OnFormClosing(e);
    }

    private static string Flag(string code)
    {
        if (code.Length != 2) return "🌐";
        int b = 0x1F1E6 - 'A';
        return char.ConvertFromUtf32(b + char.ToUpper(code[0])) +
               char.ConvertFromUtf32(b + char.ToUpper(code[1]));
    }
}
