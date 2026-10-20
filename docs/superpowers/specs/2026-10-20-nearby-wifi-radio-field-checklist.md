# Offline Wi-Fi radio: field test checklist

Spec: `2026-10-20-nearby-wifi-radio-design.md`. Everything the radio does on real hardware (Wi-Fi Direct group, 5 GHz,
audio routing, foreground service, battery) is unverified by unit tests and can only be judged here.

**For every item record:** phone model(s), Android version, result (pass / fail / notes), and attach the exported
diagnostics file (radio card on the server screen, "Отправить диагностику рации" / "Share radio diagnostics", on the
hub and on each client). The radio card shows the export button whenever a radio has run since the app started (also after it ended or failed; the last run's numbers stay until the next radio starts).

Before starting: every phone signed in to the same Volty server, opened once **online** after the radio build (the radio
key is fetched then), microphone / nearby-devices permissions granted, Wi-Fi switched on, each phone on a different
Android model where possible. Acceptance targets: mouth-to-ear <= ~0.3 s; holds across a ~100 m column on the move;
battery per hour <= ~15 % client, <= ~25 % hub.

| # | Check | How | Record |
|---|-------|-----|--------|
| 0 | Mouth-to-ear latency (method) | Two phones A and B 2-3 m apart, radio connected, B's speaker audible. A third device records video or audio of both. Clap sharply next to A's microphone; the recording shows the clap itself and, later, the clap coming out of B's speaker. Measure the offset between the two in an audio editor / frame by frame (repeat 5 times, take the median). Target <= ~0.3 s. Do it once per connection mode. | median offset per mode and phone pair |
| 1 | Two phones, static, 10 m | One raises ("Поднять рацию"), the other joins ("Подключиться к рации"). Talk both ways for 2 min. | join time, audible delay, loss % and RTT from the export |
| 2 | Two phones, static, 50 m | Same, line of sight. | same |
| 3 | Two phones, static, 100 m | Same. Note where audio first breaks up. | same, plus the distance at first dropout |
| 4 | Each connection mode at 50 m | Hub picks "Минимальная задержка", "Сбалансированный", "Надёжность" in turn (live change). Clients show the mode read-only. | which mode sounds best at 10 / 50 / 100 m; does a live mode change reach the client |
| 5 | Column ride, 3-5 phones, 20 min | Hub rides in the middle, others spread over ~100 m. Everyone talks now and then. | dropouts, who lost the radio and when, loss % per member |
| 6 | Capacity | One hub, add clients one by one with as many phones as available. Note the number at which "Рация заполнена" appears (cap should be hub + 9). | maximum reached with N phones available (state N; if the cap was not hit, say so); the 10th client's message |
| 7 | Hub rides away | Hub leaves range. Others must show "Рация потеряна" within ~3 s. Then press "Поднять заново" on one of them; the rest should rejoin on their own. | time to Lost, time until everyone is back |
| 8 | Hub phone returns | Hub comes back within 2 min. Clients should rejoin by themselves; if two hubs exist one gives way (random back-off up to 30 s). | rejoin time, any split-brain left over |
| 9 | Bluetooth headset, wind, speed | Ride at speed with a BT headset (and once with a wired one, once with no headset). | intelligibility, echo, does audio go to the headset (not the loudspeaker), does the loudspeaker take over only with nothing plugged in |
| 10 | Battery per hour | Note battery % at start and after 1 h of radio, hub and a client separately, **screen OFF, phones in pockets** as on a real ride (an earlier revision said "screen on", which measures the display, not the radio). The export shows start and current %. | % per hour for hub, % per hour for client (screen off) |
| 10a | Screen locked, 10 min | Radio connected, then lock both phones (screen off, in pockets) for 10 min; talk both ways now and then. Then repeat item 0's clap test with the listening phone's screen off. The export's `tick` / `late(>20ms)` lines show whether the audio thread was starved. | audio both ways for the whole 10 min (yes / when it stopped), mouth-to-ear with screen off vs. on |
| 11 | Mobile data keeps working | While on the radio, open the map / Nearby on a phone using mobile data. | maps load, voice-less Nearby works, no stalls |
| 12 | Wheel BLE telemetry | Connect an EUC / BMS, start the radio, watch the dashboard 10 min. | no telemetry gaps, no reconnects caused by the radio |
| 13 | Auto-join | On a client enable "Подключаться к рации автоматически" (grants location). Keep the app open, hub raises the radio. Then leave manually: it must not rejoin for ~10 min. Put the app in the background: it must not join. | time to auto-join, battery cost of scanning |
| 14 | Server voice exclusivity | Start the server voice conversation while on the radio, then raise the radio while in a conversation. With auto-join on, start the server voice while a radio is nearby: auto-join must not take the radio back. After each switch (voice -> radio and radio -> voice) check the audio route: the other side is heard from the headset / speaker that was in use, the microphone works, and after leaving both the phone is back to normal (media plays from the speaker, a regular phone call works) — i.e. MODE_IN_COMMUNICATION and the communication device were released. | one always stops the other; auto-join stays away from an active voice call; audio route after each switch and after leaving both |
| 15 | Old key / no internet | On a phone that has not been online for > 24 h, join a radio that does not exist. Expect "Ключ рации устарел". | message shown |
| 16 | Android versions | Repeat 1 and 9 on Android 10-12 and on 13+ (permission set differs) and, if available, 14+ (foreground-service rules). | per version: permission dialogs, service start, audio routing |

Anything that fails: keep the export from both ends, and note the phone models and the moment (time) it happened.
