# 🌐 InnerNode

> **Cross-platform P2P VPN for gaming and LAN networking** — WireGuard-powered rooms for Windows & Android. No port forwarding needed.

[![Status](https://img.shields.io/badge/status-active-brightgreen)](https://bypass-vpn.duckdns.org/rooms)
[![Backend](https://img.shields.io/badge/backend-Flask%20%2B%20WireGuard-blue)](/)
[![Android](https://img.shields.io/badge/android-Kotlin%20%2B%20VpnService-green)](/)
[![Desktop](https://img.shields.io/badge/desktop-Rust%20%2B%20Tauri-orange)](/)

---

## 🎯 What is InnerNode?

InnerNode is a self-hosted alternative to **Radmin VPN / Hamachi** with native Android support. It creates an isolated L3 virtual network (10.66.66.0/24) so players on **Windows and Android** can join the same LAN room — for Minecraft, file sharing, or anything that needs a local network.

No config files. No port forwarding. Just create a room and share the name.

---

## ✨ Features

- **Room-based networking** — Create or join named rooms, each with isolated IP space
- **Private rooms** — Optional password protection
- **Cross-platform** — Windows desktop + Android, same room
- **Persistent tunnels** — Android tunnel survives app close (survives MIUI kill too)
- **Auto key management** — WireGuard keypairs generated and stored securely on first launch
- **System tray app** — Windows client minimizes to tray, stays running in background
- **Live peer list** — See who's in your room, updated every 5 seconds
- **DPI bypass** — Tunnel traffic over UDP/443 (QUIC-style) for restrictive networks

---

## 🛠 Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Backend Orchestrator                 │
│           Flask API + WireGuard + Ubuntu             │
│    IPAM: 10.66.66.0/24  │  Nginx HTTPS  │ DuckDNS   │
└──────────────┬──────────────────────────┬────────────┘
               │                          │
    ┌──────────▼──────────┐   ┌───────────▼───────────┐
    │   Android Client    │   │    Desktop Client      │
    │  Kotlin + Retrofit  │   │    Rust + Tauri        │
    │  VpnService API     │   │  wireguard.exe embed   │
    │  WireGuard-Go       │   │  Windows Firewall API  │
    └─────────────────────┘   └───────────────────────┘
```

### Backend (Python / Flask)
- REST API: room creation, join/leave, peer coordination
- Direct WireGuard interface management via `subprocess` + Linux syscalls
- Custom IPAM: dynamic IP allocation per session
- Deployed on Ubuntu Server behind Nginx + HTTPS (Certbot) + DuckDNS

### Android Client (Kotlin)
- Native `VpnService` API — OS-level IP packet interception and routing
- WireGuard-Go backend for the tunnel
- Retrofit for async API calls
- `EncryptedSharedPreferences` (AES-256-GCM) for key storage
- Non-daemon thread keeps tunnel alive after app is swiped away

### Desktop Client (Rust / Tauri)
- `wireguard.exe` and `wintun.dll` embedded directly in the binary (`include_bytes!`)
- Installs WireGuard as a Windows service on connect, removes it on disconnect
- Automatic Windows Firewall rules (netsh) for VPN subnet traffic
- Keys stored in `%APPDATA%/InnerNode/keypair.json` with `icacls` permission restriction
- System tray with show/hide and clean quit (leaves room before exit)

---

## 🔌 API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/rooms` | List all rooms |
| `POST` | `/create_room` | Create room, returns WireGuard config |
| `POST` | `/join_room` | Join room, returns WireGuard config |
| `POST` | `/leave_room` | Leave room, removes peer from WireGuard |
| `GET` | `/room_peers/{name}` | List IPs of peers in a room |

**VpnConfig response:**
```json
{
  "client_ip": "10.66.66.X",
  "server_endpoint": "bypass-vpn.duckdns.org:PORT",
  "server_public_key": "base64..."
}
```

---

## ⚙️ Technical Highlights

**Automatic peer provisioning**
No manual `wg addpeer`. The backend generates WireGuard configs on the fly and applies them live with zero downtime.

**Embedded binaries (Windows)**
`wireguard.exe` and `wintun.dll` are bundled inside the Tauri binary using `include_bytes!`. No installer, no dependencies — single `.exe` file.

**Cross-platform key compatibility**
Both clients use x25519 keys (WireGuard-native). Android uses `wireguard-android` crypto, Rust uses `x25519-dalek` with `OsRng`. Keys are base64 and fully interoperable with standard WireGuard configs.

**Android tunnel persistence**
The VPN tunnel runs on a non-daemon thread (`isDaemon = false`) and the service overrides `onTaskRemoved` to prevent Android from killing it when the app is swiped. Works on MIUI/HyperOS which aggressively kills background processes.

**Windows Firewall automation**
On connect, the app automatically adds `netsh` rules allowing all traffic from `10.66.66.0/24`, sets the tunnel interface to Private network profile, and enables ICMPv4 (ping). All rules are cleaned up on disconnect.

---

## 🔒 Security Notes

- WireGuard keys never leave the device in plaintext
- Android: keys stored in `EncryptedSharedPreferences` (AES-256-SIV/GCM backed by Android Keystore)
- Windows: keys stored in user-only file (`icacls /inheritance:r`)
- Rooms support password protection (hashed on backend)
- Input validation on all API endpoints

---

## 🗺 Roadmap

- [ ] STUN/TURN for direct peer-to-peer without full VPN routing
- [ ] Built-in VoIP (Opus codec over UDP inside the tunnel)
- [ ] iOS client
- [ ] Self-hosted backend — Docker image for one-command deploy

---

## 📦 Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Python, Flask, WireGuard, Ubuntu, Nginx, Certbot |
| Android | Kotlin, Jetpack Compose, Retrofit, WireGuard-Go |
| Desktop | Rust, Tauri, reqwest, x25519-dalek, serde |
| Infra | DuckDNS, HTTPS, Ubuntu Server |

---

*Built as a practical solution to the lack of P2P LAN tools for mobile platforms.*
