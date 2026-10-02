# Nearby Foundations Implementation Plan

Execution status 2026-10-03: research reports written, source and narrow evidence
reviews PASS. Strict SFU revoke NOT PROVEN (remove+TTL counterexample measured),
Android/device proof NOT MEASURED. Full Android suite blocked before tests by
SQLite native extraction; no commits/product edits. Task evidence and remaining
unmeasured matrix rows: source-audit/foundation-contracts/sfu-revocation-evidence
reports and `.superpowers/sdd/2026-10-03-nearby-foundations/progress.md`.
Checkboxes retain original steps; skipped device/matrix/commit checks are not
marked successful. Next ready-stage plan: `2026-10-03-nearby-voice-core.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. AGENTS.md уже задаёт этот метод; Astra не вызывать без новой просьбы пользователя.

**Goal:** Получить воспроизводимые решения для SFU-отзыва и Android-аудио, достаточные для подробных планов замены Nearby.

**Architecture:** Исследовательский этап перед production-изменениями. Сначала фиксируем реальные версии и точки управления media/routing, затем проверяем границы SFU-авторизации и формируем протокол диагностики Xiaomi. Backend-схема, новые серверы/каналы и миграции здесь не реализуются.

**Tech Stack:** Kotlin Multiplatform/Android, LiveKit Android 2.25.3, локальный AudioSwitch AAR, WebRTC из фактического dependency graph; self-hosted LiveKit из docker-compose.yml; Kotlin/JVM server SDK 0.15.0; Windows/PowerShell.

**Spec:** `docs/superpowers/specs/2026-10-03-nearby-voice-spaces-design.md`. Обязательно также читать `docs/superpowers/specs/2026-10-03-nearby-astra-review.md` и `AGENTS.md`.

## Global Constraints

- Android only; minSdk 26; targetSdk 36. Не менять версии SDK наугад.
- **Never write to FFE1.** BLE, telemetry protocols, alarms и navigation runtime вне этого этапа.
- PHONE microphone → headphones — обязательный режим; желаемое устройство не выдавать за фактическое.
- **Xiaomi не объявлять исправленным без разговора на реальном телефоне.** Название «17t pro/latest» — пользовательское сообщение, точный build фиксируется измерением.
- SFU revoke gate закрыть ДО подробного backend/SFU-плана, включая refreshed JWT и медиа до webhook.
- Повторного вызова Astra нет. SDD task reviewers используют модель текущей сессии, без Astra override.
- Не читать/печатать production secrets, не менять VPS, DNS, firewall, Firebase accounts и внешние данные.
- Временные probes содержатся в `.superpowers/sdd/2026-10-03-nearby-foundations/`; это git-ignored. Отчёты без секретов — в docs.
- Перед каждым commit: `.\gradlew.bat :composeApp:testDebugUnitTest`. Не выдавать Android suite за backend/media проверку.
- Mutation sweeps только через audited harness с fresh nonce, чистыми results и exact test count.
- No fake Compose tests; фоновые delayed loops явно отменять. Строки будущего UI в values и values-ru.

## Review Focus

1. Refreshed SFU JWT переживает remove — probe проверяет реальный publish/subscribe, а не только повторный HTTP join (Task 2).
2. Webhook ещё не обработан — отсутствует недоказанное обещание отсутствия медиа до hook (Task 2).
3. AudioSwitch меняет output при выборе input — отчёт прослеживает владение AudioRecord/AudioTrack и actual routed devices (Task 1).
4. Backend умер, но SFU жив — серверный claim/клиентский lease получают конечный исход, без вечного busy (Task 3).
5. Xiaomi без/с гарнитурой молчит в обе стороны — диагностика различает capture, publication, subscription, media transport и render (Task 3).

---

## Этапы всего проекта и границы текущего плана

| Этап | Результат | Когда можно планировать подробно |
|---|---|---|
| Foundations, этот план | Source audit, SFU evidence, audio/field protocol, согласованные контракты | Сейчас |
| Voice core / Android | Controller, typed engine events, audio routes, microphone/location service и диагностический APK | После source audit; недоказанный device route остаётся полевой проверкой |
| Communities backend | Миграция, servers/channels, claims/leases, realtime, sharing и v1 upgrade boundary | После доказательства revoke mechanism или явно согласованного ограничения |
| UX / channel ride | Мои серверы, каталог, каналы, общая панель, полный disconnect и карта | По зафиксированным client/server interfaces |
| Direct calls / push | Call lifecycle, FCM, incoming notification, accepted media, end/cancel | По claims и typed voice interfaces; provisioning нужен для field acceptance |

Это подробный план начального исследования, а не ложное обещание, что уже
известен весь audio adapter. Следующие подробные планы опираются на отчёты
этого этапа. Navigation sharing — отдельное необязательное расширение и не
входит в критический путь.

## Файлы и результаты

- Create: `docs/superpowers/specs/2026-10-03-nearby-source-audit.md` — фактические версии и source-backed точки управления.
- Create: `docs/superpowers/specs/2026-10-03-nearby-sfu-revocation-evidence.md` — probe protocol, observations и решение gate.
- Create: `docs/superpowers/specs/2026-10-03-nearby-foundation-contracts.md` — выбранные session/audio/lifecycle границы, device protocol и ready/blocked матрица следующих этапов.
- Create: `.superpowers/sdd/2026-10-03-nearby-foundations/progress.md` — dispatch, evidence, review и ограничения каждой задачи.
- Read only: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/social/AndroidLiveKitVoiceRoomEngine.kt`, `composeApp/build.gradle.kts`, `composeApp/libs/audioswitch-039a35aefab7747c557242fa216c9ea11743b604.aar`, `backend/build.gradle.kts`, `backend/src/main/kotlin/ru/sodovaya/volty/backend/Voice.kt`, `docker-compose.yml`, `livekit.yaml`.
- Modify: текущую voice-spaces spec только по доказанному уточнению с явной записью отзыва старой формулировки.

### Task 1: Source audit закреплённых media dependencies

**Ownership:** source-audit report и временные выгрузки sources. Не править product Kotlin, Gradle dependency versions или AudioSwitch AAR.

**Interfaces:** consumes фактический Gradle graph/AAR/deployment tags; produces отчёт с source URL или local hash+class, version, input/output hook, lifecycle owner, platform floor и границей неопределённости. Task 2/3 обязаны читать его.

- [ ] Step 1: Проверить git/worktree и старый ledger до dispatch; изолировать execution через using-git-worktrees, перенести только согласованные незакоммиченные docs без потери оригиналов. Записать base HEAD и источник каждого документа.

```powershell
git status --short
git rev-parse HEAD
git rev-parse --git-dir
git rev-parse --git-common-dir
Get-Content AGENTS.md
```

- [ ] Step 2: Выгрузить разрешённый dependency graph и хэш локального AAR. Команды read-only по product; сохранять вывод в ledger directory.

```powershell
.\gradlew.bat :composeApp:dependencies --configuration androidDebugRuntimeClasspath
Get-FileHash -Algorithm SHA256 -LiteralPath 'composeApp/libs/audioswitch-039a35aefab7747c557242fa216c9ea11743b604.aar'
rg -n 'livekit|audioswitch' composeApp/build.gradle.kts gradle/libs.versions.toml docker-compose.yml backend/build.gradle.kts
```

Если configuration name отсутствует, прочитать `:composeApp:resolvableConfigurations`
и повторить с существующим Android debug runtime configuration; не считать пустой
вывод подтверждением версии WebRTC.

- [ ] Step 3: Получить sources именно найденных версий из Gradle cache/Maven либо tag repositories. Если sources локального AAR недоступны, inspect classes.jar через `jar tf`/`javap -p -c` и фиксировать «bytecode inspected», не притворяться, что прочитан исходник. Не печатать credential stores.

```powershell
rg --files .gradle-user/caches | rg 'livekit|webrtc|audioswitch'
rg -n 'AudioOptions|AudioRecord|AudioTrack|JavaAudioDeviceModule|preferredDeviceList|audioHandler|setPreferredDevice|reconnect|RefreshToken' .superpowers/sdd/2026-10-03-nearby-foundations/sources
```

- [ ] Step 4: Прочитать целиком релевантные factories/callbacks, а не только grep hits. В отчёте заполнить таблицу конкретными findings:

| Проверка | Доказательство | Решение |
|---|---|---|
| Кто создаёт capture/render | version + file/class + точный метод | supported injection либо custom adapter boundary |
| Кто меняет speaker/BT/mode/focus | source path + call chain | единственный routing owner |
| Можно ли наблюдать actual input/output | существующий callback/API + min API | typed route report, не только desired selection |
| Что происходит при reconnect/mute | event+ordering из закреплённого SDK | controller vs SDK ownership |
| Что делает SFU с refreshed tokens | source commit + issuance/validation path | inputs для Task 2 |

Неподдержанная точка extension отмечается как unavailable с причиной; это
результат, не пропуск. Из неё выбирается ограниченный prototype boundary,
а не бессистемное изменение Speakerphone priorities.

- [ ] Step 5: Self-check: каждый положительный hook должен иметь конкретный
source/bytecode reference; сопоставить текущий PHONE→Speakerphone call chain.
Отметить отсутствие device proof отдельно. Fresh task reviewer проверяет
source citations и версии; исправить замечания тем же implementer.
- [ ] Step 6: Перед commit прогнать Android suite, проверить вывод, затем
коммитить только отчёт. При недоступной suite сохранить документ без commit
и сообщить ошибку, не обходить правило AGENTS.md.

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest
git diff --check
git add docs/superpowers/specs/2026-10-03-nearby-source-audit.md
git commit -m 'docs: audit nearby media dependencies'
```

### Task 2: Доказательство SFU revocation boundary

**Ownership:** revocation-evidence report, локальный временный probe. Только
одноразовые identities/rooms и тестовый SFU; не заходить в рабочие комнаты людей.
**Interfaces:** consumes source audit/token lifecycle; produces выбранный
механизм и воспроизводимый evidence либо статус NOT PROVEN. NOT PROVEN блокирует
backend/SFU-план, но не запрещает независимую работу над pure controller.

- [ ] Step 1: Проверить возможности среды без установки/запуска production stack.
Зафиксировать Docker/runtime availability; если runtime недоступен, выполнить
source analysis и сохранить конкретную потребность в тестовом SFU вместо fake pass.

```powershell
Get-Command docker, adb, java -ErrorAction SilentlyContinue
Get-Content docker-compose.yml
Get-Content livekit.yaml
```

- [ ] Step 2: Поднять отдельный disposable SFU из того же image tag и конфигурации
media-портов, используя собственные dev key/secret и отдельные loopback ports.
Compose override и клиентский probe находятся в ledger directory. Фиксировать
image digest, SDK versions и hash harness. Не запускать `deploy.sh`, не читать
production `.env`. Admin operations используют установленный server SDK; точные
методы берутся из Task 1, не из latest-docs предположения.
- [ ] Step 3: Probe содержит минимум publisher A, removable B и observer C.
Каждый run получает новый nonce для room/session; токены остаются в памяти.
Перед remove наблюдать успешные audio frames A→B и B→C. Synthetic test audio
используется только локальным probe, не отправляется в product channel.

```text
runId = fresh UUID
room = "volty-revoke-probe-" + runId
originalToken(B): initial TTL 60 s
capture refreshed token as opaque in-memory value
assert baseline published/subscribed frames > 0
remove B with admin API
attempt original-token fresh join + media
attempt refreshed-token fresh join + media
attempt SDK reconnect after remove + media
delay/disable webhook consumer during each attempt
record result and frames before webhook processing
```

- [ ] Step 4: Выполнить каждую строку матрицы отдельно; baseline заново перед
каждым removal. Если baseline media не работает, run INVALID и не доказывает
revoke. Значения frames берутся из реального media client/receiver; fake SDK,
join HTTP status, timeout сам по себе и webhook acknowledgement не заменяют их.

| Случай | Обязательная проверка |
|---|---|
| Original JWT до exp | Fresh join + фактический subscribe/publish |
| Refreshed JWT | Повторная media сессия после remove |
| SDK reconnect | Старые credentials/SDK cache не дают необнаруженный доступ |
| Hook задержан | Есть ли окно media ДО обработки hook |
| Participant уже ушёл | Admin removal без текущего participant не предполагается revoke |
| Backend offline, SFU online | Повторный доступ и lease enforcement измерены отдельно |

- [ ] Step 5: Если простой remove не даёт строгого отзыва, сравнить supported
механизмы закреплённой версии по source audit: admission enforcement,
subscription grants или смена room epoch с прекращением публикации в старой room.
Для выбранного кандидата повторить всю матрицу, включая refreshed token и hook
delay. Не менять self-hosted на Cloud без согласования. Если ни один кандидат
не доказан, записать NOT PROVEN, фактическое окно и конкретное предлагаемое
изменение контракта; user decision требуется только для этого изменения.
- [ ] Step 6: Записать report: exact versions, harness hash, nonce, steps,
observations, baseline frames, post-removal frames, timestamps, ограничения,
gate conclusion. Отдельно пометить source inference и runtime measurement.
Task reviewer проверяет соответствие conclusion evidence; Astra не вызывать.
- [ ] Step 7: Прогнать Android suite перед commit отчёта; если в probe менялся
backend test code, также standalone backend tests, без вывода JWT/secrets.

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest
.\gradlew.bat -c backend/settings.gradle.kts test --no-configuration-cache
git diff --check
git add docs/superpowers/specs/2026-10-03-nearby-sfu-revocation-evidence.md
git commit -m 'docs: establish nearby sfu revocation boundary'
```

### Task 3: Фиксация контрактов и протокола device diagnostics

**Ownership:** foundation-contracts report и точечные отмеченные уточнения spec.
**Interfaces:** consumes source audit + SFU evidence; produces interface map,
ready/blocked матрицу этапов и field protocol для последующего diagnostic APK.
Production Kotlin/SQL/UI в этой задаче не пишутся.

- [ ] Step 1: Зафиксировать typed boundaries: generation/session/operationId,
connection/media events, desired/actual audio route, explicit full stop, hide
position, deadline и terminal claim cleanup. Различать source-backed SDK API
и product interfaces, которые реализует следующий план.
- [ ] Step 2: В документе привести следующие trace acceptance examples без
противоречия с source audit. Это входы будущих тестов, не выполненные тесты.

```text
join(op1) -> backend committed -> response lost -> stop(op1)
=> CANCELLED op1; no old claim; no Room resurrection

call RINGING -> ACCEPTED committed -> caller stop(callId)
=> ENDED; no late credentials can restore local intent

connected(session1) -> backend ack absent > 30 s, SFU still alive
=> local capture/share stopped; terminal cleanup releases only claim(session1)

PHONE desired input + HEADSET desired output -> route change
=> actual devices reported; unsupported pair is visible; no silent Speakerphone

hide position -> microphone session continues
=> Nearby location demand removed; navigation demand unaffected
```

- [ ] Step 3: Device protocol для Xiaomi фиксирует модель/build, app hash,
permissions, available/actual capture+render devices, audio mode/focus,
publish/subscribe события, transport state, media counters и timestamp.
Никаких speech recordings, JWT, passwords, точных координат в экспортируемом логе.
Обязательные прогоны: без гарнитуры, с пользовательской гарнитурой, PHONE→headphones,
mute/deafen, reconnect, screen lock, incoming system call и explicit disconnect.
Не просить новое подтверждение известных симптомов; данные build/route нужны
для воспроизводимости. Отсутствие телефона обозначить NOT MEASURED.
- [ ] Step 4: Проверить microphone/location FGS start из видимой Activity,
notification permission/channel, stop ownership и process-death no-auto-capture
по текущим официальным Android docs и min/target API. Прочитать существующие
manifest/location-demand providers; назначить owner только после чтения.

```powershell
Get-Content composeApp/src/androidMain/AndroidManifest.xml
Get-Content composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/social/AndroidLocationProvider.kt
rg -n 'demand|foreground|service|location' composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/location composeApp/src/androidMain/kotlin/ru/sodovaya/volty/service
```

- [ ] Step 5: Разделить readiness: pure voice core можно планировать по typed
interfaces; Android adapter требует подтверждённых hooks; backend/SFU требует
revoke gate; UX требует agreed wire/state; calls требует claims + provisioning.
Navigation summary остаётся optional. Все пять замечаний Astra должны иметь
конкретный контракт или сохраняющийся исследовательский blocker, не слово «учтено».
- [ ] Step 6: Self-review и task review, затем Android suite перед commit.
После этого writing-plans создаёт следующий подробный executable plan именно
для ready этапа; backend не планируется подробно при NOT PROVEN revoke.

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest
git diff --check
git add docs/superpowers/specs/2026-10-03-nearby-foundation-contracts.md
git commit -m 'docs: define nearby foundation contracts'
```

## Самопроверка текущего плана

Начальные исследования покрыты Tasks 1–3; это сознательная граница плана.
Обязательные продуктовые требования распределены по этапам таблицы, но не
объявлены реализованными или проверенными. Каждая source/runtime/device
неопределённость имеет конкретный результат и критерий gate. Автоматические
тесты не подменяют media/device proof. Команды commit требуют suite; никаких
commit/push/deploy при написании этого документа не сделано.

## Execution handoff

Метод уже задан AGENTS.md: fresh implementer + task review + fix loop, ledger
в `.superpowers/sdd/2026-10-03-nearby-foundations/progress.md`. Перед execution
пользователь рассматривает этот конкретный план; нового выбора метода не нужно.
До разрешения execution работа ограничивается чтением и планированием. Astra
не вызывается повторно ни implementer, ни reviewer, ни controller.
