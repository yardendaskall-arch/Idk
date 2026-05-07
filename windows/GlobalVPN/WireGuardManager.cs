using System.Diagnostics;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;

namespace GlobalVPN;

public static class WireGuardManager
{
    private const string TunnelName = "GlobalVPN";
    private static readonly string AppDataDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GlobalVPN");

    public static string? FindWireGuard()
    {
        string[] candidates =
        [
            @"C:\Program Files\WireGuard\wireguard.exe",
            @"C:\Program Files (x86)\WireGuard\wireguard.exe",
        ];
        foreach (var p in candidates)
            if (File.Exists(p)) return p;
        return null;
    }

    public static async Task EnsureInstalledAsync(Action<string> report)
    {
        if (FindWireGuard() != null) return;

        report("Downloading WireGuard for Windows…");
        using var http = new HttpClient();
        var installer = Path.Combine(Path.GetTempPath(), "wireguard-installer.exe");
        var bytes = await http.GetByteArrayAsync(
            "https://download.wireguard.com/windows-client/wireguard-installer.exe");
        await File.WriteAllBytesAsync(installer, bytes);

        report("Installing WireGuard (one-time setup)…");
        using var proc = Process.Start(new ProcessStartInfo
        {
            FileName = installer,
            Arguments = "/S",
            UseShellExecute = true,
        })!;
        await proc.WaitForExitAsync();

        if (FindWireGuard() == null)
            throw new Exception(
                "WireGuard installation failed. " +
                "Install manually from wireguard.com/install/");
    }

    public static async Task ConnectAsync(
        string config, Action<string> report, CancellationToken ct = default)
    {
        string wg = FindWireGuard() ?? throw new Exception("WireGuard not found.");
        Directory.CreateDirectory(AppDataDir);
        var cfgPath = Path.Combine(AppDataDir, $"{TunnelName}.conf");

        await RunAsync(wg, $"/uninstalltunnelservice {TunnelName}");
        await Task.Delay(500, ct);

        foreach (int port in new[] { 2408, 500, 1701, 4500 })
        {
            ct.ThrowIfCancellationRequested();
            await File.WriteAllTextAsync(cfgPath, PatchPort(config, port), ct);
            report($"Trying port {port}…");

            var (code, output) = await RunAsync(wg, $"/installtunnelservice \"{cfgPath}\"");
            if (code != 0)
                throw new Exception($"WireGuard: {output.Trim()}");

            await Task.Delay(7000, ct);

            if (await TunnelAliveAsync()) return;

            await RunAsync(wg, $"/uninstalltunnelservice {TunnelName}");
            await Task.Delay(1000, ct);
        }

        throw new Exception(
            "No UDP port responded (tried 2408, 500, 1701, 4500). " +
            "Try a different network.");
    }

    public static async Task DisconnectAsync()
    {
        var wg = FindWireGuard();
        if (wg != null) await RunAsync(wg, $"/uninstalltunnelservice {TunnelName}");
    }

    private static string PatchPort(string config, int port) =>
        Regex.Replace(config, @"(Endpoint\s*=\s*\S+):\d+", $"$1:{port}");

    private static async Task<bool> TunnelAliveAsync()
    {
        try
        {
            using var tcp = new TcpClient();
            await tcp.ConnectAsync("1.1.1.1", 80)
                     .WaitAsync(TimeSpan.FromSeconds(4));
            return true;
        }
        catch { return false; }
    }

    private static async Task<(int Code, string Output)> RunAsync(string exe, string args)
    {
        var sb = new StringBuilder();
        var tcs = new TaskCompletionSource<(int, string)>();
        var proc = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = exe,
                Arguments = args,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            },
            EnableRaisingEvents = true,
        };
        proc.OutputDataReceived += (_, e) => { if (e.Data != null) sb.AppendLine(e.Data); };
        proc.ErrorDataReceived += (_, e) => { if (e.Data != null) sb.AppendLine(e.Data); };
        proc.Exited += (_, _) => tcs.TrySetResult((proc.ExitCode, sb.ToString()));
        proc.Start();
        proc.BeginOutputReadLine();
        proc.BeginErrorReadLine();
        return await tcs.Task;
    }
}
