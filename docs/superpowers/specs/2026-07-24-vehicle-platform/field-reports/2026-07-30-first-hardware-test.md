# Field report — first real-hardware test of the debug APK (2026-07-30)

**This is the first time any part of the vehicle platform met real vehicles.**
Every previous decision about Begode and VESC was made against one stationary
capture, WheelLog's source and the VESC firmware — never against a moving
machine. Where this report contradicts a spec's reasoning, **this report wins**:
it is measurement, the rest was inference.

Reported by the rider (device owner) after riding the branch's debug build.
Verbatim, in the original Russian, because a paraphrase already lost information
once on this project:

> потестил ласт дебаг апк: Отрицательная скорость при движении вперед, и
> положительная когда колесо едет назад, нет мощности и расхода begode etmax.
> на begode exn также, только плюсом нет напряжения (ну и нет смарт бмс, хотя
> общее напряжение колесо отдает в других аппах). vesc не работает батка, нет
> скорости, тдтп, будто только температура и мощность. добавлен самокат через
> приборку, она сама по себе бмс видела и успешно эмулировала её. также через
> can forwarding был добавлен основной контроллер, но это ничем не помогло.
> если что деш сам по себе скорость не знает, показания надо брать по кану.

## 1. Symptoms as stated

### S1 — speed sign is inverted (Begode ET Max **and** EXN)
Riding **forward** shows a **negative** speed; rolling **backward** shows a
**positive** one. Two different wheels, same inversion.

The **magnitude** was asked about separately and the rider's answer (2026-07-30)
is: *"родное приложение мусор полное, а судя по раскруту колеса там плюс-минус
верная скорость"* — the wheel's own app is not worth comparing against, but
judged against how fast the wheel is visibly turning, the number is about right.

So `SPEED_KMH_PER_UNIT = 0.036f` now has **corroboration from the machine
itself**, on two wheels, which is a great deal more than it had (it was read off
WheelLog's source and pinned by nothing). It is not yet a measurement: "about
right by eye" cannot separate 0.036 from, say, 0.034. What it does do is close
the two failure modes that mattered — the constant is not 3.6x low (the 0.01 that
nearly shipped) and not 10x high (the 0.36 that was proposed as its correction).
**No further work is justified on this constant**; a GPS comparison would refine
a number that is already inside its useful tolerance.

### S2 — no power and no consumption (Begode ET Max)
Both read as absent. The ET Max **does** have a smart BMS and its battery
telemetry works.

### S3 — no voltage either (Begode EXN)
Everything in S2, **plus** no voltage. The EXN has **no smart BMS** — "хотя
общее напряжение колесо отдаёт в других аппах": other apps *do* show its total
pack voltage, so the number is on the wire and reachable.

### S4 — VESC scooter: battery dead, no speed, "as if only temperature and power"
Setup, in the rider's words:
- the scooter was added **through the head unit** ("приборка" = nyxdash, VESC
  Express on ESP32-C6);
- the head unit **saw the BMS itself and emulated it successfully** — i.e. its
  ANT-over-BLE bridge was working and answering;
- the **main controller was additionally added over CAN forwarding**, and that
  **"didn't help at all"**;
- **"деш сам по себе скорость не знает, показания надо брать по кану"** — the
  head unit does not know the speed; readings have to be taken over CAN.

### S4a — the temperatures were CORRECT (rider, 2026-07-30)
Asked about them directly: *"температуры совпадали с приборкой, там было все ок,
что мотора что колеса были верными"* — the temperatures agreed with the head
unit's own display and both readings were right.

This is load-bearing, not a detail. It means the numbers the rider *did* see were
**real telemetry from the real controller**, so CAN forwarding was working; the
dashboard was not showing a hollow source's picture. Any explanation of S4 that
starts from "the head unit's own chip temperature leaked into the gauge" is
refuted by this line.

## 2. What this settles that was open

**The head-unit phantom-row question is answered — in the opposite direction to
the first reading of this report.** `B §14` and the composer notes carried an open
item: what a bare `COMM_GET_VALUES` (opcode 4, no `FORWARD_CAN`) to a VESC
Express head unit replies — no reply, a 0 V rail, or a real rail. The firmware
settles it: **it does not reply at all.** The opcode falls through to
`default: break;` in the vesc_express `commands.c` switch and no reply frame is
ever built; `COMM_GET_VALUES_SETUP` (47) is equally unhandled. Volty's timeout
path then correctly drops both the cache and the sample, and the aggregator
excludes the source.

**So there is no phantom row, and this report's first draft was wrong to infer
one** from the symptom. The advisory `G`/`B` were reserving space for is not
needed. What IS needed is the counterfactual guard: a hollow reply *would* lie
today, because `isConnected` is set unconditionally and `hasInputVoltage`,
`hasPower`, `hasDuty` and `hasEnergyCounters` all default to `true` — only
`hasMotorTemp` and `speedSource` would be honest. A future head unit that answers
opcode 4 with zeros is a live hazard; today's one is silent.

**A CAN-forwarded controller beside it did not fix the reading, and now we know
why it didn't.** Not aggregation, and not attribution — the forward is sound and
the reply is keyed to the right controller index. Two independent defects:

- **the vehicle's one `GET_VALUES_SETUP` request is addressed to the node that
  cannot answer it.** `primary = controllers.firstOrNull()`, and the head unit is
  controller 0. That frame carries speed, trip, odometer and battery level — so
  all four are pinned at 0 no matter how well the uBox answers everything else;
- **a CAN-discovered controller is created with `MotorConfig()`**, i.e.
  `wheelDiameterMm = 0`, so the eRPM→speed fallback is unavailable too. Both
  roads to a speed are closed at once, which is why the symptom looked total.

**And adding the CAN controller DELETED the battery.** `deriveBattery` is silently
dropped on the gateway branch, `packCount` becomes `packs.size` = 0, and the
repository bails out on a zero pack count. Opcode 96 is never sent because its
sender is constructed per owned pack and there are none. That — not aggregation —
is the literal mechanism behind *"это ничем не помогло"*: the rider's action
removed the battery slot instead of filling it.

Volty has no `CAN_PACKET_*` readers, which is correct: those frames never cross
BLE.

## 3. What is NOT established here

Three of this report's four open items were closed by reading the firmware and the
code on the same day; they are struck through in §2 and §1 rather than deleted, so
the inference that was wrong stays visible beside what replaced it.

Still open:

- whether the EXN's pack voltage is reachable *without* the rider entering a cell
  count, or whether Volty must ask (WheelLog asks: it makes the rider pick a
  multiplier from a list of seven);
- the speed scale to better than "about right by eye" — deliberately abandoned,
  see §1 S1: it is inside its useful tolerance and refining it buys nothing.

## 4. The latent defects this ride did NOT cause

Found while explaining the symptoms, confirmed present, and **not** responsible for
anything the rider saw. They are recorded because the next vehicle shape reaches
them, and because two of them were being blamed for S4 until the rider corrected
the temperature reading (S4a):

- **`hasInputVoltage`'s filter in `MotionAggregator` is unreachable for VESC.** The
  fold does consult the flag — but no VESC producer can ever clear it (default
  `true`, never assigned), and `BegodeProtocol` is the only producer in the app
  that clears it at all. So `G §9.3`, written up as fixed-by-flag, is only fixed
  for a Begode. A mixed vehicle still averages an unknown 0 V into a real rail.
- **One value fold of fourteen consults its known-flag.** `speedKmh`,
  `dutyPercent`, `powerW`, `escTempC`, `motorTempC` and the four energy counters
  all ignore theirs. `faults` (a union) is the only fold that is honest by
  construction.
- **`hasEscTemp` is computed *after* the fold**, from a sentinel that `maxOf`
  destroys: any contributor with no ESC sensor is silently outvoted rather than
  excluded. This is the one that was wrongly blamed for S4.
- **`batteryLevelFraction` is filtered for `null` but not for the `0f` that
  `VescValues` emits when it is unknown** — while `VescProtocol` one file over
  already knows `> 0f` is the right test.
- **The summed counters blank the whole total** when any contributor lacks
  counters, throwing away a real measurement rather than reporting a partial one
  as partial.

The test suite could not have caught any of these: its mixed-source fixtures all
build the hollow contributor **by hand with `has*` explicitly false** — a shape no
VESC producer can emit — so the fixtures test the producers' current habits rather
than the contract. Nothing folds real `VescGatewayProtocol` output through
`MotionAggregator` at all.
