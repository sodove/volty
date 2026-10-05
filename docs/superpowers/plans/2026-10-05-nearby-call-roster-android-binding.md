# Call roster factory Android binding

Supply the root's explicit call roster factory as a lazy Koin singleton with the existing
bounded Nearby client, strict v2 URL, queued Main dispatcher, elapsedRealtime clock and cold
one-second pulses per subscription. Construction grants no account authority and allocates
no read child or microphone. Every create delegates to the tested authenticated owner with
the captured account and original earned own session; root selection owns disposal.

This configuration wiring does not activate the new conversation UI or backend routes.
Validate fresh full Android suite with exact XML count before commit. No constructor-mirror
test for this reversible binding; existing owner/root behavior tests cover the delegated path.
Parent manual review, no new agents or device/audibility assertion.
