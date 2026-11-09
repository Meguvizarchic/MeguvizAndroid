package io.github.jichuo1.meguviz.android.window;

import android.view.View;
import io.github.jichuo1.meguviz.android.runtime.core.*;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope;
import java.util.ArrayList;
import java.util.List;

/** Detach is reversible. Rebind invalidates before disposing the previous binding. */
public final class ViewScope implements AutoCloseable {
    private WindowSession session;
    private View view;
    private final OwnerDispatcher owner;
    private final LifecycleScope lifecycle;
    private final ResourceScope resources;
    private final boolean ownsResources;
    private ErrorSink errors;
    private boolean ownerStarted = true;
    private boolean locallyVisible = true;
    private final List<AutoCloseable> bindings = new ArrayList<>();
    private final List<BoundedMailbox> mailboxes = new ArrayList<>();
    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View target) {
            if (!lifecycle.admissionClosed() && parentAdmissionOpen()) {
                resources.refreshConfiguration(); lifecycle.setAttached(true); refreshOwner();
            }
        }
        @Override public void onViewDetachedFromWindow(View target) {
            if (!lifecycle.admissionClosed()) { lifecycle.setAttached(false); refreshOwner(); }
        }
    };
    ViewScope(WindowSession session, View view, String namespace) {
        this.session = session; this.view = view; owner = session.dispatcher();
        errors = session.errors(); lifecycle = new LifecycleScope(owner, errors);
        ownsResources = !session.sharesResources(view.getContext(), namespace);
        resources = ownsResources ? new ResourceScope(owner, view.getContext(), namespace, 128) : session.resources();
        lifecycle.own(new Registration(owner, this::releaseOwned));
        view.addOnAttachStateChangeListener(attachment);
        lifecycle.setAttached(view.isAttachedToWindow()); refreshOwner();
    }
    public WindowSession session() { checkOpen(); return session; }
    public OwnerDispatcher dispatcher() { return owner; }
    public LifecycleScope lifecycle() { return lifecycle; }
    public ResourceScope resources() { checkOpen(); return resources; }
    void refreshResources() { if (ownsResources && !lifecycle.admissionClosed()) resources.refreshConfiguration(); }
    public View view() { checkOpen(); return view; }
    View viewOrNull() { return view; }
    boolean parentAdmissionOpen() { return session != null && !session.lifecycle().admissionClosed(); }
    void checkOpen() {
        owner.checkOwner();
        if (lifecycle.admissionClosed() || session == null || session.lifecycle().admissionClosed())
            throw new IllegalStateException("View scope closed or closing: the view scope, or the window session it belongs to, is closed or closing, so the call is refused. Scope the view again in the live session.");
    }
    void refreshOwner() {
        if (lifecycle.admissionClosed()) return;
        lifecycle.setStarted(parentAdmissionOpen() && session.started() && ownerStarted);
        lifecycle.setVisible(parentAdmissionOpen() && session.visible() && locallyVisible);
        for (BoundedMailbox mailbox : new ArrayList<>(mailboxes)) mailbox.setPaused(!lifecycle.isActive());
    }
    public void setOwnerStarted(boolean started) { checkOpen(); ownerStarted = started; refreshOwner(); }
    public void setVisible(boolean visible) { checkOpen(); locallyVisible = visible; refreshOwner(); }
    public BindingLease binding(int resourceDependencies) {
        checkOpen();
        BindingLease lease = resourceDependencies == 0 ? lifecycle.lease(LifecycleScope.ALL)
                : lifecycle.lease(LifecycleScope.ALL, resources.state().dependencies(resourceDependencies));
        bindings.add(lease); lease.onClose(() -> bindings.remove(lease)); return lease;
    }
    /** Registration and queue cleanup belonging to the current binding must be registered here. */
    public <T extends AutoCloseable> T ownBinding(T registration) {
        checkOpen(); bindings.add(java.util.Objects.requireNonNull(registration)); return registration;
    }
    public Registration own(Registration registration) { checkOpen(); return lifecycle.own(registration); }
    public BoundedMailbox openMailbox(int stateCapacity, int commandCapacity, int quota) {
        checkOpen();
        BoundedMailbox mailbox = new BoundedMailbox(lifecycle, session.scheduler(), errors, stateCapacity, commandCapacity, quota);
        mailboxes.add(mailbox); bindings.add(mailbox);
        mailbox.onClose(() -> { mailboxes.remove(mailbox); bindings.remove(mailbox); });
        mailbox.setPaused(!lifecycle.isActive());
        return mailbox;
    }
    public int ownedBindingCount() { owner.checkOwner(); return bindings.size(); }
    public void rebind() {
        checkOpen(); lifecycle.rebind();
        List<AutoCloseable> old = new ArrayList<>(bindings); bindings.clear(); mailboxes.clear();
        try { Cleanup.closeAll(old); } catch (Throwable failure) { fail(failure); }
    }
    public LifecycleScope.CommitResult commit(BindingLease binding, Runnable callback) {
        owner.checkOwner();
        if (session == null || session.lifecycle().admissionClosed()) return LifecycleScope.CommitResult.STALE;
        return lifecycle.commit(binding.token(), callback);
    }
    public LifecycleScope.CloseRequest requestClose() { return lifecycle.requestClose(); }
    @Override public void close() { lifecycle.close(); }
    private void releaseOwned() {
        List<AutoCloseable> old = new ArrayList<>(bindings); bindings.clear(); mailboxes.clear();
        View previous = view; WindowSession parent = session; view = null; session = null;
        errors = null;
        if (previous != null) old.add(() -> previous.removeOnAttachStateChangeListener(attachment));
        if (ownsResources) old.add(resources);
        if (parent != null) parent.released(this);
        Cleanup.closeAll(old);
    }
    void fail(Throwable failure) {
        ErrorSink reporter = errors;
        try { close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        try { if (reporter != null) reporter.onError(failure); } catch (Throwable logging) { if (logging != failure) failure.addSuppressed(logging); }
        rethrowFatal(failure);
        for (Throwable suppressed : failure.getSuppressed()) rethrowFatal(suppressed);
    }
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
        if (failure instanceof LinkageError) throw (LinkageError) failure;
    }
}
