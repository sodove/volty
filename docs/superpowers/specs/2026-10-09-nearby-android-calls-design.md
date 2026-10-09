# Android calls and independent microphone routing

Status: user selected Telecom + Volty call surface, like Telegram, on 2026-10-09 after receiving the design link. Existing Nearby work remains on `codex/nearby-foundations`.

## User outcome

The user reports that Phone microphone + Headset playback actually captures the Bluetooth headset microphone. On Pixel 7 an incoming call is visible in the open application but produces no notification while the application is minimized. They propose treating a direct call as a proper Android phone call.

Keep LiveKit and the existing account-owned call state machine. Preserve the ride map pill and its details sheet. A direct incoming call must have an audible incoming-call surface, answer/reject actions, and an accessible hang-up action after answering. No microphone capture starts before acceptance. Voice-channel membership and location sharing remain independent of calls.

## Evidence and remaining uncertainties

- `OwnedVoiceAudio.requestInput` invokes the injected WebRTC ADM's `setPreferredInputDevice` and records the requested device ID. This is intent, not confirmation that capture moved.
- Inspection of the actual cached WebRTC 144.7559.05 runtime bytecode shows `AudioRecord.setPreferredDevice` returning false only writes an SDK error log. The public ADM method returns void; our code can therefore return REQUESTED even if native selection failed.
- `VoiceAudioRouteMonitor` uses AudioSwitch/CommDeviceAudioSwitch and MODE_IN_COMMUNICATION. Its output request can choose Bluetooth communication routing after the input request. Android documents that selecting a communication output automatically selects its matching input. This explains a possible conflict; it does not prove the physical phone's exact route.
- Existing recording configurations are deliberately marked uncorrelated; they cannot identify the owned ADM's capture stream. Do not relabel them as actual microphone evidence.
- FCM startup, registration, service, verified worker and notification port are wired. The notification port rejects missing POST_NOTIFICATIONS, disabled app notifications and a disabled incoming-call channel. The profile already exposes eligibility/settings. The initial permissions screen requests POST_NOTIFICATIONS.
- Production read-only checks found four bound registration tokens and twelve queue rows completed after one attempt. Queue completion covers several terminal outcomes and does not establish delivery to the Pixel or notification display.
- Cold restoration renews the binding, but registration performs fresh incoming-call discovery under the new binding before completing its wake. An old push being fenced out after renewal is intentional; removing this fence would be unsafe and is not the fix.
- No physical phone is connected to ADB. The connected emulators cannot prove Bluetooth routing or Pixel FCM delivery. Neither reported issue is declared fixed.
- User subsequently confirmed the Pixel notification eligibility card reports allowed. Do not present granting notification permission as the diagnosis. Local Firebase Android package and service-account project match; no private values were emitted by the check.

## Proposed call surface

Prefer self-managed Android Telecom integration for direct calls, with NotificationCompat.CallStyle and a Volty incoming-call screen on the lock screen when Android allows full-screen presentation. This gives Android a real call lifecycle and enables supported headset/system answer and hang-up controls. It does not promise that the stock Phone application renders Volty calls; Telecom support and the incoming-call UI are distinct responsibilities.

An alternative is a managed ConnectionService using the system dialer's call UI, requiring phone-account enablement and different user onboarding. Do not request the default-dialer role just to handle Volty calls. Another alternative is retaining ordinary notifications only; it avoids new lifecycle integration but leaves call focus/remote controls to the application.

Only direct calls register as Telecom calls in this increment. Group voice remains controlled through the existing map pill/sheet. Preserve the existing single active voice operation and original-call action identity across both surfaces.

## Ownership and lifecycle

Introduce an Android call adapter owned by the application/account call runtime, never by a screen. Associate one system call with the original account generation, call ID and operation. Existing backend state remains authoritative. An incoming hint is verified before publishing a call; expiry, caller cancellation, logout or a superseding operation removes the exact associated surface. Duplicate FCM/foreground discovery must not create duplicate calls.

System answer/reject/hang-up actions pass through the same existing acceptance and cleanup gates. Permission denial must produce an understandable outcome. Do not mark the system call active merely because acceptance was queued: project the actual call/media lifecycle. Retire the system call, notification and foreground ownership even when media cleanup needs retry.

Use the required phoneCall foreground service and call notification timing for Telecom, with microphone permission/foreground requirements handled at the accepted-call boundary. A call notification has answer/reject actions and a remaining ringing deadline. The ongoing notification changes in place and exposes hang-up. Full-screen access denial still leaves a usable heads-up notification; there is no silent reliance on full-screen privileges.

## Audio integration boundary

Telecom must be the sole communication-endpoint authority for a Telecom-owned call. The current AudioSwitch cannot independently call setCommunicationDevice/startBluetoothSco in that session. Group sessions retain an explicitly owned route authority. Do not ship a system-call adapter that leaves these competing owners enabled.

The explicit Phone input + Bluetooth output requirement remains. A Bluetooth call endpoint can be a coupled SCO/HFP input/output pair; changing the preference chip alone does not split it. First instrument the owned capture/playback route, or use an audio adapter that exposes the owned AudioRecord/AudioTrack routing directly without reflection into private SDK fields. Then evaluate a separate phone-capture/headset-playback route on the physical phone. A2DP may be a useful playback-only route, but compatibility and latency are measured requirements, not assumed guarantees.

Never silently substitute the headset mic for explicit Phone intent or the speaker for explicit Headset intent. Surface actual unsupported/unavailable routing and offer recovery. Preference change completion must distinguish request submission from native route confirmation. Preserve mute, deafen, reconnect and account-retirement guards.

## Push diagnosis before claiming a fix

Record bounded, credential-free outcomes for token registration, FCM receipt, worker start, authentication restoration, binding verification, call verification and notification submission. No token, payload, email, caller name, PCM or authentication response enters diagnostics. Display notification eligibility separately from push registration and actual receipt.

A controlled call with the receiver minimized must distinguish: no FCM callback, worker deferred/failed, account restoration timeout, verification rejection, Android eligibility rejection, native post failure, and successful native submission. Native submission still does not prove audibility or heads-up presentation. Settings already granted on Pixel must not be treated as a complete diagnosis.

## Acceptance

- Meaningful pure tests assert original call identity, duplicate coalescing, stale-account/action rejection, expiry, actual adapter commands and terminal cleanup.
- Push diagnostics assert actual boundary outcomes without replacing failures with success and without exposing secrets.
- Signed native emulator checks cover incoming, answer, reject, hang-up, caller cancellation, expired invitation and relaunch/logout. Compose rendering is assessed on the device, not with synthetic unit claims.
- Physical Pixel 7 checks cover minimized and locked application, notification eligibility allowed/blocked and caller cancellation before answer. Distinguish minimizing, removing from recents and Android force-stop.
- Physical Bluetooth checks cover Phone/Headset/Auto capture, Headset/Speaker/Auto playback, switching during capture, mute/deafen and unplug/reconnect. Verify which mic is actually heard using isolated sound near each microphone and correlate owned-route evidence. Measure playback latency if using A2DP.
- Run the fresh common unit suite, signed ARM/x86 builds and database migration verification before a code commit. Keep all private credentials out of source and APKs.

## References

- [Core-Telecom lifecycle, remote controls and route ownership](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)
- [AudioManager communication device input/output coupling](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))
- [Self-managed ConnectionService requirements](https://developer.android.com/reference/android/telecom/ConnectionService)
- [AOSP input routing policy](https://android.googlesource.com/platform/frameworks/av/+/4a2c5fe8ff4bf5d612a0726c992570eafadeea81/services/audiopolicy/enginedefault/src/Engine.cpp)
