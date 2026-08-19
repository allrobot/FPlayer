package io.github.fplayer.core.index.sort;

import androidx.annotation.NonNull;

import io.github.fplayer.core.index.SortDirection;
import io.github.fplayer.core.index.SortField;
import io.github.fplayer.core.index.SortSpec;
import io.github.fplayer.core.index.db.IndexEntities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Stable, locale-independent ordering for a materialized media snapshot. */
public final class StableMediaSorter {
    private StableMediaSorter() {}

    @NonNull
    public static List<IndexEntities.MediaEntity> sort(
            @NonNull List<IndexEntities.MediaEntity> input,
            @NonNull SortSpec spec,
            @NonNull Map<String, IndexEntities.PlaybackStateEntity> playback
    ) {
        ArrayList<IndexEntities.MediaEntity> result = new ArrayList<>(input);
        Comparator<IndexEntities.MediaEntity> primary = primary(spec.getField(), playback, spec.getDirection());
        result.sort(primary.thenComparing(Comparator.comparing((IndexEntities.MediaEntity m) -> m.normalizedTitle))
                .thenComparing(m -> m.normalizedPath)
                .thenComparing(m -> m.id));
        return result;
    }

    private static Comparator<IndexEntities.MediaEntity> primary(
            SortField field, Map<String, IndexEntities.PlaybackStateEntity> playback,
            SortDirection direction) {
        int sign = direction == SortDirection.DESCENDING ? -1 : 1;
        switch (field) {
            case TITLE: return (left, right) -> sign * left.normalizedTitle.compareTo(right.normalizedTitle);
            case MODIFIED_AT: return nullableComparator(m -> m.modifiedAtEpochMs, sign);
            case LAST_PLAYED_AT: return nullableComparator(
                    m -> playback.containsKey(m.id) ? playback.get(m.id).lastPlayedAtEpochMs : null, sign);
            case STATUS: return (left, right) -> sign * Integer.compare(statusRank(left.probeStatus), statusRank(right.probeStatus));
            case DURATION: return nullableComparator(m -> m.durationMs, sign);
            case SIZE: return nullableComparator(m -> m.sizeBytes, sign);
            case RESOLUTION: return (left, right) -> {
                boolean leftMissing = left.width == null || left.height == null;
                boolean rightMissing = right.width == null || right.height == null;
                if (leftMissing || rightMissing) {
                    if (leftMissing == rightMissing) return 0;
                    return leftMissing ? 1 : -1;
                }
                int result = sign * Long.compare(area(left), area(right));
                if (result != 0) return result;
                result = sign * compareNullable(left.width, right.width);
                return result != 0 ? result : sign * compareNullable(left.height, right.height);
            };
            case PATH: return (left, right) -> sign * left.normalizedPath.compareTo(right.normalizedPath);
            default: throw new IllegalArgumentException("SORT_FIELD_UNSUPPORTED");
        }
    }

    private static <T extends Comparable<? super T>> Comparator<IndexEntities.MediaEntity> nullableComparator(
            java.util.function.Function<IndexEntities.MediaEntity, T> getter, int sign) {
        return (left, right) -> {
            T leftValue = getter.apply(left); T rightValue = getter.apply(right);
            if (leftValue == null || rightValue == null) return compareNullable(leftValue, rightValue);
            return sign * leftValue.compareTo(rightValue);
        };
    }

    private static <T extends Comparable<? super T>> int compareNullable(T left, T right) {
        if (left == right) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return left.compareTo(right);
    }

    private static long area(IndexEntities.MediaEntity media) {
        if (media.width == null || media.height == null) return -1L;
        return (long) media.width * media.height;
    }

    private static int statusRank(String status) {
        if ("READY".equals(status)) return 0;
        if ("UNPROBED".equals(status)) return 1;
        if ("FAILED".equals(status)) return 2;
        return 3;
    }
}
