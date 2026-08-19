package io.github.fplayer.core.index.pipeline;

import android.content.Context;

import androidx.annotation.NonNull;

import java.nio.file.Path;

/** Shared app-private root used by thumbnail producers and UI consumers. */
public final class ThumbnailCacheRoot {
    private ThumbnailCacheRoot() {}

    @NonNull
    public static Path forContext(@NonNull Context context) {
        return context.getCacheDir().toPath().resolve("thumbnails").toAbsolutePath().normalize();
    }
}
