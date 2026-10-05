# V2 sharing telemetry policy before packet persistence

Retain existing LOCATION/RIDE/FULL vocabulary and schema-1 DTO. LOCATION accepts no telemetry;
RIDE reveals only speed, state of charge and power; FULL may reveal all existing metrics and
faults. Positive payload profile must match actual consent mode, not a requested broadened mode.

V2 backend pure sanitizer must preserve earned known/supported distinction independently of
producer habits. Deliberately incoherent supported=false/known=true or known=false/value=4200
must never surface as an earned number. Strip values when unknown/unsupported, preserve actual
known finite numbers including zero and signed regeneration. Known missing/nonfinite values,
wrong schema/profile and invalid bounded known fault list are invalid payloads. Unknown fault
values are stripped, while known empty fault list remains earned evidence of no current fault.

Tests first cover each audience tier, incoherent absent data, actual signed/zero values, unknown
versus known-empty faults, and invalid known/schema/profile inputs. Apply only to new v2 packet
repository afterward; legacy handlers/BLE/telemetry producers are untouched in this slice.
Fresh exact backend and Android suites before commit. No packet persistence/location/UI/device
claim from a pure policy. Parent review only; no additional PostgreSQL run for pure policy.
