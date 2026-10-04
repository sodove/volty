# Nearby: границы нового ядра и device diagnostics

Дата: 2026-10-03. Входы: approved voice-spaces design, source audit, SFU evidence.
Это контракты следующей реализации, не существующие Kotlin API и не результаты
полевого теста. Production код в foundations не изменён.

## Владение и идентификаторы

- `OperationId` — устойчивый id пользовательской команды join/accept, создаётся
  до запроса. Повтор запроса идемпотентен. Cancel по operationId создаёт tombstone
  даже если sessionId ещё неизвестен; join и cancel сериализуются на backend.
- `SessionId` — конкретный подтверждённый media lease; `Generation` — монотонный
  локальный запуск адаптера. Все callbacks, credentials, таймеры и sharing ACK
  несут оба значения. Callback от старой generation не меняет новую сессию.
- `RoomEpoch` — версия media room, отдельная от channelId. Это потенциальная
  граница SFU admission, а не доказанный механизм отзыва (см. SFU evidence).
- `ClaimId` — владение активной сессией аккаунта. Cleanup удаляет claim только
  при совпадении id/session, никогда по одному userId.
- Membership сервера существует независимо от claim. Disconnect не удаляет
  membership. Leave server — отдельная подтверждённая пользовательская команда.

Backend lease использует серверное время; локальный deadline — monotonic clock.
Время телефона не продлевает lease. Сервер гарантирует конечный pending/active
lifecycle и terminal expiry; для клиента отсутствие подтверждения >30 s
останавливает capture, receive и sharing даже при живом SFU. Это product policy,
не поведение LiveKit. Exact server TTL выбирается backend планом вместе с
доказанным revoke механизмом. Истёкшую сессию heartbeat не воскресит.

## Product ports следующего плана

`VoiceEngine` принимает start(session, generation, credentials), stop(generation),
setMute и setDeafen. События: Connecting, TransportConnected, CaptureStarted,
Published(trackId), Subscribed(peer, trackId), Reconnecting, Disconnected,
CaptureStopped, RenderStopped, Failure. Каждое tagged generation/session.
TransportConnected не означает слышимость. Peer roster поступает из
авторитетного backend snapshot/revision; SDK tracks — отдельное media состояние.
UI различает Joining/Connecting/Connected/Reconnecting/Stopping/Failed.

`SessionPort` принимает join(operationId, destination), cancel(operationId),
stop(sessionId), heartbeat(sessionId). Результаты tagged operationId/generation.
`SharingPort` публикует sessionId/generation/sequence/capturedAt, прекращает
публикацию и очищает очередь на stop. Старые snapshot/ACK не возвращают маркер.
Перед enqueue проверяется активный lease, перед send проверяется повторно.

`AudioRoutePort` хранит отдельно desired input/output, выбранное SDK устройство
и observed input/output с provenance (stream routing callback, recording config,
communication device) и timestamp. Unknown не превращается в подтверждённый
route; communication device не называется actual render stream.
Поддерживаемая пара PHONE input + HEADSET output устанавливается раздельно.
Неподдерживаемая пара отображается явно, не подменяется Speakerphone.

`ResourcePort` отвечает за foreground lifecycle и Nearby location demand.
Stop немедленно запрещает новые capture/share команды; cleanup повторяемый,
timeout каждого освобождения конечный. Backend cleanup/revoke выполняется
после локального прекращения; его ошибка не включает микрофон снова.
SDK reconnect имеет одного владельца — SDK; coordinator не создаёт параллельный
Room при Reconnecting. Неподтверждённый lease выключает SDK reconnect через stop.
При завершении stop остаётся error/retry cleanup evidence, если фактическое
освобождение устройства не подтверждено; UI не обещает остановленный AudioRecord
по одному mute ACK. Adapter обязан проверить stop/release на устройстве.

## Trace acceptance для будущих тестов

1. join(op1) → backend committed → response lost → cancel(op1): terminal op1,
   старый claim освобождён; late credentials вызывают cleanup, не engine.start.
2. RINGING → ACCEPTED committed → caller stop(callId): ENDED. Поздний accept
   не запускает caller. RINGING не захватывает claim callee; accept атомарно
   проверяет актуальность call и заменяет прежний claim callee. Cancel/accept
   сериализуются; call terminal cleanup освобождает claims обеих сторон по id.
3. session1 connected → backend ACK отсутствует >30 s → SFU жив:
   локальные capture/receive/share stopped; cleanup только claim(session1).
4. PHONE desired input + HEADSET desired output → route changed:
   actual route обновляется с provenance либо Unknown/Unsupported; UI не выдаёт
   preference/selected callback за фактический захват/воспроизведение.
5. hide position → voice продолжается: удаляется SOCIAL_SHARING demand,
   NAVIGATION demand остаётся. Full disconnect дополнительно останавливает
   voice, telemetry/navigation sharing, очереди и Nearby notification.
6. stop generation1 → join generation2 → поздний cleanup generation1:
   новая сессия не закрывается, новый claim не удаляется.
7. process death/restart: восстановление membership допустимо; автоматическое
   возобновление capture/share запрещено. Новое явное действие создаёт новый op.

## Legacy cutover

V1 sharing writes и voice mutations после v2 cutover возвращают upgrade-required;
они не изменяют v2 session/roster. Старые groupId-only stop не завершают новую
сессию. Сохраняются аккаунты и друзья; новые серверы создаются заново.
Отзыв прежнего требования о переносе групп: 2026-10-04 пользователь отменил
перенос старых групп. Исходные legacy-данные не удаляются; это не обещание
совместимости старого voice. Остальные границы cutover сохраняются.
Навигационный summary optional opt-in и выключается вместе с sharing; route
geometry/точная цель не входят в исходный протокол.

## Android: подтверждённые hooks и ограничения

Аудит закреплённых LiveKit 2.25.3/WebRTC 144.7559.05 подтверждает injectable
AudioHandler и JavaAudioDeviceModule, публичный preferred input setter, но не
публичную Kotlin фабрику сырых AudioRecord/AudioTrack. Null setter небезопасен.
Custom ADM/нативный adapter требует отдельного прототипа: нельзя обещать
per-stream observation и PHONE→headphones исходя только из public setter.
Callback replacement сохраняет SDK communication workaround явно; mute ACK
не является доказательством capture stop. Подробности/ссылки в source audit.

Репозиторий: minSdk26, target/compile36; manifest содержит только
MonitoringService connectedDevice FGS. AndroidLocationProvider уже владеет только
SOCIAL_SHARING demand через RideLocationRepository; LocationDemandPolicy сохраняет
NAVIGATION. MonitoringService не получает microphone/location ответственность
путём смены его типа. Следующий Android план добавляет NearbySessionService,
управляемый ResourcePort: microphone и location типы/permissions, runtime
RECORD_AUDIO и coarse/fine location, остановка каждого ресурса независимо.

Запуск после явного действия из видимой Activity, после runtime permissions;
входящий push только показывает уведомление, tap ведёт в Activity и принятие
звонка. Не запускать capture из push/boot/restart. Notification channel,
POST_NOTIFICATIONS denial и отмена incoming notification — отдельная приёмка.
FGS notification обязательна и при denied POST_NOTIFICATIONS; наличие уведомления
в drawer не предположение. При hide position service пересчитывает активные типы;
при stop снимает Nearby demands и прекращает собственную foreground работу.
Permission revocation/disabled location → объяснимое resource failure, не ложное
Connected; voice может продолжаться без sharing. Screen lock проверяется полем.

Основание: [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
и [while-in-use start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
Эта выбранная схема не использует исключения background start. Текущий manifest
такого сервиса ещё не реализует.

## Xiaomi: протокол диагностического APK

Текущее состояние **NOT MEASURED**: adb device отсутствует. Известный симптом
со слов пользователя: Xiaomi «17t pro», latest, двусторонняя тишина всегда.
Это не подтверждённая причина и не точный build fingerprint.

Diagnostic build фиксирует app/APK hash, SDK/SFU versions, модель/Android/HyperOS
build, timestamps+monotonic deltas, permissions, focus/mode, доступные устройства,
desired/selected/observed routes и provenance, ADM start/stop/errors,
transport/reconnect, publish/subscribe/mute, outbound/inbound RTP counters,
lease/generation и sanitized peer ids. Unknown observation остаётся Unknown.
Не экспортировать JWT/Authorization/passwords, речь/PCM, точные координаты,
MAC/личные peer names. Diagnostic APK не публикует production credentials в лог.

Два устройства одновременно проходят: без гарнитуры; с фактической гарнитурой
пользователя; PHONE input→headphones output; mute/deafen; network interruption;
screen lock; incoming system call; explicit disconnect с возвратом в канал.
Каждый прогон записывает состояние обоих endpoints и слышимость в обе стороны.
После stop проверить Android mic indicator, фактический ADM stop и отсутствие
новых media/sharing пакетов; после повторного join — новую generation/единую
численность roster. RTP без звука отделяет transport от capture/render; zero
RTP не объясняется автоматически Xiaomi routing. Не запрашивать повторно уже
известный симптом; точный build/гарнитура собираются при установке APK.

## Readiness

| Этап | Статус | Вход/приёмка |
|---|---|---|
| Pure voice core | READY FOR PLAN | tagged reducer/effects, stop/cancel races, bounded deadlines; без platform proof |
| Android adapter | READY FOR PROTOTYPE | pinned APIs; реальные route/capture/render и Xiaomi ещё NOT MEASURED |
| Backend/SFU | READY FOR SINGLE-NODE INTEGRATION PLAN | local admission overlay: revoke/expiry/race RTP measured; durable backend/restart integration and production readiness not proven |
| Servers/channels UX | contracts agreed | нужен backend wire/state и pure core; Compose/device QA отдельно |
| Direct calls/push | contracts agreed | claims/revoke gate + FCM provisioning/notification tests |
| Optional navigation summary | deferred | opt-in, outside critical path |

Все пять замечаний Astra связаны с конкретными границами: SFU gate, finite
operation/claim lifecycle, call stop-after-accept, legacy upgrade boundary,
ResourcePort/location FGS. Это контракты/исследовательский blocker, не заявленные
исправления. Повторный Astra review не выполнялся.
