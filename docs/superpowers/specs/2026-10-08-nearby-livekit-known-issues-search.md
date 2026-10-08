# Nearby LiveKit/Pion public issue search
# 2026-10-08

## Result

I found **no public issue that matches the captured failure closely enough to identify its cause**. The closest reports share either the one-way direction pattern, a track-not-bound symptom, or a negotiation race, but each has a material mismatch in codec, topology, SDK, media-section history, or observed failure boundary.

Search scope: primary GitHub issues, pull requests and releases in livekit/livekit, LiveKit SDK repositories, and pion/webrtc. This is a bounded public search, so “no exact match found” is not evidence that no relevant private or unindexed report exists.

The capture to compare against is summarized in the local [Astra review](2026-10-08-nearby-directional-failure-astra-review.md): four independent Go SDK 2.13.3 clients; all joined and rostered; A could not receive D while D could receive A; one of twelve SFU DownTracks was unbound while eleven were bound. The first active timeout had two audio sections in the current answer and three in the pending offer. The proxy observed the three-section answer and wrote it upstream, but no observer records the result of the SFU’s SetRemoteDescription. The deployed custom SFU is based on LiveKit 1.13.5 with Pion WebRTC 4.2.17; the Go SDK uses Pion 4.2.3. The browser’s and Android SDK’s behavior is not yet known.

Correction: an earlier wording said D could send to A, contradicting the failed direction. The observed working reverse direction is A → D.

## Closest public reports

| Candidate | Why it surfaced | Match assessment and status |
|---|---|---|
| [LiveKit #4695: subscriber track loops “not bound after timeout”](https://github.com/livekit/livekit/issues/4695) | Open report against server 1.13.1 and 1.13.4; it has the same visible SFU symptom and is current enough to overlap this server generation. | **Symptom-level match only.** It is specifically an H.265 screen-share publication with an H.264 backup that fails to regress for a subscriber missing H.265. Our capture is Opus/audio with three audio sections and no codec-negotiation failure evidence. The issue does not establish this as a general unbound-DownTrack bug; it remains open. |
| [LiveKit #2103: one participant sees/hears the other, but not vice versa](https://github.com/livekit/livekit/issues/2103) | The reported directionality is unusually close: participant 1 receives participant 2 while the reverse direction fails. | **Topology-level resemblance, no diagnosis.** It used LiveKit 1.4.5 and JS SDK 1.13.0. The reporter could not reproduce it and had no client logs; the issue remains open. It predates the current server/client stacks and does not identify answer delivery or application as the cause. |
| [LiveKit Android SDK #755: remote answer rejected in stable state](https://github.com/livekit/client-sdk-android/issues/755) | Intermittent SetRemoteDescription failure during join/track negotiation, followed by unexpected unpublish events. It was on a custom self-hosted server. | **Same broad negotiation-failure class, different stack and evidence.** It reports Android SDK 2.20.1 (the current local analysis says Android 2.25.3), with native WebRTC; the reproduced harness here is Go/Pion 2.13.3. Its issue page is closed but gives no visible linked fix or root cause. The current capture has not yet recorded an apply error, so this is not a match. |
| [Pion #3417: invalid answer extmap after renegotiation](https://github.com/pion/webrtc/issues/3417) and [PR #3525](https://github.com/pion/webrtc/pull/3525) | A real Pion renegotiation defect: after stopping a video m-section and later adding audio/video, Pion can reuse stale RTP header-extension IDs; Chrome rejects the answer in SetRemoteDescription. | **Specific SDP defect, not the observed pattern.** The report names Pion 4.2.11 and needs a stopped media section plus mismatched extmap assignments. The current capture is audio-only at the failing subscriber offer and has no recorded extmap or SDP rejection error. The fix PR was still open in the retrieved page; no version applicability to the custom 4.2.17 SFU or SDK 4.2.3 can be inferred from this report. |

## Version and release context

- LiveKit merged [#3924, “Wait for SetRemoteDescription before configuring senders”](https://github.com/livekit/livekit/pull/3924) on 2025-09-12. This confirms upstream has had a real sender-configuration ordering concern, but it is a different operation and predates the 1.13.5 base. It does not explain the current answer path.
- LiveKit merged [#4614, echo offered audio payload types in single-PC subscriber answers](https://github.com/livekit/livekit/pull/4614) on 2026-06-23; it is listed in the [1.13.2 changelog](https://github.com/livekit/livekit/blob/master/CHANGELOG.md). That issue was Opus payload-type mismatch causing Firefox to receive RTP but decode no samples in single-PC mode. The captured custom SFU is based on 1.13.5 and the failure is an unbound DownTrack in the separate subscriber path, so this is a poor fit. Check the custom snapshot for reverts before treating the upstream release note as proof it contains the patch.
- The exact edge still unobserved is after successful upstream write and before the corresponding answer’s SetRemoteDescription return. The existing observer’s receipt/dispatch/process events occur before that call and do not correlate the answer to an offer/PC generation. The public reports above cannot resolve that gap.

No issue found here supports a dependency or server upgrade recommendation. The strongest conclusion is only that track-not-bound and directional audio failures exist upstream under distinct, specific conditions; the local failure still needs its correlated apply-result capture.
