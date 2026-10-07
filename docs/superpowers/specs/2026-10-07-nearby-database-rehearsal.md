# Nearby: восстановление копии рабочей базы перед переключением

Проверка выполнена 2026-10-07 (`c941aq` snapshot, `c941ar` restore). Рабочая база не изменена; новый backend запущен только на отдельной восстановленной копии. Это rehearsal, не live cutover.

## Исходный проект и резервная копия

Read-only Docker-инвентаризация подтвердила существующий Compose project `volty`, config `/home/sodovaya/volty/docker-compose.yml`, физический PostgreSQL volume `volty_volty-postgres`. Старый backend image ID `sha256:3816953308836bd37ebdbe18f94596cbba09a35e221cd5acdc8886fd8a460f71`, SFU `sha256:1260b2e6346835a151bb19f065f043955acf453db04aa2f96698c1db6a85ae2a`; backend работает в прежнем legacy mode. Эти наблюдения нужно повторно сверить непосредственно перед переключением.

Перед `pg_dump --format=custom --no-owner --no-acl` проверены точные container/image IDs, project/service labels и physical volume. Snapshot SHA256 `844f00efce27c86b7173fe19941f10ff62c9bd28cbbbb61a3535627459c3b936`, 41500 bytes. Копия находится только на VPS: `/tmp/volty-nearby-backup-c941aq/database.dump`, manifest рядом. Фактические права каталога 0700, dump/manifest 0600. Пароли, bearer credentials и строки пользователей не выводились в отчёт; dump на локальную машину не скачивался.

## Восстановление и миграция

Snapshot повторно проверен по SHA256 и восстановлен `pg_restore --single-transaction --no-owner --no-acl` в отдельную PostgreSQL на tmpfs, без production volume и внешнего DB port. Новый backend image `sha256:b074df6ee5dcf5fda491776e679d7aab9d7e1fab140da377d6bc6436564d00e3` запущен в `voice_spaces` с искусственным окружением и loopback HTTP. Startup завершил миграции, `/health` ответил ok. SFU в этом rehearsal не запускался; его control origin искусственный, поэтому rehearsal не проверяет readiness/media. Это отдельно проверено full-stack прогоном.

До startup и после него сравнивались все исходные столбцы и строки с детерминированной сортировкой; сравнение salted SHA256 выполнялось только в памяти. Результаты:

| Таблица | Строк в snapshot | Сохранены без изменений |
|---|---:|---|
| users | 14 | да |
| friendships | 8 | да |
| refresh_tokens | 254 | да |
| one_time_tokens | 7 | да |
| groups | 5 | да, только исторические данные |
| group_members | 6 | да, только исторические данные |

Активных новых серверов, импортированных из старых групп (`create_request_hash IS NULL`), после startup нет. Прежние `sharing_sessions` не имеют неотозванного согласия; `live_updates` пуст. Это проверено на копии, не на рабочей базе. Миграция не переносит старые группы в доступные новые сервера.

Все fixture containers удалены после проверки точного ID/image/task label, отдельная сеть удалена. Последующая Docker-инвентаризация не нашла контейнеров этого rehearsal. Snapshot сохранён с указанными ограничениями доступа.

Эта копия отражает время pg_dump; пользователи продолжают изменять рабочую базу. Перед настоящим переключением нужна свежая согласованная копия и повторная проверка проекта/тома/старых writers. Нельзя автоматически восстанавливать старое согласие GPS при rollback. Этот rehearsal не проверяет вход телефона со старым аккаунтом, совместимость всех offline mounts или recovery работающих voice sessions после restart.
