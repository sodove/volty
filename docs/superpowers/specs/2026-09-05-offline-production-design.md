# Volty: полностью работающая production-система offline-навигации

## Запрос и определение результата

Пользователь просит план для **GPT-5.6 Luna**, который затем сам реализует,
развернёт и проверит production: готовые offline-регионы России, автоматическую
генерацию зарубежного региона по запросу приложения и автоматическое обновление.
Этот документ — проект системы, а не утверждение о её текущей готовности.
Текущая задача создаёт только документы; исполнение плана включает реальные
сборки данных и deployment. APK нового клиента — необходимая часть исполнения,
если старый клиент не понимает новые контракты. Физический телефон не трогать.

«Готово» означает: публичный HTTPS работает; исходники и ключи действительно
настроены; все территории принятой маски России опубликованы; новый зарубежный
запрос проходит от отсутствующего пакета до скачивания в приложение без участия
оператора; обновление и откат проверены на реальных пакетах; процессы переживают
рестарт и продолжают работу после окончания задачи Codex. Зелёные unit-тесты,
пустой каталог, один ЕКБ-пакет или запущенная очередь этого не доказывают.

## Подтверждённое текущее состояние

- Есть Ktor backend, PostgreSQL, Compose deployment, nginx example; публичный
  origin в конфигурации — `https://volty.sodove.ru`.
- Есть `tools/offline-navigation/build-package.sh`: одна явно заданная территория
  из локального PBF; Valhalla 3.6.3, SQLite FTS4, PMTiles. Скрипты подписи существуют.
- Есть проверенная доставка **уже собранных** пакетов (`package_cache.py`,
  `package_validation.py`, `package-service.py`) и readiness polling Android.
- Не существуют проверенный production artifact origin, автоматический загрузчик
  исходников, постоянный сборщик, полное покрытие РФ и scheduler обновлений.
- Schema 2 catalog плоский; Android ограничивает его 4 MiB и ожидает известный
  регион/manifest до скачивания. Заказ ещё не существующего региона требует
  нового discovery-контракта, а не ещё одного вызова старого ensure.
- `build-package.sh` каждый раз создаёт tools image/admin/timezone данные,
  использует `/tmp`, отдельный timezone helper с host network, bbox/центр ЕКБ
  по умолчанию. Эти предпосылки нужно устранить для массовой/международной сборки.
- В APK сейчас локальные glyph-диапазоны для русских подписей. Полноценные
  зарубежные offline-подписи не доказаны.
- Worktree содержит важные пользовательские незакоммиченные изменения.
  Нельзя начинать с `main` или копии одного HEAD, потеряв эти изменения.
- Пользователь указал существующий production VPS: `ssh sodovaya@mc.sodove.ru`
  (порт 22). Все production-сборки должны выполняться на нём. Homeserver
  `192.168.1.141` разрешён для тестов и не входит в production-схему.

## Архитектура

```text
Geofabrik index + versioned OSM snapshots
       │
       ▼
persistent build worker (Linux, existing pipeline, bounded Docker jobs)
       ▲ claims / leases                 │ validated upload
       │ private authenticated channel   ▼
PostgreSQL job/source/region registry → publisher + signing key + origin storage
       ▲                                 │ atomic signed generations
       │                                 ▼
Android → Ktor discovery API → immutable manifests / packages over HTTPS
```

Существующий VPS выполняет API, registry, publication, раздачу и тяжёлые сборки.
Worker запускает одну тяжёлую задачу одновременно, с измеренными лимитами RAM,
CPU, временного диска и I/O; при нехватке ресурсов приостанавливает приём сборок.
Резерв для работающих DB/API/voice имеет приоритет. Если даже пилот не помещается,
это явный capacity blocker, а не повод рисковать production или объявить готовность.
Покупка новой инфраструктуры не предполагается. Публичный API не получает Docker socket,
shell или приватный signing key. Builder не принимает URL/команды от пользователя.

Artifact origin создаётся **на нашей инфраструктуре**: publisher сам пишет готовые
релизы. Режим «сначала укажи внешний каталог наших пакетов» не является решением.
Старый relay-режим сохранить как отдельный совместимый режим, а не включать в
локальном production цепочку, скачивающую каталог сама у себя.

## География, объём и исходники

1. Использовать публичный машинный [индекс Geofabrik](https://download.geofabrik.de/index-v1.json):
   `properties.id`, `parent`, `urls.pbf`, `urls.updates`, геометрию. Внутренние
   endpoints `pbf-internal`/history не использовать. Реальный стартовый источник
   России — [страница выгрузки](https://download.geofabrik.de/russia.html).
2. Сетка `grid-v1`: базовые клетки 1° × 1°, идентификатор
   `g1-{latitudeIndex:03d}-{longitudeIndex:03d}`; индексы от -90/-180.
   Клетку, превышающую лимит пакета, делить на четыре дочерние с суффиксом
   `-q{path}`, path из `0,1,2,3` (SW, SE, NW, NE). Начальная глубина 0,
   максимум 4; на пределе нельзя публиковать превышающий лимит пакет.
3. Полуоткрытые границы дают единственного владельца точки; +180 нормализуется
   к -180. Геометрические проверки используют полигоны, а не один bbox страны.
   Маска initial РФ — версия геометрии `russia` из источника, её hash и различия
   с выбранной эксплуатационной маской явно фиксируются. Не писать «вся РФ»,
   если требуемая маска не покрыта. Учитывать эксклавы, острова и Чукотку.
4. Логическая территория, фактическая map/search coverage и routing buffer —
   разные вещи. Buffer по умолчанию 20 km. Источник должен покрывать весь buffer,
   в том числе за границей страны. Выбирать достаточный parent или согласованный
   набор соседних extract; проверять объединение полигонов и snapshots.
5. [Osmium](https://docs.osmcode.org/osmium/latest/osmium-extract.html) поддерживает
   batch extraction; разбивку выполнять иерархически. Переход ±180° разделять
   на части и объединять корректно. Не перечитывать PBF России с нуля для каждой
   маленькой клетки и не запускать тысячи извлечений одним процессом.
6. Начальные operational limits: target download 512 MiB, hard limit 1 GiB,
   installed 4 GiB на обычный регион, один heavy build на builder. Фактические
   host budgets фиксируются после пилота по RAM/disk/time, не по догадке о размере
   PBF. Изменение этих defaults допустимо только с измерением и записью причины.
7. За пределами России тот же алгоритм использует worldwide source index, без
   списка из нескольких вручную выбранных стран. Новая страна может потребовать
   загрузки большого исходника: показать стадию и очередь, не обещать мгновенно.
8. Покрытие не означает наличие дорог/адресов. Пакет с доказанно пустыми данными
   имеет явные capabilities, а не выдуманный маршрут или вечную failed-сборку.
   Mercator за ±85.05112878°, океан вне принятой land/source mask и реальные
   отсутствующие данные дают конкретную причину. Это географические ограничения,
   а не замаскированное «за пределами РФ не поддерживается».

## Контракты и доверие

- Manifest schema 3 сохраняет Valhalla/FTS4/PMTiles и добавляет signed per-component
  coverage, capability flags, source snapshot set и optional map-assets archive.
  Старый schema 2 читается новым клиентом через явный адаптер; его подпись сначала
  проверяется исходным codec, потом происходит нормализация. Не подписывать другой
  JSON после parse/reorder и не ослаблять signature/checksum gates.
- Source snapshot set содержит ID каждого extract, SHA-256, фактические OSM
  metadata/timestamps. Составной источник нельзя описывать выдуманным единым
  replicationSequence. Смешение generations запрещено, если не доказана допустимая
  согласованность; полная aligned replacement — безопасный default.
- `GET /offline/catalog.json` остаётся ограниченным legacy bootstrap, не всем
  мировым каталогом. Новые клиенты используют signed catalog pages и точечный
  discovery. Все страницы одного generation неизменяемы и связаны hash/signature;
  максимум 1 MiB на страницу, 100 записей. Не увеличивать старый 4 MiB лимит до
  сотен мегабайт ради обхода архитектуры.
- `POST /v1/offline/regions/resolve` получает coordinates, optional route points,
  purpose, client capabilities. Lookup не запускает build. `POST .../requests`
  заказывает server-generated region spec; не принимает URL, shell, image, bbox
  произвольного размера или готовый чужой manifest.
- Долгие стадии: queued, fetching_source, extracting, building_routing,
  building_search, building_map, validating, publishing, ready, retry_wait, failed.
  Polling не держит один HTTP-запрос часами. Job survives app/server restart.
  ready содержит только опубликованный проверенный manifest; status API не
  является основанием пропустить криптографическую проверку.
- Бесплатная автоматическая сборка не должна требовать аккаунта: использовать
  ограниченную anonymous installation session, quotas по session и доверенному
  peer IP плюс абсолютный global budget. Session не считается доказательством
  уникального человека. Учёт существующих аккаунтов можно добавить без обязательного
  login для карты. Raw coordinates не записывать в долговременные логи.
- Worker claims/heartbeat/result/upload защищены отдельным credential; private
  channel — loopback или закрытая Compose network на том же VPS.
  Никаких публичных unauthenticated admin/build endpoints.
- Один publisher владеет filesystem publication. Key существует вне git, APK
  содержит только соответствующий public key. При существующем keyId нельзя
  молча заменить ключ; потеря приватного ключа требует явной миграции trust anchor.

## Обновления и retention

Проверка source metadata ежедневно в 03:00 UTC с детерминированным jitter до
30 минут. Полное replacement snapshot — первая production-реализация; diff-based
обновления необязательны, пока не доказана непрерывность replication chains.
RU inventory обновляется еженедельно (воскресенье 02:00 UTC), зарубежные регионы,
использованные за последние 30 дней, — также еженедельно. Запрос региона старше
14 дней возвращает рабочую текущую версию и ставит refresh в очередь.

Неизменившийся source/pipeline fingerprint не запускает повторную сборку. Interactive
miss выше background update; после пяти interactive jobs дать слот background,
чтобы обновления не голодали. Lease recovery, backoff и reconciliation живут в
службах, а не в бесконечном turn агента. Не сообщать completion при merely queued.

Atomic current generation switch происходит после проверки всех ссылок. Поздний
результат старой generation не заменяет более новую. Rollback — новый подписанный
generation, указывающий на сохранённый старый release, не уменьшение generation
counter и не замена файлов под тем же URL.

RU required releases pinned. Foreign release без использования 90 дней можно
вывести из current registry, затем выдержать минимум семь дней grace. Хранить
минимум две последние исправные версии; учитывать in-flight downloads и активные
leases. APK не удаляет рабочий регион до успешной установки обновления. Auto-update
на устройстве по Wi-Fi, с повторной проверкой сети, достаточным местом и без
переключения runtime посреди активного маршрута.

## Эксплуатация и приёмка

Требуются backup/restore registry+signing key+catalog metadata, rollback scripts,
TLS, disk/queue/source-age metrics, readiness health, quotas и реальные проверки
внешнего HTTPS. Никаких новых платных сервисов без отдельного разрешения.
Существующие DB/voice/nginx не сносить, `down -v`/глобальный prune запрещены.

Пилот измеряется до полной генерации: плотная городская территория, обычная
территория, редкая/пустая территория. Full bootstrap resumeable и завершается
автоматически. Coverage proof — геометрическая разность маски и union опубликованных
leaf coverages плюс проверка каждой registry записи, а не несколько городских curl.

Реальные cold-path acceptance: Европа, Кавказ/нелатинские подписи, Восточная Азия
и Южное полушарие; пакет отсутствует до запросa и становится доступным без ручного
запуска pipeline. Offline native check нового APK: поиск, карта/glyphs, маршрут
при отключённой сети; проверка перехода границы клетки/страны в скачанной coverage.
Маршрут вне скачанной routing coverage даёт честный результат и online preparation
нужного bounded bundle. Нельзя склеивать независимые polyline вместо маршрутизации.

PMTiles delivery требует корректных byte ranges; это поддерживаемая модель
[Protomaps](https://docs.protomaps.com/pmtiles/cloud-storage). Public objects не
перекомпрессировать, immutable hashes не менять; private staging не раздавать.

Исполнитель заканчивает адресами работающего production, APK/update channel,
показателями покрытия/версий/свежести, доказательствами cold builds и restore,
командами эксплуатации и честным списком ограничений. Если ресурсы или доступ
не позволяют закончить, результат называется незавершённым, а не «готово,
осталось только настроить источник».
