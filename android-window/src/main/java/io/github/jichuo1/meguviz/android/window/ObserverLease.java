package io.github.jichuo1.meguviz.android.window;

import java.util.Objects;

/** Tracks the actual tree carrying one listener, including attach-time observer merging. */
final class ObserverLease<T> implements AutoCloseable {
    interface Access<T> { boolean alive(T tree); void add(T tree); void remove(T tree); }
    private Access<T> access;
    private T installed;
    ObserverLease(Access<T> access) { this.access = Objects.requireNonNull(access); }
    void attach(T current) {
        if (access == null) throw new IllegalStateException("Observer lease closed: attach() was refused because the lease was already released. Take a new lease.");
        if (current == installed) return;
        T previous = installed; installed = null;
        if (previous != null) {
            if (access.alive(previous)) access.remove(previous);
            else if (access.alive(current)) access.remove(current); // Old floating observer merged into this tree.
        }
        if (access.alive(current)) { access.add(current); installed = current; }
    }
    void detach() {
        T previous = installed; installed = null;
        if (previous != null && access.alive(previous)) access.remove(previous);
    }
    @Override public void close() {
        if (access == null) return;
        try { detach(); } finally { access = null; }
    }
}
