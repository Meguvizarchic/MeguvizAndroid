package io.github.jichuo1.meguviz.android.window;

import android.content.Context;
import android.view.View;
import android.view.Window;
import io.github.jichuo1.meguviz.android.runtime.core.*;
import io.github.jichuo1.meguviz.android.runtime.platform.BudgetFrameDriver;
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

/** A composition owner; opening a session never changes system bars or content views. */
public final class WindowSession implements AutoCloseable {
    public enum Permission { OBSERVE, APPLY_LOCAL, OWN_WINDOW }
    private static final WeakHashMap<Window, WeakReference<WindowSession>> WINDOW_OWNERS = new WeakHashMap<>();
    private final OwnerDispatcher owner;
    private final FrameScheduler scheduler;
    private final boolean ownsScheduler;
    private final Permission permission;
    private ErrorSink errorSink;
    private final LifecycleScope lifecycle;
    private final ResourceScope resources;
    private final List<ViewScope> views = new ArrayList<>();
    private final List<WindowFrameChannel> channels = new ArrayList<>();
    private Window window;
    private Context resourceContext;
    private View decor;
    private boolean started;
    private boolean visible = true;
    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {
            if (lifecycle.admissionClosed()) return;
            resources.refreshConfiguration(); lifecycle.setAttached(true); refreshActivity();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if (lifecycle.admissionClosed()) return;
            lifecycle.setAttached(false); refreshActivity();
        }
    };

    public static WindowSession open(Window window, Context context, String namespace, Permission permission) {
        MainThreadDispatcher owner = new MainThreadDispatcher();
        owner.checkOwner();
        ErrorSink errors = error -> android.util.Log.e("MeguvizAndroid", "Window operation failed", error);
        // Default to the deadline-aware driver: API 33+ reads the preferred frame timeline, older
        // releases fall back to the single-argument Choreographer path inside BudgetFrameDriver.
        FrameScheduler scheduler = new FrameScheduler(owner, new BudgetFrameDriver(owner, true), errors, 64);
        try { return new WindowSession(owner, scheduler, true, window, context, namespace, permission, errors); }
        catch (RuntimeException | Error failure) { scheduler.close(); throw failure; }
    }

    /** A shared scheduler remains owned by its creator when this window closes. */
    public static WindowSession open(OwnerDispatcher owner, FrameScheduler scheduler, Window window,
                                     Context context, String namespace, Permission permission, ErrorSink errors) {
        return new WindowSession(owner, scheduler, false, window, context, namespace, permission, errors);
    }

    private WindowSession(OwnerDispatcher owner, FrameScheduler scheduler, boolean ownsScheduler, Window window,
                          Context context, String namespace, Permission permission, ErrorSink errors) {
        this.owner = Objects.requireNonNull(owner); owner.checkOwner();
        this.scheduler = Objects.requireNonNull(scheduler); this.ownsScheduler = ownsScheduler;
        this.window = Objects.requireNonNull(window); this.permission = Objects.requireNonNull(permission);
        resourceContext = Objects.requireNonNull(context);
        errorSink = Objects.requireNonNull(errors);
        if (permission == Permission.OWN_WINDOW) {
            WeakReference<WindowSession> previous = WINDOW_OWNERS.get(window);
            if (previous != null && previous.get() != null) throw new IllegalStateException("Window already has an owner");
        }
        lifecycle = new LifecycleScope(owner, errors);
        resources = new ResourceScope(owner, context, namespace, 128);
        decor = window.getDecorView();
        lifecycle.own(new Registration(owner, this::releaseOwned));
        decor.addOnAttachStateChangeListener(attachment);
        lifecycle.setAttached(decor.isAttachedToWindow());
        if (permission == Permission.OWN_WINDOW) WINDOW_OWNERS.put(window, new WeakReference<>(this));
    }

    public OwnerDispatcher dispatcher() { return owner; }
    public Permission permission() { return permission; }
    public LifecycleScope lifecycle() { return lifecycle; }
    public ResourceScope resources() { checkOpen(); return resources; }
    /** Cold-path access to an existing binding's authoritative domain, including local themes. */
    public ResourceScope resourcesFor(View view) {
        checkOpen(); Objects.requireNonNull(view);
        for (ViewScope scope : views) if (scope.viewOrNull() == view) return scope.resources();
        throw new IllegalArgumentException("View is not scoped in this window session");
    }
    public FrameScheduler scheduler() { checkOpen(); return scheduler; }
    public Window window() { checkOpen(); return window; }
    ErrorSink errors() { return errorSink; }
    void checkOpen() {
        owner.checkOwner();
        if (lifecycle.admissionClosed()) throw new IllegalStateException("Window session closed or closing: the session was closed or is being closed (for example its window was destroyed), so new work is refused. Use the session of the current window.");
    }
    public void requirePermission(Permission required) {
        checkOpen();
        if (permission.ordinal() < required.ordinal()) throw new SecurityException("Requires " + required);
    }
    public ViewScope scope(View view) { return scope(view, resources.state().namespace()); }
    public ViewScope scope(View view, String resourceNamespace) {
        checkOpen();
        for (ViewScope scope : views) if (scope.viewOrNull() == view) throw new IllegalStateException("View already scoped in session");
        ViewScope result = new ViewScope(this, Objects.requireNonNull(view), Objects.requireNonNull(resourceNamespace)); views.add(result);
        return result;
    }
    boolean sharesResources(Context context, String namespace) {
        return context == resourceContext && resources.state().namespace().equals(namespace);
    }
    void released(ViewScope scope) { views.remove(scope); }
    boolean started() { return started; }
    boolean visible() { return visible; }
    public void setStarted(boolean started) {
        checkOpen(); this.started = started; lifecycle.setStarted(started); refreshActivity();
    }
    public void setVisible(boolean visible) {
        checkOpen(); this.visible = visible; lifecycle.setVisible(visible); refreshActivity();
    }
    public void refreshConfiguration() {
        checkOpen(); resources.refreshConfiguration();
        for (ViewScope scope : new ArrayList<>(views)) scope.refreshResources();
    }
    private void refreshActivity() {
        for (ViewScope scope : new ArrayList<>(views)) scope.refreshOwner();
        for (WindowFrameChannel channel : new ArrayList<>(channels)) channel.refresh();
    }
    public Registration own(Registration registration) { return lifecycle.own(registration); }
    public WindowFrameChannel openFrameChannel(Object identity, FrameScheduler.FrameWork callback, int quota) {
        checkOpen();
        WindowFrameChannel result = new WindowFrameChannel(identity, callback, quota); channels.add(result);
        return result;
    }
    public LifecycleScope.CloseRequest requestClose() { return lifecycle.requestClose(); }
    @Override public void close() { lifecycle.close(); }
    private void releaseOwned() {
        List<AutoCloseable> releasing = new ArrayList<>();
        releasing.addAll(views); views.clear(); releasing.addAll(channels); channels.clear();
        View oldDecor = decor; Window oldWindow = window; decor = null; window = null; resourceContext = null;
        errorSink = null;
        if (oldDecor != null) releasing.add(() -> oldDecor.removeOnAttachStateChangeListener(attachment));
        if (oldWindow != null && permission == Permission.OWN_WINDOW) {
            WeakReference<WindowSession> current = WINDOW_OWNERS.get(oldWindow);
            if (current != null && current.get() == this) WINDOW_OWNERS.remove(oldWindow);
        }
        releasing.add(resources);
        if (ownsScheduler) releasing.add(scheduler);
        Cleanup.closeAll(releasing);
    }

    public final class WindowFrameChannel implements AutoCloseable {
        private final FrameScheduler.FrameChannel channel;
        private FrameScheduler.FrameWork work;
        private boolean requested;
        private WindowFrameChannel(Object identity, FrameScheduler.FrameWork work, int quota) {
            this.work = Objects.requireNonNull(work);
            channel = scheduler.openChannel(identity, (tick, allowance) -> {
                if (!lifecycle.isActive() || this.work == null) return 0;
                requested = false; return this.work.drain(tick, allowance);
            }, quota);
            channel.onClose(() -> { this.work = null; requested = false; channels.remove(this); });
        }
        public void requestFrame() {
            checkOpen(); if (work == null) throw new IllegalStateException("Channel closed: this window frame channel was closed, so requestFrame() is refused. Open a new channel from the live session.");
            requested = true; refresh();
        }
        public void cancelPending() { owner.checkOwner(); requested = false; channel.cancelPending(); }
        private void refresh() {
            if (work == null) return;
            if (requested && lifecycle.isActive()) channel.requestFrame(); else channel.cancelPending();
        }
        @Override public void close() {
            owner.checkOwner(); if (work == null) return;
            work = null; requested = false; channels.remove(this); channel.close();
        }
    }
}
