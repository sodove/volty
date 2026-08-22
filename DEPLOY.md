# VPS Deploy

1. Point `volty.sodove.ru` and `voice.sodove.ru` to the VPS public IPv4.
2. Open inbound ports `80/tcp`, `443/tcp`, `7881/tcp`, `3478/udp`, and `50000-50019/udp`.
3. Install [nginx/volty.conf.example](nginx/volty.conf.example) as an nginx
   vhost, make sure the certificate covers both hostnames, then run:

   ```bash
   nginx -t && systemctl reload nginx
   ```

4. Run `bash deploy.sh` from the repository root.

`deploy.sh` is the one-command Docker path. nginx remains the owner of ports
80/443; Compose publishes only `127.0.0.1:18080` for the API and
`127.0.0.1:17880` for LiveKit signaling. It checks Docker Compose, creates
`.env` from `.env.example` only when `.env` is missing, preserves an existing
`.env`, fills placeholder secrets with base64url-safe values, auto-detects
`VOLTY_PUBLIC_IP` with `curl` if needed, forces `VOLTY_VOICE_PROVIDER=livekit`,
runs `docker compose up -d --remove-orphans --build`, waits for service health,
and prints the final status. `--remove-orphans` also cleans up the old Caddy
container if a previous version of this stack was started.

The host-loopback ports are configurable with `VOLTY_APP_HOST_PORT` and
`VOLTY_LIVEKIT_HTTP_HOST_PORT` in `.env`; if changed, update the two upstreams
in `nginx/volty.conf.example` before reloading nginx. The container ports remain
8080 and 7880.

The Android build is already configured for `https://volty.sodove.ru/v1`; no
server URL edit is needed. If you supply your own LiveKit key/secret instead of
letting the script generate them, keep them base64url-safe (`A-Z a-z 0-9 - _`;
optional `=` padding), or the LiveKit container will reject the configuration.

Runtime media requires a VPS with public UDP reachability. NAT-only hosting or a proxy without public UDP will break LiveKit audio even if HTTPS works.

If you already manage `.env` yourself, keep your real values there before running the script. The script never prints generated secrets to stdout.
