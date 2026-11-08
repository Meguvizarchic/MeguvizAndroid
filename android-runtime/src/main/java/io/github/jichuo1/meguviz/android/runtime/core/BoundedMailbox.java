package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Bounded, multi-producer latest-state and FIFO-command mailbox. */
public final class BoundedMailbox implements AutoCloseable {
    public enum Admission { ACCEPTED, REPLACED, REJECTED_CAPACITY, REJECTED_CLOSED, UNSUPPORTED }
    public enum Outcome { PENDING, RUNNING, COMPLETED, CANCELLED, SUPERSEDED, STALE, INACTIVE, FAILED, CLOSED }
    public enum Cancellation { CANCELLED, ALREADY_RUNNING, ALREADY_TERMINAL }

    /** Shared release for the no-cleanup shorthand overloads; never null, never observable. */
    private static final Runnable NOOP_RELEASE = () -> {};

    private final Object lock = new Object();
    private final Object identity = new Object();
    private final LifecycleScope scope;
    private final OwnerDispatcher owner;
    private final Failures failures;
    private final int stateCapacity;
    private final int commandCapacity;
    /** Drain order of state slots; a slot appears at most once, marked by Slot.queued. */
    private final SlotQueue stateQueue = new SlotQueue();
    /**
     * Latest state work per slot. The value is the {@link WorkHandle} itself, which carries the
     * slot/action/release payload; a separate item object per offer would only duplicate the handle.
     */
    private final IdentityHashMap<Slot, WorkHandle> latest = new IdentityHashMap<>();
    /**
     * Pending commands and in-flight work. Both are intrusive FIFOs so cancel/supersede can unlink in
     * O(1) while holding the lock; a handle is in at most one of them, tracked by
     * {@link WorkHandle#inExtracted}.
     */
    private final HandleQueue commands = new HandleQueue();
    private final HandleQueue extracted = new HandleQueue();
    private final FrameScheduler.FrameChannel channel;
    private final Registration registration;
    private Signal signal;
    /** True while a wakeup is posted (or about to be) and not yet consumed by a drain. */
    private boolean signalPending;
    private boolean paused;
    private boolean closed;
    private boolean ownerCleanupDone;
    private boolean draining;
    private Runnable closeListener;
    private boolean commandTurn = true;
    private int pendingCommands;
    private long sequence;
    Runnable afterExtract;
    Runnable beforeFinish;
    /** Reused per-frame extraction batch; package-private for contract tests. */
    final ArrayList<WorkHandle> drainBatch = new ArrayList<>();

    public BoundedMailbox(LifecycleScope scope, FrameScheduler scheduler, ErrorSink sink,
                          int stateCapacity, int commandCapacity, int perFrameQuota) {
        this.scope = Objects.requireNonNull(scope);
        owner = scope.dispatcher(); owner.checkOwner();
        failures = new Failures(sink);
        if (stateCapacity <= 0 || commandCapacity <= 0) throw new IllegalArgumentException("Positive capacities required");
        this.stateCapacity = stateCapacity; this.commandCapacity = commandCapacity;
        channel = scheduler.openChannel(identity, this::drain, perFrameQuota);
        Registration owned = null;
        try { owned = scope.own(new Registration(owner, this::close)); }
        catch (Throwable failure) {
            try { channel.close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        registration = owned;
        channel.onClose(() -> { if (!isClosed()) failClosed(new IllegalStateException("Frame channel closed")); });
    }

    public Slot newSlot() { owner.checkOwner(); synchronized (lock) { if (closed) throw new IllegalStateException("Mailbox closed"); } return new Slot(identity); }
    public Submission offerState(Slot slot, WorkToken token, Runnable callback, Runnable release) {
        if (slot == null || slot.mailboxIdentity != identity) return new Submission(Admission.UNSUPPORTED, null);
        return offer(slot, token, callback, release);
    }
    /** State offer without a caller-owned cleanup: admission, coalescing and token checks are identical. */
    public Submission offerState(Slot slot, WorkToken token, Runnable callback) {
        return offerState(slot, token, callback, NOOP_RELEASE);
    }
    public Submission offerCommand(WorkToken token, Runnable callback, Runnable release) {
        return offer(null, token, callback, release);
    }
    /** Command offer without a caller-owned cleanup: FIFO admission and token checks are identical. */
    public Submission offerCommand(WorkToken token, Runnable callback) {
        return offerCommand(token, callback, NOOP_RELEASE);
    }
    public Submission offerBatch(WorkToken token, List<Runnable> callbacks, Runnable release) {
        final List<Runnable> copy = new ArrayList<>(callbacks);
        for (Runnable callback : copy) Objects.requireNonNull(callback);
        if (copy.isEmpty()) throw new IllegalArgumentException("Empty batch");
        return offerCommand(token, () -> { for (Runnable callback : copy) callback.run(); }, release);
    }
    /** Batch offer without a caller-owned cleanup: same snapshot, order and token checks. */
    public Submission offerBatch(WorkToken token, List<Runnable> callbacks) {
        return offerBatch(token, callbacks, NOOP_RELEASE);
    }

    private Submission offer(Slot slot, WorkToken token, Runnable callback, Runnable release) {
        Objects.requireNonNull(token); Objects.requireNonNull(callback); Objects.requireNonNull(release);
        if (token.scope != scope) return new Submission(Admission.UNSUPPORTED, null);
        WorkHandle replaced = null;
        Signal dispatch;
        Submission result;
        synchronized (lock) {
            if (closed || scope.admissionClosed()) return new Submission(Admission.REJECTED_CLOSED, null);
            WorkHandle previous = slot == null ? null : latest.get(slot);
            if (slot == null ? pendingCommands >= commandCapacity : previous == null && latest.size() >= stateCapacity)
                return new Submission(Admission.REJECTED_CAPACITY, null);
            if (previous != null) replaced = terminateLocked(previous, Outcome.SUPERSEDED, null, false);
            WorkHandle item = new WorkHandle(++sequence, this, slot, token, callback, release);
            if (slot == null) { commands.addLast(item); pendingCommands++; }
            else {
                // A replacement inherits the queued slot position, so drain order never moves;
                // only a first offer (or one after the previous item left the queue) appends.
                // The flag lives on the slot, not on the terminated previous handle.
                if (previous == null || !slot.queued) { stateQueue.addLast(slot); slot.queued = true; }
                latest.put(slot, item);
            }
            if (paused && slot == null) replaced = terminateLocked(item, Outcome.INACTIVE, null, true);
            dispatch = requestSignalLocked();
            result = new Submission(previous == null ? Admission.ACCEPTED : Admission.REPLACED, item);
        }
        Throwable failure = release(replaced);
        if (failure != null) failClosed(failure);
        post(dispatch);
        return result;
    }

    private boolean hasWorkLocked() { return !stateQueue.isEmpty() || !commands.isEmpty(); }
    private Signal requestSignalLocked() {
        if (signalPending || paused || closed || !hasWorkLocked()) return null;
        signalPending = true;
        if (signal == null) signal = new Signal();
        return signal;
    }
    private void post(Signal pending) {
        if (pending == null) return;
        if (owner.isOwnerThread()) { pending.run(); return; }
        boolean accepted;
        try { accepted = owner.post(pending); }
        catch (Throwable failure) { failClosed(failure); return; }
        if (!accepted) { failClosed(new IllegalStateException("Owner dispatcher rejected mailbox wakeup")); return; }
        // Do not owner.remove(pending) here: the signal instance is shared, so a producer that
        // re-arms it between this post and the check would lose its wakeup. Signal.run re-checks
        // signalPending/paused/closed and exits without requesting a frame, so a stale wakeup is
        // only one wasted runnable, never a lost wakeup.
    }

    private final class Signal implements Runnable {
        @Override public void run() {
            owner.checkOwner();
            synchronized (lock) { if (closed || paused || !signalPending) return; }
            try { channel.requestFrame(); }
            catch (Throwable failure) { failClosed(failure); }
        }
    }

    private int drain(FrameTick tick, int quota) {
        owner.checkOwner();
        // Reentrancy is unreachable through the public contract (FrameScheduler.schedule never
        // requests a frame while one is draining, and every delivery is owner-thread checked), but
        // a reentrant drain must never touch the instance buffer, so it falls back to a local list.
        // The flag is saved and restored, not cleared: while any outer drain is still iterating the
        // instance buffer, a nested exit must leave draining set, or a second nested call would
        // reuse and clear the list the outer loop is still walking.
        boolean wasDraining = draining;
        boolean reusing = !draining;
        List<WorkHandle> batch = reusing ? drainBatch : new ArrayList<>(quota);
        if (reusing) batch.clear();
        draining = true;
        int drained;
        try {
            synchronized (lock) {
                if (closed || paused) return 0;
                while (batch.size() < quota && hasWorkLocked()) {
                    WorkHandle item;
                    if (!commands.isEmpty() && (commandTurn || stateQueue.isEmpty())) item = commands.removeFirst();
                    else {
                        Slot slot = stateQueue.removeFirst();
                        slot.queued = false;
                        item = latest.get(slot);
                    }
                    commandTurn = !commandTurn;
                    extracted.addLast(item); item.inExtracted = true; batch.add(item);
                }
            }
            try {
                if (afterExtract != null) afterExtract.run();
                for (WorkHandle item : batch) execute(item);
            } finally {
                if (beforeFinish != null) beforeFinish.run();
                boolean again;
                synchronized (lock) {
                    again = !closed && !paused && hasWorkLocked();
                    if (!again) signalPending = false;
                }
                if (again) {
                    try { channel.requestFrame(); }
                    catch (Throwable failure) { failClosed(failure); }
                }
            }
            drained = batch.size();
        } finally {
            draining = wasDraining;
            // Drop item references, which hold the caller's action/release closures, before any
            // reentrant delivery could observe this buffer again.
            batch.clear();
        }
        return drained;
    }

    private void execute(WorkHandle item) {
        Runnable action;
        WorkHandle terminated = null;
        synchronized (lock) {
            if (item.outcome != Outcome.PENDING) return;
            if (closed) terminated = terminateLocked(item, Outcome.CLOSED, null, true);
            else if (!item.token.isCurrent()) terminated = terminateLocked(item, Outcome.STALE, null, true);
            else if (paused && item.slot != null) {
                extracted.remove(item); item.inExtracted = false;
                if (latest.get(item.slot) == item) { stateQueue.addLast(item.slot); item.slot.queued = true; }
                return;
            }
            else if (paused || !scope.isActive()) terminated = terminateLocked(item, Outcome.INACTIVE, null, true);
            else {
                extracted.remove(item); item.inExtracted = false;
                if (item.slot == null) pendingCommands--;
                else if (latest.get(item.slot) == item) latest.remove(item.slot);
                item.outcome = Outcome.RUNNING;
            }
            action = item.action;
        }
        if (terminated != null) {
            Throwable failure = release(terminated);
            if (failure != null) failClosed(failure);
            return;
        }
        Throwable failure = null;
        try { action.run(); } catch (Throwable error) { failure = error; }
        synchronized (lock) { terminated = terminateLocked(item, failure == null ? Outcome.COMPLETED : Outcome.FAILED, failure, true); }
        Throwable releaseFailure = release(terminated);
        failure = Failures.combine(failure, releaseFailure);
        if (failure != null) failClosed(failure);
    }

    /**
     * Terminates one handle and returns it carrying its release callback, or null when the handle
     * was already terminal. The handle is the queued item, so no per-termination wrapper is
     * allocated and the release callback survives on the handle until {@link #release} runs it.
     */
    private WorkHandle terminateLocked(WorkHandle item, Outcome result, Throwable failure, boolean removeStatePosition) {
        Outcome previous = item.outcome;
        if (previous != Outcome.PENDING && previous != Outcome.RUNNING) return null;
        if (previous == Outcome.PENDING) {
            // Unlink from the queue the item actually occupies; every unlink is O(1), so a producer
            // thread never scans a queue while holding the lock. A state item is queued exactly when
            // slot.queued is set, and a command is in extracted exactly when inExtracted is set.
            if (item.inExtracted) extracted.remove(item);
            else if (item.slot == null) { commands.remove(item); pendingCommands--; }
            else if (removeStatePosition && item.slot.queued && latest.get(item.slot) == item) {
                stateQueue.remove(item.slot);
                item.slot.queued = false;
            }
            if (item.slot != null && latest.get(item.slot) == item) latest.remove(item.slot);
        }
        item.outcome = result; item.failure = failure;
        // Detach from the mailbox and drop the payload; release stays until release(handle) runs it.
        item.mailbox = null; item.slot = null; item.action = null; item.token = null;
        item.inExtracted = false;
        return item;
    }

    /** Test-only seam invoked between the idle decision and the wake cancellation. */
    Runnable idleWakeProbe;

    private Throwable release(WorkHandle handle) {
        if (handle == null) return null;
        Runnable callback = handle.release;
        handle.release = null;
        if (callback == null) return null;
        try { callback.run(); return null; }
        catch (Throwable failure) {
            handle.failure = Failures.combine(handle.failure, failure); handle.outcome = Outcome.FAILED;
            return failure;
        }
    }

    private Cancellation cancel(WorkHandle item) {
        WorkHandle terminated;
        synchronized (lock) {
            if (item.outcome == Outcome.RUNNING) return Cancellation.ALREADY_RUNNING;
            if (item.outcome != Outcome.PENDING) return Cancellation.ALREADY_TERMINAL;
            terminated = terminateLocked(item, Outcome.CANCELLED, null, true);
        }
        Throwable failure = release(terminated);
        if (failure != null) failClosed(failure);
        cancelIdleWake();
        return Cancellation.CANCELLED;
    }

    private void cancelIdleWake() {
        synchronized (lock) {
            if (hasWorkLocked() || !extracted.isEmpty()) return;
            // Clearing the flag is enough: the shared Signal instance may already have been
            // re-armed by a concurrent producer, and removing it by identity would delete that
            // new wakeup and stall the mailbox permanently. A stale run exits on the flag.
            signalPending = false;
        }
        // Test seam: runs after the idle decision and before any further action, so a test can
        // deterministically interleave a producer re-arming the shared wakeup here.
        Runnable probe = idleWakeProbe;
        if (probe != null) probe.run();
        // Re-check: a producer that re-armed the wakeup (or an inline dispatcher that already ran
        // it) may have requested a frame; cancelling here would drop that fresh request.
        boolean idle;
        synchronized (lock) { idle = !signalPending && !hasWorkLocked() && extracted.isEmpty(); }
        if (idle && owner.isOwnerThread()) channel.cancelPending();
    }

    public void setPaused(boolean value) {
        owner.checkOwner();
        Signal wake = null;
        Signal dispatch = null;
        List<WorkHandle> cancelled = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            if (paused == value) return;
            paused = value;
            if (paused) {
                wake = signalPending ? signal : null;
                signalPending = false;
                for (WorkHandle item = commands.head; item != null; ) {
                    WorkHandle next = item.nextLink;
                    cancelled.add(terminateLocked(item, Outcome.INACTIVE, null, true));
                    item = next;
                }
                for (WorkHandle item = extracted.head; item != null; ) {
                    WorkHandle next = item.nextLink;
                    if (item.slot == null) cancelled.add(terminateLocked(item, Outcome.INACTIVE, null, true));
                    item = next;
                }
            } else dispatch = requestSignalLocked();
        }
        if (paused) { if (wake != null) owner.remove(wake); channel.cancelPending(); }
        Throwable failure = releaseAll(cancelled);
        if (failure != null) failClosed(failure);
        post(dispatch);
    }

    public int pendingStateCount() { synchronized (lock) { return latest.size(); } }
    public int pendingCommandCount() { synchronized (lock) { return pendingCommands; } }
    public boolean isClosed() { synchronized (lock) { return closed; } }
    public Throwable lastFailure() { return failures.last(); }
    public void onClose(Runnable listener) {
        owner.checkOwner();
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Mailbox closed");
            if (closeListener != null) throw new IllegalStateException("Mailbox already has a close owner");
            closeListener = Objects.requireNonNull(listener);
        }
    }

    private Throwable releaseAll(List<WorkHandle> terminals) {
        Throwable first = null;
        for (WorkHandle terminal : terminals) {
            Throwable failure = release(terminal);
            if (failure != null) {
                first = Failures.combine(first, failure);
            }
        }
        return first;
    }

    private void failClosed(Throwable failure) {
        ErrorSink sink = failures.sink();
        terminateAll(Outcome.FAILED, failure);
        failures.reportTo(sink, failure);
    }

    private void terminateAll(Outcome outcome, Throwable reason) {
        ErrorSink sink = failures.sink();
        Signal wake;
        List<WorkHandle> terminals = new ArrayList<>();
        synchronized (lock) {
            wake = signalPending ? signal : null; signalPending = false;
            closed = true;
            // Preserve the deterministic drain order for the release callbacks instead of the
            // IdentityHashMap iteration order of latest.values().
            LinkedHashSet<WorkHandle> all = new LinkedHashSet<>();
            for (Slot slot = stateQueue.head; slot != null; slot = slot.nextLink) {
                WorkHandle queued = latest.get(slot);
                if (queued != null) all.add(queued);
            }
            for (WorkHandle item = commands.head; item != null; item = item.nextLink) all.add(item);
            for (WorkHandle item = extracted.head; item != null; item = item.nextLink) all.add(item);
            // Per-item unlink is O(1), so termination leaves every queue empty before the bulk clear.
            for (WorkHandle item : all) terminals.add(terminateLocked(item, outcome, reason, true));
            for (Slot slot = stateQueue.head; slot != null; slot = slot.nextLink) slot.queued = false;
            stateQueue.clear(); latest.clear(); commands.clear(); extracted.clear(); pendingCommands = 0;
        }
        Throwable cleanup = null;
        try { if (wake != null) owner.remove(wake); } catch (Throwable failure) { cleanup = failure; }
        Throwable payload = releaseAll(terminals);
        cleanup = Failures.combine(cleanup, payload);
        if (owner.isOwnerThread()) {
            cleanup = Failures.combine(cleanup, ownerCleanup());
        } else {
            // A producer thread can reach failClosed (release, dispatcher rejection, cancel). The
            // channel and registration must still be released on the owner; the cleanup result is
            // reported through the captured sink because the field is cleared below.
            ErrorSink deferredSink = sink;
            try {
                boolean accepted = owner.post(() -> {
                    Throwable deferred = ownerCleanup();
                    if (deferred != null) failures.reportTo(deferredSink, deferred);
                });
                if (!accepted) {
                    cleanup = Failures.combine(cleanup,
                            new IllegalStateException("Owner dispatcher rejected deferred mailbox cleanup"));
                }
            } catch (Throwable failure) {
                cleanup = Failures.combine(cleanup, failure);
            }
        }
        failures.clearSink();
        if (cleanup != null) failures.reportTo(sink, cleanup);
    }

    @Override public void close() { owner.checkOwner(); terminateAll(Outcome.CLOSED, null); }

    /** Owner-thread release of the frame channel, lifecycle registration and close listener. */
    private Throwable ownerCleanup() {
        boolean clean;
        Runnable listener;
        synchronized (lock) {
            clean = !ownerCleanupDone; ownerCleanupDone = true;
            listener = closeListener; closeListener = null;
        }
        if (!clean) return null;
        Throwable cleanup = null;
        try { channel.close(); } catch (Throwable failure) { cleanup = Failures.combine(cleanup, failure); }
        if (registration != null) {
            try { scope.release(registration); } catch (Throwable failure) { cleanup = Failures.combine(cleanup, failure); }
        }
        try { if (listener != null) listener.run(); }
        catch (Throwable failure) { cleanup = Failures.combine(cleanup, failure); }
        return cleanup;
    }

    public static final class Slot {
        private final Object mailboxIdentity;
        /** True while this slot occupies a position in the state drain queue; drain order marker. */
        boolean queued;
        /** Intrusive stateQueue links; membership is tracked by {@link #queued}. */
        private Slot previousLink;
        private Slot nextLink;
        private Slot(Object identity) { mailboxIdentity = identity; }
    }
    public static final class Submission {
        private final Admission admission;
        private final WorkHandle handle;
        Submission(Admission admission, WorkHandle handle) { this.admission = admission; this.handle = handle; }
        public Admission admission() { return admission; }
        public WorkHandle handle() { return handle; }
        public boolean accepted() { return handle != null; }
    }
    /**
     * One queued work item and its public handle. The handle is the item: it carries the slot,
     * token, action and release payload while queued, so a separate per-offer item object is not
     * allocated. Payload fields are private and are cleared on termination.
     */
    public static final class WorkHandle {
        private final long sequence;
        private volatile Outcome outcome = Outcome.PENDING;
        private volatile Throwable failure;
        private volatile BoundedMailbox mailbox;
        private Slot slot;
        private WorkToken token;
        private Runnable action;
        /** Release callback carried from termination until it runs; never exposed to callers. */
        private Runnable release;
        /** Intrusive FIFO links; a handle is in at most one of commands/extracted. */
        private WorkHandle previousLink;
        private WorkHandle nextLink;
        /** True while this handle occupies a position in the extracted queue. */
        private boolean inExtracted;
        private WorkHandle(long sequence, BoundedMailbox mailbox, Slot slot, WorkToken token,
                          Runnable action, Runnable release) {
            this.sequence = sequence; this.mailbox = mailbox;
            this.slot = slot; this.token = token; this.action = action; this.release = release;
        }
        public long sequence() { return sequence; }
        public Outcome outcome() { return outcome; }
        public Throwable failure() { return failure; }
        public Cancellation cancel() {
            BoundedMailbox current = mailbox;
            if (current == null) return Cancellation.ALREADY_TERMINAL;
            return current.cancel(this);
        }
    }

    /**
     * Intrusive doubly-linked FIFO of {@link WorkHandle}. Add, poll and arbitrary remove are all
     * O(1) and allocation-free, so a producer thread never scans a queue while holding the lock.
     * The link fields live on the handle itself; a handle belongs to at most one HandleQueue.
     */
    static final class HandleQueue {
        WorkHandle head;
        WorkHandle tail;

        boolean isEmpty() { return head == null; }
        WorkHandle peekLast() { return tail; }

        void addLast(WorkHandle node) {
            node.previousLink = tail;
            node.nextLink = null;
            if (tail == null) head = node; else tail.nextLink = node;
            tail = node;
        }

        WorkHandle removeFirst() {
            WorkHandle node = head;
            if (node == null) return null;
            head = node.nextLink;
            if (head == null) tail = null; else head.previousLink = null;
            node.previousLink = null; node.nextLink = null;
            return node;
        }

        void remove(WorkHandle node) {
            if (node.previousLink == null) head = node.nextLink;
            else node.previousLink.nextLink = node.nextLink;
            if (node.nextLink == null) tail = node.previousLink;
            else node.nextLink.previousLink = node.previousLink;
            node.previousLink = null; node.nextLink = null;
        }

        void clear() { head = null; tail = null; }
    }

    /**
     * Intrusive doubly-linked FIFO of {@link Slot}, used for the state drain order. A slot belongs to
     * the queue exactly while {@link Slot#queued} is set, so removal needs no separate membership tag.
     */
    static final class SlotQueue {
        Slot head;
        Slot tail;

        boolean isEmpty() { return head == null; }

        void addLast(Slot node) {
            node.previousLink = tail;
            node.nextLink = null;
            if (tail == null) head = node; else tail.nextLink = node;
            tail = node;
        }

        Slot removeFirst() {
            Slot node = head;
            if (node == null) return null;
            head = node.nextLink;
            if (head == null) tail = null; else head.previousLink = null;
            node.previousLink = null; node.nextLink = null;
            return node;
        }

        void remove(Slot node) {
            if (node.previousLink == null) head = node.nextLink;
            else node.previousLink.nextLink = node.nextLink;
            if (node.nextLink == null) tail = node.previousLink;
            else node.nextLink.previousLink = node.previousLink;
            node.previousLink = null; node.nextLink = null;
        }

        void clear() { head = null; tail = null; }
    }
}
