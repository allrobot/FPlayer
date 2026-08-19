package io.github.fplayer.core.index.pipeline;

import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.NonNull;

import io.github.fplayer.core.index.db.IndexDao;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Builds the production thumbnail owner backed by Android media extraction. */
public final class AndroidThumbnailPipelineFactory {
    public static final int DEFAULT_WIDTH = 320;
    public static final int DEFAULT_HEIGHT = 180;
    public static final String STRATEGY_VERSION = "android-metadata-v1";

    private AndroidThumbnailPipelineFactory() {}

    @NonNull
    public static CommittedThumbnailPipelineOwner create(
            @NonNull Context context,
            @NonNull IndexDao dao
    ) throws IOException {
        Context appContext = context.getApplicationContext();
        ContentResolver resolver = appContext.getContentResolver();
        ThumbnailCache cache = new ThumbnailCache(ThumbnailCacheRoot.forContext(appContext));
        return new CommittedThumbnailPipelineOwner(
                dao,
                cache,
                new RetrieverProbe(appContext, resolver),
                new RetrieverThumbnailGenerator(appContext, resolver),
                DEFAULT_WIDTH,
                DEFAULT_HEIGHT,
                STRATEGY_VERSION
        );
    }

    @NonNull
    public static Path cacheRoot(@NonNull Context context) {
        return ThumbnailCacheRoot.forContext(context);
    }

    private static final class RetrieverProbe implements MediaMetadataProbe {
        private final Context context;
        private final ContentResolver resolver;

        RetrieverProbe(Context context, ContentResolver resolver) {
            this.context = context;
            this.resolver = resolver;
        }

        @Override
        @NonNull
        public ProbeResult probe(@NonNull String locator) throws Exception {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                setDataSource(retriever, locator);
                return new ProbeResult(
                        parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)),
                        parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),
                        parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)),
                        parseFrameRate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                        null,
                        null,
                        null
                );
            } finally {
                retriever.release();
            }
        }

        private void setDataSource(MediaMetadataRetriever retriever, String locator) {
            Uri uri = Uri.parse(locator);
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                retriever.setDataSource(context, uri);
            } else if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                retriever.setDataSource(uri.getPath());
            } else {
                retriever.setDataSource(locator);
            }
        }
    }

    private static final class RetrieverThumbnailGenerator
            implements MetadataThumbnailPipeline.ThumbnailGenerator {
        private final Context context;
        private final ContentResolver resolver;

        RetrieverThumbnailGenerator(Context context, ContentResolver resolver) {
            this.context = context;
            this.resolver = resolver;
        }

        @Override
        public byte[] generate(@NonNull String locator, int width, int height) throws Exception {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            Bitmap frame = null;
            try {
                Uri uri = Uri.parse(locator);
                if ("content".equalsIgnoreCase(uri.getScheme())) {
                    retriever.setDataSource(context, uri);
                } else if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    retriever.setDataSource(uri.getPath());
                } else {
                    retriever.setDataSource(locator);
                }
                frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) throw new IOException("THUMBNAIL_FRAME_UNAVAILABLE");
                Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                        throw new IOException("THUMBNAIL_ENCODE_FAILED");
                    }
                    return output.toByteArray();
                } finally {
                    if (scaled != frame) scaled.recycle();
                }
            } finally {
                if (frame != null) frame.recycle();
                retriever.release();
            }
        }
    }

    private static Long parseLong(String value) {
        try { return value == null ? null : Long.parseLong(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Integer parseInt(String value) {
        try { return value == null ? null : Integer.parseInt(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Double parseFrameRate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int slash = value.indexOf('/');
            if (slash > 0) {
                return Double.parseDouble(value.substring(0, slash)) /
                        Double.parseDouble(value.substring(slash + 1));
            }
            return Double.parseDouble(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
