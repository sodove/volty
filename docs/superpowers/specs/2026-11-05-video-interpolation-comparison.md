# Ride video interpolation: comparison with Telemetry Studio

The rider explicitly requested the primary agent compare the actual code, including movement inside cards. Sources read locally in `C:/Users/sodovaya/git/telemetry-studio`, commit `f904f1f81111a5594da816a7c6135c50a8d4bab9`:

| Behavior | Telemetry Studio | First agent implementation | Required correction |
|---|---|---|---|
| Numeric values | `server/src/export/pipeline.ts:113` and `frontend/src/components/preview/VideoPreview.tsx:8`: binary search adjacent timestamps and linear interpolation `v0 + (v1-v0)*fraction` | Dashboard response filter: current chases latest target with `alpha=delta/120ms` | Replace the filter with buffered linear interpolation. The rider accepted a smaller delay; start with 80ms. Hold when the next received point is unavailable. |
| Horizontal content position | `packages/engine/src/widgets/valueCard.ts:60` and `iconValue.ts:54`: actual content width, centered block, per-frame `x += (targetX-x)*0.15` | Scene rebuild snaps directly to the new position. Cards stayed left-aligned. | Center the actual label/value/icon block and apply the same 0.15 position lerp per frame. |
| Determinism | Export precomputes offsets sequentially (`pipeline.ts:429`) before parallel rendering | Preview and recording have independent state | Retain independent state per render stream, reset on preset/geometry/vehicle changes and lifecycle gaps. |
| Animation cadence | One CanvasKit RAF loop for preview; export renders each output frame | Camera overlay capped at 15Hz; preview timer also 15Hz | Preview uses the display frame clock; recorded motion may repaint on each supported normal camera frame, up to 60Hz. Draw keys must include animated positions. |
| Missing data | Offline CSV paths may substitute zero and hold endpoints indefinitely | Raw finite/presence/expiry gates | Preserve Volty's gates. Never interpolate across missing/nonfinite/expired data or vehicle changes, use a future first sample, or invent zero. |

**Retraction:** the initial agent patch implemented a useful dashboard-style numeric chase, but that was not the same algorithm or text motion as Telemetry Studio. Keep its raw-gated scene/display-key integration; replace its numeric filter, and add the missing content motion.

The live camera cannot know arbitrary future BLE points as an offline CSV exporter does. The 80ms playout buffer provides bracketing points when received in time; late packets cause a hold rather than unbounded prediction. A physical-device check is required for visual cadence and thermal cost at 1080p60.
