package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Bounded, multi-producer latest-state and FIFO-command mailbox. */
public final class BoundedMailbox implements AutoCloseable {
    public enum Admission { ACCEPTED, REPLACED, REJECTED_CAPACITY, REJECTED_CLOSED, UNSUPPORTED }
    public enum Outcome { PENDING, RUNNING, COMPLETED, CANCELLED, SUPERSEDED, STALE, INACTIVE, FAILED, CLOSED }
    public enum Cancellation { CANCELLED, ALREADY_RUNNING, ALREADY_TERMINAL }

    private final Object lock = new Object();
    private final Object identity = new Object();
    private final LifecycleScope scope;
    private final OwnerDispatcher owner;
    private final Failures failures;
    private final int stateCapacity;
    private final int commandCapacity;
    private final LinkedHashMap<Slot, Item> states = new LinkedHashMap<>();
    private final LinkedHashMap<Slot, Item> latest = new LinkedHashMap<>();
    private final ArrayDeque<Item> commands = new ArrayDeque<>();
    private final LinkedHashSet<Item> extracted = new LinkedHashSet<>();
    private final FrameScheduler.FrameChannel channel;
    private final Registration registration;
    private Signal signal;
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
    final ArrayList<Item> drainBatch = new ArrayList<>();

    public BoundedMailbox(LifecycleScope scope, FrameScheduler scheduler, ErrorSink sink,
                          int stateCapacity, int commandCapacity, int perFrameQuota) {
        this.scope = Objects.requireNonNull(scope);
        owner = scope.dispatcher(); owner.checkOwner();
        failures = new Failures(sink);
        if (stateCapacity <= 0 || commandCapacity <= 0) throw new IllegalArgumentException("Positive capacities required");
        this.stateCapacity = stateCapacity; this.commandCapacity = commandCapacity;
        channel = scheduler.openChannel(identity, this::drain, perFrameQuota);
        channel.onClose(() -> { if (!isClosed()) failClosed(new IllegalStateException("Frame channel closed")); });
        registration = scope.own(new Registration(owner, this::close));
    }

    public Slot newSlot() { owner.checkOwner(); synchronized (lock) { if (closed) throw new IllegalStateException("Mailbox closed"); } return new Slot(identity); }
    public Submission offerState(Slot slot, WorkToken token, Runnable callback, Runnable release) {
        if (slot == null || slot.mailboxIdentity != identity) return new Submission(Admission.UNSUPPORTED, null);
        return offer(slot, token, callback, release);
    }
    public Submission offerCommand(WorkToken token, Runnable callback, Runnable release) {
        return offer(null, token, callback, release);
    }
    public Submission offerBatch(WorkToken token, List<Runnable> callbacks, Runnable release) {
        final List<Runnable> copy = new ArrayList<>(callbacks);
        for (Runnable callback : copy) Objects.requireNonNull(callback);
        if (copy.isEmpty()) throw new IllegalArgumentException("Empty batch");
        return offerCommand(token, () -> { for (Runnable callback : copy) callback.run(); }, release);
    }

    private Submission offer(Slot slot, WorkToken token, Runnable callback, Runnable release) {
        Objects.requireNonNull(token); Objects.requireNonNull(callback); Objects.requireNonNull(release);
        if (token.scope != scope) return new Submission(Admission.UNSUPPORTED, null);
        Terminal replaced = null;
        Signal dispatch;
        Submission result;
        synchronized (lock) {
            if (closed || scope.admissionClosed()) return new Submission(Admission.REJECTED_CLOSED, null);
            Item previous = slot == null ? null : latest.get(slot);
            if (slot == null ? pendingCommands >= commandCapacity : previous == null && latest.size() >= stateCapacity)
                return new Submission(Admission.REJECTED_CAPACITY, null);
            if (previous != null) replaced = terminateLocked(previous, Outcome.SUPERSEDED, null, false);
            Item item = new Item(slot, token, callback, release, ++sequence);
            if (slot == null) { commands.addLast(item); pendingCommands++; }
            else { states.put(slot, item); latest.put(slot, item); }
            if (paused && slot == null) replaced = terminateLocked(item, Outcome.INACTIVE, null, true);
            dispatch = requestSignalLocked();
            result = new Submission(previous == null ? Admission.ACCEPTED : Admission.REPLACED, item.handle);
        }
        Throwable failure = release(replaced);
        if (failure != null) failClosed(failure);
        post(dispatch);
        return result;
    }

    private boolean hasWorkLocked() { return !states.isEmpty() || !commands.isEmpty(); }
    private Signal requestSignalLocked() {
        if (signal != null || paused || closed || !hasWorkLocked()) return null;
        signal = new Signal(); return signal;
    }
    private void post(Signal pending) {
        if (pending == null) return;
        if (owner.isOwnerThread()) { pending.run(); return; }
        boolean accepted;
        try { accepted = owner.post(pending); }
        catch (Throwable failure) { failClosed(failure); return; }
        if (!accepted) { failClosed(new IllegalStateException("Owner dispatcher rejected mailbox wakeup")); return; }
        boolean obsolete;
        synchronized (lock) { obsolete = signal != pending || paused || closed; }
        if (obsolete) owner.remove(pending);
    }

    private final class Signal implements Runnable {
        @Override public void run() {
            owner.checkOwner();
            synchronized (lock) { if (closed || paused || signal != this) return; }
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
        List<Item> batch = reusing ? drainBatch : new ArrayList<>(quota);
        if (reusing) batch.clear();
        draining = true;
        int drained;
        try {
            synchronized (lock) {
                if (closed || paused) return 0;
                while (batch.size() < quota && hasWorkLocked()) {
                    Item item;
                    if (!commands.isEmpty() && (commandTurn || states.isEmpty())) item = commands.removeFirst();
                    else {
                        Iterator<Item> iterator = states.values().iterator();
                        item = iterator.next(); iterator.remove();
                    }
                    commandTurn = !commandTurn;
                    extracted.add(item); batch.add(item);
                }
            }
            try {
                if (afterExtract != null) afterExtract.run();
                for (Item item : batch) execute(item);
            } finally {
                if (beforeFinish != null) beforeFinish.run();
                boolean again;
                synchronized (lock) {
                    again = !closed && !paused && hasWorkLocked();
                    if (!again) signal = null;
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

    private void execute(Item item) {
        Runnable action;
        Terminal terminal = null;
        synchronized (lock) {
            if (item.handle.outcome != Outcome.PENDING) return;
            if (closed) terminal = terminateLocked(item, Outcome.CLOSED, null, true);
            else if (!item.token.isCurrent()) terminal = terminateLocked(item, Outcome.STALE, null, true);
            else if (paused && item.slot != null) {
                extracted.remove(item);
                if (latest.get(item.slot) == item) states.put(item.slot, item);
                return;
            }
            else if (paused || !scope.isActive()) terminal = terminateLocked(item, Outcome.INACTIVE, null, true);
            else {
                extracted.remove(item);
                if (item.slot == null) pendingCommands--;
                else if (latest.get(item.slot) == item) latest.remove(item.slot);
                item.handle.outcome = Outcome.RUNNING;
            }
            action = item.action;
        }
        if (terminal != null) {
            Throwable failure = release(terminal);
            if (failure != null) failClosed(failure);
            return;
        }
        Throwable failure = null;
        try { action.run(); } catch (Throwable error) { failure = error; }
        synchronized (lock) { terminal = terminateLocked(item, failure == null ? Outcome.COMPLETED : Outcome.FAILED, failure, true); }
        Throwable releaseFailure = release(terminal);
        failure = Failures.combine(failure, releaseFailure);
        if (failure != null) failClosed(failure);
    }

    private Terminal terminateLocked(Item item, Outcome result, Throwable failure, boolean removeStatePosition) {
        Outcome previous = item.handle.outcome;
        if (previous != Outcome.PENDING && previous != Outcome.RUNNING) return null;
        if (previous == Outcome.PENDING) {
            extracted.remove(item);
            if (item.slot == null) { commands.remove(item); pendingCommands--; }
            else {
                if (removeStatePosition && states.get(item.slot) == item) states.remove(item.slot);
                if (latest.get(item.slot) == item) latest.remove(item.slot);
            }
        }
        item.handle.outcome = result; item.handle.failure = failure;
        item.handle.mailbox = null; item.handle.item = null;
        Terminal terminal = new Terminal(item.release, item.handle);
        item.action = null; item.release = null; item.token = null;
        return terminal;
    }

    private Throwable release(Terminal terminal) {
        if (terminal == null) return null;
        try { terminal.callback.run(); return null; }
        catch (Throwable failure) {
            terminal.handle.failure = Failures.combine(terminal.handle.failure, failure); terminal.handle.outcome = Outcome.FAILED;
            return failure;
        }
    }

    private Cancellation cancel(Item item) {
        Terminal terminal;
        synchronized (lock) {
            if (item.handle.outcome == Outcome.RUNNING) return Cancellation.ALREADY_RUNNING;
            if (item.handle.outcome != Outcome.PENDING) return Cancellation.ALREADY_TERMINAL;
            terminal = terminateLocked(item, Outcome.CANCELLED, null, true);
        }
        Throwable failure = release(terminal);
        if (failure != null) failClosed(failure);
        cancelIdleWake();
        return Cancellation.CANCELLED;
    }

    private void cancelIdleWake() {
        Signal old;
        synchronized (lock) {
            if (hasWorkLocked() || !extracted.isEmpty()) return;
            old = signal; signal = null;
        }
        if (old != null) owner.remove(old);
        if (owner.isOwnerThread()) channel.cancelPending();
    }

    public void setPaused(boolean value) {
        owner.checkOwner();
        Signal old;
        Signal dispatch = null;
        List<Terminal> cancelled = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            paused = value; old = signal;
            if (paused) {
                signal = null;
                for (Item item : new ArrayList<>(commands)) cancelled.add(terminateLocked(item, Outcome.INACTIVE, null, true));
                for (Item item : new ArrayList<>(extracted)) {
                    if (item.slot == null) cancelled.add(terminateLocked(item, Outcome.INACTIVE, null, true));
                }
            } else dispatch = requestSignalLocked();
        }
        if (paused) { if (old != null) owner.remove(old); channel.cancelPending(); }
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

    private Throwable releaseAll(List<Terminal> terminals) {
        Throwable first = null;
        for (Terminal terminal : terminals) {
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
        Signal old;
        List<Terminal> terminals = new ArrayList<>();
        synchronized (lock) {
            old = signal; signal = null;
            closed = true;
            LinkedHashSet<Item> all = new LinkedHashSet<>(states.values());
            all.addAll(commands); all.addAll(extracted);
            for (Item item : all) terminals.add(terminateLocked(item, outcome, reason, true));
            states.clear(); latest.clear(); commands.clear(); extracted.clear(); pendingCommands = 0;
        }
        Throwable cleanup = null;
        try { if (old != null) owner.remove(old); } catch (Throwable failure) { cleanup = failure; }
        Throwable payload = releaseAll(terminals);
        cleanup = Failures.combine(cleanup, payload);
        if (owner.isOwnerThread()) {
            boolean clean;
            Runnable listener;
            synchronized (lock) {
                clean = !ownerCleanupDone; ownerCleanupDone = true;
                listener = closeListener; closeListener = null;
            }
            if (clean) {
                try { channel.close(); } catch (Throwable failure) { cleanup = Failures.combine(cleanup, failure); }
                if (registration != null) {
                    try { scope.release(registration); } catch (Throwable failure) { cleanup = Failures.combine(cleanup, failure); }
                }
                try { if (listener != null) listener.run(); }
                catch (Throwable failure) { cleanup = Failures.combine(cleanup, failure); }
            }
        }
        failures.clearSink();
        if (cleanup != null) failures.reportTo(sink, cleanup);
    }

    @Override public void close() { owner.checkOwner(); terminateAll(Outcome.CLOSED, null); }

    public static final class Slot {
        private final Object mailboxIdentity;
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
    public static final class WorkHandle {
        private final long sequence;
        private volatile Outcome outcome = Outcome.PENDING;
        private volatile Throwable failure;
        private volatile BoundedMailbox mailbox;
        private volatile Item item;
        private WorkHandle(long sequence, BoundedMailbox mailbox, Item item) {
            this.sequence = sequence; this.mailbox = mailbox; this.item = item;
        }
        public long sequence() { return sequence; }
        public Outcome outcome() { return outcome; }
        public Throwable failure() { return failure; }
        public Cancellation cancel() {
            BoundedMailbox current = mailbox; Item pending = item;
            if (current == null || pending == null) return Cancellation.ALREADY_TERMINAL;
            return current.cancel(pending);
        }
    }
    private final class Item {
        final Slot slot;
        final WorkHandle handle;
        WorkToken token;
        Runnable action;
        Runnable release;
        Item(Slot slot, WorkToken token, Runnable action, Runnable release, long sequence) {
            this.slot = slot; this.token = token; this.action = action; this.release = release;
            handle = new WorkHandle(sequence, BoundedMailbox.this, this);
        }
    }
    private static final class Terminal {
        final Runnable callback;
        final WorkHandle handle;
        Terminal(Runnable callback, WorkHandle handle) { this.callback = callback; this.handle = handle; }
    }
}
