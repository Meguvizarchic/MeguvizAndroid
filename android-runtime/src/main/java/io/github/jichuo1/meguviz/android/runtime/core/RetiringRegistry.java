package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Optional bounded publication and cooperative retirement for registry snapshots. */
public final class RetiringRegistry<T> {
    private final State<T> state;

    public RetiringRegistry(AdapterRegistry<T> initial, int maxLeases, int maxSnapshots, Runnable releaseInitial) {
        if (maxLeases <= 0) throw new IllegalArgumentException("maxLeases must be positive");
        if (maxSnapshots < 2) throw new IllegalArgumentException("maxSnapshots must be at least two");
        state = new State<>(maxLeases, maxSnapshots, new Slot<>(initial, releaseInitial));
    }

    /** Returns null when closed or when the shared lease bound is exhausted. */
    public Lease<T> tryAcquire() {
        synchronized (state) {
            if (state.current == null || state.activeLeases == state.maxLeases) return null;
            state.activeLeases++;
            state.current.leases++;
            return new Lease<>(state, state.current);
        }
    }

    public boolean isClosed() {
        synchronized (state) { return state.current == null; }
    }

    /** Publishes a validated replacement and returns the retired snapshot's release ticket. */
    public Retirement<T> replace(AdapterRegistry<T> replacement, Runnable releaseReplacement) {
        Slot<T> next = new Slot<>(replacement, releaseReplacement);
        synchronized (state) {
            if (state.current == null) throw new IllegalStateException("Registry is closed");
            if (state.snapshots.containsKey(replacement)) throw new IllegalArgumentException("Snapshot is current or retiring");
            if (state.snapshots.size() == state.maxSnapshots) throw new IllegalStateException("Snapshot capacity exhausted");
            Slot<T> previous = state.current;
            state.current = next;
            state.snapshots.put(replacement, next);
            return new Retirement<>(state, previous);
        }
    }

    /** Stops new leases; a repeated close returns null. This method does not wait for leases. */
    public Retirement<T> close() {
        synchronized (state) {
            if (state.current == null) return null;
            Slot<T> previous = state.current;
            state.current = null;
            return new Retirement<>(state, previous);
        }
    }

    public static final class Lease<T> implements AutoCloseable {
        /**
         * Immutable snapshot and open/closed sentinel in one volatile reference. Close clears it under the
         * state monitor, so a retained closed lease pins neither the snapshot nor its providers.
         */
        private volatile AdapterRegistry<T> snapshot;
        private volatile State<T> state;
        private Slot<T> slot;

        private Lease(State<T> state, Slot<T> slot) {
            this.snapshot = slot.snapshot;
            this.slot = slot;
            this.state = state;
        }

        public AdapterRegistry<T> snapshot() {
            AdapterRegistry<T> current = snapshot;
            if (current == null) throw new IllegalStateException("Lease is closed");
            return current;
        }

        @Override public void close() {
            State<T> owner = state;
            if (owner == null) return;
            synchronized (owner) {
                if (slot == null) return;
                slot.leases--;
                owner.activeLeases--;
                slot = null;
                snapshot = null;
                state = null;
            }
        }
    }

    /** A caller-owned ticket: polling never waits and the release callback runs outside the lock. */
    public static final class Retirement<T> {
        private volatile State<T> state;
        private Slot<T> slot;
        private boolean releasing;
        private boolean releaseFailed;

        private Retirement(State<T> state, Slot<T> slot) { this.state = state; this.slot = slot; }

        public boolean isDrained() {
            State<T> owner = state;
            if (owner == null) return true;
            synchronized (owner) { return slot == null || slot.leases == 0; }
        }

        /** Claims release once after drain. The caller chooses a suitable thread for its callback. */
        public boolean tryRelease() {
            State<T> owner = state;
            if (owner == null) return false;
            Runnable action;
            synchronized (owner) {
                if (slot == null || slot.leases != 0 || releasing || releaseFailed) return false;
                action = slot.release;
                releasing = true;
            }
            boolean succeeded = false;
            try { action.run(); succeeded = true; }
            finally {
                synchronized (owner) {
                    releasing = false;
                    if (succeeded) forget(owner);
                    else { releaseFailed = true; slot.release = null; }
                }
            }
            return true;
        }

        /** Call only after independently completing or taking ownership of a failed cleanup. */
        public boolean completeAfterFailure() {
            State<T> owner = state;
            if (owner == null) return false;
            synchronized (owner) {
                if (!releaseFailed || releasing) return false;
                forget(owner);
                return true;
            }
        }

        private void forget(State<T> owner) {
            owner.snapshots.remove(slot.snapshot);
            slot.snapshot = null;
            slot.release = null;
            slot = null;
            state = null;
        }
    }

    private static final class State<T> {
        final int maxLeases;
        final int maxSnapshots;
        final Map<AdapterRegistry<T>, Slot<T>> snapshots = new IdentityHashMap<>();
        int activeLeases;
        Slot<T> current;
        State(int maxLeases, int maxSnapshots, Slot<T> current) {
            this.maxLeases = maxLeases;
            this.maxSnapshots = maxSnapshots;
            this.current = current;
            snapshots.put(current.snapshot, current);
        }
    }

    private static final class Slot<T> {
        AdapterRegistry<T> snapshot;
        Runnable release;
        int leases;
        Slot(AdapterRegistry<T> snapshot, Runnable release) {
            this.snapshot = Objects.requireNonNull(snapshot);
            this.release = Objects.requireNonNull(release);
        }
    }
}
