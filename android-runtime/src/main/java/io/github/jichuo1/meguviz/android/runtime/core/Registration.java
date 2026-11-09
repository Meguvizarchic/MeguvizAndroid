package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.Objects;

/** An owner-thread registration. The release closure is cleared before it is invoked. */
public final class Registration implements AutoCloseable {
    private final OwnerDispatcher owner;
    private Runnable release;
    private Runnable ownerRelease;

    public Registration(OwnerDispatcher owner, Runnable release) {
        this.owner = Objects.requireNonNull(owner);
        this.release = Objects.requireNonNull(release);
        owner.checkOwner();
    }

    public boolean isClosed() { owner.checkOwner(); return release == null; }

    void ownedBy(Runnable onClose) {
        owner.checkOwner();
        if (release == null) throw new IllegalStateException("Registration closed: it was already released, so it cannot be handed to an owner. Register again to get a new one.");
        if (ownerRelease != null) throw new IllegalStateException("Registration already has an owner");
        ownerRelease = Objects.requireNonNull(onClose);
    }

    @Override public void close() {
        owner.checkOwner();
        Runnable callback = release;
        if (callback == null) return;
        release = null;
        Runnable detach = ownerRelease; ownerRelease = null;
        Throwable first = null;
        try { if (detach != null) detach.run(); } catch (Throwable failure) { first = failure; }
        try { callback.run(); } catch (Throwable failure) { first = Failures.combine(first, failure); }
        if (first != null) {
            Failures.rethrowFatal(first);
            if (first instanceof RuntimeException) throw (RuntimeException) first;
            throw new IllegalStateException("Registration cleanup failed", first);
        }
    }
}
