package io.github.jichuo1.meguviz.android.window;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import io.github.jichuo1.meguviz.android.runtime.core.Registration;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Explicit ownership of a setter-only listener slot; direct writes while leased violate the contract. */
public final class InsetsSlot implements AutoCloseable {
    private static final WeakHashMap<View, WeakReference<InsetsSlot>> OWNERS = new WeakHashMap<>();
    private final ViewScope scope;
    private final Registration registration;
    private final List<Observer> observers = new ArrayList<>();
    /** Immutable dispatch snapshot; replaced wholesale whenever observers changes. */
    private volatile List<Observer> observerSnapshot = List.of();
    private View view;
    private boolean paddingOwned, marginsOwned;
    private Registration animation;

    /** Caller guarantees this listener slot is unused or has been handed over by its previous owner. */
    public static InsetsSlot claimExclusive(ViewScope scope) {
        scope.checkOpen(); View view = scope.view();
        WeakReference<InsetsSlot> current = OWNERS.get(view);
        if (current != null && current.get() != null) throw new IllegalStateException("Insets listener slot already owned");
        return new InsetsSlot(scope, view);
    }
    private InsetsSlot(ViewScope scope, View view) {
        this.scope = scope; this.view = view;
        registration = new Registration(scope.dispatcher(), this::releaseOwned);
        scope.own(registration);
        try {
            ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
                dispatchSnapshot(target, insets);
                return insets; // Observe and local application never consume siblings' input.
            });
            OWNERS.put(view, new WeakReference<>(this));
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable failure) {
            try { registration.close(); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void dispatchSnapshot(View target, WindowInsetsCompat insets) {
        if (view == null || scope.lifecycle().admissionClosed() || !scope.parentAdmissionOpen()) return;
        if (observerSnapshot.isEmpty()) return;
        InsetsSnapshot snapshot = InsetsSnapshot.copy(insets, Build.VERSION.SDK_INT,
                target.getContext().getApplicationInfo().targetSdkVersion);
        for (Observer observer : observerSnapshot) {
            if (view == null || scope.lifecycle().admissionClosed()) break;
            if (observer.callback != null) {
                try { observer.callback.accept(snapshot); }
                catch (Throwable failure) { scope.fail(failure); break; }
            }
        }
    }
    /** Caller also transfers ownership of the animation callback's setter-only slot. */
    public Registration enableAnimationUpdatesInOwnedSlot() {
        requireOpen(); if (animation != null) throw new IllegalStateException("Animation callback already owned");
        View target = view;
        ViewCompat.setWindowInsetsAnimationCallback(target,
                new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,
                            List<WindowInsetsAnimationCompat> runningAnimations) {
                        dispatchSnapshot(target, insets); return insets;
                    }
                });
        animation = new Registration(scope.dispatcher(), () -> {
            ViewCompat.setWindowInsetsAnimationCallback(target, null); animation = null;
        });
        return animation;
    }
    public Registration observe(Consumer<InsetsSnapshot> callback) {
        requireOpen(); Observer entry = new Observer(Objects.requireNonNull(callback)); observers.add(entry);
        observerSnapshot = List.copyOf(observers);
        return new Registration(scope.dispatcher(), () -> { entry.callback = null; observers.remove(entry); observerSnapshot = List.copyOf(observers); });
    }
    public LocalLayout applyPaddingToOwnedContainer(int typeMask) { return applyLayout(typeMask, false); }
    public LocalLayout applyMarginsToOwnedContainer(int typeMask) { return applyLayout(typeMask, true); }
    private LocalLayout applyLayout(int typeMask, boolean margins) {
        requireOpen(); scope.session().requirePermission(WindowSession.Permission.APPLY_LOCAL);
        if (typeMask == 0 || (typeMask & ~InsetsSnapshot.SUPPORTED_TYPES) != 0)
            throw new IllegalArgumentException("Unsupported inset types");
        if (margins ? marginsOwned : paddingOwned) throw new IllegalStateException("Layout property already owned");
        LocalLayout result = new LocalLayout(typeMask, margins);
        if (margins) marginsOwned = true; else paddingOwned = true;
        return result;
    }
    private void requireOpen() { scope.checkOpen(); if (view == null) throw new IllegalStateException("Insets slot closed: its view was released (the slot, its view scope or its window session is closed), so the call is refused. Open a new slot from a live scope."); }
    /** Close before the next owner calls setOnApplyWindowInsetsListener; no hidden getter is used. */
    public void handOffToExternalOwner() { close(); }
    @Override public void close() { registration.close(); }
    private void releaseOwned() {
        View target = view;
        if (target == null) return;
        List<AutoCloseable> releasing = new ArrayList<>();
        if (animation != null) releasing.add(animation);
        for (Observer observer : new ArrayList<>(observers)) {
            if (observer.layout != null) releasing.add(observer.layout);
        }
        // LocalLayout needs its view until it has conditionally restored its writes.
        try { Cleanup.closeAll(releasing); }
        finally {
            view = null;
            for (Observer observer : observers) observer.callback = null;
            observers.clear();
            observerSnapshot = List.copyOf(observers);
            WeakReference<InsetsSlot> current = OWNERS.get(target);
            if (current != null && current.get() == this) {
                OWNERS.remove(target); ViewCompat.setOnApplyWindowInsetsListener(target, null);
            }
        }
    }
    private static final class Observer {
        Consumer<InsetsSnapshot> callback;
        AutoCloseable layout;
        Observer(Consumer<InsetsSnapshot> callback) { this.callback = callback; }
    }
    public final class LocalLayout implements AutoCloseable {
        private final boolean margins;
        private final InsetsBaseline baseline;
        private final Observer observer;
        private boolean closed;
        private LocalLayout(int mask, boolean margins) {
            this.margins = margins; baseline = new InsetsBaseline(read());
            observer = new Observer(snapshot -> write(baseline.apply(read(), toRelative(snapshot.combined(mask)))));
            observer.layout = this; observers.add(observer);
            observerSnapshot = List.copyOf(observers);
        }
        /** Current padding/margin is the new baseline, not a delta from the last insets. */
        public void rebaseBaseline() { requireOpen(); if (closed) throw new IllegalStateException("Layout lease closed"); baseline.rebase(read()); }
        private InsetsValue toRelative(InsetsValue physical) {
            return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? new InsetsValue(physical.right, physical.top, physical.left, physical.bottom) : physical;
        }
        private InsetsValue read() {
            if (margins) {
                if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))
                    throw new IllegalStateException("Owned container needs MarginLayoutParams");
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                return new InsetsValue(lp.getMarginStart(), lp.topMargin, lp.getMarginEnd(), lp.bottomMargin);
            }
            return new InsetsValue(view.getPaddingStart(), view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
        }
        private void write(InsetsValue edges) {
            if (margins) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                lp.setMarginStart(edges.left); lp.topMargin = edges.top; lp.setMarginEnd(edges.right); lp.bottomMargin = edges.bottom;
                view.setLayoutParams(lp);
            } else view.setPaddingRelative(edges.left, edges.top, edges.right, edges.bottom);
        }
        @Override public void close() {
            scope.dispatcher().checkOwner(); if (closed) return; closed = true;
            observer.callback = null; observer.layout = null; observers.remove(observer);
            observerSnapshot = List.copyOf(observers);
            try { if (view != null) { InsetsValue current = read(); InsetsValue restore = baseline.restoreIfOwned(current); if (!current.equals(restore)) write(restore); } }
            finally { if (margins) marginsOwned = false; else paddingOwned = false; }
        }
    }
}
