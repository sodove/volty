# Sharing startup recovery preflight

Startup owns one negative-only recovery and one admission mutex. Acquire the actual bound
voice outside the mutex; inside, validate actual original voice and no current sharing,
snapshot exact live/preparing tags from this startup, then drain obsolete same-actor intents.
Only after confirmed recovery recheck wanted/account/original voice before creating control,
foreground ticket, consent or GPS. Hold admission through actual enable result, so another
preparation cannot create a new armed row between recovery snapshot and positive admission.

Register every root preparation before its queued body runs. Protect other wanted or actually
preparing original tags; hidden cancellation-ignoring producers remain protected until their
actual completion. Completed withdrawn preparations can be pruned; their journal rows are
eligible negatives. Shared foreground/audio remain separately owned. No periodic worker.

Actual HTTP tests: saved old revoke held means zero foreground launch/new start/GPS; after
ACK, launch and current consent work and only current row remains. Old revoke failure means
startup false, no foreground/native/positive, old row retained, voice still wanted. Concurrent
preparations must not revoke the first newly active sharing or launch the second. Full fresh
Android before commit. No device/production screen activation claim.
