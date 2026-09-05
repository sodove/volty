"""Validated configuration for the copyable offline production bundle."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse


class ConfigError(ValueError):
    """Configuration would make the bundle unsafe or non-reproducible."""


_ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}\Z")


@dataclass(frozen=True)
class RegionJob:
    id: str
    source_url: str
    bbox: str | None = None
    source_id: str | None = None


@dataclass(frozen=True)
class ProductionConfig:
    public_root: Path
    staging_root: Path
    source_root: Path
    signing_key: Path
    regions: tuple[RegionJob, ...]
    public_base_url: str = "https://volty.sodove.ru/offline/regions"
    key_id: str = "volty-release-1"
    min_app_version_code: int = 1
    build_script: Path | None = None
    poll_seconds: int = 30
    max_download_bytes: int = 1_073_741_824
    max_runtime_seconds: int = 86_400


def _absolute(value: object, name: str) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{name} must be a non-empty absolute path")
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise ConfigError(f"{name} must be absolute")
    return path.resolve()


def _contains(parent: Path, child: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def load_config(path: Path) -> ProductionConfig:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ConfigError(f"cannot read configuration: {path}") from error
    if not isinstance(raw, dict):
        raise ConfigError("configuration root must be an object")

    public_root = _absolute(raw.get("publicRoot"), "publicRoot")
    staging_root = _absolute(raw.get("stagingRoot"), "stagingRoot")
    source_root = _absolute(raw.get("sourceRoot"), "sourceRoot")
    signing_key = _absolute(raw.get("signingKey"), "signingKey")
    roots = {"publicRoot": public_root, "stagingRoot": staging_root, "sourceRoot": source_root}
    for name, root in roots.items():
        if root == Path(root.anchor) or root.name.lower() in {"srv", "var", "opt", "home", "root"}:
            raise ConfigError(f"{name} must be a dedicated child directory")
    if any(_contains(root, signing_key) for root in roots.values()):
        raise ConfigError("signingKey must be outside all data roots")
    if len({public_root, staging_root, source_root}) != 3:
        raise ConfigError("publicRoot, stagingRoot and sourceRoot must be distinct")

    raw_regions = raw.get("regions")
    if not isinstance(raw_regions, list):
        raise ConfigError("regions must be an array")
    regions: list[RegionJob] = []
    seen: set[str] = set()
    for item in raw_regions:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not _ID.fullmatch(item["id"]):
            raise ConfigError("region id is invalid")
        region_id = item["id"]
        if region_id in seen:
            raise ConfigError(f"duplicate region: {region_id}")
        seen.add(region_id)
        url = item.get("sourceUrl")
        parsed = urlparse(url) if isinstance(url, str) else None
        if parsed is None or parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
            raise ConfigError(f"{region_id}: sourceUrl must be a credential-free HTTPS URL")
        bbox = item.get("bbox")
        if bbox is not None and (not isinstance(bbox, str) or len(bbox.split(",")) != 4):
            raise ConfigError(f"{region_id}: bbox must be west,south,east,north")
        source_id = item.get("sourceId")
        if source_id is not None and (not isinstance(source_id, str) or not _ID.fullmatch(source_id)):
            raise ConfigError(f"{region_id}: sourceId is invalid")
        regions.append(RegionJob(region_id, url, bbox, source_id))

    def positive_int(key: str, default: int) -> int:
        value = raw.get(key, default)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ConfigError(f"{key} must be a positive integer")
        return value

    public_base_url = raw.get("publicBaseUrl", "https://volty.sodove.ru/offline/regions")
    if not isinstance(public_base_url, str) or not public_base_url.startswith("https://"):
        raise ConfigError("publicBaseUrl must be HTTPS")
    key_id = raw.get("keyId", "volty-release-1")
    if not isinstance(key_id, str) or not key_id.strip() or key_id.startswith("REPLACE_WITH"):
        raise ConfigError("keyId must not be blank")
    build_script_value = raw.get("buildScript")
    build_script = _absolute(build_script_value, "buildScript") if build_script_value is not None else None
    return ProductionConfig(
        public_root, staging_root, source_root, signing_key, tuple(regions),
        public_base_url.rstrip("/"), key_id, positive_int("minAppVersionCode", 1), build_script,
        positive_int("pollSeconds", 30), positive_int("maxDownloadBytes", 1_073_741_824),
        positive_int("maxRuntimeSeconds", 86_400),
    )
