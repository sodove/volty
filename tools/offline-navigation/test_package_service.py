"""Acquisition contract tests with signed, tiny real package fixtures."""
import base64
import copy
import gzip
import hashlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import threading
import time
import unittest

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
import test_build_catalog as fixtures
catalog_tools = fixtures.MODULE

SERVICE = Path(__file__).with_name('package-service.py')
service = None
if SERVICE.exists():
    spec = importlib.util.spec_from_file_location('package_service', SERVICE)
    service = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(service)


class PackageServiceTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(service, 'Verified package acquisition service is missing')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.key = Ed25519PrivateKey.generate()
        self.manifest = fixtures.BuildCatalogTest().valid_manifest()
        self.manifest['components']['search']['compression'] = 'gzip'
        self.artifacts = self.make_artifacts()
        for name, data in self.artifacts.items():
            component = self.manifest['components'][name]
            suffix = {'routing': 'routing/valhalla-routing.tar.gz', 'search': 'search/places.sqlite.gz',
                      'map': 'map/ekb-agglomeration.pmtiles'}[name]
            component.update(url='https://public.test/offline/regions/ekb-agglomeration/0.1.2/' + suffix,
                             downloadBytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.sign()
        self.catalog_bytes = self.make_catalog()
        self.downloads = []
        self.gate = None
        self.started = threading.Event()
        self.config = service.Config(root=self.root / 'cache',
            catalog_url='https://origin.test/catalog.json', artifact_base_url='https://origin.test/regions',
            public_base_url='https://public.test/offline/regions',
            public_key=base64.b64encode(self.key.public_key().public_bytes(
                serialization.Encoding.Raw, serialization.PublicFormat.Raw)).decode(), key_id='release-key',
            max_download_bytes=1024*1024, max_expanded_bytes=1024*1024, min_free_bytes=0,
            prune_grace_seconds=10)

    def make_artifacts(self):
        config = json.dumps({'mjolnir': {'tile_dir': 'tiles', 'tile_extract': 'tiles.tar',
            'admin': 'admins.sqlite', 'timezone': 'timezones.sqlite'}}).encode()
        files = {'tiles.tar': b'tiles', 'admins.sqlite': b'admin', 'timezones.sqlite': b'tz', 'valhalla.json': config}
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w:gz') as archive:
            for name, data in files.items():
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        self.manifest['components']['routing']['installedBytes'] = sum(map(len, files.values()))
        database = self.root / 'places.sqlite'
        with sqlite3.connect(database) as connection:
            connection.execute('CREATE VIRTUAL TABLE places USING fts4(display_name, search_text, latitude, longitude, kind, osm_id)')
            connection.execute("INSERT INTO places VALUES ('Екатеринбург', 'екатеринбург', 56, 60, 'city', '1')")
            connection.execute('CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT)')
            connection.executemany('INSERT INTO metadata VALUES (?, ?)', [('schema', '1'), ('region_id', 'ekb-agglomeration')])
        connection.close()
        search = database.read_bytes()
        self.manifest['components']['search']['installedBytes'] = len(search)
        header = bytearray(127)
        header[:8] = b'PMTiles\x03'
        header[8:16] = (127).to_bytes(8, 'little')
        header[16:24] = (1).to_bytes(8, 'little')
        header[97:102] = bytes([1, 1, 1, 5, 14])
        for offset, coordinate in ((102, 59.1), (106, 56.0), (110, 61.9), (114, 57.55)):
            header[offset:offset+4] = round(coordinate * 10_000_000).to_bytes(4, 'little', signed=True)
        map_data = bytes(header) + b'\x00'
        self.manifest['components']['map']['installedBytes'] = len(map_data)
        return {'routing': buffer.getvalue(), 'search': gzip.compress(search), 'map': map_data}

    def sign(self):
        self.manifest['manifestSignature']['value'] = base64.b64encode(
            self.key.sign(catalog_tools.canonical_payload(self.manifest))).decode()

    def make_catalog(self):
        catalog = {'schemaVersion': 2, 'generatedAt': '2026-09-05T00:00:00Z', 'regions': [{
            'region': {'regionId': 'ekb-agglomeration', 'displayName': 'Екатеринбург',
                       'bounds': {'south': 56.0, 'west': 59.1, 'north': 57.55, 'east': 61.9}},
            'latestRelease': self.manifest}]}
        return json.dumps(catalog_tools.sign_catalog(catalog, self.key, 'release-key'), ensure_ascii=False).encode()

    def fetch(self, url, target, limit):
        if url == self.config.catalog_url:
            data = self.catalog_bytes
        else:
            self.downloads.append(url)
            self.started.set()
            if self.gate:
                if not self.gate.wait(5):
                    raise TimeoutError('test gate')
            self.assertTrue(url.startswith('https://origin.test/regions/ekb-agglomeration/0.1.2/'))
            name = 'routing' if '/routing/' in url else 'search' if '/search/' in url else 'map'
            data = self.artifacts[name]
        if len(data) > limit:
            raise ValueError('download_limit')
        target.write(data)

    def manager(self):
        manager = service.PackageManager(self.config, fetch=self.fetch)
        self.addCleanup(manager.close)
        return manager

    def finish(self, manager):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            state = manager.status('ekb-agglomeration')
            if state['status'] in ('ready', 'failed'):
                return state
            time.sleep(.01)
        self.fail('worker did not finish')

    def test_cold_catalog_is_relayed_unchanged_and_resolve_earns_ready(self):
        manager = self.manager()
        manager.refresh()
        self.assertEqual(self.catalog_bytes, manager.catalog_bytes())
        self.assertEqual('available', manager.resolve(56.8, 60.6)['status'])
        self.assertEqual('unsupported', manager.resolve(0, 0)['status'])
        manager.ensure('ekb-agglomeration')
        self.assertEqual('ready', self.finish(manager)['status'])
        self.assertEqual('ready', manager.resolve(56.8, 60.6)['status'])

    def test_catalog_and_manifest_tampering_preserve_previous_catalog(self):
        manager = self.manager()
        manager.refresh()
        original = manager.catalog_bytes()
        self.catalog_bytes = self.catalog_bytes.replace(b'2026-09-05', b'2026-09-06')
        with self.assertRaises(ValueError):
            manager.refresh()
        self.assertEqual(original, manager.catalog_bytes())
        self.manifest['components']['map']['sha256'] = '0' * 64
        self.catalog_bytes = self.make_catalog()  # catalog signed, nested manifest is not
        with self.assertRaises(ValueError):
            manager.refresh()
        self.assertEqual(original, manager.catalog_bytes())

    def test_duplicate_ensure_does_not_publish_partial_files(self):
        manager = self.manager()
        manager.refresh()
        self.gate = threading.Event()
        manager.ensure('ekb-agglomeration')
        self.assertTrue(self.started.wait(2))
        for _ in range(20):
            manager.ensure('ekb-agglomeration')
        with self.assertRaises(FileNotFoundError):
            manager.open_artifact('ekb-agglomeration', '0.1.2', 'map/ekb-agglomeration.pmtiles')
        self.gate.set()
        self.assertEqual('ready', self.finish(manager)['status'])
        self.assertEqual(3, len(self.downloads))

    def test_hash_mismatch_never_becomes_ready_and_retry_can_succeed(self):
        manager = self.manager()
        manager.refresh()
        original = self.artifacts['map']
        self.artifacts['map'] = b'X' * len(original)
        manager.ensure('ekb-agglomeration')
        failed = self.finish(manager)
        self.assertEqual('failed', failed['status'])
        self.assertEqual('artifact_checksum', failed['errorCode'])
        with self.assertRaises(FileNotFoundError):
            manager.open_artifact('ekb-agglomeration', '0.1.2', 'manifest.json')
        self.artifacts['map'] = original
        manager.ensure('ekb-agglomeration')
        self.assertEqual('ready', self.finish(manager)['status'])

    def test_signed_unsafe_tar_and_expansion_are_rejected(self):
        for path, kind, size in [('../escape', tarfile.REGTYPE, 1), ('link', tarfile.SYMTYPE, 0), ('huge', tarfile.REGTYPE, 2000)]:
            with self.subTest(path=path):
                buffer = io.BytesIO()
                with tarfile.open(fileobj=buffer, mode='w:gz') as archive:
                    info = tarfile.TarInfo(path)
                    info.type, info.size, info.linkname = kind, size, '/tmp/escape'
                    archive.addfile(info, io.BytesIO(b'x' * size))
                archive_path = self.root / 'bad.tar.gz'
                archive_path.write_bytes(buffer.getvalue())
                with self.assertRaises(ValueError):
                    service.validate_routing(archive_path, self.root / 'unpack', 1000)
        self.assertFalse((self.root / 'escape').exists())

    def test_public_url_escape_is_rejected_even_when_signed(self):
        manager = self.manager()
        for url in ['https://evil.test/map.pmtiles', 'https://public.test/offline/regions/../secret',
                    self.manifest['components']['map']['url'] + '?token=secret']:
            self.manifest['components']['map']['url'] = url
            self.sign()
            self.catalog_bytes = self.make_catalog()
            with self.assertRaises(ValueError):
                manager.refresh()

    def test_restart_only_recovers_verified_publications_and_hides_private_paths(self):
        manager = self.manager()
        manager.refresh()
        manager.ensure('ekb-agglomeration')
        self.assertEqual('ready', self.finish(manager)['status'])
        manager.close()
        restarted = self.manager()
        self.assertEqual('ready', restarted.status('ekb-agglomeration')['status'])
        for suffix in ['../catalog.json', '.ready.json', 'map/../manifest.json', 'routing/unknown']:
            with self.assertRaises(FileNotFoundError):
                restarted.open_artifact('ekb-agglomeration', '0.1.2', suffix)

    def test_http_range_and_unknown_region_are_bounded(self):
        manager = self.manager()
        manager.refresh()
        manager.ensure('ekb-agglomeration')
        self.assertEqual('ready', self.finish(manager)['status'])
        server = service.make_server(manager, '127.0.0.1', 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        client = http.client.HTTPConnection(*server.server_address, timeout=3)
        self.addCleanup(client.close)
        client.request('GET', '/regions/ekb-agglomeration/0.1.2/map/ekb-agglomeration.pmtiles', headers={'Range': 'bytes=0-7'})
        response = client.getresponse()
        self.assertEqual(206, response.status)
        self.assertEqual('bytes 0-7/128', response.getheader('Content-Range'))
        self.assertEqual('"' + self.manifest['components']['map']['sha256'] + '"', response.getheader('ETag'))
        self.assertEqual(b'PMTiles\x03', response.read())
        client.request('GET', '/regions/ekb-agglomeration/0.1.2/map/ekb-agglomeration.pmtiles',
                       headers={'Range': 'bytes=0-7', 'If-Range': '"stale"'})
        response = client.getresponse()
        self.assertEqual(200, response.status)
        self.assertEqual(self.artifacts['map'], response.read())
        client.request('POST', '/regions/unknown/ensure')
        response = client.getresponse()
        self.assertEqual(404, response.status)
        self.assertEqual('unavailable', json.loads(response.read())['status'])

    def test_stale_release_does_not_acquire_new_release(self):
        manager = self.manager()
        manager.refresh()
        state = manager.ensure('ekb-agglomeration', 'old-release')
        self.assertEqual('unavailable', state['status'])
        self.assertEqual('release_unavailable', state['errorCode'])
        self.assertEqual([], self.downloads)

    def test_local_ingest_reuses_verifier_and_does_not_fetch_artifacts(self):
        ingest_root = self.root / 'ingest'
        self.config.ingest_root = ingest_root
        manager = self.manager()
        manager.refresh()
        for name, suffix in {'routing': 'routing/valhalla-routing.tar.gz', 'search': 'search/places.sqlite.gz',
                             'map': 'map/ekb-agglomeration.pmtiles'}.items():
            path = ingest_root / 'ekb-agglomeration/0.1.2' / suffix
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.artifacts[name])
        manager.ingest('ekb-agglomeration', '0.1.2')
        self.assertEqual('ready', self.finish(manager)['status'])
        self.assertEqual([], self.downloads)

    def test_bad_search_structure_and_pmtiles_header_are_not_published(self):
        manager = self.manager()
        manager.refresh()
        for name, bad in [('search', gzip.compress(b'not sqlite')), ('map', b'not pmtiles')]:
            with self.subTest(name=name):
                path = self.root / ('bad-' + name)
                path.write_bytes(bad)
                with self.assertRaises((ValueError, sqlite3.DatabaseError)):
                    if name == 'search':
                        service.validate_search(path, self.root / 'bad.sqlite', 1000, 'ekb-agglomeration')
                    else:
                        service.validate_pmtiles(path)

    def test_prune_preserves_active_and_recently_retired_releases(self):
        manager = self.manager()
        manager.refresh()
        manager.ensure('ekb-agglomeration')
        self.assertEqual('ready', self.finish(manager)['status'])
        marker = self.config.root / 'releases/ekb-agglomeration/0.1.2/.ready.json'
        import os
        os.utime(marker, (1, 1))
        self.assertEqual(0, manager.prune()['removedReleases'])
        self.manifest['releaseVersion'] = '0.1.3'
        for component in self.manifest['components'].values():
            component['url'] = component['url'].replace('/0.1.2/', '/0.1.3/')
        self.sign()
        self.catalog_bytes = self.make_catalog()
        manager.refresh()
        self.assertEqual('ready', manager.status('ekb-agglomeration', '0.1.2')['status'])
        self.assertEqual(0, manager.prune()['removedReleases'])
        os.utime(marker, (1, 1))
        self.assertEqual(1, manager.prune()['removedReleases'])
        self.assertEqual('unavailable', manager.status('ekb-agglomeration', '0.1.2')['status'])

    def test_smallest_matching_region_and_multiple_active_catalog_entries(self):
        manager = self.manager()
        catalog = json.loads(self.catalog_bytes)
        smaller = copy.deepcopy(catalog['regions'][0])
        smaller['region'].update(regionId='ekb-center', displayName='Центр')
        smaller['region']['bounds'] = {'west': 60.0, 'south': 56.5, 'east': 61.0, 'north': 57.0}
        manifest = smaller['latestRelease']
        manifest['regionId'] = 'ekb-center'
        for component in manifest['components'].values():
            component['url'] = component['url'].replace('ekb-agglomeration', 'ekb-center')
        manifest['manifestSignature']['value'] = base64.b64encode(self.key.sign(catalog_tools.canonical_payload(manifest))).decode()
        catalog['regions'].append(smaller)
        self.catalog_bytes = json.dumps(catalog_tools.sign_catalog(catalog, self.key, 'release-key'), ensure_ascii=False).encode()
        manager.refresh()
        self.assertEqual('ekb-center', manager.resolve(56.8, 60.6)['regionId'])
        self.assertEqual('ekb-agglomeration', manager.resolve(56.8, 59.5)['regionId'])
        self.assertEqual(2, len(json.loads(manager.catalog_bytes())['regions']))
        manager.ensure('ekb-agglomeration')
        self.assertEqual('ready', self.finish(manager)['status'])
        self.assertEqual(0, manager.prune()['removedReleases'])

    def test_storage_budget_fails_before_any_artifact_request(self):
        self.config.max_cache_bytes = 1
        manager = self.manager()
        manager.refresh()
        state = manager.ensure('ekb-agglomeration')
        self.assertEqual('storage_limit', state['errorCode'])
        self.assertEqual([], self.downloads)

    def test_second_writer_cannot_open_same_cache(self):
        manager = self.manager()
        with self.assertRaisesRegex(ValueError, 'cache_already_in_use'):
            service.PackageManager(self.config, fetch=self.fetch)
        manager.refresh()
        self.assertEqual(self.catalog_bytes, manager.catalog_bytes())

    def test_catalog_cannot_change_content_of_an_existing_release(self):
        manager = self.manager()
        manager.refresh()
        self.manifest['components']['map']['sha256'] = '0' * 64
        self.sign()
        self.catalog_bytes = self.make_catalog()
        with self.assertRaisesRegex(ValueError, 'immutable_release_conflict'):
            manager.refresh()

    def test_transport_rejects_http_credentials_redirects_and_encoded_paths(self):
        for url in ('http://origin.test/data', 'https://user:pass@origin.test/data',
                    'https://origin.test/%2e%2e/data', 'https://origin.test/a/../b'):
            with self.assertRaises(ValueError):
                service.https_url(url)
        with self.assertRaisesRegex(ValueError, 'redirect_forbidden'):
            service.NoRedirect().redirect_request(None, None, 302, None, None, 'https://evil.test')

    def test_failed_retired_release_request_does_not_start_new_version(self):
        manager = self.manager()
        manager.refresh()
        self.artifacts['map'] = b'X' * len(self.artifacts['map'])
        manager.ensure('ekb-agglomeration')
        self.assertEqual('failed', self.finish(manager)['status'])
        self.manifest['releaseVersion'] = '0.1.3'
        for component in self.manifest['components'].values():
            component['url'] = component['url'].replace('/0.1.2/', '/0.1.3/')
        self.sign()
        self.catalog_bytes = self.make_catalog()
        manager.refresh()
        self.assertEqual('unavailable', manager.ensure('ekb-agglomeration', '0.1.2')['status'])

    def test_removed_release_identity_survives_restart(self):
        manager = self.manager()
        manager.refresh()
        original = json.loads(self.catalog_bytes)
        empty = copy.deepcopy(original)
        empty['regions'] = []
        self.catalog_bytes = json.dumps(catalog_tools.sign_catalog(empty, self.key, 'release-key')).encode()
        manager.refresh()
        manager.close()
        restarted = self.manager()
        self.manifest['components']['map']['sha256'] = '0' * 64
        self.sign()
        self.catalog_bytes = self.make_catalog()
        with self.assertRaisesRegex(ValueError, 'immutable_release_conflict'):
            restarted.refresh()


if __name__ == '__main__':
    unittest.main()
