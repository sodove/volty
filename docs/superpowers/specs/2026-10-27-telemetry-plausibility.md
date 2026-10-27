# Telemetry plausibility: rejecting numbers the wheel never meant

**Date:** 2026-10-27 · **Status:** audit + proposal, nothing implemented · **Base:** `main` @ 2a78dea2
**Evidence:** rider's T4 ride of 2026-10-27 (screenshots described by the rider), three real BLE dumps
of the same T4 (`volty-ble-dump.txt`, `(2)`, `(3)`), the code at the cited lines. Analysis scripts:
`C:\Users\sodovaya\AppData\Local\Temp\claude\C--Users-sodovaya-git-volty\d749f47d-a84c-4fd1-9422-4d745a8f53bb\scratchpad\plaus\`
(`parse.py` re-implements the app's Begode reassembly byte for byte; `dropsim.py`, `odospeed.py`, `series.py`).

---

## Кратко для райдера (RU)

- **67 км за 15 минут — это не битый Bluetooth, а «разорванное» чтение одометра в самом колесе.**
  Игла 2883,6 км ровно на 65,536 км (2^16 м) выше настоящего значения: колесо отдало старшую половину
  32-битного счётчика уже после переноса, а младшую — ещё до него. Истинный одометр в тот момент был
  ≈ 2818,05 км = 0x2B0000 м — как раз граница переноса, и она лежит внутри вашей поездки. Это вывод из
  арифметики (в дампах самой иглы нет), но он предсказывает число 2883,6 до десятой.
- Потеря или перестановка BLE-пакетов **не может** склеить два кадра Begode в правдоподобный: я выкинул
  каждый пакет (по 1, 2, 3 подряд) и переставил соседние во всех трёх дампах — ни одного ложного кадра.
  Проверка хвоста `5A5A5A5A` держит склейки. Не держит она только испорченные значения внутри кадра.
- **В кадре уже есть второй свидетель:** счётчик «с включения» (кадр 0x00, байты 8..9) идёт в ногу с
  одометром метр в метр (60/60, 242/242, 174/174 м в трёх дампах). Шаг одометра, который этот счётчик
  не подтверждает, — физически невозможен. Это правило ничего не знает о скорости и не может
  отбросить настоящую езду.
- **График мощности контроллера почти пуст не из-за «свежести» склейки** — в дампах 267 из 267 кадров
  0x07 имеют свежий 0x00. Виновата запись: каждый кадр 0x00 пишет в ту же серию «мощность неизвестна»,
  серия выглядит как «значение, дыра, значение, дыра…», и обзор графика (≤ 600 точек) сдаётся и
  показывает только 4 точки: первую, последнюю, минимум и максимум. Это ровно то, что вы видели. Из-за
  тех же дыр энергия по мощности контроллера не интегрируется вообще (нет плитки Wh и Wh/км).
- **«Батарея −39 %»**: минус — это просто формат плитки (всегда рисует «−»). Само 39 — это
  max − min заряда, оценённого по *мгновенному* напряжению; просадка под нагрузкой входит в min.
  Нужно «начало минус конец» по спокойным участкам, со знаком.
- Предложение: отбрасывать **только невозможное** (шаг счётчика, не подтверждённый свидетелем; ШИМ > 100;
  скорость сверх физического предела), **всегда считать** отброшенное (счётчик в «Всё о ТС»), сырые
  байты отброшенного кадра — в дамп. Итоги поездки — из суммы правдоподобных приращений, дистанция —
  по GPS там, где он есть, колесо — только для участков без GPS.
- Попутно: в дампе (3) поле ШИМ кадра 0x07 у T4 **не нулевое** и растёт со скоростью до 100 % на
  ~80 км/ч, а слово live-кадра 14..15 поменялось с 0x0080 на 0x08A0. В дампах (1) и (2) оно было 0.
  Это противоречит записи «T4 не сообщает ШИМ» — стоит проверить, что изменилось между 26 и 27 октября.

---

## 0. The incident, restated with the rider's correction

| Observation | Value | What it implies |
|---|---|---|
| Ride summary distance | ≈ 67.1 km in 15 min | |
| Average | ≈ 254.1 km/h | 67.1 km / ~0.264 h — and **greater than the 55.1 km/h top speed**, which is impossible on its face |
| GPS track | ~5–7 km | the true distance |
| Odometer chart | min 2816.4, avg 2818.4, max 2883.6 | **one** single-sample needle (the rider's correction: the second vertical line was the chart cursor) |
| 2883.6 − 2816.4 | 67.2 km | the summary distance is exactly the needle's height |
| Other ride | "Battery ≈ −39 %", pack still at 79 % | §3.3 |
| Controller power chart | a few dots at the ends, one needle to 5084 W, min −1798 W | §4 |

The distance chain in code: `historyRideTotals` takes `trip_distance_km` max − min first, then
`odometer_km` (`presentation/history/HistoryRideTotals.kt:71-77,83`); a Begode's trip is
`odometer − baseline` (`data/bms/BegodeProtocol.kt:504-508`), so the odometer needle is also a trip
needle of the same height. Average = distance / duration (`HistoryRideTotals.kt:89-90`), with no
cross-check against the peak (`:91`).

---

## 1. Root cause of corrupt frames, per protocol

### 1.1 Begode — reassembly is sound; values inside a frame are not protected

Reassembly (`data/bms/BegodeProtocol.kt:1273-1300`): append every notification to one buffer
(`:330-333`, `ByteArrayAccumulator.kt:10`), find `55 AA` (`:1302-1309`), wait for 24 bytes, require
`5A 5A 5A 5A` at bytes 20..23 (`:1311-1316`), else advance **one** byte (`:1289-1295`). No checksum
exists in the format (`:23-28`, `:1339-1347`).

Gates that exist today:

| Frame | Gate | Where | Catches |
|---|---|---|---|
| all | 4-byte tail | `:1289` | misalignment, byte loss/insertion, splices (see measurement below) |
| 0x00 | `liveVoltageRaw > 0` | `:1421`, `:1469` | the boot placeholder |
| 0x01 | `t1==0 && t2==0 && sectionV==0` → boot placeholder | `:1676` | boot; **on the T4 this is permanent** (its 0x01 has no temps/section/current, every frame in all three dumps) |
| 0x01 | `TEMP_SANITY = -39..150` per sensor | `:1679-1680`, `:2100` | an out-of-range battery temperature |
| 0x02/0x03 | cell `mv in 1..5000` | `:1707` | absurd cells |
| 0x04, 0x07 | `isZeroPaddedPayload` | `:1348-1353`, `:1543`, `:1593` | boot only — its own KDoc says it does **not** catch corruption (`:1339-1346`) |
| 0x07 | duty `abs().coerceAtMost(100)` | `:1615` | clamps garbage to 100 % (fails loud by design, `:676-679`) |
| alarms | `TemperatureGate` for motor/ESC/battery temperature | `domain/alert/TemperatureGate.kt:46-108`, `AlarmController.kt:134-160,211` | a single corrupt temperature frame |

So `isZeroPaddedPayload` is not the only gate, but it is the only one on 0x04/0x07, and **nothing
anywhere judges the odometer, speed, currents, voltage or the alert byte**.

**Measurement — can a lost/reordered notification splice two frames into a valid one?**
`dropsim.py` replays each dump through the app's exact reassembly with (a) every notification
dropped, singly and in runs of 2 and 3; (b) every pair of notifications at distance 1 and 2 swapped;
(c) one byte dropped or inserted at every 7th position of the first 200 notifications. A "novel" frame
is any frame that passes the tail and is not in the clean decode.

| Dump | drops (1/2/3) | swaps | byte drop/insert | novel frames |
|---|---|---|---|---|
| (1) 220 notifications | 660 trials | 436 | 1098 | **0** |
| (2) 530 | 1590 | — | 1098 | **0** |
| (3) 598 | 1794 | 1192 | 1098 | **0** |

Why: the wheel emits a fixed 4-frame cycle (0x00, 0x01, 0x04, 0x07 every ~310 ms, measured
median 309–314 ms per type in all dumps) as notifications of 20,20,20,20,16 bytes; a frame's tail
lands at a fixed offset of a notification, and losing whole notifications moves a `55 AA` 20 or 40
bytes away from any `5A5A5A5A`. **Conclusion (measured, for this notification layout):** BLE loss and
reordering are not the mechanism; the tail is a good splice detector. What the tail cannot see is a
frame whose *values* are wrong while its framing is right.

**Measurement — the wheel's UART is shared.** Dump (3) at +32.2 s carries ~125 bytes of ASCII/GBK
text inside the FFE1 stream: `The application is running...\r\n`, `ADC_5V_At_Start= 2043 MiV`,
`MPU6500` + GBK "initialisation succeeded", `97000 V`. The live frame's power-on counter did not reset
(182 m before and after), so the motherboard did not reboot; some board on the same UART printed a
boot log and the BLE module forwarded it verbatim. The reassembly discarded all of it (0 novel
frames). Implication: the BLE module is a transparent UART bridge, and whatever happens on the
wheel's internal serial line reaches us unchecked.

**The 0x04 needle — root cause (inferred, numerically exact).** Odometer is `u32BE` at bytes 2..5
(`:1544`). The needle 2883.6 km is 0x2BFFxx m (0x2BFFE6..0x2BFFFF = 2883.558..2883.583 km rounds to
2883.6). The true odometer one frame away was ~0x2AFFxx or ~0x2B00xx, i.e. **the ride crossed
0x2B0000 m = 2818.048 km**, which lies inside the ride (min 2816.4, average 2818.4). A reader that
takes the *new* high word (0x002B) and the *old* low word (0xFFxx) produces exactly
0x2BFFxx = true + 65 536 m. This is the classic torn read of a 32-bit counter on a 16-bit-wide path
(firmware copying the two halves at different instants). The opposite tear (low word already
wrapped to 0x00xx, high word not yet incremented) gives a dip of −65.5 km — equally possible, not
observed. This is inference: no dump contains a needle, and no dump crosses a 0x10000 boundary
(they span 2809005–2809313 and 2816071–2816245 m). It is the only explanation found that predicts
the needle's value to 0.1 km; a random bit flip of bit 16 would need to coincide with the last ~25 m
before a carry, which is improbable.

**Independent witness inside the stream (measured).** The live frame's power-on distance
(bytes 8..9, `u16BE`, `:1414`) and the 0x04 odometer advance identically: over the three dumps
power-on span = odometer span = 60, 242, 174 m. Speed integrated over 3 cycles matches the odometer
step within −2.0…+3.2 m (`odospeed.py`), including the 81–83 km/h free-spin sections. So a
torn/corrupt odometer can be convicted **without a clock and without a speed limit**: its step
disagrees with the power-on counter's step (mod 65 536) by ~65 km.

**Other frames.** Speed (0x00 bytes 4..5), currents, temperatures, duty and the alert byte are 16- or
8-bit fields — a 32-bit tear cannot touch them, but in-frame corruption (the ET Max "130 °C",
`TemperatureGate.kt:8-15`) can. The 0x04 alert byte 14 drives `faults` (`:1551-1552`) and
`wheelPwmAlarm` (`:852`); a corrupt one raises a CRITICAL `CONTROLLER_FAULT` notification on a
single sample (`domain/usecase/AlertEngine.kt:314-321`) and a WHEEL_PWM_ALARM banner row
(`domain/alert/ActiveAlertTracker.kt:101-103`).

**Latches.** `sawTrueDuty` and `sawMotorTempEvidence` are one-way per connection (`:1590-1609`);
a single corrupt 0x07 latches them. The trip baseline is the **first** genuine 0x04 (`:1558`); a torn
first frame offsets the live trip tile by ±65.5 km for the whole connection (the history max−min
cancels a constant offset, the dashboard does not).

### 1.2 Other protocols — integrity at a glance

| Protocol | Integrity check | Cite | Splice/corruption exposure |
|---|---|---|---|
| **Begode** | 4-byte tail only | `BegodeProtocol.kt:1311` | values unprotected (§1.1) |
| **KingSong** | 2-byte tail `5A 5A` at 18..19 only; plus range gates on the live frame | `KingSongProtocol.kt:20-22,182,200-201,219,311` | weaker than Begode (2 tail bytes); odometer is 32-bit (`kingSongDistance`, `:429`) → same torn-read class (inferred); voltage/current/temperature already range-gated |
| **Veteran / Leaperkim** | CRC32 only when `length > 38`; older frames none | `VeteranProtocol.kt:21-25,197-198,549` | legacy frames unprotected; odometer is word-swapped 32-bit (`reverseBeInt(12)`, `:256`) → torn-read class (inferred); trip already re-baselines on a backward step (`:282-288`) |
| **VESC (direct)** | CRC-16 + stop byte | `vesc/VescPacket.kt:94-113` | corruption negligible; semantic: `tachometer_abs` resets on controller reboot → trip `coerceAtLeast(0)` sits at 0 until it passes the old baseline (`VescProtocol.kt:230-237`) |
| **VESC gateway (nyxdash)** | CRC-16 (same `VescPacket`) | `VescGatewayProtocol.kt:15,1004` | as VESC; plus head-unit forwarding of stale values (not audited here) |
| **ANT BMS** | CRC-16/MODBUS | `AntBmsProtocol.kt:128-131` | negligible |
| **JK BMS** | 8-bit sum over 300 bytes | `JkBmsProtocol.kt:133-136` | 1/256 of corrupt or spliced 300-byte windows pass (inferred from the arithmetic; JK frames span ~15 notifications, so a lost notification makes a spliced window) |
| **Daly** | 8-bit sum (13-byte frames); CRC-16 (`D2 03`) | `DalyBmsProtocol.kt:179-180,205-207` | 13-byte path weak (1/256) |
| **JBD** | 16-bit sum | `JbdBmsProtocol.kt:114,151-156` | moderate |
| **InMotion** | 8-bit XOR | `InMotionProtocol.kt:137,390-395` | 1/256 |
| **Ninebot** | 16-bit complement sum, 15-bit mask also accepted | `NinebotProtocol.kt:115,271-279` | moderate; range gates on voltage/board temperature `:164,168` |
| **FarDriver, Kelly** | own checksums | `FarDriverProtocol.kt:146-148,167`; `KellyProtocol.kt:30` | low |

No other protocol has field data here. The rules below are written so that the **checksum-less
three (Begode, KingSong, Veteran-legacy) get the frame-level rules** and **every protocol gets the
counter rule**, because torn counters are a firmware property no checksum can catch (a checksum
computed over a torn value is valid).

---

## 2. Field-by-field table

> **Rider ruling 2026-10-27 (appended; the table below is kept as written):** absolute bounds
> R3 (250 km/h frame bound), R6 (400 A), R7/R10 (absolute temperature windows), R8 (2.5..4.4 V/cell),
> R9 (300 A), R11 (reject |duty| > 100 — the existing clamp stays unchanged), R14 (cell window) and
> R2's fixed 75 m / `vmax` constants are **rejected**, because no fixed physical threshold holds across
> the vehicles this app reads: «Токи фиксировать не вариант — самокат может скушать 600А; напряжение —
> вдруг другая химия; ШИМ — Sherman/SV показывают 105 % (ослабление поля)». Allowed rule kinds are
> only: (1) in-frame / cross-frame **witnesses** (R1; R13-style agreement of two frames measuring the
> same quantity); (2) **temporal confirmation** relative to the signal's own neighbours (a single-sample
> excursion that returns to the prior level), with relative thresholds justified against the dumps;
> (3) values impossible by the field's **encoding** for every device of that protocol (left out when
> unsure); (4) counter **re-baseline** after N agreeing samples, trip as a sum of accepted increments.
> For alarms R12 stays; R4 is deferred as an open question. Live display stays raw. The implementation
> plan is `docs/superpowers/plans/2026-10-27-telemetry-plausibility.md`.

Columns: current gate → failure mode → proposed rule → false-positive risk and how it would show.
"Reject frame" means: the decoder discards the whole frame — no state write, no latch, no
`rebuildMotion`, no observation — increments a counter and keeps the raw bytes (§5). Rationale for
whole-frame rejection: a frame with one impossible field is evidence that the frame, not just the
field, is untrustworthy, and the next frame of the same type arrives in ~310 ms (measured).

### 2.1 Begode (T4, ET Max, EXN)

| Field (frame, bytes) | Reaches | Current gate | Failure mode | Proposed rule | False-positive risk / visibility |
|---|---|---|---|---|---|
| **Odometer** (0x04, 2..5 u32BE) | live, history, trip, facts | zero-payload only | torn read ±65 536 m; any corrupt step; the T4's alleged display reset ~3000→2650–2700 km (anecdote, unverified) | **R1 witness:** accept iff `Δodo` equals `Δpoweron mod 65536` (live frame 8..9) within `max(40 m, 2×(the last Δodo))`; without a power-on witness (no live frame since the last 0x04, or power-on counter reset to <100 m) fall back to **R2 step bound:** `0 ≤ Δodo ≤ 75 m` per 0x04 frame (≈ 250 km/h at the measured 310 ms cadence, ×3 margin over the 7 m/frame measured at 81 km/h). Rejected value is **confirmed** as a real discontinuity only after 3 consecutive 0x04 frames that agree with each other under R1/R2 → re-baseline (keep the session trip as a sum of accepted increments, §2.4) and record a fact `ODOMETER_REBASED`. | Very low for R1 (measured exact lock-step). R2 after a long BLE gap: a 10 s gap at 30 km/h = 83 m > 75 m → rejected, then confirmed 3 frames later; costs ~1 s of odometer and the gap's distance is lost from the *sum* (use time-aware R2 in the domain gate, §5, to avoid that). Shows as the counter in «Всё о ТС». |
| Trip (session) | live, history | `coerceAtLeast(0)` | inherits the odometer needle; poisoned baseline if the first 0x04 is torn | derive from the **sum of accepted odometer increments**, not `odometer − first baseline`; baseline only from a frame accepted by R1 or confirmed by R2 | none beyond R1/R2 |
| Power-on distance (0x00, 8..9 u16) | facts only | voltage>0 | wraps at 65 535 m (documented `:454-455`); resets at power-on | used only as witness; modular difference | n/a |
| **Speed** (0x00, 4..5 i16 × 0.036) | live, speed alarm, history, top-speed tile | voltage>0; `abs` | in-frame corruption → needle (a high-byte bit is ≥ 9.2 km/h); 32 767 × 0.036 = 1180 km/h is representable | **R3 frame bound:** `|speed| > 250 km/h` → reject frame. **R4 jump confirmation (alarm only, via shared gate):** a step `> 40 km/h` from the last accepted sample needs one agreeing successor (≈ +310 ms). **R5 cross-check (history):** a speed sample is eligible as *peak* only if a neighbour within 1 s agrees within `120 km/h/s × Δt` | R3: none (no EUC does 250). R4: measured free-spin acceleration peaks at **89 km/h/s** ≈ 28 km/h per frame (dumps 1–2), so 40 km/h/frame never fires on real motion; cost when it does fire = one frame of alarm latency. R5: a genuine one-frame peak with both neighbours >37 km/h away is physically impossible at 120 km/h/s. |
| Phase current (0x00, 10..11) | live (`motorCurrentA`) | voltage>0 | corruption | **R6:** `|I| > 400 A` → reject frame | T4/ET Max phase currents in the dumps peak at 38.4 A; real riding peaks are well under 400 A for these wheels (inferred from class, not measured) — set from the vehicle class if one ever exceeds it; visible as counter |
| Board temp (0x00, 12..13, raw/340+36.53) | live, ESC-temp alarm (gated), history, peak tile | voltage>0; alarm `TemperatureGate` | corruption → "130 °C" | **R7:** outside `-40..120 °C` → reject frame (the formula spans −60..+133 °C, so a bound is meaningful); history peak via R5-style neighbour confirmation (`±20 °C`, same constant as `TemperatureGate.MAX_JUMP_C`) | winter −40 is the sensor's own floor; MPU die above 120 °C means the board is already destroyed. Visible as counter. |
| Live voltage (0x00, 2..3, 67.2 V scale) | live, input voltage, power, SoC (dumb wheel), history | `> 0` | corruption → voltage needle → power needle, SoC needle | **R8:** once the series count is proven, `avg cell = scaled / cells` outside `2.5..4.4 V` → reject frame | sag on a 24S T4 at the measured currents is ~1 % (6473→6400 raw at 21 A); a sound pack never reads <2.5 V/cell while riding. |
| Battery current (0x07, 2..3) | live, power join, history | zero-payload | corruption → power needle (the 5084 W needle may or may not be real — §4) | **R9:** `|I| > 300 A` → reject frame | dumps: −21.6…+7.2 A. Visible as counter. |
| Motor temp (0x07, 6..7) | live, alarm (gated), latch | zero-payload; latch | corrupt frame latches `sawMotorTempEvidence` forever | **R10:** outside `-40..200 °C` → reject frame; latch only on a value repeated in 2 consecutive frames | T4 motor temp is a constant 0 in all dumps (no sensor). Latch delay one frame. |
| **Duty** (0x07, 8..9) | live, duty alarm (ungated), latch, history, peak-PWM tile | zero-payload; `abs`+clamp to 100 | a corrupt value is clamped to 100 % → instant alarm and a 100 % peak; a corrupt frame latches `sawTrueDuty` | **R11:** `|raw| > 100` → reject frame (replaces the clamp; the field is whole percent and saturates at exactly 100 — dump (3) shows 100 and never more over 11 saturated frames). **R4** applies to the duty alarm with a jump bound of 50 %/frame | dump (3): the largest real step is 11 points/frame during the free-spin ramp. A jump >50 % costs one frame (310 ms) of alarm latency — this is the one rule with a safety cost; see §5.3 for why it is still acceptable and what to do if the rider disagrees. |
| Alert bitmap (0x04, 14) | faults → CRITICAL notification; wheel PWM alarm row | none | one corrupt byte → a CRITICAL fault push and a banner row | **R12:** a newly *set* bit becomes visible only when present in 2 consecutive 0x04 frames (≈ 310 ms). A bit that *clears* clears immediately. | delays a genuine fault by one frame; a real fault persists for seconds. The wheel's own PWM alarm (bit 0) is a beep the rider already hears. |
| Tiltback (0x04, 10..11) | facts only | `1..99` | cosmetic | none | — |
| 0x01 pack voltage (6..7 × 0.1) | branch voltage, SoC (T4), cell-count proof | certified by the branch ratio streak (`:934-951`) | corruption → SoC needle | **R13:** reject 0x01 when its voltage disagrees with the scaled live voltage by >10 % once certified | the two frames are ~3 ms apart (measured), so they see the same sag |
| 0x01 current/temps/section (T4) | live `BmsData.current = 0`, `power = 0` | boot-placeholder → history unknown (`BegodeBatteryObservation.kt:41-50`) | **live** state still publishes a confident 0 A / 0 W pack current (dump header: `current=-0.0 power=-0.0 knownFlags current=true power=true`) | not a plausibility rule — a separate honesty fix: `hasCurrent=false`/`hasPower=false` while the branch is a permanent placeholder | n/a |
| Cells (0x02/0x03) | cells, cell alarms (gated) | `1..5000 mV` | corruption → one cell needle | **R14:** cell outside `2000..4400 mV` → drop that page | none for Li-ion |

### 2.2 Shared rules for every protocol's counters and alarms

| Field | Protocols | Rule |
|---|---|---|
| Odometer / tach_abs / total distance | all | **R2t (time-aware, domain):** `0 ≤ Δodo ≤ vmax·Δt + 20 m`, `vmax = 250 km/h`, `Δt` from receipt times; reject otherwise; 3 agreeing samples re-baseline. Begode additionally gets R1 in the protocol. |
| Speed, duty alarms | all | **R4** (generalised `TemperatureGate`: `maxJump`, `confirmations=2`). Protocols with CRC-16/32 may opt out. |
| Faults | Begode, KingSong, Veteran-legacy | **R12** (two consecutive frames) |
| Temperatures | all | `TemperatureGate` as today for alarms; R5-style peak confirmation in history |

### 2.3 What is deliberately NOT proposed

- No rate-of-change limit on speed for **live display** — free spin reached 89 km/h/s; any bound tight
  enough to matter would eat real data.
- No rule that rejects an *unusual* value that its neighbours corroborate. Every rejection above is
  either impossible by construction of the field (|duty| > 100, 1180 km/h), contradicted by an
  independent in-frame witness (R1), or a single sample its successors do not repeat.
- No silent smoothing. Rejected means counted and kept (§5).

### 2.4 Trip as a sum of accepted increments

A counter-based trip computed as `Σ accepted Δodo` (not `last − first`) is what makes a genuine
re-baseline (the T4's anecdotal 3000 → 2650 km reset) harmless: the backward step is confirmed,
re-baselined, and contributes 0 to the sum instead of zeroing or negating the trip.

---

## 3. History and summary layer

### 3.1 What is available without a migration (DB v27)

- `HistoryStats` = `minimum, maximum, energyWh, complete, average` (`domain/history/HistoryModels.kt:137-152`),
  computed from 60 s buckets plus a detail min/max (`data/history/HistoryStoreReader.kt:44-67`).
  None of these can express "sum of plausible increments" or "first/last".
- **Detail samples are kept for the whole ride**: `HistorySampleV2` rows (first/min/max/last per
  500 ms window, `domain/history/HistoryDetailGate.kt:3-29`), paged by
  `HistoryStoreReader.samples` (`:69-81`) up to 20 000 per page. For a monotone counter the 500 ms
  first/last pair loses nothing.
- GPS fixes are already paged the same way for distance (`HistoryTotalsComponent.kt:58-76`).

So every aggregate below is a **read-time pure function over detail samples**: no migration, and —
the important property — **it repairs rides already recorded**, including the rider's 67 km ride.

### 3.2 Distance

`historyRideDistance(odometerSamples, tripSamples, gpsFixes, duration)`:

1. **Wheel increments.** Walk the odometer (else trip) detail samples in order; accept
   `Δ = next − prev` when `0 ≤ Δ ≤ vmax·Δt + 20 m` (`vmax = 250 km/h`, Δt from sample ticks;
   unknown samples and markers end a run). A rejected step is skipped (not added) and the next sample
   is compared with the last *accepted* one (the `TemperatureGate` shape), so one needle costs only
   itself. Three consecutive samples that agree with each other but not with the last accepted one
   re-baseline (a genuine reset). Output: a list of (interval, metres) runs and a count of rejected
   steps.
2. **GPS-first.** Where GPS fixes cover an interval (consecutive fixes ≤ 30 s apart, accuracy ≤ 50 m —
   the existing rules, `HistoryRideTotals.kt:139-152`), use the GPS length. Where they do not (the
   pre-GPS head of a recording, tunnels, gaps), fill with the wheel increments of that interval.
3. **Tyre correction (optional, flagged).** When GPS and wheel overlap for ≥ 1 km, compute
   `k = gps / wheel` over the overlap; if `k ∈ [0.8, 1.25]`, scale the wheel fill by `k`; otherwise use
   it unscaled. Any fill makes the tile approximate (`≈`).
4. **Impossibility cross-check.** If `distance > peak_speed × duration × 1.05` (equivalently
   average > top speed), the counter distance is impossible: use GPS alone if available, else show the
   tile approximate with the counter value clamped to that bound. This alone would have flagged the
   67 km ride (254 km/h > 55.1 km/h).

False-positive risk: GPS overestimates at walking pace (jitter); the existing 50 m accuracy filter
does not remove that. A ride that is mostly standing still could read GPS-longer than the wheel.
Mitigation (cheap): ignore GPS segments with implied speed < 3 km/h when the wheel says ~0 for that
interval. This is inference, not measured on the rider's data.

### 3.3 Battery used

Today: `max − min` of `soc_percent` averaged over sources (`HistoryRideTotals.kt:104-108`), rendered
with a hard-coded "−" (`HistoryTotalsRow.kt:42`). For a Begode the history SoC is the
**instantaneous** voltage estimate (`data/history/HistoryEstimatedSoc.kt:17-29` →
`VoltageSocEstimator.estimateFromPackVoltage`), not the dashboard's load-compensated, rate-limited
`ChargeTracker` (`domain/energy/ChargeTracker.kt:30-40`, whose own KDoc records the T4 showing
"60 % → 15 % under a hard pull", 2026-10-24). So the minimum is a sag minimum. The "−39 %" on a pack
at 79 % is consistent with that (start ≈ 80 %, a sag sample near 40 %) — **inferred**; the rider can
confirm in one minute by opening that ride's SoC chart and checking whether the minimum sits on a
current/power spike.

Proposal: `used = robustStart − robustEnd`, **signed**:
- `robustStart` = median of known samples in the first 60 s after the first known sample;
  `robustEnd` = median of the last 60 s; when a battery or controller current series exists for the
  same ride, prefer samples with `|I| < 3 A` in those windows.
- Display: positive → "−N %", negative (charged / regen-dominant) → "+N %"; approximate (`≈`) when the
  SoC is voltage-estimated (every Begode, every `!reportsStateOfCharge` BMS).
- Option (not required): record `ChargeTracker`'s value instead of the raw OCV estimate in
  `HistoryEstimatedSoc`. It is equally an estimate, but a calmer one. Deferred: it changes what is
  recorded, and the read-side fix already gives a sane tile.

### 3.4 Peaks

Bucket `maximum` is the raw maximum, so one corrupt sample is the "Top speed" / "Controller 130 °C" /
"Max power" tile. Robust peak = the largest sample that has a neighbour within 1 s agreeing within
the metric's jump bound (speed 120 km/h/s·Δt; temperatures 20 °C; power: none — power is volatile,
rely on the decoder rules R8/R9). Computed from detail samples like §3.2. Tiles stay exact when no
sample was discarded, approximate otherwise.

### 3.5 Peak PWM (rider request; where it fits)

- Ride summary: new `HistoryTotalKind.MAX_DUTY_PERCENT` from `duty_percent` (motion tier), robust
  peak per §3.4 with a 50 % jump bound, strings in both `values/` and `values-ru/`.
- Ride list card: today the card shows only duration and status (`HistoryRideList.kt:76-88`). Add one
  lazily loaded number per visible ride (same pattern as previews), from the cheap bucket `stats`
  maximum — duty needles are already impossible after R11.
- The "~" marker: estimated duty is recorded **unmarked** under `duty_percent`
  (`TelemetryObservation.kt:36-45`). That KDoc says a marker "needs a schema migration"; it does not
  if the estimate is recorded under its **own metric id** (`duty_estimated_percent`): `metricId` is
  TEXT and the registry is code. Then "~" is a fact of the series, not a guess. Rides recorded before
  the split cannot be told apart; show them unmarked (honest: we do not know).

### 3.6 Migration verdict

None needed for anything in this spec. A v28 table of per-ride rollups (distance, used %, peaks)
would only be justified if the ride list must show robust numbers for many rides at once and detail
scans prove too slow on the device — not established; measure first.

---

## 4. The sparse controller-power chart

**Not a join problem (measured).** Re-running the app's own receipt logic
(`motionFramesSinceLive in 1..3`, `BegodeProtocol.kt:1461,1625,1631,1852`) on the dumps: every 0x07
had a genuine 0x00 exactly one frame earlier — **44/44, 105/105, 118/118**; by wall clock all 267
within 1000 ms. The join requires `hasPower` (a voltage), which the T4 proves via the branch ratio
(dump headers: controller `power=24.17 … knownFlags power=true`).

**The real cause (code chain, deterministic):**
1. Every 0x00 frame records `CONTROLLER_POWER` as **unknown** `PARTIAL_SOURCE`
   (`data/bms/BegodeTelemetryObservation.kt:45,56`); every 0x07 records a value (`:64`). Pinned by
   tests (`BegodeTelemetryObservationTest.kt:49,51`).
2. `HistoryDetailGate` emits every unknown explicitly (`domain/history/HistoryDetailGate.kt:19`), so
   the stored series alternates value, unknown, value, unknown at ~3 Hz.
3. The chart overview (`maxPoints ≤ 600`, `DefaultHistoryReadRepository.kt:35,41`) marks every
   unknown **and both its neighbours** mandatory (`domain/history/HistoryOverview.kt:32-34`); a 15-min
   ride has ~5 800 such rows, more than the budget, so it falls back to "isolated earned extrema/
   endpoints" — **min, max, first known, last known** (`:36-47`).
4. Each of those is a one-point segment (`HistoryChartScene.kt:57`), drawn as a 2 dp dot
   (`HistoryChartPanel.kt:147`).

That is exactly "a few dots at the ends and one needle to 5084 W, min −1798 W". Prediction the rider
can check: zoom the chart to ~1.5 min (< 600 rows) and it becomes a cloud of unconnected dots.

**Second consequence:** the 60 s bucket accumulator integrates energy only between two known
neighbours (`HistoryBucketAccumulator.kt:34-35,48`), so controller energy is never accumulated and
the bucket is `complete=false`; with the T4's pack power also unknown, the ride has **no Energy and
no Wh/km tile**, and Max power is approximate.

**Fix:** on 0x00, *omit* `CONTROLLER_POWER` from the observation instead of recording it unknown —
`TelemetryObservation`'s contract already says "omitted fields were not observed in this decode"
(`domain/model/TelemetryObservation.kt:24`). Record the retraction in
`BegodeTelemetryObservation.kt` and change the two pinned assertions. Audit the same pattern
elsewhere: any per-frame `PARTIAL_SOURCE` for a metric that *another frame of the same source*
supplies (Begode cell summaries per page, `BegodeBatteryObservation.kt:76-79`, are the next
candidate).

Whether the 5084 W / −1798 W extremes are real cannot be determined from the data available: at
~100 V a 5 kW peak is 50 A — plausible for a T4 under hard acceleration, and −1.8 kW is plausible
braking. Neither is evidence of corruption.

---

## 5. Where rejections live, and how they surface

### 5.1 Three layers, each with one job

| Layer | Knows | Rules | Why here |
|---|---|---|---|
| **Decoder** (`data/bms/*Protocol.kt`) | frame boundaries, in-frame witnesses, latches; no clock | R1, R3, R6–R11, R13, R14 (frame rejection), R12 (fault debounce in frames) | only the decoder can reject a *frame* before it latches `sawTrueDuty`/baselines and before live state, alarms and history all see it — one decision, three consumers |
| **Shared domain gates** (`domain/plausibility/`, pure, clock passed in) | receipt time | R2t (`CounterStepGate`), R4 (`StepConfirmGate` = `TemperatureGate` generalised with `maxJump`/`confirmations`) | reused by every protocol; same deterministic, clock-free-by-injection shape as `TemperatureGate` (`TemperatureGate.kt:42-45`) |
| **History read side** (`presentation/history/` pure functions) | whole ride, GPS | §3 aggregates | repairs old rides; never rewrites stored samples |

`TemperatureGate` stays as is for temperatures; `StepConfirmGate` is a sibling, not a rewrite, so the
reviewed temperature behaviour is not disturbed.

### 5.2 Surfacing

- **Counter in «Всё о ТС»**: new `DeviceFactKey.REJECTED_FRAMES` (`domain/model/DeviceFact.kt:7`),
  value "N за подключение · последнее: одометр +65,5 км (свидетель: +6 м)" / English equivalent, both
  `values/` and `values-ru/`. A healthy wheel shows 0; a growing number on a healthy wheel is a rule
  that is too tight — visible, not hidden.
- **Dump annotation**: an always-on ring of the last 32 rejected frames (raw 24 bytes, rule id,
  previous accepted value, witness value), independent of the manual capture
  (`data/ble/BleDiagnosticBuffer.kt:20-81`, which only records while capturing), appended as a
  `rejected frames` section of the exported dump. This is how the next torn read gets *measured*
  instead of inferred.
- **History**: a rejected reading is **omitted** from the observation (no sample), so it cannot break
  chart segments (§4 shows what explicit unknowns do). Optional: a `HistoryMarkerKind.REJECTED`
  marker (marker `kind` is TEXT, no migration) — it would have to be excluded from continuity
  boundaries like `GPS_GAP` is (`HistoryChartScene.kt:44`, `HistoryContinuity.kt:50`).
  Read-side aggregates report how many steps they discarded; the tile becomes `≈`.
- The raw bytes always remain in the dump; nothing is erased silently.

### 5.3 The safety trade in R4/R11 for duty

Today duty fails loud (garbage → 100 % → alarm). R11 turns "|raw| > 100" from an alarm into a
rejected frame; R4 delays a >50-point jump by one frame. A real duty can rise 50 points in 310 ms
only on a violent event; one frame of latency there is the cost. If the rider prefers fail-loud, keep
the clamp for the alarm path and apply R11 only to latches and history — the spec's default is
rejection because a phantom 100 % alarm at speed is itself a hazard (a rider reacting to a false
cut-out warning).

---

## 6. Task breakdown (ordered; each independently testable in pure code)

Fixtures: real hex from the dumps, e.g. (dump 3)
`55aa002af84728001c1e00c800000000001204185a5a5a5a` (0x04, odometer 2 816 071 m),
`55aa1906f743006a0065fe5cf1d208a0000500185a5a5a5a` (0x00, −80.5 km/h, power-on 101 m),
`55aa0362000000000064000000000000000007185a5a5a5a` (0x07, duty 100, −8.66 A),
`55aa0050000003ce0000000000000000000001005a5a5a5a` (0x01, 97.4 V, T4 placeholder payload); the
existing `BegodeT4Fixture` (`commonTest/.../BegodeT4Fixture.kt`) for the notification layout. Torn
frames are built by editing those real frames (byte 3 `2A→2B` with bytes 4..5 `FFxx`), not invented
from scratch.

1. **Omit 0x00 power (§4).** Change `observedBegodeController` 0x00 branch; retract
   `BegodeTelemetryObservationTest.kt:49,51`; test: replay `BegodeT4Fixture` through the protocol into
   `HistoryDetailGate` + `projectHistoryOverviewRows` and assert the controller-power overview has
   >4 points and one multi-point segment. *Incoherent fixture:* a 0x00 frame with `hasPower = true,
   powerW = 4200f` must still record no power. Smallest, highest-visibility fix; do first.
2. **`CounterStepGate` (domain, pure).** R2t with re-baseline after 3 agreeing samples; tests: torn
   +65 536 m needle mid-run is skipped and the next sample accepted against the last accepted; −65 536
   dip likewise; a genuine 3000→2650 km reset re-baselines after 3; a 10 s gap at 30 km/h passes.
3. **Begode odometer witness R1 + trip as Σ accepted increments.** Tests from the dump frames:
   in-step frames accepted; a torn 0x04 (power-on witness +6 m, odometer +65 536 m) rejected, counter
   = 1, trip unaffected, baseline never taken from a rejected frame (*incoherent fixture: the first
   0x04 of the connection is torn*); power-on counter reset (<100 m) falls back to R2.
4. **Begode frame bounds R3, R6–R11, R13, R14** with a per-connection rejection counter and the
   rejected-frame ring; assert latches (`sawTrueDuty`, `sawMotorTempEvidence`) stay open after a
   rejected frame (assert on the published `hasDuty`/`hasMotorTemp`, not on timing). Mutation note:
   each bound's test must use a value just outside the bound **and** a real dump value just inside.
5. **Fault debounce R12** (Begode; then KingSong/Veteran-legacy): one corrupt alert byte publishes no
   fault and no `wheelPwmAlarm`; two consecutive do; a clear is immediate.
6. **`StepConfirmGate` (R4)** for speed and duty in `AlarmController`/`AlertEngine`; test with the
   dump-3 free-spin ramp (must never delay) and a single 9 999 km/h sample (must never sound).
7. **Read-side distance (§3.2)**: pure function over detail samples + GPS fixes, wired into
   `readHistoryTotals`. Tests: the rider's ride reconstructed (flat odometer 2816.4→~2822 with one
   2883.58 sample) gives ≈ 5–6 km, not 67; GPS-first with a 2 km pre-GPS head filled from the wheel;
   tyre factor applied only within [0.8, 1.25]; the average>top-speed cross-check alone (no
   increment rule) rejects the counter distance. *Incoherent fixture:* `complete = true` stats with a
   needle.
8. **Read-side battery used (§3.3)** signed, robust start/end, `≈` for estimated SoC; fix the
   hard-coded "−" in `HistoryTotalsRow.kt:42`. Test: a sag dip to 40 % mid-ride with 80→79 % ends
   reads ≈ −1 %, a charge session reads "+".
9. **Robust peaks (§3.4)** for speed and temperatures.
10. **Surfacing (§5.2)**: `REJECTED_FRAMES` fact (strings in `values/` and `values-ru/`), dump section.
    The «Всё о ТС» rendering is Compose — judged on device, said so plainly.
11. **Peak PWM (§3.5)**: `MAX_DUTY_PERCENT` tile, `duty_estimated_percent` metric id for the estimate,
    card number with "~".
12. **Port R2t to VESC/KingSong/Veteran/Ninebot/InMotion counters** (one task per protocol, each with a
    torn-counter fixture built from that protocol's existing test frames).

Each task: run `:composeApp:testDebugUnitTest`; mutation checks only with the audited harness
(CLAUDE.md "a green sweep is not evidence").

---

## 7. Open questions / could not determine

- **Whether the needle is a torn read or a corrupt byte** — inferred from arithmetic only. The
  rejected-frame ring (task 4/10) will capture the next one with its witness.
- **Whether the 5084 W / −1798 W power extremes are real.**
- **The T4's alleged odometer reset at ~3000 km** — not in any frame we have; the re-baseline rule is
  written so that either answer is safe.
- **Duty on the T4 (side finding, outside this audit):** dumps (1)/(2) (2026-10-26) show the 0x07 duty
  field constant 0 even at 81 km/h free spin; dump (3) (2026-10-27) shows it non-zero from the first
  frame and rising with speed to a saturated 100 % at ~80 km/h, while live-frame bytes 14..15 changed
  from `0x0080` to `0x08A0`. This contradicts the retraction in `BegodeProtocol.kt:686-692`
  ("the 0x07 duty field stays 0") for that session. Something changed on the wheel between the two
  days (a setting? a firmware mode?) — ask the rider before relying on the estimate path for the T4.
- **The "−39 %" ride's SoC chart** was not available; the sag explanation is inferred.
- Notification layout other than 20/20/20/20/16 (other phones, other MTUs) was not tested; the splice
  result is measured only for this layout.
