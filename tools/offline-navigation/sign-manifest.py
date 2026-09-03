#!/usr/bin/env python3
"""Sign a regional manifest without ever storing the signing key in the repo."""

from __future__ import annotations

import argparse
import base64
import copy
import json
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def canonical_payload(manifest: dict[str, object]) -> bytes:
    """Match OfflineRegionPackageManifestCodec.signingPayload byte-for-byte."""

    unsigned = copy.deepcopy(manifest)
    unsigned.pop("manifestSignature", None)
    # kotlinx.serialization omits nullable properties whose constructor
    # default is null when encodeDefaults is false (the current Android
    # codec configuration). Normalize those three fields before dumping.
    coverage = unsigned.get("coverage")
    if isinstance(coverage, dict) and coverage.get("polygonUrl") is None:
        coverage.pop("polygonUrl", None)
    components = unsigned.get("components")
    if isinstance(components, dict):
        for component_name in ("search", "map"):
            component = components.get(component_name)
            if isinstance(component, dict) and component.get("compression") is None:
                component.pop("compression", None)
    return json.dumps(
        unsigned,
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def load_private_key(path: Path) -> Ed25519PrivateKey:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("private key must be an unencrypted Ed25519 PEM key")
    return key


def atomic_write_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as temporary:
        temporary.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        temporary_path = Path(temporary.name)
    temporary_path.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path, help="manifest.unsigned.json")
    parser.add_argument("output", type=Path, help="signed manifest.json")
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--key-id", required=True)
    parser.add_argument(
        "--public-key-output",
        type=Path,
        help="optional file for the raw 32-byte public key in Base64",
    )
    args = parser.parse_args()

    if args.input.resolve() == args.output.resolve():
        raise ValueError("input and output must be different files")
    if not args.key_id.strip():
        raise ValueError("key id must not be blank")

    manifest = json.loads(args.input.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("manifest root must be an object")
    private_key = load_private_key(args.private_key)
    payload = canonical_payload(manifest)
    signature = private_key.sign(payload)

    signed = copy.deepcopy(manifest)
    signed["manifestSignature"] = {
        "keyId": args.key_id,
        "algorithm": "ed25519",
        "value": base64.b64encode(signature).decode("ascii"),
    }
    # Verify before publishing, so a malformed output cannot replace a good one.
    private_key.public_key().verify(signature, canonical_payload(signed))
    atomic_write_json(args.output, signed)

    if args.public_key_output:
        raw_public_key = private_key.public_key().public_bytes(
            serialization.Encoding.Raw,
            serialization.PublicFormat.Raw,
        )
        args.public_key_output.write_text(
            base64.b64encode(raw_public_key).decode("ascii") + "\n",
            encoding="ascii",
        )

    print(f"signed {args.input} -> {args.output} with key id {args.key_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
