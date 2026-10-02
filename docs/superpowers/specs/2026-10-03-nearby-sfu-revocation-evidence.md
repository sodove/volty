# Nearby: SFU revocation evidence

Дата: 2026-10-03. **Строгий отзыв NOT PROVEN; remove + короткий JWT опровергнут.**
Production не менялся. Локальный disposable loopback SFU остановлен после опыта.

## Runtime measurement

Official Windows LiveKit v1.13.5 binary, SHA256
`0CAF64F2CA235AF462C9BCBAA2670C7420A4229753BB067EABB6B93557701CA1`.
Release: https://github.com/livekit/livekit/releases/tag/v1.13.5
Go probe SDK v2.13.3, commit `cc10e20fa20269060a4eb460e6f9f64d7f667cbe`.
Это SFU test client, не Android 2.25.3 reconnect test.
Go auto toolchain 1.26.8; dependency lock сохранён в scratch.

Harness `main.go` SHA256
`D14109ED4540362172689ED8357D7E07925AFE00F4BE3DB054FFBB561EF8C57D`;
go.sum SHA256 `4A9D33D0F9895DB55F4E6A6E78D0392C55832B75D167454697DD97A2D0345A57`.
Лог `probe-run-3.log`, harness/config/dependencies сохранены в
`.superpowers/sdd/2026-10-03-nearby-foundations/sfu-probe/` изолированного worktree.
Room nonce `a37abce6-8293-4828-89bb-812e51139561` свежий для данного запуска.
Адреса 127.0.0.1 ports17881/17882/17883 + transparent WS proxy17884;
disposable nonproduction ключ. Proxy держал RefreshToken в памяти, bearer не
печатался/не сохранялся. Webhook не настроен; backend отсутствует в опыте.

Три клиента: A публикует synthetic Opus silence каждые20ms, B получает A и
публикует свою synthetic track, C наблюдает B. Счётчики считают nonempty RTP
payload реально полученной remote track. Baseline обязан дать >=10 пакетов
в обеих направлениях, иначе опыт INVALID. До каждой повторной публикации
снимается новый C baseline; B — новый receiver. Поэтому post-removal счётчики
не заимствованы из старой сессии. Media timeout8s; это не assertion на timing alone.

| Сценарий | B получает A | C получает новые B пакеты | Результат |
|---|---:|---:|---|
| Baseline | 31 | 11 | valid=true |
| Remove B → fresh join исходным JWT | 64 | 11 | subscribe=true, publish=true |
| Remove B → дождаться expiry исходного → fresh join refreshed JWT | 66 | 12 | subscribe=true, publish=true |

Исходный exp=1790973713, server-refreshed exp=1790974253: refresh продлил
доступ на540s относительно исходного expiry. Последняя попытка at=1790973714,
то есть исходный токен уже истёк. RemoveParticipant подтверждён admin RPC до
каждой повторной попытки; локальная прежняя Room также disconnect.
`counterexample_demonstrated=true`. SFU room удалена по завершении.

Предыдущие attempts сохранены: run1 INVALID — proxy не передавал Authorization,
upstream401, ни одного baseline; harness исправлен. Run2 valid, но обе попытки
до исходного expiry; run3 добавил проверку после expiry. Run2 не выдаётся за run3.
Windows SFU сообщил unsupported CPU monitoring: capacity management disabled;
media baseline успешен, capacity/load тестом не исследовались.

**Что доказано:** в этой pinned self-hosted версии удаление текущего участника
не предотвращает повторный duplex encoded RTP по ещё действующему bearer;
server-refresh переживает исходный60s TTL. Backend webhook не участвовал и не
ограничивал медиа. Это сильнее signalling join, но не доказательство слышимой
речи/Android routing: использованы синтетические encoded packets без микрофона.

## Source-backed interpretation и gate

Source audit pinning: SFU commit `3b9f118327b257301083a7c4aa46076c8012918a`,
protocol `28e604c046c6`, Android2.25.3. Refresh на join/every5min,
validFor=max10min/current remaining. RemoveParticipant закрывает текущую
participant session; JWT denylist/application claim lookup в audited admission
path нет. Subscription ACTIVE до asynchronous hook enqueue — порядок исходников,
не measurement времени доставки webhook. Runtime counterexample согласуется
с исходниками.

Матрица: original/refreshed fresh join measured; Android SDK auto-reconnect
NOT MEASURED; delayed webhook timing NOT MEASURED (вместо него hooks absent);
backend-offline fresh join measured только в изолированном SFU без backend,
не production outage; already-absent repeated removal NOT MEASURED. Эти пробелы
не мешают контрпримеру, но запрещают заявить полную successful revoke matrix.

Supported candidates из source: UpdateParticipant permission grants действует
на текущего participant, но admission старого JWT заново восстанавливает grants;
DeleteRoom уничтожает текущую room, но roomJoin token может создать её снова;
async webhook kick имеет окно до обработки. Они не выбраны как строгий revoke.
Обычный proxy только перед signalling не закрывает уже живой media session.

Следующее исследование выбирает один кандидат: SFU pre-admission enforcement
на application session/epoch + отключение текущей media; либо смена epoch с
полным прекращением всех доверенных публикаций в старую room и миграцией
активных peers. Последний вариант требует отдельного доказательства atomic
cutover/no-old-media при backend failure, а не просто rename room.
Ни один кандидат здесь не реализован и не объявлен безопасным.
Не менять self-hosted на Cloud автоматически. Подробный backend implementation
plan BLOCKED до доказанного механизма либо явно утверждённого ослабления
контракта. Pure local voice core и diagnostic Android prototype допускаются.

## Ограничения проверки

Android suite трижды остановилась до тестов: SQLDelight SQLite native extraction
пишет DLL lock в C:/WINDOWS и получает AccessDeniedException. Никаких тестов
не выполнено; schema/product code не менялись; commits не сделаны. Device adb
отсутствует: Xiaomi/PHONE→headphones NOT MEASURED.

Важное уточнение раннего вывода: отсутствие Docker на Windows не означало
отсутствия SFU runtime. Official native binary позволил измерить этот
контрпример. Пользователь позднее предоставил SSH endpoint
`sodovaya@mc.sodove.ru:/home/sodovaya/volty`; это отдельно проверяется read-only,
локальный опыт не выдаётся за измерение deployed backend.

## Read-only deployed inventory

SSH доступ подтверждён. Checkout `/home/sodovaya/volty` HEAD
`481dd865ae523c1c3f5aa68de4381571232b6664`. Docker container
`volty-livekit-1`, image `livekit/livekit-server:v1.13.5`, executable --version
подтверждает1.13.5. App healthy по docker status; health не доказывает voice.
Remote Voice.kt issueJoin выдаёт microphone publish/subscribe JWT для постоянной
`volty-group-$groupId`; в этом файле нет session lease/removal. Grep backend
main не нашёл RemoveParticipant/webhook. Deployed source и actual binary
не отождествляются: app image commit данным чтением не установлен.

Remote есть незакоммиченные Model.kt и SharingEndpointContractTest.kt:
с игнорированием CRLF это26 insertions/1 deletion. Они сохранены без изменений.
Не читалась .env, не менялись файлы, не перезапускались сервисы, не выполнялся
production room/media probe. Docker здесь доступен, поэтому следующий кандидат
можно испытывать в отдельном изолированном окружении, не выдавая synthetic
room опыт за исправление production.
