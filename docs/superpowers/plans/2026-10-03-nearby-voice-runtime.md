# Nearby composed voice runtime

Compose the tested control owner, engine bridge and scheduler on one explicitly
queued serial dispatcher. Require the authenticated account-bound HTTP port,
SDK factory, including-sleep monotonic clock, pulse source and nonblocking sharing
effect sink. Expose state and independent local/remote cleanup projections.

Public commands join/disconnect/mute/deafen/explicit sharing must enter this same
dispatcher. Disconnect affects voice/position only; the runtime has no membership
removal port. Explicit retirement permanently closes admission to new joins and
sharing, stops current voice, and retains scheduling for original cleanup retries.
It does not cancel the parent or acknowledge native/SFU cleanup by inference.
No close/dispose convenience until durable retirement ownership is implemented.

Author finite tests before implementation: issued SDK disconnect, separate failed
native/server cleanup retries, retirement admission fence, explicit position
consent/hide, sleep-clock expiry. Fresh exact-count Android suite and narrow source
review before commit. Production DI/foreground/account binding/durable persistence
and actual device routing remain separate required integration work.
