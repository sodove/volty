# Рация рядом: групповой голос по Wi‑Fi без сети

Дата: 2026-10-20. Статус: описание для планирования (этап 1). Ветка-основа:
`codex/nearby-foundations` (в `main` Nearby нет).

## Зачем

Райдеры (моноколёса, самокаты) ездят колонной до ~10 человек, растягиваясь не
более чем на ~100 м. Сейчас часть из них общается через мотогарнитуры
(Cardo/Sena) с их собственной связью. Рация в Volty должна это заменить:
гарнитура остаётся просто Bluetooth-гарнитурой телефона (динамик + микрофон),
а разговор идёт через приложение — **телефон с телефоном по Wi‑Fi, без
мобильной сети и без нашего сервера**.

Почему без сервера: мобильная связь на маршруте рвётся, а 2026-10-20 мы
убедились, насколько хрупок путь до сервера (см. память о Cloudflare и
`docs/superpowers/specs/2026-10-07-nearby-live-transport.md`). Рация — это
дополнительный режим рядом с серверным голосовым каналом, не замена ему.

## Как этим пользуются

- Все говорят **с открытым микрофоном**, кнопкой (PTT) никто не пользуется.
- Каждый слышит всех остальных участников рации, кроме себя.
- **Подключение вручную по умолчанию**: в начале поездки один человек
  «поднимает рацию» в сервере Volty, остальные из этого же сервера видят её
  рядом и присоединяются.
- **Опция в настройках**: «подключаться автоматически, когда рядом рация моего
  сервера» — с явным предупреждением о расходе батареи (постоянный поиск).
- Подключиться могут **только участники того же сервера Volty**. Посторонний
  с приложением рядом рацию не видит и войти не может.

## Цели этапа 1

1. Рация на 2–10 телефонов, топология «звезда»: телефон, поднявший рацию —
   **главный** (хаб), остальные подключены к нему.
2. Звук: открытый микрофон с отсечением тишины и подавлением шума/ветра,
   у каждого свой микс «все, кроме меня».
3. Работа через Bluetooth-гарнитуру (режим звонка) и через динамик/микрофон
   телефона.
4. Работа в фоне с выключенным экраном (foreground service), как у текущего
   голосового чата.
5. Если главный телефон пропал — у остальных состояние «Рация потеряна» и
   кнопка поднять её заново (одним нажатием, без поиска руками).
6. Диагностика, достаточная, чтобы в поездке понять: кто подключён, уровень
   сигнала/потери пакетов, задержка — и экспорт в файл (по образцу
   существующей диагностики голоса).

**Главная задача этапа 1 — проверить то, что может похоронить идею**:
реальную дальность на ходу, держит ли один телефон 9–10 клиентов, расход
батареи за поездку, разборчивость при открытых микрофонах и ветре.

### Критерии успеха этапа 1 (согласованы с пользователем)

- Задержка от рта до уха — не больше ~0,3 с.
- Связь держится по всей колонне на ~100 м, на ходу, телефоны в карманах.
- Батарея за час поездки: обычный участник — до ~15 %, главный — до ~25 %.
- 10 участников — цель; фактический предел хаба фиксируется (см. «Лимит
  клиентов»).

## Не цели этапа 1

- **Мост к серверному каналу** (этап 2, см. ниже) — но архитектура должна его
  допускать.
- Автоматическая передача роли главного при его уходе (этап 2+).
- Многоскачковая сеть (mesh), ретрансляция через промежуточные телефоны.
- iOS, китайские прошивки без Google Play Services (если решение их требует).
- Запись разговоров.

## Анти-цели (провал, даже если «работает»)

- **Bluetooth для связи между телефонами не используется** («Блутуз плохо»).
  Bluetooth занят гарнитурой и BLE-соединением с колесом/BMS; рация не должна
  мешать BLE-связи с колесом ни при каких условиях.
- Рация не должна ломать или ухудшать существующий серверный голосовой чат.
- Нельзя слышать себя или одного человека дважды.
- Десять открытых микрофонов не должны превращаться в сплошной шум ветра.
- Нельзя, чтобы посторонний рядом мог подслушать или подключиться.
- Не повторять ошибку текущего голосового стека: никакой многослойной
  самописной машинерии там, где хватает штатного механизма платформы.

## Ограничения

- Android only, `minSdk 26`, Kotlin Multiplatform + Compose, Koin, Decompose —
  как весь проект.
- UI на русском; строки в `values/` и `values-ru/` (Compose не обрабатывает
  Android-экранирование).
- Приложение уже держит BLE к колесу/BMS и foreground-сервисы голоса
  (`NearbyVoiceService`, тип `microphone`), навигации и геопозиции.
- Связь между телефонами — Wi‑Fi (Wi‑Fi Direct и/или Wi‑Fi Aware), без точки
  доступа и без интернета. Предпочтительно 5 ГГц: 2,4 ГГц делит эфир с
  Bluetooth-гарнитурой и BLE колеса.
- Должно работать **без интернета в момент поездки**. Всё, что нужно для
  проверки «свой сервер», телефон получает заранее, пока сеть есть.
- Проверка возможна только на реальных телефонах на улице; эмулятор Wi‑Fi
  Direct не проверяет. Ожидаемые модели в группе: Xiaomi 17T (HyperOS,
  Android 16), Samsung S24 Ultra, Pixel 7, Nothing, Huawei.

## Решения, принятые с пользователем

- Wi‑Fi, не Bluetooth.
- Группа до 10, дистанция до ~100 м.
- Открытый микрофон, без PTT.
- Ручной старт по умолчанию, автоподключение — опцией с предупреждением.
- Только участники того же сервера Volty.
- Два этапа: сначала рация без сервера, мост — вторым этапом (это решение
  Claude, пользователь доверил выбор).

## Решения Claude (пользователь может оспорить)

- **Звезда с главным телефоном**, а не «все со всеми»: проще, меньше эфира,
  микс считает один телефон, и именно главный станет мостом на этапе 2.
- **Пока человек в рации, его личный серверный голос на этом телефоне
  выключен** (выход из серверного канала при входе в рацию). Это убирает
  двойной звук без моста; на этапе 2 правило заменяется мостом.
- **Режим связи на выбор (просьба пользователя «как в Discord»).** Главный
  выбирает режим для всей рации, участники следуют ему автоматически:
  - **Минимальная задержка** — без сжатия (PCM 16 кГц), буфер 40 мс;
  - **Сбалансированный** (по умолчанию) — Opus 24 кбит/с, буфер 60 мс;
  - **Надёжность** (край дальности) — Opus 12 кбит/с со встроенной
    избыточностью (FEC), буфер 120 мс.
  Звук всегда 16 кГц моно: через Bluetooth-гарнитуру больше не пройдёт, а
  пропускной способности Wi‑Fi хватает с запасом — настоящий выбор здесь
  «задержка против устойчивости». Opus — библиотека Concentus
  (`io.github.jaredmdobson:concentus:1.0.2`, чистая Java, BSD).
- Гарнитура используется в режиме звонка (SCO / LE Audio): узкополосно, но
  для речи достаточно и это единственный режим с микрофоном гарнитуры.

- **Android 10+.** Рация требует Android 10 и выше (группа с заданным именем
  и паролем без системного диалога). На Android 8–9 — понятное сообщение
  «Рация недоступна на этой версии Android». Все известные телефоны группы
  новее.
- **Ключ сервера — небольшое изменение бэкенда в этапе 1.** Бэкенд выдаёт
  участникам сервера ключ рации с номером поколения; при удалении участника
  поколение меняется. Телефон кеширует текущий ключ, пока есть сеть, и
  использует его офлайн. Из ключа выводятся пароль группы Wi‑Fi и шифрование
  голоса. Удалённый участник теряет доступ после смены поколения (с
  задержкой до следующего онлайн-обновления у остальных — это допустимо).
- **Обнаружение.** Рация объявляет себя рядом идентификатором, выведенным из
  ключа сервера и меняющимся со временем; посторонний не может понять, чей это
  сервер, и не может войти.
- **Мобильный интернет не трогаем.** Рация не должна отключать мобильные
  данные; карта, телеметрия и прочее продолжают работать по сотовой сети.
  Проверить на каждой модели в полевых тестах.
- **Лимит клиентов.** 10 — цель, а не условие провала. Если хаб на конкретной
  модели держит меньше, новым показывается «Рация заполнена», а предел
  фиксируется в диагностике и полевом отчёте.
- **Главный — кто поднял.** Выбор ручной, любой участник сервера; без
  автоматического предпочтения моделей.
- **«Рация потеряна».** У всех участников появляется кнопка «Поднять заново».
  Кто нажал первым — новый главный. Остальные, кто был в потерянной рации,
  в течение ~2 минут автоматически ищут и подключаются к новой рации своего
  сервера (без нажатий). Если две рации одного сервера подняли одновременно,
  остаётся одна по детерминированному правилу, участники второй переходят в
  неё.

## Этап 2 (для понимания архитектуры, не строить сейчас)

**Мост.** Главный телефон рации одновременно сидит в серверном голосовом канале
этого же сервера Volty как один участник («Рация (N чел.)»): отправляет туда
общий микс рации и проигрывает в рацию голоса из канала. Отставший, у которого
пропала связь с рацией, автоматически переходит в серверный канал и через мост
продолжает слышать всех и быть услышанным; вернулся в зону — снова в рацию.
Участники рации в серверном канале лично не звучат (иначе двойной звук).
Следствие для этапа 1: аудиоконвейер главного должен позволять подмешать
внешний источник и отдать наружу микс «всех участников рации».

## Отложено: шумоподавление «как Krisp»

Решено сделать после первой поездки с рацией (этап 1 идёт со стандартным
шумодавом телефона + WebRTC). Затем — общий модуль для рации и серверного
голоса, настройка «Шумоподавление: Нейросеть / Стандартный / Выключен»;
кандидат — RNNoise (BSD), запасной — DeepFilterNet. Krisp — закрытый SDK,
не используется.

## Отдельная задача (не входит сюда)

Голос поверх музыки в существующем серверном чате: собеседник сейчас идёт
медиа-потоком, чтобы музыка не выключалась, но тихий микрофон тонет в музыке.
Идея (догадка Claude): приглушать музыку только пока кто-то говорит — как
подсказки навигатора — и выравнивать громкость тихих микрофонов. Обсуждается
и делается отдельно; рации стоит переиспользовать тот же подход.

## Оставлено строителю

- Выбор между Wi‑Fi Direct (группа с заданным именем сети и паролем,
  `WifiP2pConfig.Builder`, Android 10+, без системного диалога) и Wi‑Fi Aware
  (поддержка зависит от модели), и что делать на Android 8–9.
- Как приложение заранее получает и хранит секрет «своего сервера» для
  входа в рацию офлайн (бэкенд-эндпоинт или вывод из уже имеющихся данных) и
  как шифруется голос.
- Транспорт пакетов (UDP поверх группы), буфер против рывков, отсечение
  тишины/шума, микширование «все, кроме меня».
- Как именно показывать рацию в интерфейсе Nearby (рядом с панелью голоса
  сервера), тексты, иконка в шторке.
- Состав и формат диагностики.
- Набор полевых проверок: дальность, 10 клиентов на одном хабе, батарея за
  час, гарнитура + ветер.

## Решения, принятые при реализации этапа 1 (R1–R25)

Записаны контролёром в ходе выполнения плана; каждое — что решено, почему, чем грозит ошибка.

- **R1**: Replace `newestEpochSeen` with `fetchedAtMillis` stored next to each key (`RadioKeyStore.get` returns `StoredRadioKey(key, fetchedAtMillis)`). `RadioController.join` that ends `NotFound` while the key is older than 24 h reports `Error(STALE_KEY)` instead of `NOT_FOUND`; `raise` without a key → NO_KEY as planned. Test moves to T7 as `notFoundWithOldKeySuggestsRefresh`; T3 drops `staleEpochIsReported`. — STALE_KEY in the plan had no data source; key age is the only signal available offline. — If wrong: a misleading "key out of date" hint on genuine not-found after a day.
- **R2**: `RadioRoster` payload (T3) carries `mode:Int` (RadioMode.wire). — T5 requires rosters to carry mode. — If wrong: one field to drop.
- **R3**: T5 defines direction-neutral messages: `sealed interface RadioMessage { Hello(userId,name,sentAtMillis); Audio(slot,seq,pcm:ShortArray); Bye(slot); Roster(entries:List<RadioRosterEntry>, modeWire:Int, echoMillis:Long?); Full }` + `data class RadioRosterEntry(slot,userId,name)` + `data class RadioDatagram(peer:RadioAddress, message:RadioMessage)`. Hub: `receive(RadioDatagram, now): List<RadioDatagram>`, `tick(now, hubFrame): List<RadioDatagram>`. Client (T6): `receive(RadioMessage, now)`, `tick(now, micFrame): List<RadioMessage>` (all to hub). Replaces RadioInbound/RadioOutbound names. — The planned types only modelled the hub direction. — If wrong: rename churn only.
- **R4**: `NearbyVoiceController` must not depend on `RadioController` directly; it gets an optional `onConversationStarting: () -> Unit` (default no-op) wired in DI to `RadioController.onServerVoiceStarted()`; the radio's `RadioServerVoice` impl reads/disconnects via the controller instance. Implementer may pick an equivalent cycle-free wiring. — Avoids a Koin cycle. — If wrong: wiring rework in T9.
- **R5**: `RadioController` (T7) exposes `fun setMode(mode: RadioMode)` storing the mode for the next raise and forwarding to the running hub via a `(RadioMode)->Unit` hook the runtime installs. — T10 needs it; T7 owns the class. — If wrong: small API addition later.
- **R6**: roster for 10 members with long Cyrillic names can exceed the 1200-byte datagram and be silently dropped → clients never reach CONNECTED. Load-bearing for T5/T6. Fix in T3: encode requires sender/mode/slot/entries ranges and total ≤ 1200; RadioRoster.encode truncates displayName to 24 chars so 10 entries always fit. — reviewer rated Minor; ruled load-bearing because it silently breaks the join handshake. — If wrong: names truncated at 24 chars in the radio roster.
- **R7**: RELIABLE mode uses Opus PLC only on loss, no in-band FEC recovery (sessions are PCM-only and decode at receive time, so the next packet is not available to the decoder). Encoder keeps FEC on so a later decoder change needs no wire change. — FEC recovery needs decode-in-buffer-order, a design change across T5/T9. — If wrong: RELIABLE is weaker at range edge than promised; its 120 ms buffer still helps; revisit after the field test.
- **R8**: streams carry identity. (a) RadioMessage.Hello gets streamId: Int (sender's random per-process id, = the packet header session); hub creates a fresh jitter buffer for a client whenever its streamId changes. (b) Hub keeps its per-recipient send seq per userId for the hub's whole lifetime (survives eviction/rejoin). (c) RadioMessage.Roster gets hubStreamId: Int; T6 client rebuilds its hub-stream buffer when hubStreamId changes and when leaving LOST. — jitter buffers never reset high-water by design, so stream identity must be explicit. — If wrong: one extra Int per Hello/Roster.
- **R9**: in LOST the client stops audio but keeps sending Hello every 1 s (brief said 'stop sending'). — Without a probe the hub (which evicts silent clients) can never answer, so the R8 exit from LOST could never fire; Hello is ~100 bytes/s. — If wrong: a lost client wastes a tiny bit of airtime until the controller's 120 s rejoin window ends.
- **R10**: client phase FULL → controller calls link.leave() and sets Error(community, FULL) (UI shows 'Рация заполнена' from RadioError.FULL); retry is a fresh user join. — the brief's RadioLinkResult has no Full, so the controller is the only place to surface it uniformly. — If wrong: UI mapping moves to the panel.
- **R11**: RadioUdpTransport owns the frame codecs: encoders keyed by destination address (the hub sends a different mix per client), decoders keyed by (source address, packet session); all recreated on mode change (mode supplied by a () -> RadioMode). Lost frames are not decoded by the transport (jitter buffer conceals). — sessions are PCM-only (T5/T6) and Opus state is per stream. — If wrong: codec ownership moves to T9 runtime.
- **R12**: packet header seq (= GCM nonce with session) is a transport-level per-process counter incremented for EVERY packet sent, independent of message seq; RadioMessage.Audio.seq travels inside the encrypted AUDIO payload (8-byte prefix before the codec bytes). Hello.streamId and Roster.hubStreamId = the sender's header session; Audio.slot/Bye.slot = header sender. Decode BEFORE replay guard; replay guard keyed on header seq. — the hub's per-recipient audio seqs would otherwise repeat (session, seq) under the shared group key = AES-GCM nonce reuse. — If wrong: 8 bytes more per audio packet.
- **R13**: RadioPacketCodec.encode may throw (R6 size/range checks) — the transport catches, drops the message and counts it; it must never crash the audio thread.
- **R14**: duplicate hub is reported, not dissolved by the link (link must not bypass the controller Mutex). RadioLink interface gets 'var onDuplicateHub: ((community: String) -> Unit)?'; the controller (T9 adds RadioController.onDuplicateHub()) leaves and re-runs raise semantics (5 s join probe, else host). Yield decision: on seeing a same-name foreign hub, wait random 0–30 s, re-scan; if still present, yield when RadioDuplicateRule says so OR when the own address is unknown/redacted (02:00:00:00:00:00 or all-zero). — deterministic BSSID compare is unreliable across vendors/MAC randomisation; random back-off + raise probe converges. — If wrong: occasional extra re-host in the field.
- **R15**: RadioUdpTransport has no injectable session; each instance draws a fresh SecureRandom session (so a fresh header-seq space). — closes AES-GCM nonce reuse across transport instances. — If wrong: none.
- **R16**: accept RadioError.AUDIO_UNAVAILABLE + RadioController.onRuntimeFailure(reason) (added by T9) — needed to surface mic/FGS refusal; T10 adds its UI string. — If wrong: one extra enum value.
- **R17**: with no BT SCO/LE headset present, the radio routes to the built-in speaker (setCommunicationDevice(TYPE_BUILTIN_SPEAKER) on 31+, isSpeakerphoneOn below) instead of the MODE_IN_COMMUNICATION default earpiece. — a rider without a headset would hear nothing from the earpiece while riding; the spec says phone speaker/mic must work. — If wrong: louder default for someone holding the phone to the ear (unlikely while riding).
- **R18**: reviewer Minor 'blocking AudioTrack.write' treated as load-bearing — the ≤0.3 s mouth-to-ear target is a spec acceptance criterion; write non-blocking and drop when full so the mic is the only clock.
- **R19**: R17's speaker fallback must not override a wired/USB headset — speaker only when no BT SCO/LE, wired headset or USB headset is present (fix in T10, RadioAudioIo). — reviewer found the literal R17 forces speaker over wired headsets. — If wrong: none.
- **R20**: auto-join attempts only while the app is visible (foreground); in background it does nothing. — Android 14+ refuses starting a microphone FGS from the background (would surface AUDIO_UNAVAILABLE every poll). — If wrong: riders must open the app once to be picked up automatically.
- **R21**: accept (a) radio permissions requested by the radio card's own launcher, not the NearbyPermissionGate (gate only serves voice/sharing); (b) ACCESS_FINE_LOCATION requested at raise/join below API 33 (Wi‑Fi Direct needs it there); (c) auto-join suppressed for 10 min after a manual Leave. — each fixes a real gap the plan missed. — If wrong: small UX adjustments.
- **R22**: treat as load-bearing for the field run: (a) diagnostics export available whenever a snapshot exists, incl. Idle/Error after a run; (b) never show 'Рация: 0 чел.'/empty CONNECTED — fall back to Starting/connecting title until the roster has members, and a JOINING client shows no mode until the hub's roster arrives; (c) auto-join poll loop wrapped so an exception logs and continues (no scope crash), suppression field @Volatile; (d) field checklist gets a mouth-to-ear latency method (clap test recorded by a third device) and 'maximum reached' wording for the cap. — field run is the acceptance gate; these decide whether its results are usable and honest. — If wrong: small extra work.
- **R23**: final fix wave includes I1–I7 plus pre-field minors M1 (decode by payload length during mode change), M3 (failed auto-join returns silently to Idle), M6 (checklist: screen-off battery/latency/background, routing after voice↔radio switch), M7 (stopSelf(startId)). Other minors wait for after the field test per the final review's triage.
- **R24**: I3 consequence accepted — a hub raised before UTC midnight is not findable by NEW joiners after 06:00 UTC (09:00 MSK) until re-raised; members already joined are unaffected. — joining today's name with the full budget matters on every join; the overnight case is near-impossible for rides. — If wrong: someone re-raises once.
- **R25**: accept implementer's I4 reshape (scan waits up to 10 s for its own SCAN_RESULTS broadcast; radioNearby/radioNearbyAny suspend) and I2 addition (Lost deadline not extended by Joined-without-hearing-hub). — both required for the findings to be effective. — If wrong: slower auto-join poll.
