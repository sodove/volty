package androidx.camera.video;

import android.annotation.SuppressLint;

import androidx.camera.video.internal.encoder.VideoEncoderConfig;

/** Bridge to the package-private encoder factory in CameraX 1.5.3. */
public final class VoltyRideRecorder {
    private static final int TARGET_VIDEO_BITRATE = 20_000_000;
    private static final int KEY_FRAME_INTERVAL_SECONDS = 2;

    private VoltyRideRecorder() {}

    @SuppressLint("RestrictedApi")
    public static Recorder create(Quality quality) {
        return new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .setTargetVideoEncodingBitRate(TARGET_VIDEO_BITRATE)
                .setVideoEncoderFactory((executor, config, sessionType) -> {
                    // Preserve CameraX's resolved profile, bitrate, frame rates and timebase.
                    VideoEncoderConfig videoConfig = ((VideoEncoderConfig) config).toBuilder()
                            .setIFrameInterval(KEY_FRAME_INTERVAL_SECONDS)
                            .build();
                    return Recorder.DEFAULT_ENCODER_FACTORY.createEncoder(
                            executor, videoConfig, sessionType);
                })
                .build();
    }
}
