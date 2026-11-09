package io.github.jichuo1.meguviz.android.runtime.core;

/** A short lived token owner. Closing it invalidates every token issued by it. */
public final class BindingLease implements AutoCloseable {
    private final LifecycleScope scope;
    private final WorkToken token;
    private boolean closed;
    private Runnable closeListener;

    BindingLease(LifecycleScope scope, int dependencies, EpochDomain... extra) {
        this.scope = scope;
        EpochDomain[] domains = extra.clone();
        EpochDomain.Stamp[] stamps = new EpochDomain.Stamp[domains.length];
        for (int i = 0; i < domains.length; i++) stamps[i] = domains[i].stamp();
        token = new WorkToken(scope, this, dependencies, scope.attachStamp(), scope.bindingStamp(), domains, stamps);
    }

    public WorkToken token() { return token; }
    public boolean isClosed() { scope.dispatcher().checkOwner(); return closed; }
    public void onClose(Runnable listener) {
        scope.dispatcher().checkOwner();
        if (closed) throw new IllegalStateException("Binding lease closed: onClose() was refused because the lease is already closed. Check isClosed() first, or register the listener before the lease can close.");
        if (closeListener != null) throw new IllegalStateException("Binding lease already has a close owner");
        closeListener = java.util.Objects.requireNonNull(listener);
    }
    @Override public void close() {
        scope.dispatcher().checkOwner();
        if (closed) return;
        closed = true;
        Runnable listener = closeListener; closeListener = null;
        if (listener != null) listener.run();
    }
}
