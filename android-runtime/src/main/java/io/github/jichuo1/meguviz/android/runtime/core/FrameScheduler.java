package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One platform driver, stable batches and rotating service opportunities. */
public final class FrameScheduler implements AutoCloseable {
    @FunctionalInterface public interface FrameWork { int drain(FrameTick tick, int quota); }
    private final OwnerDispatcher owner;
    private final FrameDriver driver;
    private final Failures failures;
    private final int globalQuota;
    private final List<FrameChannel> channels = new ArrayList<>();
    private final FrameDriver.Callback frameCallback = new FrameDriver.Callback() {
        @Override public void onFrame(long time) { FrameScheduler.this.onFrame(time, null, null); }
        @Override public void onFrame(long time, long deadline, long presentation) {
            FrameScheduler.this.onFrame(time, deadline, presentation);
        }
    };
    private boolean scheduled;
    private boolean draining;
    private boolean closed;
    private int cursor;
    private long sequence;
    /** Reused per-frame snapshot buffer; grows with the channel count. Package-private for contract tests. */
    BatchEntry[] batchEntries = new BatchEntry[0];

    public FrameScheduler(OwnerDispatcher owner, FrameDriver driver, ErrorSink sink, int globalQuota) {
        this.owner = Objects.requireNonNull(owner);
        this.driver = Objects.requireNonNull(driver);
        failures = new Failures(sink);
        if (globalQuota <= 0) throw new IllegalArgumentException("Positive global quota required");
        this.globalQuota = globalQuota;
        owner.checkOwner();
    }

    public FrameChannel openChannel(Object identity, FrameWork callback, int quota) {
        owner.checkOwner();
        if (closed) throw new IllegalStateException("Scheduler closed");
        if (quota <= 0) throw new IllegalArgumentException("Positive channel quota required");
        Objects.requireNonNull(identity); Objects.requireNonNull(callback);
        for (FrameChannel channel : channels) {
            if (channel.identity == identity) throw new IllegalStateException("Owner already has a frame driver channel");
        }
        FrameChannel channel = new FrameChannel(this, owner, identity, callback, quota);
        channels.add(channel);
        return channel;
    }

    private boolean hasPending() {
        for (FrameChannel channel : channels) if (channel.pending) return true;
        return false;
    }

    private void schedule() {
        if (closed || scheduled || draining || !hasPending()) return;
        scheduled = true;
        boolean accepted;
        try { accepted = driver.request(frameCallback); }
        catch (Throwable failure) {
            scheduled = false;
            ErrorSink sink = failures.sink();
            close();
            failures.reportTo(sink, failure);
            throw new IllegalStateException("Frame driver failed", failure);
        }
        if (!accepted) {
            scheduled = false;
            close();
            throw new IllegalStateException("Frame driver rejected request");
        }
    }

    private void onFrame(long time, Long deadline, Long presentation) {
        owner.checkOwner();
        if (closed || !scheduled) return;
        scheduled = false;
        // Reentrancy is unreachable through the public contract (schedule never requests a frame
        // while one is draining), but a reentrant delivery must never share the instance snapshot
        // buffer, so it falls back to a private array. The flag is saved and restored, not cleared:
        // while any outer frame is still walking the instance buffer, a nested exit must leave
        // draining set, or a second nested delivery would reuse and overwrite the outer snapshot.
        boolean wasDraining = draining;
        boolean reusing = !draining;
        draining = true;
        int size = channels.size();
        int start = size == 0 ? 0 : cursor % size;
        BatchEntry[] batch = reusing ? batchEntries : new BatchEntry[0];
        if (batch.length < size) {
            BatchEntry[] grown = new BatchEntry[size];
            batch = grown;
            if (reusing) batchEntries = grown;
        }
        int count = 0;
        for (int offset = 0; offset < size; offset++) {
            FrameChannel channel = channels.get((start + offset) % size);
            if (channel.pending) {
                channel.pending = false;
                BatchEntry entry = batch[count];
                if (entry == null) { entry = new BatchEntry(); batch[count] = entry; }
                entry.reset(channel, channel.cancellation);
                count++;
            }
        }
        if (size > 0) cursor = (start + 1) % size;
        int remaining = globalQuota;
        FrameTick tick = new FrameTick(++sequence, time, deadline, presentation);
        try {
            for (int index = 0; index < count; index++) {
                BatchEntry entry = batch[index];
                FrameChannel channel = entry.channel;
                if (closed) break;
                entry.processed = true;
                if (channel.callback == null || entry.cancellation != channel.cancellation) continue;
                if (remaining == 0) { channel.pending = true; continue; }
                int quota = Math.min(remaining, channel.quota);
                try {
                    int consumed = channel.callback.drain(tick, quota);
                    if (consumed < 0 || consumed > quota) throw new IllegalStateException("Frame callback violated quota");
                    remaining -= Math.max(1, consumed);
                } catch (Throwable failure) {
                    remaining -= quota;
                    channel.close();
                    failures.report(failure);
                }
            }
        } finally {
            draining = wasDraining;
            for (int index = 0; index < count; index++) {
                BatchEntry entry = batch[index];
                if (!entry.processed && entry.channel.callback != null && entry.cancellation == entry.channel.cancellation)
                    entry.channel.pending = true;
                // Drop the channel reference so a closed or replaced channel is never retained.
                entry.channel = null;
            }
            schedule();
        }
    }

    public int channelCount() { owner.checkOwner(); return channels.size(); }
    public boolean hasScheduledFrame() { owner.checkOwner(); return scheduled; }
    public Throwable lastFailure() { return failures.last(); }

    @Override public void close() {
        owner.checkOwner();
        if (closed) return;
        closed = true;
        ErrorSink sink = failures.sink();
        Throwable first = null;
        try { if (scheduled) driver.remove(frameCallback); } catch (Throwable failure) { first = failure; }
        scheduled = false;
        for (FrameChannel channel : new ArrayList<>(channels)) {
            try { channel.close(); }
            catch (Throwable failure) {
                first = Failures.combine(first, failure);
            }
        }
        channels.clear();
        // Replace the snapshot buffer wholesale; an in-flight onFrame keeps its own local array.
        batchEntries = new BatchEntry[0];
        failures.clearSink();
        if (first != null) failures.reportTo(sink, first);
    }

    private static final class BatchEntry {
        private FrameChannel channel;
        private long cancellation;
        private boolean processed;
        private void reset(FrameChannel channel, long cancellation) {
            this.channel = channel; this.cancellation = cancellation; this.processed = false;
        }
    }

    public static final class FrameChannel implements AutoCloseable {
        private FrameScheduler scheduler;
        private final OwnerDispatcher owner;
        private Object identity;
        private FrameWork callback;
        private final int quota;
        private boolean pending;
        private long cancellation;
        private Runnable closeListener;
        private FrameChannel(FrameScheduler scheduler, OwnerDispatcher owner, Object identity, FrameWork callback, int quota) {
            this.scheduler = scheduler; this.owner = owner;
            this.identity = identity; this.callback = callback; this.quota = quota;
        }
        public boolean isClosed() { owner.checkOwner(); return callback == null; }
        public void onClose(Runnable listener) {
            owner.checkOwner();
            if (callback == null) throw new IllegalStateException("Frame channel closed");
            if (closeListener != null) throw new IllegalStateException("Close listener already owned");
            closeListener = Objects.requireNonNull(listener);
        }
        public void requestFrame() {
            owner.checkOwner();
            if (callback == null || scheduler == null || scheduler.closed) throw new IllegalStateException("Frame channel closed");
            pending = true; scheduler.schedule();
        }
        public void cancelPending() {
            owner.checkOwner(); pending = false; cancellation++;
            if (scheduler != null && scheduler.scheduled && !scheduler.hasPending()) {
                try { scheduler.driver.remove(scheduler.frameCallback); } finally { scheduler.scheduled = false; }
            }
        }
        @Override public void close() {
            owner.checkOwner();
            if (callback == null) return;
            callback = null; identity = null;
            Runnable listener = closeListener; closeListener = null;
            Throwable first = null;
            try { cancelPending(); } catch (Throwable failure) { first = failure; }
            FrameScheduler previous = scheduler; scheduler = null;
            if (previous != null) previous.channels.remove(this);
            try { if (listener != null) listener.run(); }
            catch (Throwable failure) {
                first = Failures.combine(first, failure);
            }
            if (first != null) {
                Failures.rethrowFatal(first);
                if (first instanceof RuntimeException) throw (RuntimeException) first;
                throw new IllegalStateException("Frame channel cleanup failed", first);
            }
        }
    }
}
