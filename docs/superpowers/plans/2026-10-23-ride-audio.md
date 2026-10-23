# Звук в поездке — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** в поездке слышно и алерты, и голосовой канал поверх музыки, тихих участников можно выровнять, а выбросы чужих микрофонов (молния, вдох в гарнитуру) не глушат остальных; голос сам возвращается после обрыва.

**Architecture:** каждое решение — чистая функция или маленький класс в `commonMain` с юнит-тестами; Android-слой только применяет готовые числа (громкость дорожки, аудиофокус, усиление PCM). Все настройки живут в `AppPrefs` и собраны в один раздел настроек «Звук». Настройки, меняемые во время звонка (громкости, приглушение), приходят в сессию потоком (`StateFlow`), а не снимком на старте.

**Tech Stack:** Kotlin Multiplatform (Android), Compose Multiplatform, DataStore (`AppPrefs`), LiveKit Android 2.28.0 (`RemoteAudioTrack.setVolume(double)`, `RoomEvent.ActiveSpeakersChanged`, `LocalAudioTrackOptions`), `android.media.AudioFocusRequest`, `android.media.audiofx.LoudnessEnhancer`, kotlin.test.

**Spec:** этот документ; решения приняты с пользователем 2026-10-23 (раздел «Решения» ниже). Контекст полевых жалоб — там же.

## Решения (согласованы с пользователем 2026-10-23)

1. **Приглушать музыку, пока кто-то говорит в канале** — включено по умолчанию.
2. **Автоусиление микрофона (WebRTC AGC)** — остаётся включённым по умолчанию (часть ребят и так слышно слабо); пользователь хочет «поиграться пределами» → своя ступень: чувствительность микрофона и потолок ограничителя.
3. **Громкость каждого участника** — нужна сразу: третьего участника со слабым микрофоном было еле слышно.
4. **Алерты** — дать выбор типа звука в настройках: «как подсказка навигатора» (по умолчанию) или «как будильник»; режим музыки по умолчанию «приглушать»; плюс ползунок громкости алертов.
5. **Сам голос возвращается после обрыва** — сейчас попытка сдаётся за ~6 с («Подключить голос не удалось»), а после обрыва сессия уходит в `FAILED` без повтора (Салазкин, 2026-10-23 15:20 UTC).
6. **Гейт по речи (RNNoise)** — не делаем сейчас; задача 7 описывает, при каком наблюдении её начинать.

Полевые факты, на которые опирается план:
- Пользователь на **Pixel** (чистый Android). Ребята на режиме «приглушать» под алерты жалоб не имеют; у пользователя в этом режиме музыка (Яндекс Музыка, Symphonium) уходила в 0 — поэтому дефолт меняется на «как навигатор», а «как будильник» остаётся выбором.
- Голос в канале сейчас не берёт аудиофокус в режиме Bluetooth/SDK — музыка играет на полной громкости поверх речи.
- `createAudioTrack()` без аргументов = `LocalAudioTrackOptions(noiseSuppression, echoCancellation, autoGainControl, highPassFilter, typingNoiseDetection)` — все `true` (проверено `javap` по 2.28.0).
- LiveKit-участник канала идентифицируется **id сессии** (`VoiceRosterPeer.sessionId`), а человек — `actorId`; громкость участника хранится по `actorId`.

## Global Constraints

- Строки — в `composeApp/src/commonMain/composeResources/values/strings.xml` **и** `values-ru/strings.xml`; Compose Multiplatform не обрабатывает Android-экранирование (`\'`, `\n`) — не использовать.
- `minSdk 26`; `LoudnessEnhancer` доступен с API 19, `AudioFocusRequest` — с 26.
- `.\gradlew.bat :composeApp:testDebugUnitTest` перед каждым коммитом; число тестов только растёт.
- Compose не тестируется юнит-тестами (CLAUDE.md, правило 3): решения — в чистых функциях; то, что проверяется только на устройстве, так и пишется в отчёте задачи.
- Никаких бесконечных `delay`-циклов в `runTest` (CLAUDE.md, правило 4): таймеры приглушения/повтора тестируются как чистые функции от времени.
- Голос — DIRECT `LiveKit` через `voice.sodove.ru`; сервер не меняется ни в одной задаче.
- Усиление в дБ везде: хранится `Float` дБ, в линейный множитель переводит одна функция `dbToGain(db: Float): Double = 10.0.pow(db / 20.0)`.

## Review Focus

1. **Музыка не возвращается**: участник вышел из канала / сессия закрылась, пока он говорил → приглушение должно сняться (фокус отпущен) не позже чем через hold-время. Тест в задаче 3: `aClosedSessionReleasesTheDuck`.
2. **Двойное усиление**: громкость участника 200 % × общая 200 % = 400 % → клиппинг. Итог ограничивается `MAX_LISTEN_GAIN_DB = 12f`. Тест в задаче 4: `combinedGainIsCappedAtTwelveDecibels`.
3. **Режим микрофона телефона** (`phoneCapture`): воспроизведение идёт через `PhoneVoicePlayback`, `setVolume` WebRTC туда не доходит → громкость применяется к PCM в `PhoneVoicePlayback`. Тест в задаче 4: `phonePlaybackScalesAndClampsSamples`.
4. **Повтор после явного «Отключиться»** не должен запускаться, и повтор не должен идти бесконечно на выключенной сети. Тест в задаче 6: `aUserLeaveCancelsTheRejoin`, `rejoinStopsAfterTheBudget`.
5. **Ограничитель микрофона на тишине** не должен поднимать шум при чувствительности +дБ сильнее потолка. Тест в задаче 5: `aBoostedFrameNeverExceedsTheCeiling`.

---

### Task 1: Настройки звука — модель и хранение

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/audio/RideAudioSettings.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/prefs/AppPrefs.kt` (новые ключи в `Keys`, потоки и сеттеры рядом с `alarmMusicMode`)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/audio/RideAudioSettingsTest.kt`

**Interfaces:**
- Produces:
  - `enum class AlertSoundKind { NAVIGATION, ALARM }`
  - `data class AlertSoundSettings(val kind: AlertSoundKind = AlertSoundKind.NAVIGATION, val gainDb: Float = 0f)`
  - `data class VoiceListenSettings(val duckMusicWhileSpeaking: Boolean = true, val masterGainDb: Float = 0f, val participantGainDb: Map<String, Float> = emptyMap())` — ключ карты `actorId`
  - `data class MicProcessingSettings(val autoGain: Boolean = true, val sensitivityDb: Float = 0f, val limiterCeilingDbfs: Float = -3f)`
  - `fun dbToGain(db: Float): Double`
  - `const val MAX_LISTEN_GAIN_DB = 12f`, `const val MIN_LISTEN_GAIN_DB = -30f`, `const val MAX_ALERT_GAIN_DB = 12f`, `const val MAX_MIC_SENSITIVITY_DB = 12f`
  - `fun VoiceListenSettings.encodeParticipants(): String` / `fun decodeParticipantGains(raw: String?): Map<String, Float>` (формат `actorId=dB;…`, битые пары пропускаются, значения зажимаются в `MIN..MAX`)
  - `AppPrefs`: `alertSoundSettings: StateFlow<AlertSoundSettings>`, `voiceListenSettings: StateFlow<VoiceListenSettings>`, `micProcessingSettings: StateFlow<MicProcessingSettings>`, `suspend fun setAlertSoundKind(kind)`, `setAlertGainDb(db)`, `setDuckMusicWhileSpeaking(on)`, `setMasterListenGainDb(db)`, `setParticipantGainDb(actorId, db)`, `setMicAutoGain(on)`, `setMicSensitivityDb(db)`, `setMicLimiterCeilingDbfs(dbfs)` — все сеттеры зажимают значение в допустимый диапазон.
  - Дефолт `alarmMusicMode` **не меняется** (уже `DUCK_MEDIA`).

- [ ] **Step 1: Тесты** `dbToGainMatchesKnownPoints` (0 дБ → 1.0, +6 → 1.995±0.001, −20 → 0.1), `participantGainsSurviveARoundTrip`, `aCorruptParticipantEntryIsSkippedNotFatal` (`"a=3;b=x;=2;c=99"` → `{a=3, c=12}`), `defaultsMatchTheAgreedDecisions` (`NAVIGATION`, `duckMusicWhileSpeaking=true`, `autoGain=true`).
- [ ] **Step 2:** запустить — FAIL (нет типов).
- [ ] **Step 3:** реализовать модель и `AppPrefs` (ключи `alert_sound_kind`, `alert_gain_db`, `voice_duck_music`, `voice_master_gain_db`, `voice_participant_gains`, `mic_auto_gain`, `mic_sensitivity_db`, `mic_limiter_ceiling_dbfs`).
- [ ] **Step 4:** тесты — PASS; весь набор зелёный.
- [ ] **Step 5:** commit `feat(audio): ride audio settings model and persistence`.

### Task 2: Алерты — тип звука и громкость

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/alert/AlertAudioPlan.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/notification/AudibleAlarm.android.kt` (атрибуты и фокус из плана), `.../notification/AlarmTonePlayer.kt` (`LoudnessEnhancer` на сессию трека), `.../domain/alert/AlarmSignal.kt` (команда несёт `AlertSoundSettings`), место сборки `AlarmCommand` из `AppPrefs` (там же, где сейчас `alarmMusicMode`, `AppPrefs.kt:160`)
- Modify UI: `presentation/alerts/VehicleAlertsScreen.kt` / `VehicleAlertsComponent.kt` — рядом с «Музыка во время сигнала»: выбор «Как подсказка навигатора / Как будильник» и ползунок «Громкость алертов» 0…+12 дБ.
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/alert/AlertAudioPlanTest.kt`

**Interfaces:**
- Consumes: `AlertSoundSettings`, `AlarmMusicMode`, `dbToGain` (Task 1).
- Produces: `enum class AlertUsage { NAVIGATION_GUIDANCE, ALARM }`, `enum class AlertFocus { NONE, TRANSIENT_MAY_DUCK }`, `data class AlertAudioPlan(val usage: AlertUsage, val focus: AlertFocus, val loudnessMillibels: Int)`, `fun alertAudioPlan(settings: AlertSoundSettings, music: AlarmMusicMode): AlertAudioPlan`.

- [ ] **Step 1: Тесты** `navigationDucksByDefault` (дефолты → `NAVIGATION_GUIDANCE`, `TRANSIENT_MAY_DUCK`, `0`), `overMediaRequestsNoFocusInEitherKind`, `alarmKindKeepsTheOldAttributes` (`ALARM` + `DUCK_MEDIA` → `ALARM`, `TRANSIENT_MAY_DUCK`), `gainBecomesMillibelsAndIsCapped` (+6 дБ → 600; +40 → 1200; −3 → 0 — алерт не делаем тише системной громкости).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** `alertAudioPlan`; в Android: `AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` / `USAGE_ALARM` по `usage` (contentType `SONIFICATION` в обоих), фокус по `focus` (существующая логика «потеря фокуса не останавливает алерт» сохраняется), `LoudnessEnhancer(track.audioSessionId).setTargetGain(loudnessMillibels)` при `> 0`, освобождать вместе с треком в `finally` `AlarmTonePlayer.run`. Атрибуты трека и запроса фокуса — из одного `AlertAudioPlan`, чтобы не разошлись.
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** На устройстве (только руками, записать в отчёт): кнопка прослушивания алерта при играющей Яндекс Музыке на Pixel — в режиме «навигатор + приглушать» музыка тише, но не 0; «поверх» + 12 дБ — писк слышен поверх громкой музыки.
- [ ] **Step 6:** commit `feat(alerts): navigation-style alert sound and alert gain`.

### Task 3: Приглушать музыку, пока говорят в канале

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/voice/MusicDuckGate.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/voice/AndroidVoiceSdkSession.kt` (`onRoomEvent`: `RoomEvent.ActiveSpeakersChanged` → gate; закрытие сессии → `release`), `.../data/voice/VoiceAudioRouteMonitor.kt` (запрос/отпуск `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` с атрибутами `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`/`CONTENT_TYPE_SPEECH`, отдельный от фокуса `phoneCapture`), `AndroidVoiceSdkSessionFactory` — принимает `StateFlow<VoiceListenSettings>` вместо одного снимка (`AndroidModule.kt:~351`).
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/voice/MusicDuckGateTest.kt`

**Interfaces:**
- Consumes: `VoiceListenSettings.duckMusicWhileSpeaking` (Task 1).
- Produces: `class MusicDuckGate(private val holdMillis: Long = 1_500)` с `fun onSpeakers(remoteSpeaking: Boolean, nowMillis: Long): Boolean` (true = держать приглушение), `fun onTick(nowMillis: Long): Boolean`, `fun release(): Boolean` (всегда false). Свой голос не считается: в `remoteSpeaking` входят только удалённые участники.

- [ ] **Step 1: Тесты** `speechDucksImmediately`, `silenceShorterThanTheHoldKeepsTheDuck` (речь 0 мс, тишина с 100, `onTick(1500)` → true), `silenceLongerThanTheHoldReleases` (`onTick(1601)` → false), `ownSpeechDoesNotDuck`, `aClosedSessionReleasesTheDuck`, `turningTheSettingOffReleasesAtOnce`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** gate + Android: каждые 250 мс тик на `Dispatchers.Main` в scope сессии (не в `runTest`), запрос фокуса только на переходе false→true, отпуск на true→false и в `close()`.
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** На устройстве: в канале с музыкой — когда второй телефон говорит, музыка тише; через ~1,5 с после фразы — обратно. Записать, что делает Яндекс Музыка.
- [ ] **Step 6:** commit `feat(voice): duck music while someone in the channel is speaking`.

### Task 4: Громкость голоса — общая и по участникам

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/voice/ListenGain.kt`
- Modify: `.../androidMain/.../data/voice/AndroidVoiceSdkSession.kt` (`reconcileRemoteAudio` и смена настроек → `RemoteAudioTrack.setVolume(gain)` по участнику), `.../data/voice/PhoneVoicePlayback.kt` (`Stream` масштабирует и зажимает PCM тем же коэффициентом), `.../data/voice/OwnedVoiceAudio.kt` (`remoteTracks` получает коэффициенты)
- Modify: сопоставление `participant.identity` (= `VoiceRosterPeer.sessionId`) → `actorId`: сессия получает `actorFor: (identity: String) -> String?` из ростера канала (то, что уже питает `NearbyChannelRosterPanel`).
- UI: `presentation/root/nearby/NearbyConversationPanelScreen.kt` — долгое нажатие на участника → ползунок −30…+12 дБ с подписью «Громкость %имя%» и «Сбросить»; `presentation/settings/SettingsScreen.kt` — раздел «Звук»: «Громкость голоса» −10…+12 дБ.
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/voice/ListenGainTest.kt`

**Interfaces:**
- Consumes: `VoiceListenSettings`, `dbToGain`, `MAX_LISTEN_GAIN_DB`, `MIN_LISTEN_GAIN_DB` (Task 1).
- Produces: `fun listenGain(settings: VoiceListenSettings, actorId: String?): Double` (сумма дБ общей и участника, зажатая в `MIN..MAX`, затем в множитель; неизвестный `actorId` → только общая), `fun scalePcm16(samples: ShortArray, gain: Double)` (на месте, с насыщением до `Short.MIN_VALUE..MAX_VALUE`).

- [ ] **Step 1: Тесты** `unknownParticipantGetsOnlyTheMasterGain`, `participantAndMasterAddInDecibels` (+3 и +3 → 1.995), `combinedGainIsCappedAtTwelveDecibels` (+12 и +12 → 3.981), `mutingAParticipantToTheFloorIsNearSilent` (−30 → 0.0316), `phonePlaybackScalesAndClampsSamples` (`[10000, -20000]` × 2.0 → `[20000, -32768]`).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализация + применение при подписке, при смене настроек (поток из Task 3) и при переподключении.
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** На устройстве: слабый участник +9 дБ — заметно громче; общая −10 — голоса тише музыки не становятся неразборчивыми.
- [ ] **Step 6:** commit `feat(voice): master and per-participant listening volume`.

### Task 5: Микрофон — автоусиление, чувствительность, ограничитель

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/voice/MicGainStage.kt`
- Modify: `.../androidMain/.../data/voice/AndroidVoiceSdkSession.kt:136` (`createAudioTrack(options = LocalAudioTrackOptions(autoGainControl = settings.autoGain))`, остальные флаги `true` как сейчас), `.../data/voice/OwnedVoiceAudio.kt` (`setAudioBufferCallback`: после успешного чтения — `MicGainStage.process` над PCM16, в обеих ветках: SDK и `phoneCapture`), `.../data/radio/RadioAudioIo.kt` (тот же `MicGainStage` на захвате рации — рация следует настройкам микрофона голоса)
- UI: раздел «Звук» → «Микрофон»: переключатель «Автоусиление», ползунок «Чувствительность» 0…+12 дБ, ползунок «Потолок» −12…−1 дБFS с пояснением «ниже — тише выбросы (молния, вдох)».
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/voice/MicGainStageTest.kt`

**Interfaces:**
- Consumes: `MicProcessingSettings`, `dbToGain` (Task 1).
- Produces: `class MicGainStage(settings: MicProcessingSettings, sampleRate: Int)` с `fun process(samples: ShortArray, count: Int)`; ограничитель — мгновенная атака, отпускание 80 мс, порог = `limiterCeilingDbfs`.

- [ ] **Step 1: Тесты** `zeroSensitivityAndAHighCeilingLeaveSpeechUntouched`, `aBoostedFrameNeverExceedsTheCeiling` (+12 дБ на синус −6 dBFS, потолок −3 → пик ≤ −3 dBFS ± 0,1 дБ), `aShortBurstIsHeldDownThenReleases` (импульс 0 dBFS 20 мс, затем речь −20 dBFS: через 80 мс усиление вернулось ≥ 0,9 от исходного), `theStateIsPerStageNotShared`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализация; новый `MicGainStage` создаётся при смене настроек (сессия читает поток), AGC применяется при следующей публикации трека — записать в UI-подписи «применится при следующем подключении».
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** На устройстве: застегнуть молнию на кармане с телефоном при потолке −9 дБFS — у слушателя шорох не перекрывает речь другого. Поведение WebRTC AGC/NS после нашей ступени (она стоит **до** встроенной обработки) — наблюдать и записать.
- [ ] **Step 6:** commit `feat(voice): microphone auto-gain switch, sensitivity and burst limiter`.

### Task 6: Голос сам возвращается после обрыва

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/voice/VoiceRejoinPolicy.kt`
- Modify: владелец намерения «я в канале» — `NearbyVoiceController` (`AndroidModule.kt:~298` создаёт его): при `VoicePhase.FAILED`, если пользователь не нажимал «Отключиться», — новый `Join` со **свежим** `operationId` (редьюсер требует свежий: `VoiceSessionReducer.kt:30`) после задержки политики; UI-фаза в это время «Переподключение…», а не ошибка.
- Strings: `nearby_voice_rejoining` «Связь пропала, подключаюсь снова…» / «Connection lost, reconnecting…».
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/voice/VoiceRejoinPolicyTest.kt`

**Interfaces:**
- Produces: `class VoiceRejoinPolicy(private val delaysMillis: List<Long> = listOf(2_000, 4_000, 8_000, 15_000, 30_000), private val budgetMillis: Long = 10 * 60_000)` с `fun nextDelay(failureCount: Int, firstFailureAtMillis: Long, nowMillis: Long): Long?` (null = сдаться), `fun reset()`; последняя задержка повторяется до исчерпания бюджета.

- [ ] **Step 1: Тесты** `delaysGrowThenRepeatTheLast`, `rejoinStopsAfterTheBudget`, `aUserLeaveCancelsTheRejoin` (на уровне контроллера: фейковый движок падает, затем `Leave` — нового `Join` не выпущено; проверять выпущенные команды, не время), `aSuccessfulJoinResetsTheCount`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализация политики и проводка в контроллер; таймер — `delay` в scope контроллера, тест контроллера продвигает виртуальное время ровно на задержку (без бесконечных циклов).
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** На эмуляторе: войти в канал, выключить сеть на 20 с, включить — сессия возвращается сама; «Отключиться» во время ожидания — повтора нет.
- [ ] **Step 6:** commit `feat(voice): rejoin the channel by itself after a drop`.

### Task 7 (отложена): гейт по речи RNNoise

**Начинать, только если** после Task 5 в поездке выбросы (молния, вдох) всё ещё перекрывают речь при потолке −9 дБFS. Тогда: (1) вне телефона прогнать RNNoise на записи из поездки и прислать «до/после»; (2) только при хорошем результате — нативная библиотека и обработчик захвата. Отдельный план.

### Task 8: Сборка

- [ ] Поднять версию (`composeApp/build.gradle.kts`: `appVersionCode`/`appVersionName`), полный набор тестов, коммит.
- [ ] Влить в `main` (по слову пользователя) — workflow «Release to Telegram» соберёт и опубликует. В эту же сборку попадает клиентская половина фикса авторизации (`b9cac03f`).
