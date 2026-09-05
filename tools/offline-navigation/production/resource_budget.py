"""Admission checks that keep existing services ahead of a heavy build."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Admission:
    allowed: bool
    reason: str


def admit_build(available: dict[str, int], reserve: dict[str, int], estimate: dict[str, int]) -> Admission:
    for resource, required in estimate.items():
        if available.get(resource, 0) < reserve.get(resource, 0) + required:
            return Admission(False, f"{resource}_reserve")
    return Admission(True, "admitted")
