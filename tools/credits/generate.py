"""Build the offline third-party credits catalog from checked-in metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit


DEFAULT_ROOT = Path(__file__).resolve().parents[2]


class CatalogError(ValueError):
    """Raised when catalog inputs cannot produce a complete, safe catalog."""


def _normalized_text(raw: bytes, description: str) -> str:
    try:
        decoded = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise CatalogError(f"{description} is not valid UTF-8: {error}") from error
    return decoded.replace("\r\n", "\n").replace("\r", "\n")


def _safe_repo_file(root: Path, raw_path: str | os.PathLike[str], label: str) -> tuple[Path, str]:
    raw = os.fspath(raw_path)
    if not isinstance(raw, str) or not raw.strip():
        raise CatalogError(f"{label} path must be a non-empty string")

    candidate = Path(raw)
    if not candidate.is_absolute():
        if ".." in PurePosixPath(raw.replace("\\", "/")).parts:
            raise CatalogError(f"{label} path traversal is not allowed: {raw}")
        candidate = root / candidate

    try:
        resolved = candidate.resolve(strict=True)
        relative = resolved.relative_to(root)
    except (OSError, ValueError) as error:
        raise CatalogError(f"{label} must be an existing file inside the repository: {raw}") from error

    if not resolved.is_file():
        raise CatalogError(f"{label} is not a file: {raw}")
    return resolved, relative.as_posix()


def _safe_output_file(root: Path, raw_path: str | os.PathLike[str]) -> Path:
    raw = os.fspath(raw_path)
    if not isinstance(raw, str) or not raw.strip():
        raise CatalogError("output path must be a non-empty string")
    candidate = Path(raw)
    if not candidate.is_absolute():
        if ".." in PurePosixPath(raw.replace("\\", "/")).parts:
            raise CatalogError(f"output path traversal is not allowed: {raw}")
        candidate = root / candidate
    try:
        resolved = candidate.resolve(strict=False)
        resolved.relative_to(root)
    except (OSError, ValueError) as error:
        raise CatalogError(f"output must be inside the repository: {raw}") from error
    if resolved.exists() and not resolved.is_file():
        raise CatalogError(f"output is not a file: {raw}")
    return resolved


def _read_json(root: Path, raw_path: str | os.PathLike[str], label: str) -> tuple[object, Path, str]:
    path, relative = _safe_repo_file(root, raw_path, label)
    try:
        text = _normalized_text(path.read_bytes(), label)
        return json.loads(text), path, relative
    except json.JSONDecodeError as error:
        raise CatalogError(f"{label} is not valid JSON: {error}") from error


def _record_text_input(
    root: Path,
    raw_path: str | os.PathLike[str],
    label: str,
    input_hashes: dict[str, str],
) -> str:
    path, relative = _safe_repo_file(root, raw_path, label)
    normalized = _normalized_text(path.read_bytes(), label)
    input_hashes[relative] = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return normalized


def _record_binary_input(
    root: Path,
    raw_path: str | os.PathLike[str],
    label: str,
    binary_hashes: dict[str, str],
) -> None:
    path, relative = _safe_repo_file(root, raw_path, label)
    binary_hashes[relative] = hashlib.sha256(path.read_bytes()).hexdigest()


def _require_mapping(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        raise CatalogError(f"{label} must be an object")
    return value


def _string(value: object, label: str, *, required: bool = True) -> str | None:
    if value is None and not required:
        return None
    if not isinstance(value, str) or not value.strip():
        if required:
            raise CatalogError(f"{label} must be a non-empty string")
        return None
    return value.strip()


def _string_list(value: object, label: str, *, required: bool = True) -> list[str]:
    if value is None and not required:
        return []
    if not isinstance(value, list):
        raise CatalogError(f"{label} must be an array of strings")
    result = []
    for item in value:
        item_text = _string(item, label)
        result.append(item_text)
    if required and not result:
        raise CatalogError(f"{label} must contain at least one value")
    if len(result) != len(set(result)):
        raise CatalogError(f"{label} contains duplicate values")
    return result


def _display_name(value: object, fallback: str, label: str) -> str:
    name = _string(value, label, required=False)
    return name if name is not None else fallback


def _https_url(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    candidate = value.strip()
    try:
        parsed = urlsplit(candidate)
    except ValueError:
        return None
    if parsed.scheme.lower() != "https" or not parsed.netloc:
        return None
    return candidate


_PLACEHOLDER_AUTHORS = {
    "-",
    "?",
    "n/a",
    "na",
    "none",
    "not available",
    "not specified",
    "null",
    "unknown",
}


def _metadata_authors(library: dict) -> list[str]:
    names: set[str] = set()
    developers = library.get("developers", [])
    if isinstance(developers, list):
        for developer in developers:
            if isinstance(developer, dict):
                name = _string(developer.get("name"), "developer name", required=False)
                if name and name.casefold() not in _PLACEHOLDER_AUTHORS:
                    names.add(name)
    organization = library.get("organization")
    if isinstance(organization, dict):
        name = _string(organization.get("name"), "organization name", required=False)
        if name and name.casefold() not in _PLACEHOLDER_AUTHORS:
            names.add(name)
    return sorted(names, key=lambda item: (item.casefold(), item))


def _read_notice(
    root: Path,
    notice_path: object,
    label: str,
    input_hashes: dict[str, str],
) -> str | None:
    if notice_path is None:
        return None
    path_text = _string(notice_path, f"{label} noticePath")
    notice = _record_text_input(root, path_text, f"{label} notice", input_hashes)
    if not notice.strip():
        raise CatalogError(f"{label} notice file is empty: {path_text}")
    return notice


def _project_license_ids(value: object, project_id: str) -> list[str]:
    return sorted(
        _string_list(value, f"{project_id} licenseIds"),
        key=lambda item: (item.casefold(), item),
    )


def _validate_license_references(project: dict, licenses: dict[str, dict]) -> None:
    missing = [license_id for license_id in project["licenseIds"] if license_id not in licenses]
    if missing:
        raise CatalogError(
            f"project {project['id']} references unknown license ID(s): {', '.join(missing)}"
        )


def build_catalog(
    root: str | os.PathLike[str],
    aboutlibraries_path: str | os.PathLike[str] = "composeApp/build/reports/credits/aboutlibraries.json",
    graph_path: str | os.PathLike[str] = "composeApp/build/reports/credits/dependencies.json",
    registry_path: str | os.PathLike[str] = "third-party/credits/registry.json",
) -> dict:
    """Build the project and license catalog, rejecting incomplete runtime coverage."""
    try:
        repository = Path(root).resolve(strict=True)
    except OSError as error:
        raise CatalogError(f"repository root does not exist: {root}") from error
    if not repository.is_dir():
        raise CatalogError(f"repository root is not a directory: {repository}")

    about_data, _, _ = _read_json(repository, aboutlibraries_path, "AboutLibraries report")
    graph_data, _, _ = _read_json(repository, graph_path, "runtime dependency graph")
    registry_data, _, registry_relative = _read_json(repository, registry_path, "credits registry")
    about = _require_mapping(about_data, "AboutLibraries report")
    registry = _require_mapping(registry_data, "credits registry")
    if not isinstance(graph_data, list) or any(not isinstance(item, str) or not item.strip() for item in graph_data):
        raise CatalogError("runtime dependency graph must be an array of coordinate strings")
    if len(graph_data) != len(set(graph_data)):
        raise CatalogError("runtime dependency graph contains duplicate coordinates")
    if not graph_data:
        raise CatalogError("runtime dependency graph is empty")

    raw_libraries = about.get("libraries")
    raw_licenses = about.get("licenses")
    if not isinstance(raw_libraries, list):
        raise CatalogError("AboutLibraries report libraries must be an array")
    if not isinstance(raw_licenses, dict):
        raise CatalogError("AboutLibraries report licenses must be an object")

    registry_projects = registry.get("projects", [])
    if not isinstance(registry_projects, list):
        raise CatalogError("registry projects must be an array")
    registry_licenses = _require_mapping(registry.get("licenses", {}), "registry licenses")
    license_overrides = _require_mapping(registry.get("licenseOverrides", {}), "registry licenseOverrides")
    library_overrides = _require_mapping(registry.get("libraryOverrides", {}), "registry libraryOverrides")
    registry_inputs = registry.get("inputs", [])
    binary_inputs = registry.get("binaryInputs", [])
    if not isinstance(registry_inputs, list):
        raise CatalogError("registry inputs must be an array of repo-relative paths")
    if not isinstance(binary_inputs, list):
        raise CatalogError("registry binaryInputs must be an array of repo-relative paths")

    input_hashes: dict[str, str] = {}
    binary_hashes: dict[str, str] = {}
    # The raw AboutLibraries and graph reports are build outputs. The checked-in registry,
    # copied notices/licenses, and explicitly tracked upstream sources form the audit input set.
    if registry_relative:
        registry_path_resolved, _ = _safe_repo_file(repository, registry_path, "credits registry")
        registry_text = _normalized_text(registry_path_resolved.read_bytes(), "credits registry")
        input_hashes[registry_relative] = hashlib.sha256(registry_text.encode("utf-8")).hexdigest()

    for source_path in registry_inputs:
        source_name = _string(source_path, "registry input path")
        _record_text_input(repository, source_name, "registry source input", input_hashes)
    for binary_path in binary_inputs:
        binary_name = _string(binary_path, "registry binary input path")
        _record_binary_input(repository, binary_name, "registry binary input", binary_hashes)

    about_library_by_id: dict[str, dict] = {}
    for raw_library in raw_libraries:
        library = _require_mapping(raw_library, "AboutLibraries library entry")
        unique_id = _string(library.get("uniqueId"), "AboutLibraries library uniqueId")
        if unique_id in about_library_by_id:
            raise CatalogError(f"duplicate AboutLibraries library ID: {unique_id}")
        about_library_by_id[unique_id] = library

    unknown_library_overrides = sorted(set(library_overrides) - set(about_library_by_id))
    if unknown_library_overrides:
        raise CatalogError(
            "libraryOverrides reference unknown AboutLibraries ID(s): "
            + ", ".join(unknown_library_overrides)
        )
    unknown_license_overrides = sorted(set(license_overrides) - set(raw_licenses))
    if unknown_license_overrides:
        raise CatalogError(
            "licenseOverrides reference unknown AboutLibraries license ID(s): "
            + ", ".join(unknown_license_overrides)
        )

    licenses: dict[str, dict] = {}
    for raw_id, raw_license in raw_licenses.items():
        license_id = _string(raw_id, "AboutLibraries license ID")
        metadata = _require_mapping(raw_license, f"AboutLibraries license {license_id}")
        override = license_overrides.get(license_id)
        if override is not None:
            override = _require_mapping(override, f"licenseOverrides.{license_id}")
            override_path = _string(override.get("path"), f"licenseOverrides.{license_id}.path")
            content = _record_text_input(
                repository,
                override_path,
                f"license {license_id}",
                input_hashes,
            )
            name = _display_name(
                override.get("name"),
                _display_name(metadata.get("name"), license_id, f"license {license_id} name"),
                f"licenseOverrides.{license_id}.name",
            )
        else:
            content_value = metadata.get("content")
            if not isinstance(content_value, str) or not content_value.strip():
                raise CatalogError(
                    f"license {license_id} has no text content and no local licenseOverrides entry"
                )
            content = content_value.replace("\r\n", "\n").replace("\r", "\n")
            name = _display_name(metadata.get("name"), license_id, f"license {license_id} name")
        if not content.strip():
            raise CatalogError(f"license {license_id} has empty text content")
        licenses[license_id] = {"id": license_id, "name": name, "content": content}

    for raw_id, raw_license in registry_licenses.items():
        license_id = _string(raw_id, "registry license ID")
        if license_id in licenses:
            raise CatalogError(f"manual license ID duplicates an AboutLibraries license: {license_id}")
        manual = _require_mapping(raw_license, f"registry license {license_id}")
        name = _string(manual.get("name"), f"registry license {license_id}.name")
        path = _string(manual.get("path"), f"registry license {license_id}.path")
        content = _record_text_input(repository, path, f"license {license_id}", input_hashes)
        if not content.strip():
            raise CatalogError(f"license {license_id} has empty text content")
        licenses[license_id] = {"id": license_id, "name": name, "content": content}

    libraries = {
        library_id: library
        for library_id, library in about_library_by_id.items()
    }
    manual_projects_by_id: dict[str, dict] = {}
    manual_projects_by_artifact: dict[str, dict] = {}
    for raw_project in registry_projects:
        manual = _require_mapping(raw_project, "registry project entry")
        project_id = _string(manual.get("id"), "registry project id")
        if project_id in manual_projects_by_id:
            raise CatalogError(f"duplicate manual project ID: {project_id}")
        if project_id in about_library_by_id:
            raise CatalogError(f"manual project ID conflicts with AboutLibraries ID: {project_id}")
        license_ids = _project_license_ids(manual.get("licenseIds"), project_id)
        project = {
            "id": project_id,
            "name": _string(manual.get("name"), f"registry project {project_id}.name"),
            "licenseIds": license_ids,
        }
        version = _string(manual.get("version"), f"registry project {project_id}.version", required=False)
        if version is not None:
            project["version"] = version
        authors = manual.get("authors", [])
        if not isinstance(authors, list):
            raise CatalogError(f"registry project {project_id}.authors must be an array")
        author_names = {
            name
            for value in authors
            if (name := _string(value, f"registry project {project_id}.author", required=False))
            and name.casefold() not in _PLACEHOLDER_AUTHORS
        }
        project["authors"] = sorted(author_names, key=lambda item: (item.casefold(), item))
        website = _https_url(manual.get("website"))
        if website is not None:
            project["website"] = website
        notice = _read_notice(repository, manual.get("noticePath"), f"project {project_id}", input_hashes)
        if notice is not None:
            project["notice"] = notice
        artifact = _string(manual.get("artifact"), f"registry project {project_id}.artifact", required=False)
        if artifact is not None:
            if artifact in manual_projects_by_artifact:
                other = manual_projects_by_artifact[artifact]["id"]
                raise CatalogError(f"duplicate manual artifact {artifact}: {other} and {project_id}")
            manual_projects_by_artifact[artifact] = project
        manual_projects_by_id[project_id] = project
        _validate_license_references(project, licenses)

    projects_by_id: dict[str, dict] = {}
    covered_coordinates: set[str] = set()
    versions_by_module: dict[str, str] = {}
    for coordinate in graph_data:
        coordinate = coordinate.strip()
        parts = coordinate.split(":")
        if coordinate.startswith("file:"):
            manual = manual_projects_by_artifact.get(coordinate)
            if manual is None:
                raise CatalogError(f"runtime coordinate has no manual project metadata: {coordinate}")
            projects_by_id[manual["id"]] = manual
            covered_coordinates.add(coordinate)
            continue
        if len(parts) != 3 or any(not part for part in parts):
            raise CatalogError(f"runtime Maven coordinate must be group:artifact:version: {coordinate}")
        module_id = ":".join(parts[:2])
        version = parts[2]
        previous_version = versions_by_module.get(module_id)
        if previous_version is not None and previous_version != version:
            raise CatalogError(
                f"runtime graph has multiple versions for {module_id}: {previous_version}, {version}"
            )
        versions_by_module[module_id] = version
        library = libraries.get(module_id)
        if library is None:
            manual = manual_projects_by_artifact.get(coordinate)
            if manual is None:
                raise CatalogError(f"runtime coordinate is missing AboutLibraries metadata: {coordinate}")
            projects_by_id[manual["id"]] = manual
            covered_coordinates.add(coordinate)
            continue

        override = library_overrides.get(module_id, {})
        if library.get("artifactVersion") != version:
            raise CatalogError(f"metadata version does not match runtime version for {module_id}; export again")
        override = _require_mapping(override, f"libraryOverrides.{module_id}")
        raw_license_ids = override.get("licenseIds", library.get("licenses"))
        license_ids = _project_license_ids(raw_license_ids, module_id)
        project = {
            "id": module_id,
            "name": _display_name(library.get("name"), module_id, f"library {module_id}.name"),
            "version": version,
            "authors": _metadata_authors(library),
            "licenseIds": license_ids,
        }
        website = _https_url(library.get("website"))
        if website is not None:
            project["website"] = website
        notice = _read_notice(
            repository,
            override.get("noticePath"),
            f"library {module_id}",
            input_hashes,
        )
        if notice is not None:
            project["notice"] = notice
        _validate_license_references(project, licenses)
        projects_by_id[module_id] = project
        covered_coordinates.add(coordinate)

    artifact_project_ids = {project["id"] for project in manual_projects_by_artifact.values()}
    for project in manual_projects_by_id.values():
        if project["id"] not in artifact_project_ids:
            projects_by_id[project["id"]] = project

    uncovered = sorted(set(graph_data) - covered_coordinates)
    if uncovered:
        raise CatalogError("runtime coordinates are not covered: " + ", ".join(uncovered))

    return {
        "projects": sorted(
            projects_by_id.values(),
            key=lambda project: (project["name"].casefold(), project["id"]),
        ),
        "licenses": {key: licenses[key] for key in sorted(licenses)},
        "sources": {
            "graph": sorted(graph_data),
            "inputs": {key: input_hashes[key] for key in sorted(input_hashes)},
            "binaries": {key: binary_hashes[key] for key in sorted(binary_hashes)},
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate the offline third-party credits catalog")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="repository root")
    parser.add_argument("--aboutlibraries", help="AboutLibraries JSON report, relative to --root")
    parser.add_argument("--graph", help="runtime dependencies JSON, relative to --root")
    parser.add_argument("--registry", help="manual credits registry, relative to --root")
    parser.add_argument("--output", help="catalog output path, relative to --root")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    aboutlibraries = args.aboutlibraries or "composeApp/build/reports/credits/aboutlibraries.json"
    graph = args.graph or "composeApp/build/reports/credits/dependencies.json"
    registry = args.registry or "third-party/credits/registry.json"
    output = args.output or "composeApp/src/commonMain/composeResources/files/credits/catalog.json"
    try:
        catalog = build_catalog(root, aboutlibraries, graph, registry)
        output_path = _safe_output_file(root, output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="",
        )
    except (CatalogError, OSError) as error:
        parser.error(str(error))
    print(f"Wrote {len(catalog['projects'])} projects to {output_path.relative_to(root).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
