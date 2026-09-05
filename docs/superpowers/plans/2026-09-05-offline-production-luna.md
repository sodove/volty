# Volty Offline Production — Implementation Plan для Luna

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Развернуть полностью работающий production с предварительно собранным покрытием России, автоматической сборкой зарубежных регионов по запросу Android и проверенными обновлениями данных.

**Architecture:** Все production-роли работают на существующем VPS `sodovaya@mc.sodove.ru:22`: Ktor, PostgreSQL, локальное хранилище, publisher и постоянный worker. Worker последовательно строит Valhalla/FTS4/PMTiles из автоматически полученных OSM snapshots; publisher публикует неизменяемые подписанные версии. Android заказывает отсутствующую территорию, наблюдает подготовку, скачивает и проверяет пакет, затем использует его без интернета.

**Tech Stack:** Существующие Kotlin/Ktor/PostgreSQL, Kotlin Multiplatform Android, Valhalla 3.6.3, SQLite FTS4, PMTiles, Python build tools, Docker Compose, nginx. Версии образов закреплять digest после проверки совместимости; не обновлять весь стек ради этой задачи.

**Spec:** [2026-09-05-offline-production-design.md](../specs/2026-09-05-offline-production-design.md). Читать целиком вместе с этим планом.

## Global Constraints

- SSH: `ssh sodovaya@mc.sodove.ru`, порт 22. Сборка должна идти на этом VPS. Homeserver `192.168.1.141` — только необязательные тесты, не production dependency.
- Текущая задача пользователя — подготовка плана. Этот документ предназначен для последующего исполнения Luna, включая настоящий deployment и генерацию данных.
- Прочитать `AGENTS.md`, текущий diff и `.superpowers/sdd/2026-09-05-offline-production-luna/progress.md`, если существует. Сохранить все пользовательские изменения. Не начинать с устаревшего `main` и не переносить только HEAD, теряя dirty worktree.
- Никогда не писать в FFE1 Begode. BLE и телеметрию эта работа не меняет. Физический телефон не использовать.
- Android `minSdk 26`; SQLite features не новее 3.19; FTS4. SQLDelight: `N.sqm` переводит `N.db` в `(N+1).db`, удаление колонок через rebuild таблицы.
- Сохранить текущую матрицу `RouteProfilePolicy` personal EV, включая законное использование bicycle/pedestrian costing в её ветках. Не возвращать GraphHopper и не подменять политику универсальным автомобильным/велосипедным маршрутом.
- UI-строки в `values/` и `values-ru/`; Compose Multiplatform не обрабатывает Android backslash escapes. Решения тестируются в component/pure code; native smoke проверяет реальный renderer/runtime.
- Нельзя отключать подпись, SHA, ограничение распаковки или требования совместимости ради прохождения тестов. Неполный пакет никогда не становится ready.
- Запрещены `docker compose down -v`, глобальный prune, удаление чужих сервисов/данных, публикация секретов. Покупка новых ресурсов требует отдельного решения пользователя.
- Один heavy build, измеренный ресурсный admission и резерв для действующих API/DB/voice. Огромная страна не считается мгновенной задачей. Нехватка ресурсов — честный blocker, не разрешение вызвать OOM.
- Полная РФ означает 100% принятой версионированной маски в поддерживаемой проекции. Исключения и несовпадения масок явно показывать; нельзя выдавать несколько городов за страну.
- В git не помещать PBF, пакеты, production `.env`, ключи, дампы DB. Только документы, код, небольшие тестовые fixtures.

## Порядок и рабочие артефакты

Исполнять задачи 1–19 последовательно; компоненты разделены собственными тестовыми gates. AGENTS требует свежего implementer, review и fix loop на задачу; пользователь выбрал модель **gpt-5.6-luna**, использовать её для исполнения. Перед дочерней задачей передавать конкретные принадлежащие ей файлы и предупреждать о чужих изменениях.

Ledger: `.superpowers/sdd/2026-09-05-offline-production-luna/progress.md`. Для каждой задачи: статус, файлы, реальные команды/exit codes/свежие test counts, review findings, исправления, commit или причина отсутствия commit. Старый ledger доставки пакетов не доказывает завершение production.

Реальные параметры VPS, абсолютный project directory, Compose project name, размеры и лимиты сохранять в локальном git-ignored `production-target.json` рядом с ledger. Значения получать измерением, не подставлять догадки. Production persistent root — отдельный `/srv/volty/offline-production`, если inventory не выявит уже действующее соответствующее хранилище; при существующем хранилище использовать его с документированной миграцией. Не менять владельца общего `/srv/volty` рекурсивно.

Обозначения путей в задачах (это точные префиксы, не новые Gradle-модули):

| Сокращение | Путь |
|---|---|
| B | `backend/src/main/kotlin/ru/sodovaya/volty/backend/` |
| BT | `backend/src/test/kotlin/ru/sodovaya/volty/backend/` |
| C | `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/` |
| CT | `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/` |
| A | `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/` |
| T | `tools/offline-navigation/` |

Новые Python production-модули — пакет `T/production/`, с `__init__.py`; тесты `T/production/tests/`, также с `__init__.py`. Общие dataclass и wire serialization живут в `production/models.py`, а не дублируются между worker и publisher. Новые Kotlin backend файлы группировать в `B/offline/`; общие wire DTO в `C/domain/navigation/region/OfflinePreparation.kt`, backend имеет зеркальные serialization fixtures, не зависимость от Compose.

## Общие проверки для каждой кодовой задачи

Сначала добавить конкретный отрицательный тест, запустить его и увидеть ожидаемое падение; затем реализация и зелёный запуск. Примеры ниже задают обязательные контракты, не ограничивают остальные реальные failure paths. Не добавлять тест, просто копирующий формулу реализации, вместо проверки наблюдаемого результата.

```powershell
# Из корня worktree; backend — отдельный Gradle build.
.\gradlew.bat -p backend test --no-build-cache --rerun-tasks
.\gradlew.bat :composeApp:testDebugUnitTest --no-build-cache --rerun-tasks
python -m unittest discover -s tools/offline-navigation -p 'test_*.py' -v
```

Перед **каждым commit** обязательна полная `:composeApp:testDebugUnitTest` по AGENTS. После изменения SQLDelight также `:composeApp:verifyCommonMainVoltyDatabaseMigration`. До запуска убрать только известный test-results directory соответствующей проверки, предварительно проверив абсолютный путь внутри worktree; сверить количество fresh XML tests с обнаруженными testcase и сохранить baseline+delta. Не считать cached `BUILD SUCCESSFUL in 1s` доказательством. Настоящий mutation sweep требует существующего аудированного harness, свежего bytecode nonce и точного test count; если harness отсутствует, не изобретать фиктивное доказательство mutation coverage.

Для Python контрактов ниже рабочая директория `tools/offline-navigation`; команда одной задачи, например `python -m unittest production.tests.test_grid -v`. Если локально нужной среды нет, использовать ограниченный тестовый контейнер без production volumes. PostgreSQL интеграции идут с отдельной временной DB, никогда с production schema. Code-step завершается review исправлений и staging только принадлежащих задаче файлов/фрагментов, без `git add .`.

### Task 1: Инвентаризация VPS и воспроизводимый baseline

**Files:** Create ledger `progress.md`, ignored `production-target.json`, `docs/superpowers/sdd/2026-09-05-offline-production-report.md`; read `deploy.sh`, `docker-compose.yml`, `.env.example`, `backend/OFFLINE.md`, предыдущий offline report.

**Interfaces:** Produces inventory: `sshTarget`, `port`, `projectDirectory`, `composeProject`, `publicOrigin`, service names, image IDs, data paths, available CPU/RAM/disk, current keyId/public key, backup location. Секретные значения в report не включать.

- [ ] Сохранить `git status --short` и baseline diff в ignored ledger area. Записать, что предшествующая работа реализует delivery, а не производство артефактов.
- [ ] Проверить SSH и read-only inventory:

```powershell
ssh -o BatchMode=yes -p 22 sodovaya@mc.sodove.ru "hostname; uname -a; nproc; free -b; df -B1; docker compose ls; docker ps --format '{{.Names}} {{.Image}} {{.Ports}}'"
```

- [ ] Найти текущий deployment через labels `com.docker.compose.project.working_dir` и mounted paths. Читать только необходимые nginx/Compose/config поля; не печатать `.env`, `docker inspect` с полным Env или приватные ключи. Проверить домен `volty.sodove.ru` и существующие TLS/voice/DB health.
- [ ] Записать тестовый baseline общими командами. Выделить существующие failures отдельно от новых. Сделать PostgreSQL backup и защищённую копию configs/ключей перед дальнейшим изменением VPS; восстановление будет проверено в задаче 14.
- [ ] Gate: реальный SSH, deployment directory и ресурсный inventory установлены. При отсутствии доступа продолжать локальную реализацию; production не объявлять готовым. Не обходить host-key mismatch без установления причины.

### Task 2: Каноническая география и размеры region spec

**Files:** Create `T/production/models.py`, `grid.py`, `coverage.py`, `tests/test_grid.py`, `tests/test_coverage.py`.

**Interfaces:** `cell_id(lat: float, lon: float) -> str`; `cell_bounds(region_id: str) -> tuple[float,float,float,float]` в порядке W,S,E,N; `split_cell(region_id: str) -> list[str]`; `uncovered(required, actual) -> geometry`. `RegionSpec`: `id`, `gridVersion`, `logicalGeometry`, `routingGeometry`, `purpose`, `recipeVersion`, `specHash`. JSON schema этих полей сохранить в `T/production/contracts/region-spec.schema.json`.

- [ ] Добавить тест единственного владельца границы, wrap ±180 и дочерних клеток:

```python
def test_boundary_has_one_owner(self):
    self.assertEqual(cell_id(0.0, 0.0), 'g1-090-180')
    self.assertEqual(cell_id(0.0, 180.0), cell_id(0.0, -180.0))
    self.assertEqual(split_cell('g1-090-180'),
        ['g1-090-180-q0', 'g1-090-180-q1',
         'g1-090-180-q2', 'g1-090-180-q3'])
```

- [ ] Запустить `python -m unittest production.tests.test_grid production.tests.test_coverage -v`, убедиться в падении до реализации.
- [ ] Реализовать проверку finite lat/lon, диапазона широты, нормализацию долготы и полуоткрытые клетки. +90 и Mercator-предел возвращают unsupported, не индекс 180. Quadtree depth≤4; геометрии antimeridian хранить раздельными частями.

```python
latitude_index = math.floor(lat + 90.0)
longitude_index = math.floor(((lon + 180.0) % 360.0))
return f'g1-{latitude_index:03d}-{longitude_index:03d}'
```

- [ ] Реализовать 20km geodesic buffer, polygon intersection/difference и детерминированную canonical serialization. Зафиксировать геобиблиотеку после проверки официальной документации; расчёт buffer в градусах запрещён. Тестировать остров, дыру полигона, эксклав, переход дат и недостающий соседний buffer.
- [ ] Gate: union дочерних logical geometries равен parent без дыр; routing coverage не выводится из logical bbox без реального source coverage. Зелёные тесты, review, commit по общим правилам.

### Task 3: Автоматические исходники, snapshots и source cache

**Files:** Create `T/production/sources.py`, `source_cache.py`, `contracts/source-snapshot.schema.json`, `tests/test_sources.py`.

**Interfaces:** `select_sources(required_geometry, index) -> list[str]`; `fetch_snapshot(source_id, cache_root) -> SourceSnapshot`. `SourceSnapshot`: `sourceId`, `url`, `sha256`, `sizeBytes`, `osmTimestamp`, nullable `replicationSequence`, `geometryHash`, `fetchedAt`. `SourceSnapshotSet`: отсортированный список snapshot и его content hash. Никакой выдуманной общей sequence у нескольких sources.

- [ ] Тестировать source selector fixture с двумя соседними странами: logical cell внутри первой, routing buffer пересекает вторую; один источник не должен быть признан достаточным. Тестовый сервер прерывает скачивание, меняет ETag и возвращает redirect на private IP: restart корректный, private URL отклонён.

```python
def test_no_internal_download_url(self):
    index = {'features': [{'properties': {'id': 'x', 'urls':
             {'pbf-internal': 'https://internal.example/x.pbf'}}}]}
    with self.assertRaises(ValueError):
        public_pbf_url(index, 'x')
```

- [ ] Определить `public_pbf_url(index: dict, source_id: str) -> str`; реализовать parsing публичного index, выбор покрывающего parent/соседей и source SHA+osmium metadata. Сохранять сам index и hash его геометрий. Использовать только `urls.pbf`/`urls.updates`, не внутренние URL.
- [ ] Загрузка в `.partial` с дисковым admission, connect/read timeout, ограниченными retries, Range/If-Range и проверкой identity; SHA завершённого файла вычислять независимо от upstream MD5. URL сервер выбирает из индексированного allowlist; каждый redirect заново проверяется, private/link-local адреса запрещены.
- [ ] Проверять согласованность timestamps в source set; при несовместимых snapshots выбрать покрывающий общий источник либо ждать aligned snapshots. Не принимать молча разные даты. Cache immutable; active jobs pin sources; dry-run GC перечисляет кандидатов без удаления.
- [ ] Gate: `python -m unittest production.tests.test_sources -v`; один небольшой реальный публичный PBF скачан, hash/metadata сохранены, повторный fetch использует cache. Большую Россию до admission не скачивать.

### Task 4: Durable registry, leases и fencing

**Files:** Create `B/offline/OfflineRegistry.kt`, `OfflineJobRepository.kt`, `OfflineRegistryMigration.kt`; Test `BT/offline/OfflineJobRepositoryTest.kt`; Modify `B/Database.kt`.

**Interfaces:** Таблицы `offline_regions`, `offline_sources`, `offline_jobs`, `offline_releases`, `offline_requests`, `offline_catalog_generations`, `offline_schedule_runs`. Job уникален по `(spec_hash, source_set_hash, recipe_hash)`; `claim(workerId, now, leaseSeconds)` возвращает `JobLease(jobId, token, expiresAt, input)`; `heartbeat(jobId, token, now)` и `finish(jobId, token, result)` отклоняют старый token. SQL state/time authoritative в PostgreSQL.

- [ ] Red integration: два соединения одновременно claim одну job, только одно получает lease; после expiry новый token, поздний finish старого worker не публикует release.

```sql
SELECT id FROM offline_jobs
WHERE state IN ('queued','retry_wait') AND next_attempt_at <= now()
ORDER BY priority DESC, created_at
FOR UPDATE SKIP LOCKED LIMIT 1;
```

- [ ] Реализовать claim+смену token в одной транзакции, bounded attempts/backoff, durable stages/progress, dedup повторного запроса; рестарт восстанавливает expired leases. Progress monotonic внутри attempt, новый attempt явно виден клиенту.
- [ ] Миграции idempotent под lock, expand-only для первого cutover; сервер предыдущей версии продолжает читать старые таблицы. Не добавлять разрушающую миграцию в startup.
- [ ] Gate: ` .\gradlew.bat -p backend test --tests '*OfflineJobRepositoryTest' --no-build-cache --rerun-tasks`; integration с настоящим PostgreSQL и рестартом процесса, не один mock repository. Проверить дедуп и stale publish CAS.

### Task 5: Ресурсно ограниченный pipeline

**Files:** Create `T/production/build_runner.py`, `resource_budget.py`, `tests/test_build_runner.py`; Modify `T/build-package.sh`, `build-manifest.py`, существующие tools Dockerfiles.

**Interfaces:** `admit_build(available, reserve, estimate) -> Admission(allowed, reason)`; `run_build(spec: RegionSpec, sources: SourceSnapshotSet, workdir, budget) -> BuildOutput`. `BuildOutput` содержит пути artifacts, фактическую coverage/capabilities, SHA/size, peaks и elapsed; приватного ключа не содержит.

- [ ] Red: при свободных 8GiB, резерве 4GiB и оценке 6GiB build не запускает ни одного subprocess. Повтор запуска после crash использует отдельный attempt directory и не считает partial готовым.

```python
def test_admission_preserves_live_services(self):
    decision = admit_build({'ram': 8}, {'ram': 4}, {'ram': 6})
    self.assertFalse(decision.allowed)
```

- [ ] Реализовать запуск argv без shell interpolation пользовательского ввода; pinned images заранее build/pull один раз. Worker единственный владеет правом запускать Docker; публичный app не получает socket. Передать cgroup CPU/RAM/pids limits, пониженный I/O priority, hard runtime timeout, dedicated temp volume на том же filesystem, что staging. Измерять disk до и между стадиями; exhaustion завершает attempt, сохраняя live services.

```python
subprocess.run(['docker', 'run', '--rm', '--memory', budget.memory,
                '--cpus', budget.cpus, '--pids-limit', '512',
                '--mount', work_mount, budget.image, *build_argv],
               check=True, timeout=budget.timeout_seconds)
```

- [ ] Убрать EKB defaults из production entrypoint: bbox/region/version/source metadata обязательны; PMTiles/MBTiles center вычислять из региона. Cache timezone/admin sources с version/hash; не rebuild всего source admin DB для каждого leaf без причины. Иерархические PBF chunks позволяют не сканировать страну с нуля на каждый leaf. Проверить необходимость старого host-network helper и заменить ограниченной загрузкой кешируемых данных.
- [ ] Gate: `python -m unittest production.tests.test_build_runner -v`; реальная небольшая сборка в контейнере, measured peak RAM/temp/output и отсутствие ключа в окружении worker. Ресурсы VPS пока не достаточны для полного bootstrap — измерение продолжается в задаче 16.

### Task 6: Schema 3, sparse data и международные map assets

**Files:** Create `T/production/contracts/manifest-v3.schema.json`, `manifest_v3.py`, `map_assets.py`, `tests/test_manifest_v3.py`; Modify `T/build-manifest.py`, `package_validation.py`, `sign-manifest.py`; Modify `C/domain/navigation/region/OfflineRegionPackageManifest.kt`; Create `CT/domain/navigation/region/OfflineManifestV3Test.kt`.

**Interfaces:** Schema3: `schemaVersion`, `regionId`, `releaseVersion`, `minAppVersionCode`, `sourceSnapshotSet`, `logicalCoverage`, per-component `coverage`, `capabilities`, `artifacts`, `keyId`, `signature`. Artifact entry: type, HTTPS URL, size, SHA, installed limit. `routing`/`search` optional только при соответствующем false capability и проверенном отсутствии данных; map-assets содержит glyphs/style support и свои hashes. Codec3 общий по golden bytes между Python/Kotlin; adapter2 выполняется после проверки подписи codec2.

- [ ] Red: валидная signature2 после adapter сохраняет смысл; перестановка/подмена подписанного поля3 не проходит. Manifest с `hasRouting=true` без routing artifact отвергается; реальная территория без дорог публикуется с false, без фиктивных адресов.

```python
def test_claimed_routing_requires_artifact(self):
    manifest = minimal_manifest_v3(has_routing=True, artifacts={})
    with self.assertRaises(ValueError):
        validate_manifest_v3(manifest)
```

`minimal_manifest_v3` — test fixture factory в `tests/test_manifest_v3.py`; `validate_manifest_v3(manifest: dict) -> None` — production interface.

- [ ] Реализовать строгий codec3, golden signed fixtures, legacy adapter; version gate не позволяет старому клиенту получить несовместимый manifest как schema2. Архив map-assets защищён теми же traversal/expansion/symlink лимитами, что routing.
- [ ] Генерировать необходимые glyph ranges для фактических script labels региона из лицензируемого pinned font source. Проверить лицензию распространения и включить notices, OSM attribution/ODbL и происхождение данных. Не скачивать public tile server для массовой offline выгрузки. Проверить грузинские и японские подписи, а не только латиницу.
- [ ] Gate: Python codec/validator tests и Kotlin `*OfflineManifestV3Test`; golden bytes/hash совпадают между языками, schema2 fixtures остались валидными.

### Task 7: Publisher и собственный artifact origin

**Files:** Create `T/production/publisher.py`, `catalog_pages.py`, `tests/test_publisher.py`; Modify `T/package_cache.py`, `package-service.py`, `package_validation.py`, `build-catalog.py`.

**Interfaces:** `publish(job_id, lease_token, build_output) -> PublishedRelease`; `PublishedRelease`: immutable `releaseId`, `manifestUrl`, `manifestSha256`, `generation`. Local producer mode получает проверенный build output; relay mode остаётся отдельным. `GET /offline/v3/generations/{generation}/pages/{page}.json`, root подписан и перечисляет hashes страниц. Page≤100 entries и≤1MiB; root не содержит неограниченный список миллионов страниц — при необходимости дерево индексов с теми же bounds.

- [ ] Red: crash перед current switch оставляет предыдущую generation доступной; повтор идентичного publish idempotent; те же release URL с другими bytes отвергаются. Поздний lease не меняет current.

```python
def test_old_job_cannot_replace_current(self):
    current = self.publisher.current_generation()
    with self.assertRaises(StaleLease):
        self.publisher.publish(self.expired_job, self.expired_token, self.output)
    self.assertEqual(self.publisher.current_generation(), current)
```

`StaleLease` — ошибка из `production/models.py`; fixture publisher использует настоящий временный filesystem и test registry.

- [ ] Один filesystem writer: validate → write immutable artifacts → fsync → подписать manifest → построить полный generation → CAS desired version → atomic current switch. Разрыв DB/filesystem устраняется reconciliation по immutable committed generation marker; startup не активирует неполную транзакцию. Ключ только у publisher, файл mode0600 вне git, mounted read-only.
- [ ] Release identity учитывает source/recipe и фактические artifact hashes: недетерминированные байты получают новый URL. Подпись нового каталога не переписывает старые объекты. Legacy catalog оставить bounded bootstrap; не подсовывать v3 старым клиентам.
- [ ] Gate: `python -m unittest production.tests.test_publisher -v` плюс существующий `test_package_service`; public Range/ETag/If-Range/SHA и restart reconciliation. Origin создаётся этим publisher, никаких обязательных upstream URL на собственный ещё не существующий каталог.

### Task 8: Public discovery, build requests и abuse limits

**Files:** Create `B/offline/OfflinePreparationRoutes.kt`, `OfflinePreparationModels.kt`, `OfflineSessionRepository.kt`, `OfflineRequestPolicy.kt`; Test `BT/offline/OfflinePreparationRoutesTest.kt`; Modify `B/Application.kt`, `B/OfflineRegionRoutes.kt`, `backend/API.md`.

**Interfaces:**

```text
POST /v1/offline/sessions                      -> installation token, expiresAt
POST /v1/offline/regions/resolve               -> ready | preparable | unsupported
POST /v1/offline/requests {specId, purpose}    -> 202 {requestId, statusUrl}
GET  /v1/offline/requests/{requestId}          -> stage, attempt, bytes?, readyRelease?, retryAt?, reason?
```

Resolve body: `lat`, `lon`, optional bounded route points, `purpose` (`map`/`route`), supported schema/runtime versions. `specId` server-generated content hash из Task2; сервер хранит canonical spec, requests не принимает произвольный bbox. Ready содержит signed manifest URL/hash, не сырые доверенные данные для обхода подписи.

- [ ] Red HTTP integration: resolve missing location не создаёт job; два POST создают одну job; чужой request status не раскрывает session; oversized route request отвергается до build.

```kotlin
// В testApplication: fixture содержит fake registry со счётчиком issued jobs.
assertEquals(0, registry.issuedJobCount)
assertEquals(HttpStatusCode.Accepted, first.status)
assertEquals(HttpStatusCode.Accepted, repeated.status)
assertEquals(1, registry.issuedJobCount)
```

- [ ] Реализовать durable installation session, expiry и token hash storage; rate limits session+trusted peer+global. Начальные caps: 2 pending specs/session, 10 новых specs/day/session, глобально 100 pending interactive jobs; административно регулируются после измерений. Shared-NAT лимиты не должны запрещать только потому, что все запросы пришли через nginx.
- [ ] Доверять forwarded IP только от настроенного nginx peer; edge перезаписывает header. Не доверять произвольному XFF. Ограничить body, points≤32, route bundle≤16 base cells и measured package cap; 429+Retry-After, понятные resource_wait/unsupported причины. Raw coordinates не писать в долговременные logs. Session не считать Sybil-proof; абсолютные CPU/disk/queue бюджеты обязательны.
- [ ] Gate: targeted `*OfflinePreparationRoutesTest`; старый allowlist gateway по-прежнему закрывает admin/upload/private paths; tests spoofed XFF, public admin denied и quota bypass after session rotation.

### Task 9: Постоянный worker и scheduler control plane

**Files:** Create `T/production/worker.py`, `registry_client.py`, `tests/test_worker.py`; Create `B/offline/OfflineWorkerRoutes.kt`, `BT/offline/OfflineWorkerRoutesTest.kt`; Modify `B/Application.kt`.

**Interfaces:** Private `/internal/offline/jobs/claim`, `/heartbeat`, `/stage`, `/finish`; отдельный credential, только loopback/private network. Worker использует Task4 leases, Task3 sources, Task5 build и Task7 publisher; private result содержит server-owned attempt reference, не произвольный путь вне staging.

- [ ] Red: процесс падает после extraction; после expiry новая попытка продолжает/повторяет валидные стадии, старый процесс не публикует результат. Lost heartbeat прерывает работу до публикации.

```python
def test_lost_lease_prevents_publish(self):
    self.registry.reject_heartbeat = True
    self.worker.run_once()
    self.assertEqual(self.publisher.publish_calls, [])
```

- [ ] Реализовать `Worker.run_once() -> bool`, graceful SIGTERM, bounded subprocess cancellation, leases с heartbeat каждые20s и expiry120s; проверять lease непосредственно перед finish. Backoff transient failures, permanent geometry/capability errors не зацикливать.
- [ ] Реализовать resource_wait без сжигания retry budget; interactive выше background, после пяти interactive дать очередь background. Crash/reconciliation не зависит от активности агента Codex.
- [ ] Gate: `python -m unittest production.tests.test_worker -v` и private route tests; реальная отдельная test-job переживает kill worker и restart, production API/voice не перезапускать ради этого теста.

### Task 10: Android discovery и ожидание ещё не существующей карты

**Files:** Create `C/domain/navigation/region/OfflinePreparation.kt`, `C/data/navigation/HttpOfflinePreparation.kt`, `CT/data/navigation/HttpOfflinePreparationTest.kt`; Modify `C/domain/navigation/region/OfflineFirstNavigationRepository.kt`, `OfflineRegionPackageState.kt`, `C/data/navigation/HttpOfflineRegionAcquisition.kt`, `A/data/navigation/offline/AndroidOfflineRegionPackageRepository.kt`; Test соответствующие существующие common tests.

**Interfaces:** `OfflinePreparationRepository.resolve(query): Resolution`, `request(specId): PreparationRequest`, `status(requestId): PreparationStatus`. Status соответствует Task8; `PreparationStatus.Ready` переводится в обычный manifest verification/install, никогда напрямую в Installed. Preparation state отделён от download progress, которому сейчас нужен latestRelease.

- [ ] Red component: отсутствует регион в локальном каталоге, backend ready ещё нет; клиент выдаёт request, показывает queued, переживает recreation, затем скачивает опубликованный manifest. Assert на реально issued resolve/request/download, не только на UI state.

```kotlin
assertEquals(listOf(expectedSpecId), preparation.issuedRequests)
assertTrue(downloads.issued.isEmpty()) // preparing, manifest ещё отсутствует
preparation.publishReady(signedRelease)
assertEquals(listOf(signedRelease.manifestUrl), downloads.issued)
```

- [ ] Реализовать discovery при missing coordinates вместо немедленного fallback к отключённому online backend. Пагинация bounded и подписанная, ключи/generation/hash проверяются. Сохранить requestId/session/spec на диске, использовать существующую persistence абстракцию либо отдельный atomic JSON; не добавлять SQLDelight migration без необходимости.
- [ ] Polling cancellable с bounded backoff/Retry-After, короткие HTTP calls. После ожидания повторить metered/WiFi/storage/minVersion проверки; не начать гигабайтную загрузку через мобильную сеть по старому согласию. Expired session восстанавливает разрешённый status по spec без дублирования job.
- [ ] Gate: targeted common tests с fake clock без unbounded runTest loop, process recreation tests; legacy installed region остаётся usable при недоступном discovery.

### Task 11: Международная offline карта и route coverage

**Files:** Modify `A/data/navigation/offline/AndroidOfflinePmtilesTileServer.kt`, `AndroidOfflineRegionPackageStore.kt`, `AndroidOfflineValhallaRuntime.kt`, `AndroidOfflineMapSource.kt`; Create `C/domain/navigation/region/OfflineRouteCoveragePolicy.kt`, `CT/domain/navigation/region/OfflineRouteCoveragePolicyTest.kt`; Modify `C/domain/navigation/region/OfflineRegionAccessPolicy.kt` и manifest/download/activation policies, затронутые schema3.

**Interfaces:** `OfflineRouteCoveragePolicy.select(points, installedManifests)` возвращает `UseInstalled(releaseId)`, `PrepareBundle(specId)` после server resolve, либо `Unsupported(reason)`. Source glyph lookup сначала installed assets, затем встроенные допустимые ranges; только разрешённые font stacks/paths.

- [ ] Red: endpoints формально в соседних cell bbox, но маршрут пересекает непокрытый участок — installed eligibility не выводится лишь из endpoints. Проверить route buffer, hole и antimeridian; окончательный native route обязан оставаться в заявленной доступной routing coverage.

```kotlin
assertFalse(policy.canUse(incompleteRoutingCoverage, requestedCorridor))
assertTrue(policy.canUse(completeRoutingCoverage, requestedCorridor))
```

`canUse(coverage: CoverageGeometry, corridor: CoverageGeometry): Boolean` — дополнительный pure API policy, `CoverageGeometry` — нормализованная геометрия manifest3.

- [ ] Для границ использовать routing buffer; когда одного runtime dataset недостаточно, server resolve создаёт bounded route bundle из canonical cell set, pipeline строит единый Valhalla dataset. Native runtime не умеет автоматически склеивать независимые TAR — не считать наличие двух пакетов доказательством маршрута. Большой corridor отвергается с причиной/предложением меньших участков, не возвращает склеенные polylines.
- [ ] Реализовать map-assets store/loopback paths с безопасной распаковкой, MIME и path allowlist. Проверить пустые search/routing capabilities и user-facing reason; не фабриковать адрес/маршрут. Сохранить RouteProfilePolicy matrix regression tests.
- [ ] Gate: common policy tests и native emulator: Япония/Грузия с отключённой сетью показывают читаемые подписи, поиск и маршрут при наличии данных, переход cell/country в реальной coverage. Без emulator/native доказательства отметить gate незавершённым.

### Task 12: Понятный UI и автообновления клиента

**Files:** Modify `C/presentation/settings/SettingsComponent.kt`, `SettingsScreen.kt`, `C/presentation/navigation/LightNavigationComponent.kt`, соответствующие существующие navigation views; обе `composeApp/src/commonMain/composeResources/values/strings.xml` и `values-ru/strings.xml`; Create `C/domain/navigation/region/OfflineUpdatePolicy.kt`, `CT/domain/navigation/region/OfflineUpdatePolicyTest.kt`.

**Interfaces:** `OfflineUpdatePolicy.decide(installed, offered, network, freeBytes, activeRoute)` → keep/download/deferActivation/requiresConsent. Настройка автообновлений и WiFi-only сохраняется; background execution использует существующий Android lifecycle/scheduler, а если его нет — ограниченную Android background job, с проверенной совместимостью зависимостей.

- [ ] Red: обновление скачано при активном маршруте — текущий runtime не заменён; после завершения маршрута новая версия активируется атомарно. Смена WiFi→metered во время подготовки требует повторного policy decision.

```kotlin
assertEquals(UpdateDecision.DeferActivation, decisionForActiveRoute)
assertEquals(oldRelease, runtime.activeRelease)
```

- [ ] Показать стадии «В очереди», «Загружаем исходные данные», «Готовим карту», «Проверяем», «Скачиваем», «Доступна офлайн», возраст данных, размер, причину ожидания/ошибки и retry. Не показывать процент, если denominator неизвестен; не требовать login для обычной карты.
- [ ] Обновление приложения включить в deliverable задачи18; здесь обновляются **данные карты**, а не обещается магазинная публикация APK. Проверка предложений обновления bounded, не скачивает весь мировой каталог.
- [ ] Gate: component/policy tests и emulator screenshots для human QA, screenshots не выдавать за unit tests. Старый пакет сохраняется при checksum failure, недостаточном месте и рестарте установки.

### Task 13: Автоматическое обновление исходников и пакетов

**Files:** Create `T/production/scheduler.py`, `tests/test_scheduler.py`; Modify registry из Task4 и worker из Task9.

**Interfaces:** `Scheduler.tick(now) -> list[jobId]`, durable unique `(scheduleName, periodStart, regionId, desiredFingerprint)`; `desiredFingerprint = hash(spec + sourceSet + recipe)`. Настройки: metadata ежедневно03:00UTC+jitter≤30min; pinned RU и foreign used≤30days еженедельно Sunday02:00UTC; запрос старше14days ставит refresh, сразу возвращая рабочую версию.

- [ ] Red: два scheduler процесса и restart в одном period создают одну job; одинаковый fingerprint не создаёт rebuild; старый job не активируется после более нового desired generation.

```python
def test_tick_is_idempotent(self):
    self.scheduler.tick(self.sunday)
    first = self.registry.job_ids()
    self.scheduler.tick(self.sunday)
    self.assertEqual(self.registry.job_ids(), first)
```

- [ ] Реализовать полную замену snapshots первой production-версией; `.osc` replication отложить до отдельного доказательства непрерывной chain. Scheduler реально fetches metadata, сравнивает source/recipe и запускает build/publish, не ограничивается refresh каталога.
- [ ] Сохранить pinned RU inventory, usedAt foreign и fair scheduling; длительность weekly rebuild должна измеряться. Если весь цикл дольше недели, показать backlog/age и capacity blocker, не обещать гарантированную недельную свежесть.
- [ ] Gate: `python -m unittest production.tests.test_scheduler -v`; реальный controlled source/recipe revision проходит полный replacement, old ready обслуживается в течение build, subsequent unchanged tick — no-op.

### Task 14: Retention, backup, restore и наблюдаемость

**Files:** Create `T/production/retention.py`, `tests/test_retention.py`, `T/ops/backup.sh`, `restore-check.sh`, `status.sh`, `rollback.sh`; Create `docs/offline-production-runbook.md`.

**Interfaces:** `retention_candidates(now, registry) -> list[ArtifactRef]` — только не pinned, не leased/in-flight, retired≥7days, с сохранением≥2 good versions; foreign idle90days retires. `rollback.sh --generation N` создаёт **новый** signed generation с release refs из N, не переписывает старый N. Все ops scripts получают явный config path и работают только в verified production root.

- [ ] Red: RU pinned никогда не кандидат; foreign99days idle, retired только вчера, тоже не кандидат; download lease сохраняет старый release.

```python
def test_retention_keeps_pinned_and_grace(self):
    candidates = retention_candidates(self.now, self.registry)
    self.assertNotIn(self.ru_release, candidates)
    self.assertNotIn(self.retired_yesterday, candidates)
```

- [ ] Добавить bounded metrics: queue/oldest wait/stage failures, source and package age, ready/required coverage, build CPU/RAM/temp peaks, free disk, API latency/error rate; regionId не превращать в миллионы metric labels. Health различает API healthy, builder resource_wait, source stale. Alerts только в уже доступный операторский канал без автоматической отправки новых сообщений/регистрации сервисов.
- [ ] Backup DB/registry/catalog/config/signing key encrypted, mode0600; реальное restore в отдельный temp root и DB, затем signature/public URLs mapping verify. Подписывающий ключ не печатать и не добавлять в отчёт. При отсутствии внешнего backup target сохранить локальную защищённую копию и явно указать, что она не защищает от потери VPS; не называть disaster recovery проверенным без независимой копии.
- [ ] Gate: Python retention tests; реальное restore-check, rollback metadata generation test, checksum повреждения backup выявляется. Filesystem GC dry-run → review списка → удаление только proven candidates; нет глобального Docker cleanup.

### Task 15: Production deployment на существующий VPS

**Files:** Modify `docker-compose.yml`, `deploy.sh`, `deploy-offline.sh`, `.env.example`, `T/Dockerfile.service`; Create `T/production/Dockerfile.worker`, `T/ops/deploy-production.sh`, `T/ops/nginx-offline.conf`.

**Interfaces:** Compose producer services: `offline` publisher/delivery, `offline-worker`, `offline-scheduler`; app использует private manager URL. Source/staging/published/secrets volumes разделены, app published read-only; worker имеет только необходимые source/staging и отдельный Docker runner permission. Секрет signer не доступен worker/app; private API не публикуется в internet.

- [ ] Проверить текущие mounted paths/service names из Task1. Подготовить deployment bundle из фактического worktree и pinned images; включить необходимые dirty source changes после review, не секреты/build outputs. Проверить `docker compose config --quiet` без печати resolved secrets.
- [ ] Изменить deploy на scoped service update; не применять `--remove-orphans` ко всему существующему project. Staging на другом loopback port, isolated DB и volumes. Подтвердить schema compatibility и tested image rollback до cutover.

```bash
docker compose config --quiet
docker compose up -d --no-deps offline offline-worker offline-scheduler
# Обновление app — отдельный шаг после readiness и schema gate.
docker compose up -d --no-deps app
```

- [ ] nginx: preserve Range/If-Range/ETag/Content-Range, без gzip immutable binary artifacts, timeout API короткий, jobs asynchronous; staging/private/signing paths deny. Проверить TLS/public origin, CORS только по реальной потребности. Применять nginx reload только после `nginx -t`.
- [ ] Gate: private порт недоступен извне, API/DB/voice health до/после совпадает, restart worker не ломает API; producer mode не зависит от несуществующего внешнего каталога. При существующем signing key не ротировать молча: сначала соответствие ключа установленному клиенту и доверенный migration path.

### Task 16: Измеренный пилот и полный bootstrap России

**Files:** Create `T/production/bootstrap.py`, `tests/test_bootstrap.py`, `T/ops/coverage-report.py`; Create `docs/superpowers/sdd/2026-09-05-offline-capacity-report.md`.

**Interfaces:** `python -m production.bootstrap plan --source-id russia --output inventory.json`; `... enqueue --inventory inventory.json`; `... status --inventory inventory.json --json`; `coverage-report.py --inventory inventory.json --output coverage.json`. Inventory immutable с mask hash, source set, leaves и split lineage; registry proof связывает каждый leaf с проверенным published release/capability.

- [ ] Red: missing leaf площадью малой доли процента даёт incomplete, а не округлённые100%; oversize parent становится ready coverage только после публикации всех необходимых children.

```python
def test_one_missing_leaf_blocks_completion(self):
    report = self.bootstrap.coverage_report(self.inventory)
    self.assertGreater(report.missing_leaf_count, 0)
    self.assertFalse(report.complete)
```

- [ ] На VPS построить реальные пилоты: плотный город, обычная местность, sparse/empty, пограничная клетка. Установить CPU/RAM/I/O/reserve/disk budgets по measured peaks и работающим live services. Проверить package targets512MiB/hard1GiB/installed4GiB; oversize split до depth4, на пределе explicit failure.
- [ ] Оценить полный disk: sources+chunks+temporary peak+все pinned releases+две good versions+backup+reserve. Подготовить честный прогноз времени/свежести. Если бюджет не проходит, уменьшать память pipeline/chunking в рамках формата; не переносить сборку на homeserver и не закупать VPS автоматически. Не снижать coverage, выдавая это за успех.
- [ ] Запустить durable inventory bootstrap всех leaf принятой маски России; ждать фактических ready, приоритет foreign interactive работает параллельно очереди, но heavy slot один. Периодически обновлять пользователя по изменившимся counts, не busy-poll. После restart worker инвентарь продолжается, не начинает всё заново.
- [ ] Gate: zero missing required supported geometry и zero failed required leaves, actual hashes/URLs для всех records; polygon difference и per-record validation сохранены. Unsupported polar/source-mask участки перечислены отдельно и не скрыты округлением. Скрин списка городов не coverage proof.

### Task 17: Зарубежный cold path и end-to-end public delivery

**Files:** Create `T/ops/acceptance.py`, `T/production/tests/test_acceptance_contract.py`; Update production report.

**Interfaces:** `python tools/offline-navigation/ops/acceptance.py --origin https://volty.sodove.ru --mode cold-world --output report.json`. Скрипт использует публичные Task8 endpoints, проверяет signed manifests/hashes/ranges, записывает stage timestamps; не вызывает private build API.

- [ ] Добавить contract test: acceptance не засчитывает ready URL без фактической загрузки+signature/SHA; `queued` не pass.

```python
def test_queue_is_not_completion(self):
    self.assertFalse(is_completed({'stage': 'queued'}))
```

`is_completed(status: dict) -> bool` в acceptance проверяет ready и итоговые verified artifacts; позитивная ветка требует evidence, не только строку stage.

- [ ] Выбрать ранее отсутствующие регионы: Берлин52.52,13.405; Тбилиси41.7151,44.8271; Осака34.6937,135.5023; Кейптаун-33.9249,18.4241. До запроса доказать отсутствие release. Если уже есть — выбрать другую отсутствующую cell той же географической категории, не удалять пользовательские пакеты.
- [ ] По public API создать anonymous session, resolve/request, дождаться реальной сборки; проверить source provenance и отсутствие ручного запуска build. Скачать artifacts с обычного внешнего подключения, Range resume, проверить manifest signature и native consumption в Task18. Не обещать одинаковую скорость из всех сетей/стран; зафиксировать точки внешней проверки.
- [ ] Gate: четыре cold requests completed, повторный запрос cached, abusive request bounded, API остаётся responsive во время build; source download/build delays показаны клиенту честно.

### Task 18: Подписанный Android release и проверка без интернета

**Files:** Modify `composeApp/build.gradle.kts` только нужные version/config; существующий Android metadata/config; Update `docs/offline-production-runbook.md` и production report. APK — в release storage, не git.

**Interfaces:** Production flags:

```powershell
.\gradlew.bat :composeApp:assembleRelease -PvoltyProductionRelease=true `
  -PvoltyOfflineCatalogUrl=https://volty.sodove.ru/offline/catalog.json `
  -PvoltyOfflineManifestKeyId=$manifestKeyId `
  -PvoltyOfflineManifestPublicKey=$manifestPublicKey
```

`$manifestKeyId`/`$manifestPublicKey` читаются из проверенного production public metadata Task15; это публичные значения. Signing APK — существующим keystore, versionCode увеличить относительно реально установленной/выпущенной версии, не угадывать.

- [ ] Выполнить полную app/backend/Python suite, migration check при изменениях, собрать release. Проверить APK signer fingerprint и manifest production URL/key; никакого debug trust bypass.
- [ ] На emulator установить signed-compatible candidate: public discovery→cold request→скачивание→kill/relaunch→отключение сети→карта+международные glyphs+поиск реального адреса+native маршрут. Проверить routing buffer на границе клеток/страны, отсутствие данных и oversized corridor с честной причиной. Не заявлять native pass по unit mocks.
- [ ] Обновить уже installed region: предыдущая версия работает при подготовке, новая устанавливается атомарно, active route не меняется. Проверить app restart, corrupt download и WiFi→metered.
- [ ] Найти существующий канал обновления APK и разместить совместимый release там, если разрешён текущим deployment процессом. Если канала нет — versioned HTTPS APK+SHA+signer fingerprint на своём origin и инструкция установки; это не автоматическое магазинное обновление. Не заводить store account и не менять signing identity.
- [ ] Gate: реальный signed APK доступен пользователю, native offline сценарии доказаны и записаны. Отсутствие emulator или APK key — незавершённый gate, не «прод полностью готов».

### Task 19: Обновление, откат, финальная приёмка и передача

**Files:** Finalize `docs/offline-production-runbook.md`, `docs/superpowers/sdd/2026-09-05-offline-production-report.md`, ledger.

**Interfaces:** Итоговый report содержит actual deployment revision/image digests, public API/catalog/APK URLs, keyId/fingerprint, source snapshot ages, coverage mask hash/counts, measured build resources, queue/backlog, test/native evidence, restore/rollback results, operator commands.

- [ ] Выполнить один настоящий scheduler cycle с контролируемым новым source/recipe fingerprint и публикацией; затем no-op cycle. Проверить автоматический client update и сохранность старых immutable URL.
- [ ] Выполнить rollback одной тестовой production release через новую generation; API/legacy trust не ломается. Failure injection ограничить собственными тестовыми jobs/processes: worker kill, download interruption, stale lease, insufficient staging quota. Не reboot VPS и не останавливать DB/voice для демонстрации resilience.
- [ ] Проверить backup restore evidence, таймеры/daemon restart policies, source-age/queue metrics и retention dry-run. После отключения управляющей SSH-сессии worker/scheduler продолжают работу.
- [ ] Самостоятельно пройти всю таблицу gates ниже. Незаконченный full bootstrap, placeholder origin, отсутствие APK, непроверенный native runtime или заблокированная capacity означают **не завершено**. Писать конкретный blocker, уже работающие части и сохраняемый job state; не перекладывать «сгенерируй всё сам» на пользователя после заявления об успехе.
- [ ] Передать адреса и короткие команды status/refresh/rollback/restore. Сообщить отдельно пределы: source-data gaps, Mercator, лимит route bundles, фактическая периодичность обновления и защита backup от потери хоста. Не обещать бесконечную ёмкость одной машины.

## Финальная матрица покрытия требований

| Требование | Задачи | Доказательство |
|---|---|---|
| Вся production-сборка на существующем VPS | 1,5,15,16 | SSH inventory, process/resource metrics |
| Источники и artifacts появляются автоматически | 3,5,7,9 | source snapshots → signed immutable release |
| Полная принятая маска России | 2,16 | geometric difference + every leaf ready |
| Зарубежный запрос на лету | 8–11,17 | 4 real cold public API runs |
| Offline карта, адреса, personal EV route | 6,10–12,18 | signed APK native offline smoke |
| Нелатинские подписи и границы | 2,6,11,18 | Georgia/Japan glyphs, buffered/bundle route |
| Автообновление сервера и клиента | 12,13,19 | real replacement + no-op + safe activation |
| Безопасная публикация и совместимость | 4,6–8 | signature fixtures, lease fencing, crash recovery |
| Ресурсы, abuse limits, сохранность live services | 5,8,14–17 | rejected jobs, measured reserve, before/after health |
| Эксплуатация после завершения Codex | 9,13–15,19 | durable daemons, restore, runbook |

## Проверенные исходные источники

Исполнителю повторно проверить версии/условия перед загрузкой и фиксировать фактические metadata:

- [Geofabrik machine index](https://download.geofabrik.de/index-v1.json): публичные extract URLs и геометрии.
- [Россия — Geofabrik](https://download.geofabrik.de/russia.html): исходные OSM extracts; это не готовые Volty artifacts.
- [Osmium extract](https://docs.osmcode.org/osmium/latest/osmium-extract.html): polygon/batch extraction и стратегии сохранения объектов.
- [PMTiles cloud storage](https://docs.protomaps.com/pmtiles/cloud-storage): immutable archives и HTTP range delivery.

Историческое исправление: предыдущий offline-region-backend report описывал завершённую delivery-подсистему. Это не доказательство существования country coverage, build origin или production automation. Этот план закрывает именно эти пробелы.
