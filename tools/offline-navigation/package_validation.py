"""Trust, transport and bounded structural validation for pipeline packages."""
import base64
import copy
import gzip
import hashlib
import importlib.util
import json
import logging
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import tarfile
import tempfile
import time
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

def load_tool(filename):
    spec = importlib.util.spec_from_file_location(filename.replace('-', '_'), Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


catalog_tools = load_tool('build-catalog.py')
package_tools = load_tool('verify-package.py')
LOG = logging.getLogger('offline-packages')


def https_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or '%' in value or '\\' in value
            or any(part in ('.', '..') for part in parsed.path.split('/'))):
        raise ValueError('invalid_https_url')
    return value.rstrip('/')


class Config:
    def __init__(self, *, root, catalog_url, artifact_base_url, public_base_url, public_key, key_id,
                 max_download_bytes=8*1024**3, max_expanded_bytes=24*1024**3,
                 max_cache_bytes=64*1024**3, min_free_bytes=1024**3, workers=2, max_pending=8,
                 max_catalog_bytes=4*1024**2, prune_grace_seconds=7*86400,
                 refresh_seconds=900, request_timeout_seconds=30, download_timeout_seconds=1800,
                 ingest_root=None):
        self.root = Path(root).resolve()
        self.ingest_root = Path(ingest_root).resolve() if ingest_root else None
        self.catalog_url = https_url(catalog_url)
        self.artifact_base_url = https_url(artifact_base_url)
        self.public_base_url = https_url(public_base_url)
        self.public_key = Ed25519PublicKey.from_public_bytes(base64.b64decode(public_key, validate=True))
        if not key_id or key_id in ('UNSIGNED', 'UNSIGNED_DEV'):
            raise ValueError('production_key_required')
        self.key_id = key_id
        for name, value in locals().copy().items():
            if name in ('max_download_bytes', 'max_expanded_bytes', 'max_cache_bytes', 'workers',
                        'max_pending', 'max_catalog_bytes', 'prune_grace_seconds', 'refresh_seconds',
                        'request_timeout_seconds', 'download_timeout_seconds', 'min_free_bytes'):
                if isinstance(value, bool) or not isinstance(value, int) or value < (0 if name == 'min_free_bytes' else 1):
                    raise ValueError('invalid_limit_' + name)
                setattr(self, name, value)

    @classmethod
    def from_env(cls):
        names = {'root': '/data/offline', 'catalog_url': None, 'artifact_base_url': None,
                 'public_base_url': None, 'public_key': None, 'key_id': None}
        values = {}
        for name, default in names.items():
            env = 'VOLTY_OFFLINE_' + ('UPSTREAM_CATALOG_URL' if name == 'catalog_url' else name.upper())
            value = os.environ.get(env, default)
            if value is None:
                raise ValueError('missing ' + env)
            values[name] = value
        for name in ('max_download_bytes', 'max_expanded_bytes', 'max_cache_bytes', 'min_free_bytes',
                     'workers', 'max_pending', 'max_catalog_bytes', 'prune_grace_seconds', 'refresh_seconds',
                     'request_timeout_seconds', 'download_timeout_seconds'):
            value = os.environ.get('VOLTY_OFFLINE_' + name.upper())
            if value is not None:
                values[name] = int(value)
        values['ingest_root'] = os.environ.get('VOLTY_OFFLINE_INGEST_ROOT') or None
        return cls(**values)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('redirect_forbidden')


def fetch_https(url, target, limit, timeout=30, total_timeout=1800):
    https_url(url)
    deadline = time.monotonic() + total_timeout
    with build_opener(NoRedirect()).open(Request(url, headers={'Accept-Encoding': 'identity'}), timeout=timeout) as response:
        if response.status != 200 or response.headers.get('Content-Encoding', 'identity') != 'identity':
            raise ValueError('invalid_upstream_response')
        length = response.headers.get('Content-Length')
        if length is not None and (int(length) < 0 or int(length) > limit):
            raise ValueError('download_limit')
        copied = 0
        while True:
            chunk = response.read(min(1024*1024, limit - copied + 1))
            if not chunk:
                break
            copied += len(chunk)
            if copied > limit or time.monotonic() > deadline:
                raise ValueError('download_limit')
            target.write(chunk)
        if length is not None and copied != int(length):
            raise ValueError('incomplete_download')


def copy_bounded(source, target, limit):
    copied = 0
    while True:
        chunk = source.read(min(1024*1024, limit - copied + 1))
        if not chunk:
            return copied
        copied += len(chunk)
        if copied > limit:
            raise ValueError('expansion_limit')
        target.write(chunk)


def validate_routing(path, unpacked, limit):
    """Bound gzip expansion before tar parsing, then extract regular safe members."""
    unpacked.mkdir(parents=True, exist_ok=True)
    raw_tar = unpacked / '.routing.tar'
    with gzip.open(path, 'rb') as source, raw_tar.open('wb') as target:
        copy_bounded(source, target, limit + 16*1024**2)
    total, seen = 0, set()
    try:
        with tarfile.open(raw_tar, 'r:') as archive:
            for index, member in enumerate(archive):
                name = member.name
                parts = PurePosixPath(name).parts
                if (index >= 10000 or not parts or '\\' in name or ':' in name or name.startswith('/')
                        or '..' in parts or name.startswith('.') or name in seen
                        or not (member.isfile() or member.isdir())):
                    raise ValueError('unsafe_routing_archive')
                seen.add(name)
                total += member.size
                if member.size < 0 or total > limit:
                    raise ValueError('expansion_limit')
                target = unpacked.joinpath(*parts)
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.extractfile(member) as source, target.open('xb') as output:
                        copy_bounded(source, output, member.size)
        for name in package_tools.ROUTING_REQUIRED_FILES:
            if not (unpacked / name).is_file():
                raise ValueError('missing_routing_file')
        config_file = unpacked / package_tools.ROUTING_CONFIG_FILE
        if config_file.stat().st_size > 1024*1024:
            raise ValueError('routing_config_limit')
        config = json.loads(config_file.read_bytes())
        references = package_tools._referenced_filenames(config)
        for names in package_tools.ROUTING_CONFIG_REFERENCES.values():
            if not set(names).issubset(references):
                raise ValueError('missing_routing_reference')
        return total
    finally:
        raw_tar.unlink(missing_ok=True)


def validate_search(path, unpacked, limit, region_id):
    with gzip.open(path, 'rb') as source, unpacked.open('wb') as target:
        size = copy_bounded(source, target, limit)
    connection = sqlite3.connect(unpacked.as_uri() + '?mode=ro&immutable=1', uri=True)
    deadline = time.monotonic() + 30
    connection.set_progress_handler(lambda: int(time.monotonic() > deadline), 10000)
    try:
        row = connection.execute("SELECT sql FROM sqlite_master WHERE name='places' AND type='table'").fetchone()
        if not row or not re.search(r'\bUSING\s+fts4\s*\(', row[0], re.I):
            raise ValueError('search_requires_fts4')
        columns = [item[1] for item in connection.execute('PRAGMA table_info(places)')]
        if columns != ['display_name', 'search_text', 'latitude', 'longitude', 'kind', 'osm_id']:
            raise ValueError('search_schema')
        metadata = dict(connection.execute('SELECT key, value FROM metadata'))
        if metadata.get('schema') != '1' or metadata.get('region_id') != region_id:
            raise ValueError('search_metadata')
        if connection.execute('SELECT 1 FROM places LIMIT 1').fetchone() is None:
            raise ValueError('search_empty')
        connection.execute("SELECT display_name FROM places WHERE places MATCH 'екб*' LIMIT 1").fetchone()
        if connection.execute('PRAGMA quick_check(1)').fetchone() != ('ok',):
            raise ValueError('search_corrupt')
    finally:
        connection.close()
    return size


def validate_pmtiles(path):
    with path.open('rb') as source:
        header = source.read(127)
    size = path.stat().st_size
    if len(header) != 127 or header[:8] != b'PMTiles\x03':
        raise ValueError('pmtiles_header')
    for start in (8, 24, 40, 56):
        offset = int.from_bytes(header[start:start+8], 'little')
        length = int.from_bytes(header[start+8:start+16], 'little')
        if offset + length > size or (length and offset < 127):
            raise ValueError('pmtiles_bounds')
    # An earlier implementation treated byte 102 as maxZoom; it is the first
    # longitude byte. Match AndroidOfflinePmtilesTileServer.readHeader (100/101).
    if int.from_bytes(header[16:24], 'little') == 0 or not 0 <= header[100] <= header[101] <= 24:
        raise ValueError('pmtiles_header')
    return size


def atomic_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as target:
        temporary = Path(target.name)
        target.write(data)
        target.flush()
        os.fsync(target.fileno())
    try:
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def sync_directory(path):
    if os.name != 'nt':
        descriptor = os.open(path, os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
