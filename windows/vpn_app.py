import tkinter as tk
from tkinter import messagebox
import threading
import requests
import json
import os
import subprocess
import re
import socket
import time
import uuid
import base64
import datetime
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

WARP_API = "https://api.cloudflareclient.com/v0a2158/reg"
TUNNEL_NAME = "GlobalVPN"
CREDS_FILE = Path(os.environ.get("APPDATA", ".")) / "GlobalVPN" / "credentials.json"

COUNTRIES = [
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
]


def country_flag(code):
    if len(code) != 2:
        return "\U0001f310"
    base = 0x1F1E6 - ord("A")
    return chr(base + ord(code[0].upper())) + chr(base + ord(code[1].upper()))


def generate_keypair():
    key = X25519PrivateKey.generate()
    priv = base64.b64encode(key.private_bytes_raw()).decode()
    pub = base64.b64encode(key.public_key().public_bytes_raw()).decode()
    return priv, pub


def register_warp():
    priv, pub = generate_keypair()
    tos = datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%S.000Z")
    resp = requests.post(
        WARP_API,
        json={
            "install_id": str(uuid.uuid4()),
            "tos": tos,
            "key": pub,
            "model": "Windows",
            "locale": "en_US",
            "warp_enabled": True,
        },
        headers={
            "CF-Client-Version": "a-6.38-3734",
            "User-Agent": "okhttp/3.12.1",
        },
        timeout=15,
    )
    data = resp.json()
    if not resp.ok:
        raise Exception(f"WARP registration failed: {resp.status_code} – {data.get('message', '')}")
    config = data["config"]
    addresses = config["interface"]["addresses"]
    peer = config["peers"][0]
    endpoint_obj = peer["endpoint"]
    endpoint = endpoint_obj.get("v4") or endpoint_obj["host"]
    return {
        "private_key": priv,
        "client_address": addresses["v4"],
        "client_address_v6": addresses.get("v6", ""),
        "server_public_key": peer["public_key"],
        "server_endpoint": endpoint,
    }


def load_credentials():
    try:
        if CREDS_FILE.exists():
            with open(CREDS_FILE) as f:
                return json.load(f)
    except Exception:
        pass
    return None


def save_credentials(creds):
    CREDS_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(CREDS_FILE, "w") as f:
        json.dump(creds, f)


def build_wg_config(creds):
    has_v6 = bool(creds.get("client_address_v6"))
    addresses = creds["client_address"] + "/32"
    if has_v6:
        addresses += f", {creds['client_address_v6']}/128"
    allowed_ips = "0.0.0.0/0" + (", ::/0" if has_v6 else "")
    return (
        "[Interface]\n"
        f"PrivateKey = {creds['private_key']}\n"
        f"Address = {addresses}\n"
        "DNS = 1.1.1.1, 1.0.0.1\n"
        "MTU = 1280\n"
        "\n"
        "[Peer]\n"
        f"PublicKey = {creds['server_public_key']}\n"
        f"AllowedIPs = {allowed_ips}\n"
        f"Endpoint = {creds['server_endpoint']}\n"
        "PersistentKeepalive = 25\n"
    )


def patch_port(config: str, port: int) -> str:
    return re.sub(r"(Endpoint\s*=\s*\S+):(\d+)", lambda m: f"{m.group(1)}:{port}", config)


def find_wireguard():
    candidates = [
        r"C:\Program Files\WireGuard\wireguard.exe",
        r"C:\Program Files (x86)\WireGuard\wireguard.exe",
    ]
    for path in candidates:
        if os.path.exists(path):
            return path
    try:
        result = subprocess.run(["where", "wireguard"], capture_output=True, text=True)
        if result.returncode == 0:
            return result.stdout.strip().splitlines()[0]
    except Exception:
        pass
    return None


def tunnel_alive():
    try:
        with socket.create_connection(("1.1.1.1", 80), timeout=4):
            return True
    except Exception:
        return False


class VpnApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("GlobalVPN")
        self.geometry("420x540")
        self.resizable(False, False)
        self.configure(bg="#1a1a2e")
        self._state = "idle"
        self._build_ui()
        self._check_wireguard_on_start()

    def _build_ui(self):
        header = tk.Frame(self, bg="#16213e", pady=16)
        header.pack(fill=tk.X)
        tk.Label(
            header, text="\U0001f310  GlobalVPN",
            font=("Segoe UI", 18, "bold"), fg="white", bg="#16213e",
        ).pack()

        status_frame = tk.Frame(self, bg="#1a1a2e", pady=10)
        status_frame.pack(fill=tk.X, padx=24)
        self.lbl_status = tk.Label(
            status_frame, text="● Disconnected",
            font=("Segoe UI", 13), fg="#e74c3c", bg="#1a1a2e",
        )
        self.lbl_status.pack(anchor="w")

        tk.Label(
            self, text="Select Location",
            font=("Segoe UI", 11, "bold"), fg="#aaaacc", bg="#1a1a2e",
        ).pack(anchor="w", padx=24, pady=(8, 4))

        list_frame = tk.Frame(self, bg="#1a1a2e")
        list_frame.pack(fill=tk.BOTH, expand=True, padx=24)
        scrollbar = tk.Scrollbar(list_frame)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.listbox = tk.Listbox(
            list_frame, yscrollcommand=scrollbar.set,
            bg="#0f3460", fg="white",
            selectbackground="#533483", selectforeground="white",
            font=("Segoe UI", 10), borderwidth=0,
            highlightthickness=0, activestyle="none", height=12,
        )
        self.listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.config(command=self.listbox.yview)
        for code, country, city in COUNTRIES:
            self.listbox.insert(tk.END, f"  {country_flag(code)}  {country} – {city}")
        self.listbox.select_set(0)

        btn_frame = tk.Frame(self, bg="#1a1a2e", pady=16)
        btn_frame.pack(fill=tk.X, padx=24)
        self.btn_connect = tk.Button(
            btn_frame, text="Connect",
            font=("Segoe UI", 12, "bold"),
            bg="#533483", fg="white",
            activebackground="#6a4a9c", activeforeground="white",
            relief=tk.FLAT, pady=10, cursor="hand2",
            command=self._on_connect,
        )
        self.btn_connect.pack(fill=tk.X)

        self.lbl_log = tk.Label(
            self, text="", font=("Segoe UI", 9),
            fg="#888899", bg="#1a1a2e", wraplength=380,
        )
        self.lbl_log.pack(padx=24, pady=(0, 12))

    def _check_wireguard_on_start(self):
        if not find_wireguard():
            self._log(
                "⚠  WireGuard for Windows not found. "
                "Please install it from wireguard.com/install/",
                error=True,
            )
            self.btn_connect.config(state=tk.DISABLED)

    def _on_connect(self):
        if self._state == "connected":
            threading.Thread(target=self._disconnect, daemon=True).start()
        else:
            if not self.listbox.curselection():
                messagebox.showwarning("GlobalVPN", "Please select a location.")
                return
            threading.Thread(target=self._connect, daemon=True).start()

    def _set_state(self, state, msg="", error=False):
        self._state = state
        info = {
            "idle":       ("#e74c3c", "● Disconnected", "Connect",       tk.NORMAL),
            "connecting": ("#f39c12", "◌ Connecting…", "Connecting…", tk.DISABLED),
            "connected":  ("#2ecc71", "● Connected",    "Disconnect",    tk.NORMAL),
            "error":      ("#e74c3c", "✕ Error",         "Connect",       tk.NORMAL),
        }
        color, status_text, btn_text, btn_state = info.get(state, info["idle"])
        self.after(0, lambda: [
            self.lbl_status.config(text=status_text, fg=color),
            self.btn_connect.config(text=btn_text, state=btn_state),
            self._log(msg, error=(state == "error" or error)),
        ])

    def _log(self, msg, error=False):
        self.after(0, lambda: self.lbl_log.config(
            text=msg, fg="#e74c3c" if error else "#888899",
        ))

    def _connect(self):
        self._set_state("connecting", "Getting credentials…")
        try:
            wg = find_wireguard()
            if not wg:
                raise Exception("WireGuard for Windows is not installed.")

            creds = load_credentials()
            if not creds:
                self._set_state("connecting", "Registering with Cloudflare WARP…")
                creds = register_warp()
                save_credentials(creds)

            base_config = build_wg_config(creds)
            cfg_dir = CREDS_FILE.parent
            cfg_dir.mkdir(parents=True, exist_ok=True)
            cfg_path = cfg_dir / f"{TUNNEL_NAME}.conf"

            # Tear down any stale tunnel before starting
            subprocess.run([wg, "/uninstalltunnelservice", TUNNEL_NAME], capture_output=True)
            time.sleep(0.5)

            for port in [2408, 500, 1701, 4500]:
                cfg_path.write_text(patch_port(base_config, port), encoding="utf-8")
                self._set_state("connecting", f"Trying port {port}…")

                result = subprocess.run(
                    [wg, "/installtunnelservice", str(cfg_path)],
                    capture_output=True, text=True,
                )
                if result.returncode != 0:
                    raise Exception(f"WireGuard: {(result.stderr or result.stdout).strip()}")

                time.sleep(7)

                if tunnel_alive():
                    idx = self.listbox.curselection()
                    country = COUNTRIES[idx[0]][1] if idx else ""
                    self._set_state("connected", f"Connected – {country} (port {port})")
                    return

                subprocess.run([wg, "/uninstalltunnelservice", TUNNEL_NAME], capture_output=True)
                time.sleep(1)

            raise Exception(
                "No UDP port responded (tried 2408, 500, 1701, 4500). "
                "Try a different network."
            )
        except Exception as e:
            self._set_state("error", str(e))

    def _disconnect(self):
        self._set_state("connecting", "Disconnecting…")
        try:
            wg = find_wireguard()
            if wg:
                subprocess.run([wg, "/uninstalltunnelservice", TUNNEL_NAME], capture_output=True)
        except Exception:
            pass
        self._set_state("idle", "")


if __name__ == "__main__":
    app = VpnApp()
    app.mainloop()
