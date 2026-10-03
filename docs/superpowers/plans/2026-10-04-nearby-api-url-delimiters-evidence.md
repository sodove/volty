# API URL correction evidence

All six adjacent Nearby v2 HTTP adapters now reject raw query/fragment delimiters before
parsing their base URL. Empty delimiters previously survived parsed checks, so an appended
command path could land in URL metadata. The earlier parsed-only validation assumption is
retracted for this case. Existing HTTPS/credential checks remain; encoded path data is legal.

Before changes, six independently driven constructor tests actually failed with missing
expected exceptions (6tests6failures,26s35tasks). After correction, fresh full Android2728
passed30s35tasks with zero failures/errors/skips; a separate final full run before commit
also passed with exact counts. Narrow Luna source review PASS. The driver covers raw empty
and populated delimiters and positive encoded paths without reading auth or issuing network.

Backend unchanged. No LiveKit WSS query-policy, empty-host, production DI or field claim.
Logs are in ignored `.superpowers/sdd/2026-10-04-nearby-api-url-delimiters/`.
