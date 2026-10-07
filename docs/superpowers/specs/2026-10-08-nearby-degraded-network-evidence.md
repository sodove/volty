# Nearby: проверка ухудшенного соединения

Плохая связь остаётся возможной причиной исходных полевых проблем. Direct-origin
сбой исключил только необходимость Cloudflare для зависшей подписки, а не
проблемы сети. Подробности исходного сбоя и ограничения наблюдения:
[live transport](2026-10-07-nearby-live-transport.md).

Все проверки ниже используют четыре отдельных Go SDK процесса, четыре своих
временных аккаунта и приватный сервер на рабочем backend/SFU. Backend и SFU
images/configuration не менялись; debug endpoint выключен. Источник RTP —
синтетический silent Opus. Получение пакетов не доказывает слышимость,
декодирование Android или корректность маршрута микрофон → наушники.

## Выполненные проверки

| Прогон | Условие | Проверяемый результат |
|---|---|---|
| `c941dl`, три звонка | REST grants serial; четыре SDK connect одновременно | Все 12 RTP направлений; четыре одинаковых состава канала; отключение одного останавливает его media, остальные продолжают обмен |
| `c941dm` | Закрыт signaling socket первого клиента; новые попытки через свой proxy получают 503 пять секунд | Реальные SDK callbacks reconnecting/reconnected; рост всех 12 направлений после восстановления; прежние четыре participant SID и roster |
| `c941do` | 15 секунд сбрасывается каждая десятая UDP датаграмма, остальные задерживаются на 100 ms при пересылке | Все 12 направлений растут во время ограничений и после их снятия; roster/SID не меняются; обычное отключение и оставшиеся участники проходят проверку |
| `c941dp` | 15 секунд сбрасывается каждая вторая UDP датаграмма, остальные задерживаются на 500 ms при пересылке | Те же условия выполнены; это проверка доставки/восстановления, не пригодности звука для разговора при такой задержке |

Везде actual driver exit0, свои аккаунты и серверы удалены без
`cleanup_*_retry_required`. В parallel-прогонах concurrent join сам по себе
не воспроизвёл исходный дефект. Пассивные observers завершились штатно:
`dl` 12 connections/847 packets, `dm` 5/336, `do` 4/377, `dp` 4/368;
kernelDrops, unknownPackets, incompleteStreams, invalidStreams — ноль.

## Как действительно ухудшался медиаканал

Это не изменение счётчиков и не пауза генерации audio samples. Свой localhost
UDP relay `127.0.0.1:34781` пересылает датаграммы реальному TURN endpoint
`83.222.11.71:3478` и обратно. Прозрачный signaling proxy заменяет TURN URL
только в JoinResponse своих SDK клиентов; credentials остаются прежними и
обрабатываются только в памяти. SDK ICE policy принудительно Relay, остальные
ICE server entries исключены в этих своих JoinResponse. На обоих направлениях
relay действительно пропускает, задерживает или отбрасывает сетевые датаграммы.
Никакие firewall rules, system routes, nginx/DNS/SFU configuration не менялись.

Четыре JoinResponse переписаны, восемь UDP flows наблюдаются. Контроль включает
ограничения только после заработанного четырьмя клиентами baseline; таймер
снимает ограничения автоматически. Проверки требуют положительных реальных
drop/delay counters **при активных ограничениях** и прироста каждого RTP counter
от baseline; после восстановления берётся новая пара counters. Старые накопленные
значения не могут удовлетворить проверку восстановления.

`do`: при активных ограничениях 667 dropped/6009 delayed; итоговые 1184/10657,
17936 forwarded, writeErrors0. `dp`: при активных ограничениях 2220/2220;
итоговые 4065/4065, 11395 forwarded, writeErrors0. Проценты относятся к
датаграммам relay: трафик включает TURN/ICE control, а путь между двумя SDK
пересекает relay на двух направлениях. Это не измеренная точная доля потерь RTP
и не утверждение о задержке Android playback.

Signaling-обрыв проверяет отдельный транспорт: control endpoint закрывает
ровно одно из четырёх соединений, факт закрытия подтверждён ответом и log event.
Пять секунд proxy отвергал повторные handshakes; наблюдались десять отказов,
затем SDK callbacks reconnecting1/reconnected1. По окончании новый прирост
всех 12 RTP counters и четыре совпадающих HTTP roster/SID подтвердили recovery.
UDP медиа при этом отдельно не блокировалось.

В первой попытке UDP fixture `c941dn` media вообще не началось: два upstream
WebSocket handshake error/status0, ноль rewritten joins и UDP flows. Потери
не включались, fixture удалён. Категория этих двух ошибок не установлена;
нельзя задним числом назвать их DNS, TURN или исходным subscriber defect.
Повторный `do` прошёл. Следующий диагностический proxy выводит только fixed
failure category, без raw error/URL/auth; новых handshake failures с этой
классификацией в успешном прогоне не было.

## Оставшиеся ограничения

- Исходный редкий subscriber defect не устранён. Эти условия не воспроизвели
  его; плохая сеть другого вида, включая длительную задержку/обрыв signaling,
  остаётся открытой гипотезой.
- Прогоны проведены на Go SDK. Android/Xiaomi, split microphone/output,
  hotplug, notifications/foreground lifecycle и слышимость ждут телефонов,
  как попросил пользователь.
- Полная потеря IP-связности/долгий обрыв, активная production SFU recovery и
  killed-process incoming push этими прогонами не проверены.
- Исследован отдельный Android full-reconnect риск. Закреплённый SDK2.25.3
  очищает local publication map в prepareForFullReconnect, затем позже
  republishTracks возвращает только unmuted tracks. Текущий
  AndroidVoiceSdkSession не обрабатывает локальный RoomEvent.TrackPublished;
  reducer при Reconnecting очищает published, а UI разрешает audio settings
  только при published=true. setMuted возвращает false, если publication ещё
  отсутствует. Это пробел source-level reconcile/ordering, требующий отдельного
  исправления и проверки; Go signaling resume не доказывает правильность этого
  Android пути. Источники прочитаны локально, версия совпадает с Gradle:
  [source audit](2026-10-03-nearby-source-audit.md#reconnect-и-mute-ownership).

Все временные Windows proxy/UDP listeners и observer containers убраны.
Финальный read-only topology audit подтвердил прежние exact app/DB/SFU IDs,
healthy backend, один control/signaling origin, debug disabled, active rooms0
и readiness protocol4. Нет утверждения о готовности Nearby к боевому выпуску.
