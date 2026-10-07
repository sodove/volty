# Nearby: подготовка согласованного переключения

Обновление 2026-10-07: описанное ниже состояние подготовки историческое. Рабочие backend/SFU уже переключены, база сохранена. Повторно выполнять переход нельзя. Фактические результаты и оставшиеся ограничения: [live transport](../specs/2026-10-07-nearby-live-transport.md).

Статус: образ SFU и отдельный override подготовлены; production **не переключён**. Проверка JSON ниже выполнена настоящим Docker Compose только на скопированной конфигурации с искусственными значениями окружения. Она не доказывает состояние production-базы, проекта или секретов.

## Подготовленные артефакты

- SFU: проверенный image ID `sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`. Архив docker-save SHA256 `d02e36c4e22ee5cb8b40f5a5cefa2c0fb97eb08821e66cd8a2aafe840d7915c2`; контрольные и синтетические RTP-проверки пройдены на этом ID.
- Backend source `35bc5a40`, image ID `sha256:b074df6ee5dcf5fda491776e679d7aab9d7e1fab140da377d6bc6436564d00e3`, docker-save SHA256 `49c7eaef3b25dc24c21037a4f302efab1e07c9934a1776721377b2ae289c5f17`; свежий дистрибутив и все 84 файла проверены по SHA256, локальная архивная копия совпала. Опциональный `-BackendImageId` генератора ниже фиксирует backend image/pull never, сбрасывает source build через `!reset null` и задаёт curl healthcheck, проверенный в этом образе.
- Генератор `tools/nearby-sfu-gate/prepare-compose-override.ps1 -ImageId <exact-sha256-id> -Destination <fresh-file>`. Он создаёт файл, ничего не запускает и не перезаписывает существующий файл. Пример свежего проверенного файла хранится в ignored ledger как c941a7-override.yml.
- Override выбирает exact image ID с pull_policy never, provider livekit, mode voice_spaces, внутренний control origin http://livekit:7880, conservative singleton=false. Остальные поля берутся из исходного Compose. Штатная конфигурация без override остаётся legacy/stock.
- Реальный Compose merge с fixture env проверен: exact image/pull policy, новый graph, control origin/conservative cleanup, исходная JDBC-привязка, read-only SFU config, существующий shell+server launch contract и named DB volume. Это проверка структуры, не запуск нового backend. В контейнере отдельно проверено наличие /bin/sh и исполняемого /livekit-server.

## Следующая изолированная проверка

До live-переключения собрать backend из согласованной ревизии и прогнать его вместе с этим image ID на отдельной тестовой базе/проекте/портах. Проверить создание публичного и приватного сервера, вступление/приглашение, channel admission/renew/disconnect, звонок другу, roster нескольких подключённых участников и отдельную геопозицию. Отзыв и поздний запрос не должны возвращать прежнюю сессию; отключение от войса не удаляет членство. Использовать искусственные аккаунты и минимальные статические результаты без экспорта bearer credentials. Ранняя редакция фиксировала, что полный связный прогон не выполнен. Теперь проверка состоялась на пустой изолированной PostgreSQL и неизменяемых образах: четыре настоящих SDK-транспорта, согласованный roster, disconnect/late heartbeat, отдельный GPS revoke и двусторонний прямой звонок с остановкой медиапотока. Подробности и ограничения — в `docs/superpowers/specs/2026-10-07-nearby-full-stack-evidence.md`. Production этим прогоном не проверен. Позже выполнен restore rehearsal snapshot рабочей базы: исходные строки аккаунтов/друзей/токенов сохранены, legacy imports/sharing отозваны только на копии; см. `docs/superpowers/specs/2026-10-07-nearby-database-rehearsal.md`.

## Порядок live-перехода

Read-only инвентаризация 2026-10-07: существующий project `volty`, physical DB volume `volty_volty-postgres`; snapshot и restore rehearsal проверены. Это наблюдение, а не разрешение слепо использовать устаревшие IDs — повторно проверить перед переключением. Полное окружение production не экспортировалось.

1. Зафиксировать исходные backend/SFU image IDs и фактические container IDs, существующее имя Compose project и физическое имя volume базы. Не выводить production .env/credential-bearing rendered config в отчёты. Новый каталог bundle не должен случайно создать новый пустой DB volume: запуск использует проверенный прежний project/volume.
2. Подготовить и проверить резервную копию/восстановление базы до новой startup-транзакции. Старые аккаунты и друзья остаются; группы не импортируются в новые сервера.
3. Подготовить неизменяемый backend bundle, проверенный архив SFU, совместимый подписанный APK и отдельный override. Проверить SHA256 до загрузки; после загрузки подтвердить точный image ID. Не собирать из изменённого server checkout и не сбрасывать его.
4. В согласованное окно остановить именно старые backend writers и старый SFU, затем запустить подготовленный backend/SFU с явным набором файлов Compose и проверенным project. Эти операции данным документом/генератором не выполняются. Readiness одного узла не является доказательством, что прежний SFU остановлен; singleton=true требует отдельного подтверждения единственного origin.
5. Проверить реальный readiness протокола4, public signaling/media connectivity и новые API. Выполнить phone acceptance, включая Xiaomi и четыре реальных участника. При отказе отделить ошибку развертывания, сервера, транспорта и устройств; не объявлять успех по одному зелёному индикатору.
6. Откат возвращает исходные артефакты, но не восстанавливает автоматически отозванное согласие на передачу геопозиции. Старые ephemeral позиции уже удаляются startup-транзакцией; повторное sharing требует явного действия пользователя.

Проверки только fixture-конфигурации можно повторять через `check-compose-configuration.py --config <fixture-json> --image <exact-id>`; helper не печатает содержимое JSON. Нельзя выдавать его проверку стандартной fixture базы за доказательство, что реальный production volume выбран правильно. Подписанный APK/маршруты/наушники и Xiaomi проверяются по отдельному device acceptance плану.


## Рабочая конфигурация после проверки единственного origin

Дополнение 2026-10-07: существующий локальный bridge `volty_default` имеет ровно один target для alias `livekit`; app не задаёт DNS/ExtraHosts overrides, control origin напрямую `http://livekit:7880`, один SFU-процесс без Redis, публичный порт 17880 принадлежит тому же SFU. Это отдельная проверка топологии, а не вывод из readiness singleNode.

На основании этой проверки к release добавлен `singleton.override.yaml` с единственным изменением `VOLTY_NEARBY_SINGLETON_ORIGIN: "true"`. Пересоздан только app из прежнего exact image. База и SFU сохранили IDs. Результат и rollback записаны в `singleton-manifest.json` в существующем release directory. Исходный `nearby.override.yaml` остаётся с conservative false как generic default.

Для следующих операций с рабочим app использовать **все три** Compose-файла:

```text
docker compose --project-name volty --env-file /home/sodovaya/volty/.env \
  -f /home/sodovaya/volty/docker-compose.yml \
  -f /home/sodovaya/volty-nearby-releases/2026-10-07-c941ay/nearby.override.yaml \
  -f /home/sodovaya/volty-nearby-releases/2026-10-07-c941ay/singleton.override.yaml \
  up -d --no-deps --no-build --pull never app
```

Это команда с побочным действием, не диагностическая проверка. Пропуск третьего файла вернёт strict mode, при котором после замены SFU повторный join ждёт подтверждения cleanup. Откат настройки использует прежние первые два файла и пересоздаёт только app; база не восстанавливается. Executor `c941c0-singleton.py` уже выполнен и запрещает повторный запуск при существующем journal. Перед переходом к нескольким SFU/балансировщику необходимо отключить singleton guarantee и внедрить подтверждение retirement прежних origins.

На отдельном стенде `c941bx` проверено: после подтверждённого restart единственного SFU старые четыре сессии CANCELLED, старое медиа молчит, членство сохраняется; четыре новых session identities снова передают пакеты во всех 12 направлениях и имеют согласованный состав. На момент этого результата рабочий SFU ещё не перезапускался. Обновление: ниже записаны последующие пересоздания при отсутствии active rooms; recovery живого разговора по-прежнему не проверен.


## Текущий SFU после временной диагностики

`c941cy` временно добавил private debug listener только в command SFU через in-memory Compose override; source Compose files не менялись. Перед каждым действием подтверждены active_rooms=0, единственный origin, exact image/ports/environment и прежние app/DB. Debug не публиковался и затем был выключен восстановлением исходной команды. `private-debug-c941cy.json` в том же release имеет PRIVATE_DEBUG_RESTORED/verified=true.

Текущий container ID SFU: `46633b9b1a5d7051ea2d6f1a86c7a211bf771b26b37e90dd7306186a916a99f0`, прежний image `sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`. Первоначальный `c050…` и промежуточный diagnostic `91e663…` удалены. Backend `515506…`, DB `793a…`, singleton=true и перечисленные три обязательных Compose файла сохранены. Начальные cutover/singleton manifests описывают первоначальные экземпляры; для текущего audit учитывать последующий journal и проверять фактический container/image/alias/process mapping.

`c941d7` подтвердил отключённый debug, healthy app, exclusive control origin, единственный SFU process, active_rooms=0 и protocol4 readiness. Эти quiescent replacements не заменяют проверку recovery активного звонка и не устраняют rare lost-answer defect. [Наблюдения и границы](../specs/2026-10-07-nearby-live-transport.md#несогласованный-последний-трек--состояние-рабочего-sfu).
