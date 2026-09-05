"""Single-writer persistent cache with atomic publication and release lifecycle."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import hashlib
import io
import json
import math
import os
from pathlib import Path
import shutil
import tempfile
import threading
import time
from cryptography.exceptions import InvalidSignature
from package_validation import (
    Config, LOG, atomic_bytes, catalog_tools, package_tools, copy_bounded,
    fetch_https, sync_directory, validate_pmtiles, validate_routing, validate_search,
)

class PackageManager:
    def __init__(self, config, fetch=None):
        self.config = config
        self.root = config.root
        self.root.mkdir(parents=True, exist_ok=True)
        self._process_lock = (self.root / '.service.lock').open('a+b')
        try:
            if os.name == 'nt':
                import msvcrt
                self._process_lock.seek(0)
                if not self._process_lock.read(1):
                    self._process_lock.write(b'0')
                    self._process_lock.flush()
                self._process_lock.seek(0)
                msvcrt.locking(self._process_lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self._process_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self._process_lock.close()
            raise ValueError('cache_already_in_use')
        self._fetch = fetch or (lambda url, target, limit: fetch_https(url, target, limit,
            config.request_timeout_seconds, config.download_timeout_seconds))
        self._lock = threading.RLock()
        self._refresh_lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=config.workers, thread_name_prefix='offline-package')
        self._states, self._ready, self._entries = {}, {}, {}
        identities = self.root / '.release-identities.json'
        # Retain small fingerprints even after pruning bytes: versioned URLs may never be reused.
        self._identities = json.loads(identities.read_bytes()) if identities.exists() else {}
        self._catalog = None
        self._catalog_time = None
        self._closed = False
        self.releases = self.root / 'releases'
        self.staging = self.root / '.staging'
        self.releases.mkdir(exist_ok=True)
        self.staging.mkdir(exist_ok=True)
        for path in self.staging.iterdir():
            if path.is_dir() and not path.is_symlink():
                shutil.rmtree(path)
            else:
                path.unlink()
        cached = self.root / 'catalog.json'
        if cached.is_file():
            try:
                data = cached.read_bytes()
                self._entries, self._catalog_time = self._validate_catalog(data)
                self._catalog = data
                for entry in self._entries.values():
                    manifest = entry['latestRelease']
                    self._identities.setdefault(self._identity_key(manifest), self._fingerprint(manifest))
            except (ValueError, OSError):
                LOG.exception('Discarding invalid cached catalog')
        for marker in self.releases.glob('*/*/.ready.json'):
            try:
                path = marker.parent
                if any(p.is_symlink() for p in (path, path.parent, marker)):
                    continue
                manifest = json.loads((path / 'manifest.json').read_bytes())
                self._validate_manifest(manifest)
                if path.name != manifest['releaseVersion'] or path.parent.name != manifest['regionId']:
                    raise ValueError('release_path_mismatch')
                self._verify_files(path, manifest, structures=False)
                metadata = json.loads(marker.read_bytes())
                if metadata.get('manifestSha256') != hashlib.sha256((path / 'manifest.json').read_bytes()).hexdigest():
                    raise ValueError('ready_marker_mismatch')
                self._ready[(manifest['regionId'], manifest['releaseVersion'])] = manifest
            except (ValueError, OSError, KeyError):
                LOG.exception('Ignoring damaged cached release %s', marker.parent)

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._pool.shutdown(wait=True)
        self._process_lock.close()

    def _signature(self, document, field, payload):
        signature = document.get(field)
        if not isinstance(signature, dict) or signature.get('keyId') != self.config.key_id or signature.get('algorithm') != 'ed25519':
            raise ValueError('untrusted_signature')
        try:
            self.config.public_key.verify(catalog_tools.decode_signature(signature.get('value'), Path(field)), payload(document))
        except InvalidSignature as error:
            raise ValueError('signature_verification_failed') from error

    @staticmethod
    def _identity_key(manifest):
        return manifest['regionId'] + '/' + manifest['releaseVersion']

    @staticmethod
    def _fingerprint(manifest):
        return hashlib.sha256(catalog_tools.canonical_payload(manifest)).hexdigest()

    def _suffixes(self, manifest):
        region, release = manifest['regionId'], manifest['releaseVersion']
        expected = {'routing': 'routing/valhalla-routing.tar.gz', 'search': 'search/places.sqlite.gz',
                    'map': 'map/' + region + '.pmtiles'}
        for name, suffix in expected.items():
            if manifest['components'][name]['url'] != f'{self.config.public_base_url}/{region}/{release}/{suffix}':
                raise ValueError('artifact_url_not_public_endpoint')
        return expected

    def _validate_manifest(self, manifest):
        catalog_tools.validate_manifest_compatibility(manifest, Path('manifest.json'),
            catalog_tools.EXPECTED_ROUTING_DATA_VERSION, None)
        self._signature(manifest, 'manifestSignature', catalog_tools.canonical_payload)
        self._suffixes(manifest)
        components = manifest['components']
        if components['routing'].get('compression') != 'gzip' or components['search'].get('compression') != 'gzip' or components['map'].get('compression') is not None:
            raise ValueError('unsupported_compression')
        if sum(c['downloadBytes'] for c in components.values()) > self.config.max_download_bytes:
            raise ValueError('package_download_limit')
        if sum(c['installedBytes'] for c in components.values()) > self.config.max_expanded_bytes:
            raise ValueError('package_expansion_limit')

    def _validate_catalog(self, data):
        if len(data) > self.config.max_catalog_bytes:
            raise ValueError('catalog_limit')
        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ValueError('duplicate_json_key')
                result[key] = value
            return result
        catalog = json.loads(data, object_pairs_hook=pairs)
        if not isinstance(catalog, dict) or catalog.get('schemaVersion') != 2:
            raise ValueError('catalog_schema')
        self._signature(catalog, 'catalogSignature', catalog_tools.canonical_catalog_payload)
        catalog_tools.validate_timestamp(catalog.get('generatedAt'), 'generatedAt')
        timestamp = datetime.fromisoformat(catalog['generatedAt'].replace('Z', '+00:00'))
        entries = catalog.get('regions')
        if not isinstance(entries, list) or len(entries) > 10000:
            raise ValueError('catalog_regions')
        result = {}
        for entry in entries:
            region, manifest = entry['region'], entry['latestRelease']
            self._validate_manifest(manifest)
            region_id = manifest['regionId']
            if region['regionId'] != region_id or region_id in result or not region.get('displayName'):
                raise ValueError('catalog_region_identity')
            bounds = region['bounds']
            box = catalog_tools.finite_bbox([bounds[k] for k in ('west', 'south', 'east', 'north')], 'bounds')
            if not catalog_tools.coverage_covers(manifest['coverage']['bbox'], box):
                raise ValueError('catalog_coverage')
            result[region_id] = entry
        return result, timestamp

    def refresh(self):
        with self._refresh_lock:
            buffer = io.BytesIO()
            self._fetch(self.config.catalog_url, buffer, self.config.max_catalog_bytes)
            data = buffer.getvalue()
            try:
                entries, timestamp = self._validate_catalog(data)
            except (KeyError, TypeError, UnicodeError) as error:
                raise ValueError('invalid_catalog') from error
            with self._lock:
                if self._catalog_time and timestamp < self._catalog_time:
                    raise ValueError('catalog_rollback')
                identities = dict(self._identities)
                for region_id, entry in entries.items():
                    manifest = entry['latestRelease']
                    identity = self._identity_key(manifest)
                    fingerprint = self._fingerprint(manifest)
                    if identity in identities and identities[identity] != fingerprint:
                        raise ValueError('immutable_release_conflict')
                    identities[identity] = fingerprint
                    key = (region_id, manifest['releaseVersion'])
                    previous = self._ready.get(key)
                    current = self._entries.get(region_id, {}).get('latestRelease')
                    if current and current['releaseVersion'] == key[1]:
                        previous = current
                    if previous and catalog_tools.canonical_payload(previous) != catalog_tools.canonical_payload(manifest):
                        raise ValueError('immutable_release_conflict')
                for region_id, old in self._entries.items():
                    old_version = old['latestRelease']['releaseVersion']
                    if entries.get(region_id, {}).get('latestRelease', {}).get('releaseVersion') != old_version:
                        marker = self.releases / region_id / old_version / '.ready.json'
                        if marker.exists():
                            os.utime(marker, None)  # grace begins when no longer advertised
                atomic_bytes(self.root / '.release-identities.json', json.dumps(identities).encode())
                self._identities = identities
                atomic_bytes(self.root / 'catalog.json', data)
                self._entries, self._catalog, self._catalog_time = entries, data, timestamp
            return {'status': 'ready', 'regions': len(entries)}

    def catalog_bytes(self):
        with self._lock:
            if self._catalog is None:
                raise ValueError('catalog_unavailable')
            return self._catalog

    def resolve(self, latitude, longitude):
        if not math.isfinite(latitude) or not math.isfinite(longitude) or not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
            raise ValueError('invalid_coordinates')
        with self._lock:
            self.catalog_bytes()
            candidates = []
            for region_id, entry in self._entries.items():
                box = entry['region']['bounds']
                if box['south'] <= latitude <= box['north'] and box['west'] <= longitude <= box['east']:
                    candidates.append(((box['north']-box['south'])*(box['east']-box['west']), region_id))
            if not candidates:
                return {'status': 'unsupported', 'regionId': None, 'releaseVersion': None}
            region_id = min(candidates)[1]
            release = self._entries[region_id]['latestRelease']['releaseVersion']
            return {'status': 'ready' if (region_id, release) in self._ready else 'available',
                    'regionId': region_id, 'releaseVersion': release}

    def status(self, region_id, release_version=None):
        with self._lock:
            entry = self._entries.get(region_id)
            release = release_version or (entry['latestRelease']['releaseVersion'] if entry else None)
            state = {'status': 'unavailable', 'regionId': region_id, 'releaseVersion': release}
            key = (region_id, release)
            if key in self._ready:
                state['status'] = 'ready'
            elif key in self._states:
                state.update(self._states[key])
            elif entry and entry['latestRelease']['releaseVersion'] == release:
                state.update(errorCode='not_acquired')
            else:
                state.update(errorCode='release_unavailable')
            return state

    def ingest(self, region_id, release_version):
        if not self.config.ingest_root:
            raise ValueError('ingest_not_configured')
        return self.ensure(region_id, release_version, local=True)

    def ensure(self, region_id, release_version=None, local=False):
        with self._lock:
            state = self.status(region_id, release_version)
            if state['status'] in ('ready', 'queued', 'downloading') or state.get('errorCode') == 'release_unavailable':
                return state
            entry = self._entries.get(region_id)
            if not entry or (release_version and entry['latestRelease']['releaseVersion'] != release_version):
                return {'status': 'unavailable', 'regionId': region_id, 'releaseVersion': release_version,
                        'errorCode': 'release_unavailable'}
            pending = [k for k, v in self._states.items() if v['status'] in ('queued', 'downloading')]
            if len(pending) >= self.config.max_pending:
                return {**state, 'status': 'failed', 'errorCode': 'queue_full', 'retryAfterSeconds': 30}
            manifest = self._entries[region_id]['latestRelease']
            # Routing validation temporarily holds both the raw tar and its extracted files.
            needed = (sum(c['downloadBytes'] + c['installedBytes'] for c in manifest['components'].values())
                      + manifest['components']['routing']['installedBytes'] + 16*1024**2)
            occupied = sum(p.stat().st_size for p in self.releases.rglob('*') if p.is_file())
            reserved = sum(self._states[k].get('_reserved', 0) for k in pending)
            if occupied + reserved + needed > self.config.max_cache_bytes or shutil.disk_usage(self.root).free - reserved - needed < self.config.min_free_bytes:
                return {**state, 'status': 'failed', 'errorCode': 'storage_limit', 'retryAfterSeconds': 60}
            key = (region_id, manifest['releaseVersion'])
            self._states[key] = {'status': 'queued', 'retryAfterSeconds': 2, '_reserved': needed}
            self._pool.submit(self._acquire, manifest, local)
            return {k: v for k, v in self.status(*key).items() if not k.startswith('_')}

    def _verify_files(self, directory, manifest, structures=True):
        for name, suffix in self._suffixes(manifest).items():
            path = directory / suffix
            component = manifest['components'][name]
            if path.is_symlink() or path.parent.is_symlink() or not path.is_file() or path.stat().st_size != component['downloadBytes']:
                raise ValueError('artifact_size')
            if package_tools.digest(path).lower() != component['sha256'].lower():
                raise ValueError('artifact_checksum')
        if structures:
            with tempfile.TemporaryDirectory(dir=self.staging) as temporary:
                work = Path(temporary)
                for name, suffix in self._suffixes(manifest).items():
                    path, limit = directory / suffix, manifest['components'][name]['installedBytes']
                    if name == 'routing':
                        actual = validate_routing(path, work / 'routing', limit)
                    elif name == 'search':
                        actual = validate_search(path, work / 'places.sqlite', limit, manifest['regionId'])
                    else:
                        actual = validate_pmtiles(path)
                    if actual != limit:
                        raise ValueError('installed_size_mismatch')

    def _acquire(self, manifest, local=False):
        key = (manifest['regionId'], manifest['releaseVersion'])
        temporary = Path(tempfile.mkdtemp(dir=self.staging))
        try:
            with self._lock:
                self._states[key]['status'] = 'downloading'
            for name, suffix in self._suffixes(manifest).items():
                path = temporary / suffix
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open('xb') as target:
                    if local:
                        source = self.config.ingest_root / key[0] / key[1] / suffix
                        current = source
                        while current != self.config.ingest_root:
                            if current.is_symlink():
                                raise ValueError('ingest_link_forbidden')
                            current = current.parent
                        if not source.resolve().is_relative_to(self.config.ingest_root):
                            raise ValueError('ingest_path_forbidden')
                        with source.open('rb') as stream:
                            copy_bounded(stream, target, manifest['components'][name]['downloadBytes'])
                    else:
                        self._fetch(f'{self.config.artifact_base_url}/{key[0]}/{key[1]}/{suffix}',
                                    target, manifest['components'][name]['downloadBytes'])
                    target.flush()
                    os.fsync(target.fileno())
            self._verify_files(temporary, manifest)
            data = json.dumps(manifest, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()
            atomic_bytes(temporary / 'manifest.json', data)
            atomic_bytes(temporary / '.ready.json', json.dumps({'manifestSha256': hashlib.sha256(data).hexdigest()}).encode())
            with self._lock:
                destination = self.releases / key[0] / key[1]
                destination.parent.mkdir(parents=True, exist_ok=True)
                if destination.exists():
                    if key not in self._ready:
                        raise ValueError('unverified_release_exists')
                else:
                    os.rename(temporary, destination)
                    sync_directory(destination.parent)
                self._ready[key] = manifest
                self._states.pop(key, None)
        except Exception as error:
            LOG.exception('Acquisition failed for %s/%s', *key)
            code = str(error) if isinstance(error, ValueError) else 'upstream_unavailable' if isinstance(error, OSError) else 'invalid_package'
            if code not in {'artifact_checksum', 'artifact_size', 'installed_size_mismatch', 'expansion_limit',
                            'unsafe_routing_archive', 'missing_routing_file', 'routing_config_limit',
                            'missing_routing_reference', 'search_requires_fts4', 'search_schema',
                            'search_metadata', 'search_empty', 'search_corrupt', 'pmtiles_header',
                            'pmtiles_bounds', 'ingest_link_forbidden', 'ingest_path_forbidden',
                            'download_limit', 'incomplete_download', 'redirect_forbidden',
                            'upstream_unavailable', 'unverified_release_exists'}:
                code = 'invalid_package'
            with self._lock:
                self._states[key] = {'status': 'failed', 'errorCode': code, 'retryAfterSeconds': 30}
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)

    def open_artifact(self, region_id, release, suffix):
        with self._lock:
            manifest = self._ready.get((region_id, release))
            if not manifest or suffix not in ('manifest.json', *self._suffixes(manifest).values()):
                raise FileNotFoundError()
            path = self.releases / region_id / release / suffix
            if path.is_symlink() or path.parent.is_symlink():
                raise FileNotFoundError()
            # Open while holding publication/pruning lock; POSIX reads survive unlink.
            return path.open('rb')

    def artifact_etag(self, region_id, release, suffix):
        with self._lock:
            manifest = self._ready[(region_id, release)]
            if suffix == 'manifest.json':
                data = json.dumps(manifest, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()
                digest = hashlib.sha256(data).hexdigest()
            else:
                name = next(name for name, path in self._suffixes(manifest).items() if path == suffix)
                digest = manifest['components'][name]['sha256'].lower()
            return '"' + digest + '"'

    def open_response(self, region_id, release, suffix):
        with self._lock:
            stream = self.open_artifact(region_id, release, suffix)
            try:
                return stream, self.artifact_etag(region_id, release, suffix)
            except Exception:
                stream.close()
                raise

    def prune(self):
        removed = 0
        with self._lock:
            active = {(region, entry['latestRelease']['releaseVersion']) for region, entry in self._entries.items()}
            for key in list(self._ready):
                path = self.releases / key[0] / key[1]
                if key in active or time.time() - (path / '.ready.json').stat().st_mtime < self.config.prune_grace_seconds:
                    continue
                try:
                    shutil.rmtree(path)
                except PermissionError:  # Windows: keep an in-flight open response alive.
                    continue
                del self._ready[key]
                removed += 1
        return {'status': 'ready', 'removedReleases': removed}
