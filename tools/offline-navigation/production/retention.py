"""Pure retention policy; filesystem deletion is deliberately separate."""

from __future__ import annotations

from collections import defaultdict


GRACE_SECONDS = 7 * 86400


def retention_candidates(now: float, registry: list[dict], *, grace_seconds: int = GRACE_SECONDS) -> list[dict]:
    grouped: dict[str, list[dict]] = defaultdict(list)
    for item in registry:
        grouped[str(item.get("regionId", item.get("id", "")))].append(item)
    keep: set[int] = set()
    for items in grouped.values():
        good = [item for item in items if item.get("good") and not item.get("retiredAt")]
        good += [item for item in items if item.get("good") and item.get("retiredAt") and item.get("retiredAt") > now - grace_seconds]
        good.sort(key=lambda item: item.get("createdAt", item.get("id", "")), reverse=True)
        keep.update(id(item) for item in good[:2])
    result = []
    for item in registry:
        retired_at = item.get("retiredAt")
        if item.get("pinned") or id(item) in keep or not item.get("good"):
            continue
        if item.get("leased") or item.get("inFlight") or not isinstance(retired_at, (int, float)):
            continue
        if retired_at <= now - grace_seconds:
            result.append(item)
    return result
