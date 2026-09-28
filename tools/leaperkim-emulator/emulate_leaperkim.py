"""Replay captured Leaperkim FFE1 notifications through Bumble's Android Netsim radio.

No physical Bluetooth adapter is used. The Android Emulator and Bumble join the same
Netsim virtual radio; Volty runs in that Android Emulator as the BLE central.
"""
from __future__ import annotations

import argparse
import asyncio
import re
from dataclasses import dataclass
from pathlib import Path

import bumble.logging
from bumble.core import AdvertisingData
from bumble.device import Device, DeviceConfiguration
from bumble.gatt import Characteristic, CharacteristicValue, Service
from bumble.hci import Address
from bumble.transport import open_transport


SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
MAX_NOTIFICATION_BYTES = 20

DIAGNOSTIC_ROW = re.compile(
    r"(?P<timestamp>\d{13})\s+LK16313\s+[0-9A-F]{2}(?::[0-9A-F]{2}){5}"
    r"\s+VETERAN\s+0000ffe1-0000-1000-8000-00805f9b34fb"
    r"\s+(?P<length>\d+)\s+(?P<payload>(?:[0-9A-F]{2}\s*)+)",
    re.IGNORECASE,
)
FIXTURE_ROW = re.compile(r"^\s*(?P<elapsed>\d+)\s+(?P<payload>(?:[0-9A-F]{2}\s*)+)\s*$")


@dataclass(frozen=True)
class Packet:
    elapsed_ms: int
    payload: bytes


def parse_payload(text: str, expected_length: int | None = None) -> bytes:
    payload = bytes.fromhex(text)
    if not payload:
        raise ValueError("empty BLE notification")
    if len(payload) > MAX_NOTIFICATION_BYTES:
        raise ValueError(
            f"notification is {len(payload)} bytes; this capture is expected to use "
            f"the default 20-byte ATT payload"
        )
    if expected_length is not None and len(payload) != expected_length:
        raise ValueError(
            f"record declares {expected_length} bytes but contains {len(payload)}"
        )
    return payload


def load_packets(path: Path) -> list[Packet]:
    text = path.read_text(encoding="utf-8-sig")
    diagnostic_records: list[tuple[int, int, bytes]] = []
    for source_index, line in enumerate(text.splitlines()):
        match = DIAGNOSTIC_ROW.search(line)
        if not match:
            continue
        payload = parse_payload(
            match.group("payload"), int(match.group("length"))
        )
        diagnostic_records.append(
            (int(match.group("timestamp")), source_index, payload)
        )

    if diagnostic_records:
        # Debug exports list newest first, including their order for equal timestamps.
        diagnostic_records.sort(key=lambda record: (record[0], -record[1]))
        start_ms = diagnostic_records[0][0]
        return [
            Packet(timestamp - start_ms, payload)
            for timestamp, _source_index, payload in diagnostic_records
        ]

    packets: list[Packet] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = FIXTURE_ROW.fullmatch(line)
        if not match:
            raise ValueError(f"{path}:{line_number}: unrecognized capture row")
        packets.append(
            Packet(
                int(match.group("elapsed")),
                parse_payload(match.group("payload")),
            )
        )

    if not packets:
        raise ValueError(f"no notification rows found in {path}")
    if any(right.elapsed_ms < left.elapsed_ms for left, right in zip(packets, packets[1:])):
        raise ValueError("fixture timestamps must already be chronological")
    return packets


def make_advertising_data(name: str) -> bytes:
    return bytes(
        AdvertisingData(
            [
                (AdvertisingData.FLAGS, b"\x06"),
                (
                    AdvertisingData.COMPLETE_LIST_OF_16_BIT_SERVICE_CLASS_UUIDS,
                    b"\xE0\xFF",
                ),
                (AdvertisingData.COMPLETE_LOCAL_NAME, name.encode("utf-8")),
            ]
        )
    )


async def replay(
    device: Device,
    characteristic: Characteristic[bytes],
    packets: list[Packet],
    speed: float,
    loop_gap_s: float,
    repeat: bool,
) -> None:
    loop_number = 0
    while True:
        loop_number += 1
        sent = 0
        previous_elapsed = 0
        for packet in packets:
            delay_ms = max(0, packet.elapsed_ms - previous_elapsed)
            if delay_ms:
                await asyncio.sleep(delay_ms / 1000.0 / speed)
            await device.notify_subscribers(characteristic, packet.payload)
            sent += 1
            previous_elapsed = packet.elapsed_ms
            if sent % 32 == 0 or sent == len(packets):
                print(
                    f"[replay] pass={loop_number} sent={sent}/{len(packets)} "
                    f"offset={packet.elapsed_ms}ms"
                )
        print(f"[replay] pass {loop_number} complete")
        if not repeat:
            return
        if loop_gap_s > 0:
            await asyncio.sleep(loop_gap_s)


async def run(args: argparse.Namespace) -> None:
    packets = load_packets(args.capture)
    duration_ms = packets[-1].elapsed_ms
    print(
        f"Loaded {len(packets)} notifications, {duration_ms} ms, "
        f"payload lengths {min(map(lambda p: len(p.payload), packets))}-"
        f"{max(map(lambda p: len(p.payload), packets))} bytes"
    )
    print(
        f"Advertising {args.name}, service FFE0, characteristic FFE1 "
        f"via {args.transport}"
    )
    print(f"Static random address: {args.address}")

    if args.summary_only:
        return

    bumble.logging.setup_basic_logging(args.log_level)
    advertisement = make_advertising_data(args.name)
    config = DeviceConfiguration(
        name=args.name,
        address=Address(args.address),
        advertising_data=advertisement,
        scan_response_data=b"",
        advertising_interval_min=100.0,
        advertising_interval_max=150.0,
    )

    replay_task: asyncio.Task[None] | None = None

    def ignore_writes(_connection, value: bytes) -> None:
        # The real adapter is read-only from Volty's telemetry path. Log but do
        # not interpret writes, so a command can never change the test fixture.
        print(f"[write ignored] FFE1 {value.hex(' ')}")

    characteristic = Characteristic(
        CHARACTERISTIC_UUID,
        Characteristic.Properties.READ
        | Characteristic.Properties.WRITE
        | Characteristic.Properties.WRITE_WITHOUT_RESPONSE
        | Characteristic.Properties.NOTIFY,
        Characteristic.READABLE | Characteristic.WRITEABLE,
        CharacteristicValue(read=lambda _connection: b"", write=ignore_writes),
    )
    service = Service(SERVICE_UUID, [characteristic])

    def on_subscription(_bearer, notify_enabled: bool, _indicate_enabled: bool) -> None:
        nonlocal replay_task
        if notify_enabled:
            print("[gatt] FFE1 notifications subscribed")
            if replay_task is None or replay_task.done():
                replay_task = asyncio.create_task(
                    replay(
                        device,
                        characteristic,
                        packets,
                        args.speed,
                        args.loop_gap,
                        not args.once,
                    )
                )
        else:
            print("[gatt] FFE1 notifications unsubscribed")
            if replay_task is not None:
                replay_task.cancel()
                replay_task = None

    characteristic.add_listener(
        Characteristic.EVENT_SUBSCRIPTION, on_subscription
    )

    async with await open_transport(args.transport) as hci_transport:
        device = Device.from_config_with_hci(
            config, hci_transport.source, hci_transport.sink
        )
        device.add_service(service)
        await device.power_on()
        await device.start_advertising(auto_restart=True)
        print("[ble] advertising; start Volty's scan/connect flow in the Android Emulator")
        await hci_transport.source.terminated


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--capture",
        type=Path,
        default=Path(__file__).with_name("sherman-l-notifications.tsv"),
        help="chronological TSV fixture or the original Volty diagnostics text",
    )
    parser.add_argument("--transport", default="android-netsim")
    parser.add_argument("--name", default="LK16313")
    parser.add_argument("--address", default="C0:DE:CA:FE:BA:BE")
    parser.add_argument("--speed", type=float, default=1.0, help="1.0 replays captured timing")
    parser.add_argument("--loop-gap", type=float, default=0.75)
    parser.add_argument("--once", action="store_true", help="send the capture only once")
    parser.add_argument("--summary-only", action="store_true")
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=("DEBUG", "INFO", "WARNING", "ERROR"),
    )
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    if args.speed <= 0:
        parser.error("--speed must be greater than zero")
    if args.loop_gap < 0:
        parser.error("--loop-gap cannot be negative")
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
