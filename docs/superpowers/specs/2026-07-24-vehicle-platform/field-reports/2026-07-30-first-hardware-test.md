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

## 2. What this settles that was open

**The head-unit phantom-row question is answered.** `B §14` / the composer notes
carried an open item: what a bare `COMM_GET_VALUES` (opcode 4, no
`FORWARD_CAN`) to a VESC Express head unit replies — no reply, a 0 V rail, or a
real rail — each implying a different advisory. The field answer is that the
head unit **does** answer, and answers with something Volty renders as a live
controller carrying a temperature and a power but no speed and no battery. It is
a phantom row that looks alive.

**A CAN-forwarded controller beside it did not fix the reading.** That is the
part that matters most: the user did the thing the composer exists to let them
do, and the dashboard still showed the head unit's picture. Whatever the
aggregation of a real controller with a hollow one currently does, it is not
"prefer the one that measured something".

## 3. What is NOT established here

- the speed **scale** (see S1) — still needs a moving capture beside a reference;
- whether the EXN's pack voltage is reachable *without* the rider entering a
  cell count, or whether Volty must ask (WheelLog asks: it makes the rider pick
  a multiplier from a list of seven);
- what exactly the head unit puts in each field of its `COMM_GET_VALUES` reply —
  the firmware is on the rider's machine
  (`E:\sodovaya\nyxdash\firmware\components\vesc_express\`) and is the primary
  source for this, not guesswork from the symptom;
- whether the CAN-forwarded uBox was polled at all, or polled and then averaged
  away. Two very different defects with the same symptom.
