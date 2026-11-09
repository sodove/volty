# Ride HUD 1440p60 stress test

**Goal** Exercise the Canvas and GLES HUD FIFOs with the same 2560×1440 AVC clip and a 60 fps source clock.

The current AVD's Camera2 metadata caps video at 30 fps even when `videofile` accepts a 1440p60 clip. The debug probe connects a paced MediaCodec decoder to each actual effect using a synthetic CameraX surface request, then muxes the hardware or software encoder output. The identical built-in HUD uses changing synthetic telemetry and the production interpolation code. The camera HAL, BLE, and microphone are outside this comparison.

- [x] Inspect Camera2 metadata with a 1440p60 clip as the virtual camera input.
- [x] Build the debug probe and run both effect modes.
- [x] Verify encoded dimensions, source and output frame counts, and packet timing.

The probe never uses a BLE device. Never write to FFE1 on Begode. Keep the Gradle worker and processor limits at four.

## Reproduce

Generate an AVC clip with `ffmpeg -f lavfi -i testsrc2=size=2560x1440:rate=60 -t 8 -c:v libx264 -preset ultrafast -threads 4 -pix_fmt yuv420p source.mp4`. Install the debug APK, copy the clip to `files/hud-stress-source.mp4` under the app UID, then launch `adb shell am start -n ru.sodovaya.volty/.video.HudStressActivity --es mode gles`. Use `canvas` for the Canvas comparison and `codec` for the decoder-to-encoder control. Pull `files/hud-stress-<mode>.mp4` using `adb exec-out run-as ru.sodovaya.volty cat` and inspect packet PTS. The probe logs under `RideHudStress`.

## Emulator results

All runs decoded 480 input frames and wrote MP4s at 2560×1440. Verify the input dimensions and rate and compare the first and last output PTS with the source before accepting a run. The probe gives the last surface callback a short grace period before draining, so a missing end PTS makes that run inconclusive. All five outputs started at 0 and ended at 7.983333 seconds.

The codec control wrote 480 frames, with no gaps above 25 ms. Canvas wrote 421 and 396 frames, with 49 and 68 gaps above 25 ms. GLES wrote 451 and 460 frames, with 24 and 15 such gaps. The second pair was run in reverse order. The GLES FIFO submitted all 451 and 460 frames it received, including all 13 frames in each stop drain. These results describe the emulator's video path with synthetic telemetry.
