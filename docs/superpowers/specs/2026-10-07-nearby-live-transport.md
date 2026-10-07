# Nearby: рабочее развёртывание и публичный транспорт

Состояние на 2026-10-07: backend и SFU переключены на новый Nearby. Проверки Android на телефонах ещё не выполнены. Пакеты синтетического Opus подтверждают передачу RTP, но не слышимость, источник микрофона или выход звука.

## Развёртывание

Использован существующий Compose project `volty`, база и volume `volty_volty-postgres`. Пересозданы только app и livekit; рабочий checkout `/home/sodovaya/volty` не изменён. Backend работает в `voice_spaces`, control origin `http://livekit:7880`, singleton override выключен.

- Backend image: `sha256:b074df6ee5dcf5fda491776e679d7aab9d7e1fab140da377d6bc6436564d00e3`, исходники `35bc5a40`.
- SFU image: `sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`, протокол 4, production overlay без тестовых barrier endpoints.
- Release directory: `/home/sodovaya/volty-nearby-releases/2026-10-07-c941ay`, права 0700. Содержит явный override, manifest и подготовленный rollback на прежние точные образы. Rollback не выполнялся.
- После остановки прежнего backend сделана резервная копия: `pre-cutover.database.dump`, 41500 байт, права 0600, SHA256 `7b039df3113290cf00847b0b9a2b604b237e66fcd45ccb02268437eadfe3cb85`. Дамп не экспортирован в репозиторий.
- Фактические health HTTPS и readiness протокола 4 прошли. Compose вызван с `--no-deps --no-build --pull never`; DB не пересоздавалась и не восстанавливалась.

Cutover executor уже выполнен. Повторять его нельзя: старые container IDs удалены и execution journal существует. Успешный health не означает приёмку звука.

## Публичные проверки

Windows SDK подключался через `https://volty.sodove.ru` и `wss://voice.sodove.ru`. Backend выдавал participant tokens; SDK не создавал их сам. Credentials оставались в памяти. Искусственные аккаунты использовали `@nearby.invalid`, сервер был приватным; сервер и четыре тестовых аккаунта удалены после каждого прогона.

- `c941b4`, `c941b5`: неудача. Четыре SDK подключились, heartbeat сообщал CONNECTED, но за 12 секунд не набралось минимум 10 пакетов в каждой из 12 направленных пар. В `b5` joins занимали 2.0–2.17 секунды и первоначальный lease был около 30 секунд. Поэтому гипотеза истечения lease из-за последовательных joins не подтверждена.
- `c941b6`: остановился до создания аккаунтов из-за локальной ошибки DNS. Это не проверка RTP.
- `c941b8`, actual exit0: четыре клиента с обязательным TURN relay обменялись пакетами во всех 12 направлениях; состав совпал; серверное отключение прекратило передачу отключённого участника без остановки его SDK, остальные продолжили разговор. Отдельные relay-пара и звонок друзьям также прошли, завершение звонка остановило оба потока.
- `c941b9`, actual exit0: те же четыре клиента в обычном режиме прошли полный обмен, согласование состава и отключение; отдельная forced-relay пара и звонок друзьям тоже прошли.

- `c941bg`, actual exit1: повторный обычный прогон снова не прошёл начальные 12 секунд; единственная пара receiver 1 ← publisher 4 оставалась без пакетов, остальные 11 росли. Запланированная sustained-часть поэтому не была достигнута. Тестовые ресурсы очищены.
- `c941bh`, actual exit0: отдельный диагностический прогон с пределом ожидания 60 секунд получил полный обмен уже при первой проверке. Затем десять интервалов по 4 секунды показали рост всех 12 направлений при heartbeat CONNECTED, то есть работа продолжилась за первоначальным 30-секундным lease. Relay-пара, звонок и отключение тоже прошли. Более длинный diagnostic timeout не исправляет прежние failures и не меняет production contract.

Начальная нестабильность обычного режима пока не объяснена. Успехи не отменяют ранних неудач и не доказывают работу на всех сетях. TURN здесь UDP3478, без TLS. Проверка pinned LiveKit source показала: UDP TURN URL строится из RTC.NodeIP, а не TURN.Domain; изменение domain по предположению о Cloudflare не выполнялось.

## Сохранение реальных данных

`c941bf`, actual exit0: snapshot восстановлен в отдельный принадлежащий прогону PostgreSQL на tmpfs; исходные данные сравнивались с рабочей базой в памяти. Контейнер проверки удалён.

- 14 исходных пользователей, 8 дружб, 7 одноразовых токенов, 5 исторических групп и 6 членств сохранились без изменения исходных строк.
- Все 254 исходных refresh credentials сохранились. У одного токена после snapshot штатно изменились `revoked_at` и `replaced_by_hash`; утверждение о полной неизменности live refresh rows было бы неверным.
- Старые группы не стали активными новыми серверами; действующего старого согласия sharing и старых координат нет.
- Нет оставшихся активных voice claims, незавершённых admission/cleanup операций или активных тестовых аккаунтов.

## Открытая приёмка

Подписанный клиент `c8d0c90a`: Volty 0.7.13/code36, APK SHA256 `e39a453a434b6ad9226254c81dc1ebba7af63aa39e6033e66102c9034f5ea70f`. Native client source не менялся после этой сборки. `c941b3` заново выполнил Android suite без caches: 3373 теста, ноль failure/error/skip, 35 executed tasks, actual exit0.

Остаются реальные телефоны: двусторонняя слышимость Xiaomi, PHONE mic + HEADSET output, hotplug, фон/FGS, уведомления и размеры UI. Полный live restart/recovery ещё не проверен. Killed-process push не реализован. Чеклист: [приёмка на телефонах](../plans/2026-10-07-nearby-device-acceptance.md).


Дополнение: диагностические `c941bl` trial2 и `c941br` trial1 снова не прошли даже 60-секундное окно. В момент начального отсутствия потока SDK видел часть опубликованных треков без подписки и иногда не видел удалённого участника; OnTrackSubscriptionFailed не сработал. `br` показал connected и два ICE connected для каждого клиента, без зарегистрированных ошибок сигналинга/ICE в начальной фазе. Позднее heartbeat стал CONNECTING/EXPIRED и чтение ранее работавших треков завершилось. Это наблюдения, не установленная причина. Предшествующие три `c941bn` прошли; один успех или серия успехов не устраняют этот дефект.

Отдельный full-stack стенд теперь проверил восстановление backend при живом SFU (`c941bs`, 28 checks); рабочие сервисы для этого не перезапускались. Результаты — в [full stack evidence](2026-10-07-nearby-full-stack-evidence.md).


## Явная гарантия единственного рабочего origin

Обновление: первоначальное описание singleton=false выше относится к первому cutover. `c941bz` подтвердил отдельными Docker network/alias/process/port observations единственный локальный control origin. `c941c0` dry-run и execute завершились actual exit0: третий `singleton.override.yaml` меняет только `VOLTY_NEARBY_SINGLETON_ORIGIN` на true. Новый app `515506f5678aab9124788881a733dd22d9e86110b9bf7f12c43d4caada05fd8a`, прежний exact backend image; остальные environment values сохранены, рабочие SFU/DB IDs не изменились, public health прошёл. Credentials не экспортировались. `singleton-manifest.json` имеет state LIVE_SINGLETON_CONFIG_VERIFIED.

Это включает уже предусмотренное восстановление после замены единственного SFU. Изолированный `c941bx`, actual exit0/17 checks, подтвердил retirement старых сессий, quiet old media, сохранение членства и новые четыре реальные SDK transports во всех 12 направлениях после SFU restart. `c941bw` со singletonOrigin=false дал PENDING вместо выдачи новых credentials; это прежняя operational limitation, а не потеря пользователей. Рабочий SFU не перезапускался. [Команды и границы guarantee](../plans/2026-10-07-nearby-cutover-preparation.md#рабочая-конфигурация-после-проверки-единственного-origin).


После изменения конфигурации `c941c1` сравнил отдельный SDK process на каждого участника (те же закреплённые зависимости, public WSS/RTP): trial1 прошёл, trial2 воспроизвёл пропавшую подписку, actual exit1; trial3 не запускался. Cleanup тестовых аккаунтов/приватного сервера выполнен. Гипотеза влияния нескольких Room в одном Go-процессе не подтверждена. Singleton setting не исправляет этот независимый дефект и не выдаётся за такое исправление.


## Границы сигналинга и SFU forwarding

Редкий дефект остаётся открытым. В `c941cf` один receiver не подписался на одного publisher и не принял ни одного пакета, пока другие 11 направлений росли. Прозрачный диагностический proxy видел ACTIVE participant update, offer с тремя audio sections и client answer со всеми тремя audio port9/recvonly. Наблюдение выполнено перед proxy WriteMessage: это не доказательство обработки answer сервером или отправки RTP. Answer ID0 допустим в закреплённом SFU; исправление ID по предположению не выполнялось. Свежий heartbeat credit подтверждён до деградации, поэтому initial lease expiry не объявляется причиной.

Уточнение прежней диагностики: Room.SetLogger не заменяет Pion logger factory, созданную из глобального SDK logger. Старые room-only counters не доказывали отсутствие Pion warnings. Новый global route проверен отдельно (`c941ce`); в неудачном `c941cf` он не зарегистрировал IncomingUnhandledRTP/undeclaredMediaFailure. Это не исключает все низкоуровневые ошибки. Ранее здесь утверждалось: «Negotiation timeout логируется INFO, а live logging=WARN; отсутствие WARN также не исключает этот путь». Утверждение о live logging=WARN отозвано: `c941d0` прочитал фактический `log_level: info` и INFO events. Прежняя выборка брала только WARN/ERROR и потому не показывала уже существовавшие тайм-ауты.

Отдельный стенд с exact backend/SFU images (`c941ci`) подтвердил, что DebugInfo показывает binding и счётчики направления. Повторяемый runner теперь проверяет эти условия приватно: [forwarding evidence](2026-10-07-nearby-full-stack-evidence.md#отправка-каждого-трека-со-стороны-sfu). Production debug endpoints не включались.

Попытки Windows → отдельный стенд через SSH signaling (`c941ch`, `c941cj`, `c941ck`) не установили начальный медиатранспорт и не воспроизвели исходное частичное выпадение уже подключённого трека. В отдельном публичном UDP50020 fixture SFU показывал правильный node IP/порт, но все ICE pairs имели ноль полученных запросов/ответов; TCP18882 был доступен обычному socket, TCP ICE pairs не наблюдались. Причина недоступности этого UDP пути не установлена. Все fixture accounts/servers, контейнеры/сети и собственный SSH tunnel удалены. Это ограничение стенда, а не доказанная причина production-дефекта; live сеть/firewall/DNS не менялись.


## Несогласованный последний трек — состояние рабочего SFU

`c941d1` прочитал сохранённые INFO в окне неудачного `c941cf`. В первом subscriber timeout у receiver2 были current offer/answer с двумя audio sections и pending local offer с тремя; remotePending отсутствовал. Прокси видел ответ клиента с тремя sections, но SFU не завершил его согласование. Это уточняет границу ошибки, а не устанавливает причину потери ответа.

`c941d2` добавил post-WriteMessage receipt без изменения или инъекции сигналов: trial1 прошёл, trial2 снова потерял receiver1←publisher4. Ответ со всеми тремя audio sections был записан в upstream socket успешно. У используемого Go SDK не наблюдались application Ping/Pong, поэтому такую запись нельзя выдавать за подтверждение обработки ответа SFU.

Для измерения server forwarding временно включён приватный debug listener 17884 (`c941cy`). Reviewed Compose graph менял только команду SFU; image, environment, published ports, backend/DB IDs и исходные три Compose файла сохранялись. Listener не публиковался, development=false, signaling `/debug/rooms` отдавал 404. В private bridge отдельно проверены прежние app/DB/SFU и две штатные offline services по exact IDs/images. Включение и восстановление выполнялись только при отсутствии active rooms; это первые рабочие замены SFU на том же image, а не испытание recovery активного разговора.

`c941d3` шесть прогонов прошли. Затем observer стал вызываться только после обнаружения zero RTP минимум три секунды, чтобы уменьшить влияние DebugInfo/CreateSenderReport на нормальное согласование. `c941d4` trial2 и `c941d5` trial5 воспроизвели дефект. В обоих SFU имел ровно один unbound DownTrack (bound=0, PacketCount отсутствовал); другие 11 были bound=2 и отправляли пакеты. INFO снова показал current answer с двумя audio sections и pending offer с тремя.

Расширенная выборка goroutines `d5` показала четыре RTC session worker в select и четыре WebSocket ReadRequest в IO wait; sampled operations queues ожидали работу, SCTP readers — данные. Заблокированный SetRemoteDescription/handleRemoteAnswer в этих снимках не наблюдался. Это не подтверждение исправности каждой внутренней очереди и не доказательство вины Cloudflare/nginx. Raw SDP/ICE credentials, identity values и goroutine arguments не экспортировались; наблюдались только выбранные состояния, имена функций и ordinal counters своих тестовых участников.

Временный debug выключен через проверенное восстановление (`c941cy --restore`, actual exit0). Текущий SFU `46633b9b1a5d7051ea2d6f1a86c7a211bf771b26b37e90dd7306186a916a99f0`, прежний image `sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`; временный `91e663…` и исходный `c050…` удалены. Backend `515506…` и DB `793a…` не менялись. Execution journal `private-debug-c941cy.json` имеет PRIVATE_DEBUG_RESTORED/verified=true. Собственные аккаунты/приватные серверы удалены, временный proxy остановлен по exact executable/listener ownership.

Для следующего сравнения подтверждено read-only TLS соединение к origin 83.222.11.71 с проверкой сертификата и SNI voice.sodove.ru. Можно сравнить публичный Cloudflare путь с прямым origin при тех же TLS/Host, SDK и публичном RTP, не меняя DNS/nginx. Само TLS соединение не проверяет WebSocket/media и не устанавливает причину дефекта. Исправление голосового сбоя по-прежнему не доказано.

## Прямой origin и пассивное наблюдение локального signaling

Сравнение `c941da` выполнено: direct trials 1/2 прошли, trial3 потерял
receiver1 ← publisher4; public trials 1/2 потеряли поток, trial3 прошёл.
Все шесть своих серверов/наборов аккаунтов удалены. Прозрачный proxy менял
только TCP destination; URL, проверка TLS, SNI, Host и содержимое сообщений
сохранялись. В неудачном direct trial четыре joins и ноль upstream write errors.
**Гипотеза, что Cloudflare необходим для этого сбоя, опровергнута.** Такое
малое число прогонов не позволяет сравнивать надёжность маршрутов. DNS/nginx
и рабочий SFU не изменялись.

Добавлен [пассивный observer](../../../tools/nearby-sfu-gate/probe/wiretrace/README.md)
для IPv4 TCP lo/127.0.0.1:17880. Его stdout содержит только connection ordinal,
время, offer/answer и количество audio sections, а также fixed capture counters.
Нет pcap, raw SDP, HTTP headers, credentials или identities. Захват начинается
с SYN, буферы/число соединений/время ограничены; неполные кадры не считаются
доставленными. Девять parser/privacy tests прошли после RED отсутствующих
реализаций; Linux static build и vet прошли. Это не Android mutation sweep.

Первый запуск `c941db` не начал захват: обычный UID образа не мог выполнить
смонтированный observer. При этом отдельно начавшийся звонок снова потерял
поток; аккаунты/сервер удалены. Для последующих запусков используется UID0
только внутри disposable container: host network, read-only filesystem,
cap-drop ALL/cap-add NET_RAW, no-new-privileges, 64 MiB/CPU/pid limits,
единственный read-only mount — собственный executable. Рабочие сервисы
не перезапускались, debug endpoint не включался.

В `c941dc` observer увидел 12 последовательных соединений трёх успешных
direct-origin звонков; финальные kernelDrops/unknownPackets/incompleteStreams/
invalidStreams — все ноль. Offer/answer с тремя audio sections наблюдались
на loopback. Это подтверждает пригодность измерения, а не исправление сбоя.
Наблюдение на опубликованном Docker port не доказывает чтение/обработку
ответа самим SFU; следующий переход — опубликованный port → SFU parser —
по-прежнему требует доказательства. Отсутствие события само по себе не
доказывает отсутствие ответа. Нужна корреляция при воспроизведённом сбое.

`c941dd` два публичных звонка прошли; восемь соединений, все четыре final
capture counters — ноль. После усиления учёта SYN retransmission/повторного
использования портов окончательный executable отдельно проверен в `c941df`:
три публичных звонка прошли, observer завершился штатно. Итого восемь звонков
под пассивным наблюдением без воспроизведения исходного дефекта; это не
доказательство его устранения или влияния observer на вероятность сбоя.
Все свои контейнеры, proxy listeners, аккаунты и приватные серверы убраны.
Read-only live audit снова подтвердил прежние exact app/DB/SFU IDs/images,
healthy backend, единственный signaling origin, debug disabled, active rooms0
и readiness protocol4. Свежий Android прогон `c941de`: actual exit0, 3373 теста
в 334 свежих XML, zero failure/error/skip, 35 executed tasks. Проверки на
телефонах остаются отложенными по просьбе пользователя.

Узкий read-only review Luna/xhigh выявил ошибки именно observer: обычное
HTTP body могло быть принято за WebSocket, неконаническая длина кадра
принималась, server accept не сверялся с client key и данные после Close
могли учитываться. Все воспроизведены RED и исправлены, fixtures теперь
имеют полный валидный handshake. Повторный review подтвердил устранение
найденных проблем. Текущие 17 parser/privacy tests, Linux vet/static build
прошли; это review инструмента, не всего Nearby и не доказательство
исправления рабочего голосового дефекта. Дополнительно пояснено, что
1 MiB ограничивает logical payload, а heap overhead byte-map больше;
map сбрасывается после drain, общий лимит памяти контейнера — 64 MiB.
Промежуточная строгая версия `c941dh` отдельно наблюдала ещё один успешный
звонок: четыре соединения, все capture counters ноль; fixture удалён.

Окончательная reviewed версия отдельно проверена в `c941dj`: публичный
звонок прошёл, четыре соединения/287 packets, final kernelDrops,
unknownPackets, incompleteStreams, invalidStreams — ноль; observer exit0.
Свежий Android после последних правок: exit0/50s/35 executed,
3373 теста/334 свежих XML/zero failure-error-skip. Все временные процессы,
контейнеры и свои fixtures удалены, live topology audit снова прошёл.

Дополнительная read-only корреляция `c941dk` подтвердила тот же SFU symptom
на неудачном **direct-origin** trial3 `c941da`: четверка RTC starts
18:50:14–18:50:22 UTC, первый receiver потерял publisher4; proxy connection9
записал успешную отправку answer с тремя audio sections в 18:50:23.527.
Первый timeout этой четверки — 18:50:38.984: localCurrent2,
localPending3, remoteCurrent2, remotePending=null. Более ранний timeout
18:50:07 принадлежит предыдущему разговору и не считается началом этого
сбоя. Raw SDP/ICE credentials не экспортировались. Ответ не был committed
на SFU и без Cloudflare; точная граница потери/необработки ещё не установлена.
