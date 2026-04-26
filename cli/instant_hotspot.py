#!/usr/bin/env python3
"""
Instant Hotspot — BLE scan helper (Linux / macOS / Windows).

Full command signing is not yet implemented; use the Android controller app to pair
and control the hotspot. This script discovers hosts advertising the app service.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
import uuid

# Must match com.spandan.instanthotspot.core.BleProtocol.SERVICE_UUID
SERVICE_UUID = uuid.UUID("6d8f3e10-a896-4a2f-b63a-9c1c6ff00101")


def _env_secret() -> str | None:
    s = os.environ.get("INSTANT_HOTSPOT_SECRET", "").strip()
    return s or None


async def cmd_scan(timeout: float) -> int:
    try:
        from bleak import BleakScanner
    except ImportError as e:
        print("Install bleak: pip install -r requirements.txt", file=sys.stderr)
        raise SystemExit(1) from e

    found: dict[str, str] = {}

    def _det(d, ad):
        a = (ad or {}).get("manufacturer_data") or {}
        uuids = (ad or {}).get("service_uuids") or []
        uuids = [str(u) for u in uuids]
        if str(SERVICE_UUID).lower() not in [u.lower() for u in uuids] and not uuids:
            # Some stacks only expose 16/32-bit in advertisement; also accept local name match later
            return
        addr = d.address
        name = d.name or ""
        if addr and addr not in found:
            found[addr] = name

    devices = await BleakScanner.discover(timeout=timeout, detection_callback=_det)
    for d in devices:
        s = d.metadata.get("service_uuids") or []
        if str(SERVICE_UUID).lower() in [str(x).lower() for x in s]:
            found[d.address] = d.name or found.get(d.address, "")

    if not found:
        print("No Instant Hotspot hosts found (timeout=%ss). Ensure host app is in Host mode." % timeout)
        return 2
    for addr, name in sorted(found.items()):
        label = f'  {addr}  {name}' if name else f'  {addr}'
        print(label)
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Instant Hotspot BLE tools")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("scan", help="Scan for advertising hosts")
    sp.add_argument("--timeout", type=float, default=8.0, help="Scan duration in seconds")
    # Placeholder: ON/OFF when Python signing is implemented
    p_on = sub.add_parser("on", help="(Reserved) send HOTSPOT_ON after signing is implemented")
    p_on.add_argument("--address", help="Target BLE address")
    p_on.add_argument(
        "--secret-file",
        help="File containing shared secret (default: $INSTANT_HOTSPOT_SECRET)",
    )

    args = p.parse_args()
    if args.cmd == "scan":
        return asyncio.run(cmd_scan(args.timeout))
    if args.cmd == "on":
        if not args.address:
            p.error("--address is required (until mDNS/last-device selection is added)")
        sec = _env_secret()
        if args.secret_file:
            with open(args.secret_file, encoding="utf-8") as f:
                sec = f.read().strip()
        if not sec:
            print(
                "Set INSTANT_HOTSPOT_SECRET or use --secret-file. "
                "HMAC command signing in Python is not yet wired; this subcommand is a stub.",
                file=sys.stderr,
            )
            return 3
        print("Command signing (Python) is not yet implemented. Use the Android app to toggle.", file=sys.stderr)
        return 3
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
