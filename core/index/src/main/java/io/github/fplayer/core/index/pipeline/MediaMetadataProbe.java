package io.github.fplayer.core.index.pipeline;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Metadata-only probe contract; implementations may use libmpv or a platform extractor. */
public interface MediaMetadataProbe {
    @NonNull ProbeResult probe(@NonNull String locator) throws Exception;

    final class ProbeResult {
        @Nullable public final Long durationMs;
        @Nullable public final Integer width;
        @Nullable public final Integer height;
        @Nullable public final Double frameRate;
        @Nullable public final String mimeType;
        @Nullable public final String videoCodec;
        @Nullable public final String audioCodec;
        @Nullable public final Integer subtitleCount;

        public ProbeResult(Long durationMs, Integer width, Integer height, Double frameRate,
                           String mimeType, String videoCodec, String audioCodec, Integer subtitleCount) {
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.mimeType = mimeType;
            this.videoCodec = videoCodec;
            this.audioCodec = audioCodec;
            this.subtitleCount = subtitleCount;
        }
    }
}
