# Instant Hotspot CLI (experimental)

Cross-platform (Linux, macOS, Windows) **Bluetooth Low Energy** helper for the same GATT service the Android
controller app uses. Requires **Python 3.9+** and a BLE-capable computer with Bluetooth on.

## Install

```bash
cd cli
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## Usage

```bash
# List nearby hosts (filters by the Instant Hotspot service UUID)
python3 instant_hotspot.py scan

# After pairing in the app and saving the shared secret, send ON/OFF (see script --help for secret handling)
python3 instant_hotspot.py on --address AA:BB:CC:DD:EE:FF
```

The app remains the **authoritative** tool for pairing (ECDH + short code) and for computing the
HMAC used on commands. This CLI is intended for **automation, scripting, and headless** workflows once
you export or copy a shared secret from a paired device (or implement pairing in a future version).

Security note: do not store the shared secret in shell history. Prefer environment variables or a
`chmod 600` local file (see the script for optional `--secret-file`).

## Status

- **Scan / discover** — working with `bleak`.
- **Sign / send commands** — implement `CommandCodec` + HMAC in Python to match the app (same wire format
  as `com.spandan.instanthotspot.core.CommandCodec` and `CommandSecurity`).

Pull requests to complete signing and to mirror the v2 pairing messages are welcome.
