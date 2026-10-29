# Offline maps investigation (release 0.7.59) - 2026-10-29

Read-only investigation. Nothing was built or run on a device. Statements marked
**[measured]** come from code or the live catalog; **[inference]** is reasoning that
needs one adb command or one field observation to confirm.

## Краткое резюме для райдера (RU)

1. **Почему приложение предлагает скачать карту, хотя она уже скачана.** Найдено две
   реальные причины в коде, обе проявляются «иногда»:
   - **Мобильный интернет.** При старте карты приложение сначала проверяет сеть и только
     потом - «а не готова ли карта уже». Если сеть мобильная (metered) и галка
     «не спрашивать при мобильных данных» выключена, оно ВСЕГДА вызывает загрузку региона,
     даже когда он установлен. Статус установленного региона перезаписывается из
     «Готово» в «Ждёт подтверждения мобильных данных» и появляется диалог
     «Скачать регион по мобильной сети? Регион пока недоступен офлайн».
     Нажать «Скачать» там опасно: код скачает пакет (~100 МБ) заново.
   - **Вы вышли за границы единственной готовой ячейки.** Сейчас готов один квадрат
     1°x1° (56-57 с.ш., 60-61 в.д.). Все остальные 5739 квадратов "Россия" - это «по требованию».
     Стоит GPS-фиксу оказаться за краем (Первоуральск, Ревда, Заречный, Берёзовский-восток и т.д.),
     приложение берёт соседний пустой квадрат и предлагает (или молча, на Wi-Fi, начинает) его скачать.
   Минимальный фикс: в `runPreparation` проверять «регион READY и карта готова» ДО сетевой
   проверки и не вызывать `requestDownload` для региона в статусе READY; плюс не
   дёргать скачивание в соседнюю ячейку без явного согласия.
2. **330 МБ вместо «90 МБ скачано / 200 МБ распаковано».** Цифры в настройках - это только
   размеры из подписанного манифеста для двух компонентов (маршрутизация + поиск), в МиБ,
   подписанных как «МБ»: 99,2 МБ скачивание и 205,3 МБ на устройстве. Растровая/векторная
   карта (MapLibre offline pack, 1410 тайлов на стиль) и общий кэш MapLibre в эти цифры
   не входят вообще. Разница ~125 МБ - это почти наверняка `mbgl-offline.db`
   (карта + «ambient» кэш, который никто не ограничивает) [inference], возможно плюс остатки
   старых пакетов. Чтобы убедиться: `adb shell run-as ru.sodovaya.volty du -ah files cache databases`.
3. **Регионы.** Каталог собирается на сервере скриптами из этого репозитория
   (`tools/offline-navigation`). Новые регионы - это ячейки 1°x1° «по требованию»: в каталоге
   уже есть 5739 ячеек «Россия», но готова только одна (Екатеринбург). Ячейка собирается при
   первом запросе из приложения (Geofabrik `ural-fed-district` и т.п.). Отдельный список
   городов/областей нужно вносить в конфиг сервера. Каталог уже 3,81 МиБ из лимита 4 МиБ -
   скоро его надо будет ужимать.

---

## 1. False "download the map" prompt

### Flow (measured)

1. App start: `VoltyApplication.kt:45-49` fires `refreshCatalog()` fire-and-forget.
2. `AndroidOfflineRegionPackageRepository.init` (`...PackageRepository.kt:~113-116`)
   synchronously fills `_states` from disk: `packageStore.installedRegions().map(::stateForInstalled)`,
   all `READY`. `installedRegions()` goes through `active()`, which silently returns null when the
   manifest fails signature verification, `minAppVersionCode > current`, or the package files
   fail `verifyInstalledPackage` (`PackageStore.kt:143-164`). So a bad key/version config makes the
   installed region invisible - but the rider confirmed the region shows as installed, so this is not the cause here.
3. `RootScreen.kt:247-256`: `LaunchedEffect(rideMapVisible, ownFix != null, darkTheme, offlineRegionStates.size)`
   calls `offlinePreparation.prepareCurrentRegion(fix, style)` once a GPS fix exists. It re-fires when
   the catalog lands (`states.size` goes 1 -> 5740) and when the theme flips.
4. `DefaultOfflineRegionPreparationCoordinator.prepareCurrentRegion` (`PreparationCoordinator.kt:80-85`)
   picks `states.filter { bounds.contains(coordinate) }.minByOrNull { regionId }` and calls `prepare`.

### Root cause A - network gate runs before the "already done" gate (measured)

`runPreparation` (`PreparationCoordinator.kt:198-291`):

- L211-247: if `network.current()` is not `Allowed` (i.e. `METERED` while
  `skipMeteredConfirmation == false`, the default, `OfflineDownloadPolicy.kt:44-56`), it publishes a
  pending request and calls `packages.requestDownload(regionId, ...)` **unconditionally**, then returns.
- Only at L251 does it check `store.completed(...)`, and only at L266 does it check whether the
  package is already `READY`. Neither check precedes the network branch.
- `requestDownloadLocked` (`PackageRepository.kt:~160-300`) has no "installed release == latest release"
  short-circuit. With `release != null` it reaches `downloadAllowed()` (L317-338), which for
  `METERED` writes `status = AWAITING_METERED_APPROVAL` over the existing `READY` state.
- `RootScreen.kt:159-161` renders the dialog for any state with `AWAITING_METERED_APPROVAL`
  (`settings_offline_metered_text`: "The region is not available offline yet...").
- Confirming the dialog calls `prepareExplicitRegionConfirmed` -> `requestDownloadLocked(meteredConfirmed=true)`
  -> full 99 MiB re-download and reinstall of an already-installed release.

This matches "sometimes": it only happens on mobile data (the rider's normal condition on the road) and
not on Wi-Fi (on Wi-Fi the `Allowed` branch hits the `completed` check and exits quietly).
The same path fires when the style key is new: `store.completed` is keyed `region|BRIGHT/DARK|revision`
(`AndroidOfflineRegionPreparationStore.kt`), so the first launch in the other theme is "not completed".

Test gap: `OfflineRegionPreparationCoordinatorTest` fixtures never combine
`status = READY` + `network = METERED` + `store.completed = true` [inference from file name; not read in full].

**Minimal fix (two small edits):**
1. In `runPreparation`, move the `store.completed(...)`/"package READY and map pack Ready" early return
   above the network block (or at least skip `packages.requestDownload` when the package state is
   `READY`/`UPDATE_AVAILABLE`; the map pack, if missing, is a separate, much cheaper decision).
2. In `requestDownloadLocked`, return early when
   `packageStore.active(regionId)?.manifest?.releaseVersion == preparedRelease.releaseVersion`.
   Optionally never let `downloadAllowed` overwrite a `READY` status.

### Root cause B - GPS leaves the single built cell (measured geometry, inference about rider position)

- Live catalog [measured]: 5740 regions, 5739 are `displayName "Россия"` 1°x1° cells with
  `latestRelease: null, onDemand.enabled: true`; exactly one has a release:
  `g1-146-240` "Екатеринбург", bounds 56-57 N, 60-61 E, release `g1-146-240-1788863027`
  (2026-09-08, source Geofabrik `ural-fed-district`, routing buffer 20 km).
- `prepareCurrentRegion` takes whatever cell contains the fix. Outside `g1-146-240`
  (e.g. Pervouralsk/Revda ~59.9 E, Zarechny ~61.3 E, anything north of 57 N), the cell is an
  on-demand NOT_INSTALLED one, so `runPreparation` issues `requestDownload` -> on Wi-Fi it silently asks the
  server to build and then downloads that cell (storage grows by ~100-200 MB per cell), on mobile
  data it shows the same metered dialog for a "Россия" cell.
- Older `ekb-agglomeration` (59.10-61.90 E, 56.00-57.55 N, see `tools/offline-navigation/README.md`)
  covered this area in one package; the grid migration split it.

**Fix:** automatic preparation from `FIRST_MAP_OPEN` must not start an on-demand build/download
for a cell that is not already installed; offer it only via Settings or an explicit prompt.
Optionally prepare 3x3 neighbours of an installed cell, or ship larger cells for the home area.

### Things checked and ruled out (measured)

- Installed-state vs prompt race: installed state is loaded synchronously in the repository `init`;
  `AndroidOfflineMapPackManager.prepare` awaits `loadPacksLocked()` under the mutex
  (`MapPackManager.kt:69-72`), so it will not create a duplicate pack before the MapLibre listing finishes.
  Cosmetic only: `mapPacks.state(key)` is `null` until that async load ends, and the coordinator falls back
  to `Missing` (`PreparationCoordinator.kt:302`), so Settings can briefly show the map as missing.
- Catalog failure / mismatch marking installed stale: `stateFor` derives `READY`/`UPDATE_AVAILABLE` from
  `packageStore.active()` (signature checked with the local key) and does not depend on the catalog being
  reachable; a failed `refreshCatalog` leaves the disk-derived state in place.
- Cache vs files dir: package store uses `filesDir/offline-regions` consistently; nothing moves.

## 2. Storage

### What the feature writes (measured)

| Path / owner | Content | Size for g1-146-240 |
|---|---|---|
| `filesDir/offline-regions/packages/<region>/<release>-<uuid>/routing` | extracted Valhalla tiles.tar, admins/timezones sqlite, valhalla.json | 178,196,114 B installed |
| `.../search/places.sqlite` | FTS4 geocoder | 37,040,128 B installed |
| `filesDir/offline-regions/downloads/<region>-<release>/` | `.tar.gz`, `.sqlite.gz` and `.part` files | 94,903,891 + 9,090,098 B while present |
| `filesDir/offline-regions/active/*.pointer` | tiny pointers | ~0 |
| `filesDir/offline-routing` (`AndroidOfflineRoutingPackageManager`) | legacy routing store | unknown, possibly leftover |
| MapLibre `mbgl-offline.db` (SDK-owned, in app files dir, `OfflineManager.getInstance`, `AndroidModule.kt:489`) | offline pack for BRIGHT (and DARK once used) + shared ambient cache | not measured |

Pack definition: z5-13 over the 1° cell = 1410 tiles per style (computed with the same Web-Mercator math as
`OfflineMapPackPolicy.estimatedTileCount`; z13 alone is 1008). Both styles use the same OpenFreeMap vector tiles,
so the second style mostly adds sprites/glyphs [inference].

### Why 90 / 200 / 330 (measured numbers, inferred attribution of the gap)

- UI size line: `SettingsScreen.kt:1087-1093` prints `region.totalDownloadBytes` / `totalInstalledBytes`
  (`OfflineRegionPackageState.kt`), which are **sums from the signed manifest for routing + search only**, formatted by
  `formatOfflineBytes` (1024-based, label "MB"): 103,993,989 B = **99.2 "MB"** download and 215,236,242 B =
  **205.3 "MB"** on device. The rider's "~90" is the routing archive alone (90.5 MiB); the rest is the same ballpark.
  Nothing on this screen measures the disk or includes the MapLibre map pack.
- 330 - 205 = **~125 MB unexplained by the UI**. Likely contributors, most to least:
  1. MapLibre `mbgl-offline.db`: 1410 tiles x ~60-90 KB for the BRIGHT pack (~85-125 MB), plus the DARK pack
     resources, plus the ambient cache. No code anywhere calls `setMaximumAmbientCacheSize`, `clearAmbientCache` or
     `invalidateAmbientCache` (grep over `composeApp/src`), so MapLibre's default cap (50 MB class) applies
     and history preview rendering (`MapLibreHistoryPreviewLoader.kt:63`) feeds the same DB [inference].
  2. Leftover `downloads/` staging after a failed or paused attempt: only a successful install or an install
     failure calls `discardDownloadStaging`; a failed download is kept for resume, and `cleanupStaging()` at
     store init only removes `staging-*` dirs, never `downloads/` (`PackageStore.kt:545-549`). Peak during an install is
     archive (104 MB) + extracted copy (215 MB) [measured].
  3. Legacy v2 package directories (`navigation-v2-*` copy plus the original with its old `map.pmtiles`) are
     deliberately never deleted (`cleanupOrphanedPackages`, `PackageStore.kt:559-603`, and the "rollback input"
     comment), so an upgrade from an earlier ekb package would have doubled the 215 MB [inference, depends on rider history].
  4. Old versions on update: the previous release directory is removed by `cleanupOrphanedPackages` after the new pointer
     is published, so this is not a leak [measured].

**Confirm with:** `adb shell run-as ru.sodovaya.volty du -ah files cache databases | sort -h | tail -20`
(and `ls files/offline-regions/downloads`).

### Proposed fixes

1. Report real size: sum file lengths under `offline-regions/packages/<region>` and query the MapLibre pack
   (`OfflineRegion.getStatus().completedResourceSize`) and show the three numbers (routing+search / map / cache);
   fix the label to MiB or switch to 1000-based MB.
2. At store init also reap `downloads/*` that do not belong to an in-progress job, and delete the archives right after
   checksum + extraction (`install` already validated them) rather than after the state update.
3. Cap the ambient cache explicitly (e.g. `OfflineManager.setMaximumAmbientCacheSize(20L * 1024 * 1024, cb)`) and
   call `clearAmbientCache` after pack completion; keep only the style in use (delete the unused theme pack, or
   prepare lazily).
4. Add a Settings action/diagnostic that lists and offers deletion of legacy `navigation-v2-*`/old release dirs.
5. Delete the legacy `AndroidOfflineRoutingPackageManager` directory if nothing reads it (verify first).

## 3. Regions: where the catalog comes from

All in this repo, server side (measured):

- **Builder toolchain:** `tools/offline-navigation/` - `build-package.sh` (Docker, pinned Valhalla 3.6.3 image),
  `build-search.py` (FTS4 geocoder), `build-manifest.py`, `sign-manifest.py` (external Ed25519 PEM),
  `build-catalog.py`, `verify-package.py`, docs in `tools/offline-navigation/README.md`.
- **Automatic production pipeline:** `tools/offline-navigation/production/` (`grid.py` 1°x1° "grid-v1" cells with
  quadtree split to depth 4, `bootstrap.py` builds an inventory from Geofabrik's `index-v1.json`, `worker.py`
  consumes a job queue, builds, signs and publishes, and `_write_catalog` regenerates and signs `catalog.json`;
  `scheduler.py`, `retention.py`, `resource_budget.py`; ops in `tools/offline-navigation/ops/*.sh`,
  `bootstrap-regions.sh [env] [source-id]`, `deploy-production.sh`).
- **Delivery:** `backend/` Ktor + `package-service.py` (see `backend/OFFLINE.md`): serves the catalog, resolves a
  coordinate to a cell, `POST /offline/regions/{id}/ensure` for on-demand builds. The app's
  `HttpOfflineRegionAcquisition.ensurePrepared` waits for that (`~maxWaitMillis`).
- Design docs: `docs/superpowers/specs/2026-09-05-offline-production-design.md`,
  `2026-09-06-offline-map-and-on-demand-regions-design.md`, plans in `docs/superpowers/plans/2026-09-0*-offline*.md`.

Live catalog (measured, fetched 2026-10-29): `schemaVersion 2`, `generatedAt 2026-10-21T16:45:28Z`, 3,999,559 bytes,
5740 entries; key `volty-offline-2026-09-06`. So "only Yekaterinburg and all Russia" is exactly the data: one
built cell plus 5739 empty on-demand cells.

### What adding regions takes

- No app change is needed for more Russian coverage: any cell becomes available by the app (or an operator) calling
  `ensure` for it; the production worker downloads the Geofabrik extract (`ural-fed-district` for this cell; other
  federal districts need their `sourceId`/`sourceUrl` in the production config `regions` list), runs
  `build-package.sh`, signs with the production key (`keyId` in `production.json`, private key mounted at
  `/run/secrets/volty-offline-signing-key.pem`), publishes to `/data/offline/<id>/<version>/` and rewrites the signed catalog.
- To pre-build a metro area instead of waiting for a rider: enqueue the cell ids (`worker.request_build(region_id)` /
  `bash deploy-offline.sh --region ... --release ...` or `bootstrap-regions.sh`), several adjacent cells for continuous
  routing (a route cannot cross cells: `OfflineRegionAccessPolicy` comment about "no safe single-package route").
- Cost per cell is ~100 MB download / ~200 MB unpacked (dense urban) plus the MapLibre pack; check
  `resource_budget.py` and server disk (`MAX_CACHE_BYTES` 64 GiB).
- Outside Russia: the 1° grid and catalog already generalise (`grid.py`), but the bootstrap's default source id is
  `russia`, the catalog only lists Russian cells, and `OfflineRegionDisplayNamePolicy` / generated names are tied to the Russian data.

### Risks to schedule

- **Catalog size ceiling:** 3,999,559 B vs `MAX_CATALOG_BYTES = 4 MiB` (4,194,304) in both the app
  (`PackageRepository.kt` companion) and the package service (`OFFLINE.md`: `MAX_CATALOG_BYTES` 4 MiB).
  Each built release adds roughly 1.3 KB; ~190 KB of headroom (~150 releases). Exceeding it makes every client's
  `fetchCatalog` throw "Catalog is too large" and the server reject its own upstream catalog. Fix: omit empty
  on-demand cells from the catalog (serve them through `/offline/resolve?lat=&lon=`), or paginate/compress, or raise the limit
  in app and server together (an app update is required for the former).
- 5739 identical-name "Россия" rows pollute Settings search; names are generated from OSM only after a build.

## Summary of recommended order

1. Fix root cause A (`runPreparation` ordering + `requestDownloadLocked` idempotency) with a deliberately incoherent fixture
   (READY + METERED + completed) - highest value, pure code.
2. Stop automatic on-demand build/download of non-installed neighbouring cells (root cause B).
3. Real size reporting, `downloads/` reaping, ambient cache cap.
4. Catalog size ceiling plan before adding many built regions.
