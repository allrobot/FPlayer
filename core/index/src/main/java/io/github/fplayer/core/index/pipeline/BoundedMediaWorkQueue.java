package io.github.fplayer.core.index.pipeline;

import androidx.annotation.NonNull;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Deterministic, bounded queue for metadata and thumbnail work. */
public final class BoundedMediaWorkQueue<T> {
    private final int capacity;
    private final PriorityQueue<Item<T>> queue = new PriorityQueue<>(
            Comparator.comparingInt((Item<T> item) -> item.priority.rank)
                    .thenComparingLong(item -> item.sequence)
    );
    private long sequence;

    public BoundedMediaWorkQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("QUEUE_CAPACITY_INVALID");
        this.capacity = capacity;
    }

    public synchronized boolean offer(@NonNull T value, @NonNull WorkPriority priority) {
        if (queue.size() >= capacity) {
            Item<T> worst = queue.stream().max(
                    Comparator.comparingInt((Item<T> item) -> item.priority.rank)
                            .thenComparingLong(item -> item.sequence)
            ).orElseThrow();
            if (worst.priority.rank <= priority.rank) return false;
            queue.remove(worst);
        }
        queue.add(new Item<>(value, priority, sequence++));
        return true;
    }

    public synchronized Item<T> poll() { return queue.poll(); }
    public synchronized int size() { return queue.size(); }
    public synchronized void clear() { queue.clear(); }

    public enum WorkPriority {
        CURRENT(0), ADJACENT(1), REMAINING(2);
        final int rank;
        WorkPriority(int rank) { this.rank = rank; }
    }

    public static final class Item<T> {
        @NonNull public final T value;
        @NonNull public final WorkPriority priority;
        public final long sequence;
        Item(T value, WorkPriority priority, long sequence) {
            this.value = value;
            this.priority = priority;
            this.sequence = sequence;
        }
    }
}
