# Ride History source freshness audit

The recording pipeline cannot treat a new merged `BmsData` instance as proof that
all its fields were measured again. Source observations are separate from dashboard
snapshots and contain only measured or explicitly unavailable fields from that decode.
No BLE command, polling cadence or Begode FFE1 write is added.

## Local findings

- JBD basic-info and cell replies each rebuild a dashboard object with the other
  group's cached fields. The new observer publishes every complete decoded reply
  separately, including multiple replies in one BLE notification.
- Daly replies update different accumulator groups. Cell/temperature fragments
  immediately publish mixed-age snapshots; a fault reply can also restamp cached
  scalar fields. Observer integration for Daly is pending.
- ANT emits a whole status snapshot, but missing payload guards can leave scalar
  defaults marked available. History qualification must use actual field coverage,
  not those defaults. ANT now emits qualified per-status observations; production
  consumer integration is still pending.
- Begode, JK, plain VESC and the VESC gateway still need qualified observations.
  Gateway overlays and derived contributors require explicit freshness evidence.

The generic observation vocabulary carries physical contributor kind/local index,
canonical values, typed absence and physical cell index. The history adapter binds
these to an attributed source; vehicle aggregates cannot duplicate contributor rows.
Multiple frames with one receipt timestamp can earn peaks and sample counts, but
cannot prove distinct measurement intervals or energy between them.

## JBD qualification and retraction

Checked the reference client's [`jbd.py` at 76317567a51fcfbf9df9e65315fcb2d5af125237](https://github.com/fl4p/batmon-ha/blob/76317567a51fcfbf9df9e65315fcb2d5af125237/bmslib/models/jbd.py)
on 2026-10-14. Its response validation rejects nonzero status; cell-voltage reads
preserve raw reported millivolts, including zero. The NTC declaration must fit the
payload; checksum bytes are not temperature measurements.

Retraction: the first observation prototype reused the dashboard's 1..5000mV
filter and treated zero as invalid. That would hide an earned zero reading and
could conceal a cell fault. New history observations retain unsigned raw cell
readings and their physical positions. The existing dashboard list filter remains
unchanged; this audit does not claim that its cell presentation is repaired.

Failed responses publish typed unavailability for the affected fields and retain
the prior live snapshot. Smaller cell layouts explicitly retire previously known
indices. Reset clears the decoder's cell-layout history; cross-session layout
retirement must also be handled by the attributed runtime source state.

JBD observer exceptions are contained and counted without logging payloads.
The new observer is not connected to production DI or the BLE consumer yet.

Retraction: an even JBD cell reply proves its returned pairs, not completeness of
the physical pack. Basic-info payload offset 21 declares the expected cell count.
Whole-pack summaries now require that declaration and exact, even payload coverage.
A shorter reply invalidates absent declared indices on every occurrence; only a new
successful basic-info declaration can certify a smaller layout. Zero/missing count
and reset remove that evidence without suppressing raw cell readings.

## ANT qualification

Checked [`ant.py` at the same pinned batmon-ha revision](https://github.com/fl4p/batmon-ha/blob/76317567a51fcfbf9df9e65315fcb2d5af125237/bmslib/models/ant.py)
and [ESPHome's ANT decoder](https://github.com/syssi/esphome-ant-bms/blob/main/components/ant_bms_ble/ant_bms_ble.cpp)
on 2026-10-14. Declared cell and NTC counts determine later field offsets; NTC/MOS
temperature words are signed, and -40 is an explicit absent-sensor sentinel.
The local `nyxdash/firmware/components/vesc_express/src/ant_bms.c` was also read:
it says its layout matches the Kotlin parser, so it is corroboration rather than
independent proof. Its unsigned temperature handling is not copied.

Only CRC-valid status replies accepted by the existing decoder emit observations.
Coverage ends before CRC/trailer, even when another frame follows in the accumulator.
Raw cell positions and zero readings survive dashboard filtering. Missing declared
pairs produce partial values; pack summaries require all declared pairs. A new
smaller declaration retires older series. Reset clears that decoder history.
Voltage, current and SOC each require their own payload bytes; calculated power
requires both voltage and current. Current retains the domain's charge-positive
sign. Hottest battery temperature follows the existing dashboard's NTC+MOS scope,
excluding balancer: absent sensors are excluded, but truncated or invalid declared
measurements make the maximum partial. No command, cadence or live dashboard
decoding behavior was changed. Exceptions remain contained by the common observer.

These are decoder tests, not field verification and not production activation.
