# Transwarp privacy policy

Transwarp collects no analytics, has no accounts and sends no data to its developers.

- Tunnel configs, keys and settings stay on the device (exported only when you choose to).
- The standalone build checks `api.github.com/repos/trip1/android/releases` for updates.
- Mesh enrollment sends a one-time code and your WireGuard **public** key to the gofire control server named in that code. The private key never leaves the device.
- Location permission is used only to read the Wi-Fi SSID for auto-tunneling, on the device.
- Beam (off by default) receives files only over an active tunnel, from peers you configured.
