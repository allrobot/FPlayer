package io.github.fplayer.core.index.smb;

import androidx.annotation.NonNull;

import io.github.fplayer.core.index.saf.LocalSafScanner;
import io.github.fplayer.core.index.db.IndexDao;

import java.io.IOException;
import java.util.function.LongSupplier;

/** One-shot SMB scan. A failed generation leaves the previous current snapshot intact. */
public final class SmbScanner {
    private final LocalSafScanner delegate;

    public SmbScanner(IndexDao dao, LongSupplier clock) {
        delegate = new LocalSafScanner(dao, clock, "SMB");
    }

    @NonNull
    public SmbScanResult scan(
            @NonNull SmbSourceConfig config,
            long generation,
            @NonNull SmbDocumentTree tree,
            @NonNull LocalSafScanner.Cancellation cancellation
    ) throws IOException {
        if (!tree.isOnline()) return SmbScanResult.offline(generation);
        try {
            return SmbScanResult.online(delegate.scan(config.sourceId, generation, tree, cancellation));
        } catch (IOException exception) {
            return SmbScanResult.failed(generation, exception.getClass().getSimpleName());
        }
    }

    public static final class SmbScanResult {
        public enum State { ONLINE, OFFLINE, FAILED }
        public final long generation;
        public final State state;
        public final LocalSafScanner.ScanResult scan;
        public final String failureCode;

        private SmbScanResult(long generation, State state, LocalSafScanner.ScanResult scan, String failureCode) {
            this.generation = generation; this.state = state; this.scan = scan; this.failureCode = failureCode;
        }
        static SmbScanResult online(LocalSafScanner.ScanResult scan) {
            return new SmbScanResult(scan.generation, State.ONLINE, scan, scan.failureCode);
        }
        static SmbScanResult offline(long generation) { return new SmbScanResult(generation, State.OFFLINE, null, "SMB_OFFLINE"); }
        static SmbScanResult failed(long generation, String failure) { return new SmbScanResult(generation, State.FAILED, null, "SMB_IO_FAILED"); }
    }
}
