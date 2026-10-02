# Nearby: однократный review Astra

Дата: 2026-10-03. Reviewer: gpt-6-astra, high. Вызван один раз по явной просьбе
пользователя; повторные вызовы Astra без новой просьбы запрещены. Reviewer
не менял файлы и не создавал subagents. Проверена спецификация
`2026-10-03-nearby-voice-spaces-design.md`, исходное исследование и релевантный код.
Подробный implementation plan ещё не написан: оценена готовность к планированию.

## Замечания reviewer и решения основного агента

1. **P1: SFU-отзыв проверяется слишком поздно.** Self-hosted RemoveParticipant
   не инвалидирует старый JWT; server-refreshed tokens не ограничиваются обещанием
   исходного 60-секундного TTL. Webhook kick после повторного подключения не
   доказывает отсутствие промежуточного доступа к медиа. Решение: обязательное
   исследование закреплённых SFU/SDK до backend-плана/реализации. Строгий механизм
   остаётся НЕ доказанным; уточнение gate не является его реализацией.
2. **P1: зависший claim и pending/accepted session.** Потерянный join response,
   отмена или process death могут навсегда оставить busy. Решение: operationId,
   cancel tombstone даже без sessionId, конечный lifecycle и terminal expiry с
   освобождением своих claims, revoke sharing и SFU cleanup. ACCEPTED call входит
   в тот же media lifecycle. При недоступном backend живой SFU не сохраняет
   бесконечную неподтверждённую сессию.
3. **P1: accept побеждает cancel и оставляет звонок после ухода caller.** Решение:
   stop(callId) завершает и RINGING, и ACCEPTED; поздний accept не подключает
   отменившего пользователя. RINGING не отнимает у callee активный канал;
   подтверждённый accept атомарно заменяет его claim.
4. **P1: v1 adapter не знает generation.** Проверено: PublishSharingRequest
   содержит capturedAt/location/telemetry, stop адресуется groupId. Решение:
   после v2 cutover legacy sharing writes/voice операции требуют обновления и
   не меняют v2 состояние. Данные аккаунтов/друзей/групп сохраняются; полная
   поведенческая совместимость прежнего обещания явно отозвана.
5. **P2: не назначен location FGS для фоновой поездки.** Решение: активный
   sharing имеет location FGS lifecycle и permissions отдельно от microphone;
   hide-position/stop снимают Nearby demand, не navigation demand. Permission
   denial/revocation и заблокированный экран входят в приёмку.

Reviewer разрешает оставить исследовательскому этапу точную причину Xiaomi,
PHONE→headphones и SDK focus/routing. Navigation sharing остаётся вне критического
пути. Push→notification→видимая Activity→microphone FGS признан разумной моделью;
provisioning, notification channel/permission, Doze и отмена уведомлений должны
быть в плане и полевой проверке.

Итог reviewer: направление готово к подробному планированию с исследовательскими
gates; контракты 1–4 нужно закрыть до соответствующей backend/signalling реализации.
Четыре этапа обоснованы. Это не подтверждение исправления реального голоса.

## Проверка замечаний

Основной агент прочитал текущую официальную документацию токенов LiveKit и
проверил v1 DTO. Android manifest в checkout содержит только connectedDevice
FGS, targetSdk=36; новые microphone/location lifecycle ещё не реализованы.
Изменён только текст проекта. Повторного review Astra не запускалось.

- LiveKit: https://docs.livekit.io/frontends/reference/tokens-grants/
- Android: https://developer.android.com/develop/background-work/services/fgs/service-types
