#!/usr/bin/env python3
"""Private HTTP distribution service and operator CLI; one process per cache root."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import os
import re
import threading
import time
from urllib.parse import parse_qs, urlsplit, urlencode
from urllib.request import Request
from package_cache import PackageManager
from package_validation import Config, LOG, NoRedirect, https_url, validate_routing, validate_search, validate_pmtiles

def make_server(manager, host='0.0.0.0', port=8091):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            LOG.info(fmt, *args)

        def setup(self):
            super().setup()
            self.connection.settimeout(30)

        def reply(self, code, value):
            data = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            if isinstance(value, dict) and value.get('retryAfterSeconds'):
                self.send_header('Retry-After', str(value['retryAfterSeconds']))
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(data)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            try:
                url = urlsplit(self.path)
                query = parse_qs(url.query, strict_parsing=True)
                if any(len(v) != 1 for v in query.values()):
                    raise ValueError('duplicate_query')
                if url.path == '/catalog.json':
                    return self.reply(200, manager.catalog_bytes())
                if url.path == '/resolve':
                    if set(query) != {'lat', 'lon'}:
                        raise ValueError('invalid_coordinates')
                    return self.reply(200, manager.resolve(float(query['lat'][0]), float(query['lon'][0])))
                match = re.fullmatch(r'/regions/([a-z0-9][a-z0-9._-]{0,63})/status', url.path)
                if match:
                    state = manager.status(match[1], query.get('releaseVersion', [None])[0])
                    return self.reply(200, {k: v for k, v in state.items() if not k.startswith('_')})
                match = re.fullmatch(r'/regions/([a-z0-9][a-z0-9._-]{0,63})/([A-Za-z0-9][A-Za-z0-9._-]{0,63})/(.+)', url.path)
                if match and not query:
                    stream, etag = manager.open_response(*match.groups())
                    with stream:
                        return self.send_file(stream, match[3], etag)
                self.reply(404, {'errorCode': 'not_found'})
            except FileNotFoundError:
                self.reply(404, {'errorCode': 'not_ready'})
            except (ValueError, KeyError) as error:
                self.reply(503 if str(error) == 'catalog_unavailable' else 400, {'errorCode': str(error)})
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass

        def send_file(self, stream, name, etag):
            size = os.fstat(stream.fileno()).st_size
            start, end, code = 0, size - 1, 200
            request_range = self.headers.get('Range')
            if self.headers.get('If-Range') and self.headers['If-Range'] != etag:
                request_range = None
            if request_range:
                match = re.fullmatch(r'bytes=(\d*)-(\d*)', request_range)
                if not match or not any(match.groups()):
                    return self.range_error(size)
                if match[1]:
                    start = int(match[1])
                    end = min(int(match[2]), end) if match[2] else end
                else:
                    length = int(match[2])
                    if length <= 0:
                        return self.range_error(size)
                    start = max(0, size-length)
                if start > end or start >= size:
                    return self.range_error(size)
                code = 206
            self.send_response(code)
            self.send_header('Content-Type', 'application/json' if name == 'manifest.json' else 'application/octet-stream')
            self.send_header('Content-Length', str(end-start+1))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('ETag', etag)
            self.send_header('Cache-Control', 'public, max-age=86400, immutable')
            if code == 206:
                self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.end_headers()
            if self.command == 'HEAD':
                return
            stream.seek(start)
            remaining = end-start+1
            while remaining:
                chunk = stream.read(min(1024*1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

        def range_error(self, size):
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{size}')
            self.send_header('Content-Length', '0')
            self.end_headers()

        def do_POST(self):
            try:
                if self.headers.get('Transfer-Encoding') or int(self.headers.get('Content-Length', '0')) != 0:
                    return self.reply(400, {'errorCode': 'body_not_supported'})
                url = urlsplit(self.path)
                query = parse_qs(url.query, strict_parsing=True)
                if any(len(v) != 1 for v in query.values()):
                    raise ValueError('invalid_query')
                if url.path == '/ingest' and set(query) == {'regionId', 'releaseVersion'}:
                    return self.reply(202, manager.ingest(query['regionId'][0], query['releaseVersion'][0]))
                if set(query) - {'releaseVersion'}:
                    raise ValueError('invalid_query')
                match = re.fullmatch(r'/regions/([a-z0-9][a-z0-9._-]{0,63})/ensure', url.path)
                if match:
                    state = manager.ensure(match[1], query.get('releaseVersion', [None])[0])
                    code = 200 if state['status'] == 'ready' else 404 if state['status'] == 'unavailable' else 503 if state['status'] == 'failed' else 202
                    return self.reply(code, state)
                if url.path == '/refresh' and not query:
                    return self.reply(200, manager.refresh())
                if url.path == '/prune' and not query:
                    return self.reply(200, manager.prune())
                self.reply(404, {'errorCode': 'not_found'})
            except (ValueError, OSError) as error:
                LOG.warning('Request failed: %s', error)
                self.reply(503, {'errorCode': 'upstream_unavailable'})
    return ThreadingHTTPServer((host, port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=int(os.environ.get('VOLTY_OFFLINE_PORT', '8091')))
    parser.add_argument('--ingest', metavar='REGION_ID')
    parser.add_argument('--release', metavar='RELEASE_VERSION')
    parser.add_argument('--refresh', action='store_true')
    parser.add_argument('--prune', action='store_true')
    args = parser.parse_args()
    if args.ingest or args.refresh or args.prune:
        if args.ingest and not args.release:
            parser.error('--ingest requires --release')
        import urllib.request
        base = f'http://127.0.0.1:{args.port}'
        def operation(path, method='POST'):
            with urllib.request.urlopen(Request(base + path, method=method), timeout=30) as response:
                return json.loads(response.read(16384))
        if args.refresh:
            print(json.dumps(operation('/refresh')))
        if args.prune:
            print(json.dumps(operation('/prune')))
        if args.ingest:
            query = urlencode({'regionId': args.ingest, 'releaseVersion': args.release})
            state = operation('/ingest?' + query)
            deadline = time.monotonic() + 1800
            while state['status'] in ('queued', 'downloading') and time.monotonic() < deadline:
                time.sleep(min(30, max(1, state.get('retryAfterSeconds', 2))))
                state = operation(f'/regions/{args.ingest}/status?' + urlencode({'releaseVersion': args.release}), 'GET')
            print(json.dumps(state))
            if state['status'] != 'ready':
                raise SystemExit(1)
        return
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    manager = PackageManager(Config.from_env())
    stop = threading.Event()
    def refresh_loop():
        while not stop.is_set():
            try:
                manager.refresh()
                manager.prune()
            except Exception:
                LOG.exception('Catalog refresh failed; keeping last verified catalog')
            stop.wait(manager.config.refresh_seconds)
    refresher = threading.Thread(target=refresh_loop, daemon=True)
    refresher.start()
    server = make_server(manager, args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        manager.close()


if __name__ == '__main__':
    main()
