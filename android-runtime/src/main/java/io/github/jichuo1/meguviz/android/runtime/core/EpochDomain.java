package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.Objects;

/** An independent owner-thread generation domain. Stamps contain no Android objects. */
public final class EpochDomain implements AutoCloseable {
    private final OwnerDispatcher owner;
    private Object identity = new Object();
    private long epoch;
    private boolean closed;

    public EpochDomain(OwnerDispatcher owner) { this(owner, 0); }
    EpochDomain(OwnerDispatcher owner, long initialEpoch) {
        this.owner = Objects.requireNonNull(owner);
        owner.checkOwner();
        if (initialEpoch < 0) throw new IllegalArgumentException("Negative epoch: initialEpoch must be >= 0, got " + initialEpoch);
        epoch = initialEpoch;
    }

    public Stamp stamp() {
        owner.checkOwner();
        if (closed) throw new IllegalStateException("Closed domain: this EpochDomain is closed, so stamp() and invalidate() are refused (matches() just returns false). A closed domain cannot be reopened; create a new one.");
        return new Stamp(identity, epoch);
    }

    public boolean matches(Stamp stamp) {
        owner.checkOwner();
        return !closed && stamp != null && stamp.identity == identity && stamp.epoch == epoch;
    }

    public void invalidate() {
        owner.checkOwner();
        if (closed) throw new IllegalStateException("Closed domain: this EpochDomain is closed, so stamp() and invalidate() are refused (matches() just returns false). A closed domain cannot be reopened; create a new one.");
        if (epoch == Long.MAX_VALUE) { identity = new Object(); epoch = 0; }
        else epoch++;
    }

    @Override public void close() { owner.checkOwner(); closed = true; identity = new Object(); }

    public static final class Stamp {
        private final Object identity;
        private final long epoch;
        private Stamp(Object identity, long epoch) { this.identity = identity; this.epoch = epoch; }
        public long epoch() { return epoch; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Stamp)) return false;
            Stamp value = (Stamp) other;
            return identity == value.identity && epoch == value.epoch;
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(identity) + Long.hashCode(epoch); }
    }
}
