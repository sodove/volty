# Nearby: проверка собранного backend, PostgreSQL и SFU

Обновление 2026-10-07: ниже записан изолированный стенд. Позже выполнены рабочий переход, публичные обычные/TURN прогоны и сравнение исходных аккаунтов/друзей с live DB. Предыдущее ограничение «deployment/TLS/TURN не проверены» уточнено отдельными наблюдениями: [live transport](2026-10-07-nearby-live-transport.md). Приёмка телефонов остаётся открытой.

Изолированный связный прогон выполнен 2026-10-07. Production не переключён; проверки телефона, Xiaomi, наушников и внешнего NAT/TURN остаются открытыми.

## Артефакты

- Backend source `35bc5a40`: свежий `test installDist --rerun-tasks --no-build-cache --no-configuration-cache`, фактический exit0, 226 тестов без ошибок/пропусков. В контекст скопированы 84 файла дистрибутива; SHA256 каждого проверен после передачи. Изменённый checkout VPS не использован.
- Runtime `eclipse-temurin@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5`; Docker build networknone/pullfalse. Backend image ID `sha256:b074df6ee5dcf5fda491776e679d7aab9d7e1fab140da377d6bc6436564d00e3`. Manifest хранит исходную ревизию и хеши файлов. Пользователь контейнера 10001; конфигурация и секреты в образ не включены.
- Backend docker-save SHA256 `49c7eaef3b25dc24c21037a4f302efab1e07c9934a1776721377b2ae289c5f17`; скачанная локальная копия совпала.
- SFU image ID `sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`: тот же production overlay без тестовых barrier endpoints, ранее проверенный отдельной матрицей RTP/expiry/revoke.
- PostgreSQL `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`; отдельная пустая база на tmpfs, четыре искусственных аккаунта.
- Linux SDK probe: Go1.24.4, CGO0/amd64, readonly уже закреплённые зависимости. SHA256 `cb8a7ea9ae65b7eaa0794f94d38204127310bcefe347572bf8c091ee394251a8`.

## Наблюдаемые результаты

Финальный прогон `c941am`, actual exit0, 23 проверки. HTTP-запросы направлены в собранный backend; participant JWT выдал этот backend после регистрации admission на указанном SFU. SDK не создаёт собственные participant tokens. Bearer credentials передаются процессу SDK через stdin, остаются в памяти и не экспортируются в файлы или отчёт.

- Публичный сервер доступен в каталоге; приватный отсутствует и недоступен постороннему. Одноразовое приватное приглашение принимается и повторяется идемпотентно, второй пользователь не может использовать исчерпанное приглашение.
- Четыре пользователя вступают в публичный сервер. Выданный токен сам по себе означает CONNECTING; затем четыре настоящих SDK-клиента устанавливают соединения и публикуют синтетический Opus. Каждая из 12 направленных пар получает минимум 10 RTP-пакетов; heartbeat всех четырёх возвращает CONNECTED.
- Четыре HTTP-наблюдателя получают одинаковые actor/session/SID/phase/mediaPresence проекции с четырьмя CONNECTED участниками. Это транспорт, не доказательство слышимости на Android.
- Отзыв геопозиции возвращает REVOKED/нулевой остаток; голос остаётся CONNECTED.
- Backend disconnect четвёртого участника выполнен при продолжающем работать SDK и его цикле публикации. После 300 ms drain за 1 s не выросли счётчики пакетов от него и к нему; между остальными участниками счётчики продолжают расти. Поздний heartbeat остаётся CANCELLED. Все четыре наблюдателя видят три оставшихся actor IDs; членство отключённого пользователя сохранено.
- После принятия дружбы прямой звонок появляется во входящих, принимается; caller resume использует исходный operationId. Два SDK-клиента устанавливают двустороннюю передачу, оба HTTP-наблюдателя видят двух CONNECTED участников. Stop завершает звонок и прекращает медиапоток обоих SDK-клиентов без их локальной остановки. Отдельный leave удаляет членство в сервере.
- Контейнеры удалены только после сравнения точного ID, image ID и task label. Отдельная сеть удалена; последующая Docker-инвентаризация подтвердила отсутствие контейнеров/сети этого прогона. Production volumes/config/services не использованы.

## Диагностика и границы доказательства

Первый SDK-прогон через Docker bridge с loopback ICE node_ip дал connection timeout. При сохранении тех же backend/SFU image IDs SFU перенесён в host network с bind только на loopback и gateway отдельной fixture сети; backend/база остаются в bridge. Двусторонний RTP установился. Это исправление топологии стенда, не найденная ошибка production-приложения и не проверка публичной сети.

Ранний драйвер неверно ожидал mediaPresence ACTIVE вместо контрактного CONNECTED. Другой драйвер посылал новый operationId в caller resume и правильно получил operation_conflict. Исправлены именно драйверы; серверные контракты не ослаблены. Все неудачные прогоны также удалили свои контейнеры; один preflight остановился до создания ресурсов из-за TCP TIME_WAIT, при отсутствии слушателя. Bind preflight теперь использует SO_REUSEADDR.

Этот прогон не проверял миграцию реальной базы с прежними аккаунтами/друзьями. Позже snapshot рабочей базы был восстановлен и мигрирован на отдельной копии с сохранением исходных строк; см. `2026-10-07-nearby-database-rehearsal.md`. По-прежнему не проверены restart/recovery этого полного стека, deployment reverse proxy/TLS/TURN, Android audio capture/rendering, Xiaomi, физическое разделение PHONE+HEADSET, системные уведомления/FGS и UI на устройстве. Killed-process push не реализован. Успешный стенд не означает готовность всех этих сценариев.

Дополнительный прогон `c941au`, actual exit0, 24 проверки: добавлен запуск точного `/usr/bin/curl --fail --silent --max-time 5 http://127.0.0.1:8080/health` внутри контейнера backend от пользователя образа. Остальные 23 связные проверки повторно прошли. Это подтверждает новый healthcheck contract для явного backend Compose override; старый BusyBox отсутствует в этом runtime.

## Повторение

Из `tools/nearby-sfu-gate/probe` собрать Linux executable командой `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -mod=readonly -trimpath -buildvcs=false -o <sdk-probe> ./stackprobe`. Передать его и `run-stack-probe.py` как `sdk-probe`/`probe.py` в новый `/tmp/volty-nearby-stack-<unique>`; каталог должен содержать только эти файлы. На amd64 Docker host запускать:

```text
python3 probe.py --workspace /tmp/volty-nearby-stack-<unique> \
  --backend-image sha256:<verified-backend-id> \
  --sfu-image sha256:<verified-sfu-id> \
  --sdk-sha256 <verified-sdk-hash>
```

Runner требует точные image IDs и хеш executable, собственную свежую сеть/каталог, свободные loopback порты 18089/17881/17882 и UDP17883. Использует только известные fixture credentials, resource limits, read-only backend/SFU и tmpfs базы. Этот runner создаёт и удаляет тестовые контейнеры; он не запускает production Compose.


## Восстановление backend на отдельном стенде

`c941bs`, actual exit0, 28 проверок: runner с `--restart-backend` перезапустил только собственный backend после согласования четырёх подключённых клиентов. Перед restart повторно проверены container ID, exact image и task label. За пределы стенда действие не выходит.

Backend вернулся в healthy в пределах 20-секундного окна; четыре durable session остались CONNECTED с первоначальными SID и одинаковыми roster. RTP продолжился во всех 12 направлениях. Затем прошли обычные GPS revoke, disconnect/late heartbeat, дружеский звонок и stop. Все контейнеры и сеть удалены. Это перезапуск backend при живом SFU, а не SFU restart и не live restart рабочей системы.

История: первый ad-hoc `c941bm` дал тот же результат. Перенос сценария в optional flag сначала выявил ошибку runner `args` shadowed by Docker argv list (`c941bp`): restart не был вызван, fixture cleanup прошёл. Аргументы контейнера переименованы; исправленный reusable runner прошёл `c941bs`. Android suite после исправления: `c941bt`, actual exit0/55s/35 executed tasks, 3373 теста без failure/error/skip, rerun/no caches.

Для повторения добавить `--restart-backend` к приведённой выше команде. Default по-прежнему выполняет исходные 24 проверки без перезапуска.


## Отправка каждого трека со стороны SFU

Runner принимает дополнительный `--observe-forwarding`. Только при этом флаге собственный SFU получает debug listener 17884 с теми же явными bind addresses: loopback и gateway приватной сети стенда. HTTP debug не публикуется наружу. Production config и default runner не меняются.

`c941cq`, actual exit0: сочетание `--observe-forwarding --restart-backend` выполнило ровно 30 проверок и удалило свои контейнеры/сеть. До restart проверены все 12 направлений: один AUDIO publisher track на участника, ровно три различных удалённых subscriber, bound=2, оба muted=false, sender PacketCount >=10. Повторное наблюдение подтвердило рост каждого счётчика. Затем прошли restart backend, прежние SID/rosters, продолжающийся RTP, GPS revoke, disconnect и дружеский звонок.

До реализации CLI проверка `c941cp` завершилась exit2 на неизвестном флаге до Docker actions. `c941cr` исполнил извлечённую из фактического runner функцию на двух намеренно некогерентных контрольных данных: unbound track с положительным packet counter и отсутствующее направление. Оба отвергнуты. Это узкая проверка диагностического условия, а не mutation sweep приложения.

Повторение: добавить `--observe-forwarding`; нужен свободный loopback TCP17884. Без restart ожидаются 26 checks, с restart — 30. Raw room/participant/track/SSRC debug values остаются в памяти; наружу выводятся только фиксированные имена проверок. Счётчик отправки не доказывает физическую слышимость и не заменяет проверку клиентского RTP.
