using System.Net.Http;
using System.Text;
using System.Text.Json;
using NSec.Cryptography;

namespace GlobalVPN;

public record WarpCredentials(
    string PrivateKey,
    string ClientAddress,
    string ClientAddressV6,
    string ServerPublicKey,
    string ServerEndpoint);

public static class WarpClient
{
    private static readonly HttpClient Http = new();
    private const string ApiUrl = "https://api.cloudflareclient.com/v0a2158/reg";
    private static readonly string CredPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "GlobalVPN", "credentials.json");

    static WarpClient()
    {
        Http.DefaultRequestHeaders.Add("CF-Client-Version", "a-6.38-3734");
        Http.DefaultRequestHeaders.Add("User-Agent", "okhttp/3.12.1");
    }

    private static (string Priv, string Pub) GenerateKeyPair()
    {
        using var key = Key.Create(KeyAgreementAlgorithm.X25519);
        return (
            Convert.ToBase64String(key.Export(KeyBlobFormat.RawPrivateKey)),
            Convert.ToBase64String(key.PublicKey.Export(KeyBlobFormat.RawPublicKey))
        );
    }

    public static WarpCredentials? LoadCached()
    {
        try
        {
            if (File.Exists(CredPath))
                return JsonSerializer.Deserialize<WarpCredentials>(File.ReadAllText(CredPath));
        }
        catch { }
        return null;
    }

    private static void SaveCached(WarpCredentials creds)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(CredPath)!);
        File.WriteAllText(CredPath, JsonSerializer.Serialize(creds));
    }

    public static async Task<WarpCredentials> GetOrRegisterAsync()
    {
        var cached = LoadCached();
        if (cached != null) return cached;

        var (priv, pub) = GenerateKeyPair();
        var payload = JsonSerializer.Serialize(new
        {
            install_id = Guid.NewGuid().ToString(),
            tos = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ"),
            key = pub,
            model = "Windows",
            locale = "en_US",
            warp_enabled = true,
        });

        var resp = await Http.PostAsync(ApiUrl,
            new StringContent(payload, Encoding.UTF8, "application/json"));
        var json = await resp.Content.ReadAsStringAsync();

        if (!resp.IsSuccessStatusCode)
        {
            using var errDoc = JsonDocument.Parse(json);
            var msg = errDoc.RootElement.TryGetProperty("message", out var m) ? m.GetString() : null;
            throw new Exception($"WARP registration failed: {resp.StatusCode} – {msg}");
        }

        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var addresses = root.GetProperty("config").GetProperty("interface").GetProperty("addresses");
        var peer = root.GetProperty("config").GetProperty("peers")[0];
        var epObj = peer.GetProperty("endpoint");
        var endpoint = (epObj.TryGetProperty("v4", out var v4) && (v4.GetString()?.Length ?? 0) > 0)
            ? v4.GetString()!
            : epObj.GetProperty("host").GetString()!;

        var creds = new WarpCredentials(
            PrivateKey: priv,
            ClientAddress: addresses.GetProperty("v4").GetString()!,
            ClientAddressV6: (addresses.TryGetProperty("v6", out var v6) ? v6.GetString() : null) ?? "",
            ServerPublicKey: peer.GetProperty("public_key").GetString()!,
            ServerEndpoint: endpoint);

        SaveCached(creds);
        return creds;
    }

    public static string BuildConfig(WarpCredentials c)
    {
        bool v6 = !string.IsNullOrEmpty(c.ClientAddressV6);
        var addresses = c.ClientAddress + "/32" + (v6 ? $", {c.ClientAddressV6}/128" : "");
        var allowed = "0.0.0.0/0" + (v6 ? ", ::/0" : "");
        return
            $"[Interface]\nPrivateKey = {c.PrivateKey}\nAddress = {addresses}\n" +
            $"DNS = 1.1.1.1, 1.0.0.1\nMTU = 1280\n\n" +
            $"[Peer]\nPublicKey = {c.ServerPublicKey}\nAllowedIPs = {allowed}\n" +
            $"Endpoint = {c.ServerEndpoint}\nPersistentKeepalive = 25\n";
    }
}
