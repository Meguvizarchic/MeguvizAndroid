package io.github.jichuo1.meguviz.android.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-thread state with a thread-safe admission barrier for close requests. */
public final class LifecycleScope implements AutoCloseable {
    public static final int ATTACH = 1;
    public static final int BINDING = 2;
    public static final int ALL = ATTACH | BINDING;
    public enum State { CREATED, SUSPENDED, ACTIVE, CLOSED }
    public enum CommitResult { COMPLETED, STALE, INACTIVE, FAILED }

    private final OwnerDispatcher owner;
    private final Failures failures;
    private final EpochDomain attach;
    private final EpochDomain binding;
    private final AtomicBoolean admissionClosed = new AtomicBoolean();
    private final List<Registration> registrations = new ArrayList<>();
    private final CloseRequest closeRequest = new CloseRequest();
    private final Runnable closeCallback = this::close;
    private boolean attached;
    private boolean started;
    private boolean visible = true;
    private boolean touched;
    private boolean closed;

    public LifecycleScope(OwnerDispatcher owner, ErrorSink sink) {
        this.owner = Objects.requireNonNull(owner);
        failures = new Failures(sink);
        attach = new EpochDomain(owner);
        binding = new EpochDomain(owner);
    }

    public OwnerDispatcher dispatcher() { return owner; }
    public boolean admissionClosed() { return admissionClosed.get(); }
    public boolean isClosed() { owner.checkOwner(); return closed; }
    public boolean isActive() {
        owner.checkOwner();
        return !admissionClosed.get() && !closed && attached && started && visible;
    }
    public State state() {
        owner.checkOwner();
        if (closed) return State.CLOSED;
        return isActive() ? State.ACTIVE : touched ? State.SUSPENDED : State.CREATED;
    }
    private void requireOpen() {
        owner.checkOwner();
        if (admissionClosed.get() || closed) throw new IllegalStateException("Scope is closing or closed: no new leases or registrations are admitted once close has been requested. Create a new LifecycleScope for the next owner instance.");
    }
    public void setAttached(boolean value) {
        requireOpen(); touched = true;
        if (attached != value) { attached = value; attach.invalidate(); }
    }
    public void setStarted(boolean value) { requireOpen(); touched = true; started = value; }
    public void setVisible(boolean value) { requireOpen(); touched = true; visible = value; }
    public void rebind() { requireOpen(); binding.invalidate(); }
    public BindingLease lease(int dependencies, EpochDomain... extraDomains) {
        requireOpen();
        if ((dependencies & ~ALL) != 0) throw new IllegalArgumentException("Unknown dependencies");
        return new BindingLease(this, dependencies, extraDomains);
    }
    EpochDomain.Stamp attachStamp() { return attach.stamp(); }
    EpochDomain.Stamp bindingStamp() { return binding.stamp(); }

    public boolean isCurrent(WorkToken token) {
        owner.checkOwner();
        if (token == null || token.scope != this || admissionClosed.get() || closed || token.lease.isClosed()) return false;
        if ((token.dependencies & ATTACH) != 0 && !attach.matches(token.attach)) return false;
        if ((token.dependencies & BINDING) != 0 && !binding.matches(token.binding)) return false;
        for (int i = 0; i < token.extraDomains.length; i++) {
            if (!token.extraDomains[i].matches(token.extraStamps[i])) return false;
        }
        return true;
    }

    public CommitResult commit(WorkToken token, Runnable callback) {
        owner.checkOwner(); Objects.requireNonNull(callback);
        if (!isCurrent(token)) return CommitResult.STALE;
        if (!isActive()) return CommitResult.INACTIVE;
        try { callback.run(); return CommitResult.COMPLETED; }
        catch (Throwable failure) {
            ErrorSink sink = failures.sink();
            try { close(); } finally { failures.reportTo(sink, failure); }
            return CommitResult.FAILED;
        }
    }

    public Registration own(Registration registration) {
        requireOpen(); Objects.requireNonNull(registration);
        registration.ownedBy(() -> registrations.remove(registration));
        registrations.add(registration); return registration;
    }
    public void release(Registration registration) {
        owner.checkOwner(); registrations.remove(registration); registration.close();
    }
    public int registrationCount() { owner.checkOwner(); return registrations.size(); }
    public Throwable lastFailure() { return failures.last(); }

    public CloseRequest requestClose() {
        if (admissionClosed.compareAndSet(false, true)) {
            closeRequest.accepted = true;
            if (owner.isOwnerThread()) close();
            else {
                boolean posted;
                try { posted = owner.post(closeCallback); }
                catch (Throwable failure) {
                    closeRequest.failure = failure;
                    Failures.rethrowFatal(failure);
                    return closeRequest;
                }
                if (!posted) closeRequest.failure = new IllegalStateException("Owner dispatcher rejected close; owner must close explicitly");
            }
        }
        return closeRequest;
    }

    @Override public void close() {
        owner.checkOwner();
        admissionClosed.set(true);
        closeRequest.accepted = true;
        if (closed) return;
        closed = true;
        attach.close(); binding.close();
        List<Registration> releasing = new ArrayList<>(registrations);
        registrations.clear();
        Throwable first = null;
        ErrorSink sink = failures.sink();
        try {
            try { owner.remove(closeCallback); } catch (Throwable failure) { first = failure; }
            for (Registration registration : releasing) {
                try { registration.close(); }
                catch (Throwable failure) {
                    first = Failures.combine(first, failure);
                }
            }
        } finally {
            closeRequest.failure = first;
            closeRequest.completed = true;
            failures.clearSink();
        }
        if (first != null) failures.reportTo(sink, first);
    }

    public static final class CloseRequest {
        private volatile boolean accepted;
        private volatile boolean completed;
        private volatile Throwable failure;
        public boolean accepted() { return accepted; }
        public boolean completed() { return completed; }
        public Throwable failure() { return failure; }
    }
}
