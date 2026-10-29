# Third-party material

Volty itself is licensed under the GNU Affero General Public License v3.0 or later
(see `LICENSE` and `NOTICE.md`). The material below keeps its own, compatible license.

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
individual license declarations are inventoried in
`docs/third-party/ride-history-runtime-licenses.md`. Local/native libraries have
additional notices beside their source/build documentation. These declarations
and the source bundle do not by themselves establish a legal conclusion about all
combined-application dependencies; the scoped evidence and unresolved items remain
explicit in `docs/third-party/vescape-history.md`.
