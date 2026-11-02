# Third-party material

Volty itself is licensed under the GNU Affero General Public License v3.0 or later
(see `LICENSE` and `NOTICE.md`). The material below keeps its own, compatible license.

Leaperkim telemetry corrections and regression fixtures reference
[cancelledbit/loeuc-core](https://github.com/cancelledbit/loeuc-core), revision
`ae10acc6e6de20b459928925531f2cbfd10bd56a` (and `83f2d99` for the PowerFlow
clarification), by **Nikita Vasilevskiy**. The decoder integration remains Volty's;
upstream material retains the following notice:

> MIT License
>
> Copyright (c) 2026 Nikita Vasilevskiy
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

Ride History UI adaptations from **Vescape**, project author **Kacper Kozak**, use
upstream revision `2527c346bbdb25d4e018fdd68e916621e20fac27` of
https://github.com/vescape-app/vescape and are licensed **GPL-3.0-or-later**.
The preserved upstream GNU GPL version 3 text is included verbatim at
`composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/history/ported/LICENSE`.
The adjacent README and file headers identify the transferred files and changes.
Volty's additional telemetry metrics, source attribution, persistence and ownership
policies are documented separately; no upstream device configuration or Mapbox
access token is imported.

The accompanying source archive contains the source for the matching application
revision, Gradle wrapper/build scripts and the original notices. Machine-specific
SDK paths, private signing keys, passwords and service-account credentials are not
part of that source archive. Runtime dependencies are resolved by Gradle; their
individual license declarations are inventoried in the generated offline catalog
at `composeApp/src/commonMain/composeResources/files/credits/catalog.json`;
see `tools/credits/README.md` for refresh and verification. The earlier
`docs/third-party/ride-history-runtime-licenses.md` remains a historical snapshot,
not the current release inventory. Local/native libraries have
additional notices beside their source/build documentation. These declarations
and the source bundle do not by themselves establish a legal conclusion about all
combined-application dependencies; the scoped evidence and unresolved items remain
explicit in `docs/third-party/vescape-history.md`.

## Roboto

The ride-video overlay burns text in with Roboto Medium and Bold
(`composeApp/src/androidMain/assets/fonts/`), copied unmodified from the Android
platform's `data/fonts`. Copyright 2011 Google Inc., licensed under the Apache License,
Version 2.0; the full text is `third-party/credits/licenses/Roboto-Apache.txt`.
