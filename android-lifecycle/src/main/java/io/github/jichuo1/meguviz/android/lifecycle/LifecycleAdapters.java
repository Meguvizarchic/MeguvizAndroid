package io.github.jichuo1.meguviz.android.lifecycle;

import android.app.Activity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import io.github.jichuo1.meguviz.android.runtime.core.Registration;
import io.github.jichuo1.meguviz.android.window.ViewScope;
import io.github.jichuo1.meguviz.android.window.WindowSession;
import java.util.Objects;

/** Explicit owner adapters; no application/provider initialization or Context type guessing. */
public final class LifecycleAdapters {
    private LifecycleAdapters() { }
    /**
     * One call for an Activity that is its own {@link LifecycleOwner} (ComponentActivity, AppCompatActivity):
     * {@link #openWindow(Activity, String, WindowSession.Permission)} with the Activity's package name
     * as the resource namespace.
     */
    public static <A extends Activity & LifecycleOwner> WindowSession openWindow(A activity, WindowSession.Permission permission) {
        return openWindow(activity, Objects.requireNonNull(activity, "activity").getPackageName(), permission);
    }
    /**
     * Opens a session on the Activity's window and hands it to the Activity's lifecycle. It is exactly
     * {@code WindowSession.open(activity.getWindow(), activity, namespace, permission)} followed by
     * {@link #bindWindow}: start/stop follow the lifecycle and the session closes on destroy, so the
     * Activity forwards nothing from onStart, onStop or onDestroy. Nothing is cached: every call opens
     * a new session, and a second {@code OWN_WINDOW} session on one window is refused as usual.
     *
     * <p>Owner (main) thread only. An already destroyed Activity is refused before anything is opened,
     * instead of handing back a session that is already closed.</p>
     */
    public static <A extends Activity & LifecycleOwner> WindowSession openWindow(A activity, String namespace, WindowSession.Permission permission) {
        Objects.requireNonNull(activity, "activity");
        if (activity.isDestroyed() || activity.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED)
            throw new IllegalStateException("Activity destroyed: " + activity.getClass().getName() + " is already destroyed, so no window session is opened for it. Open the session while the Activity is alive, usually in onCreate.");
        WindowSession session = WindowSession.open(activity.getWindow(), activity, namespace, permission);
        try { bindWindow(activity, session); }
        catch (RuntimeException | Error failure) {
            try { session.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return session;
    }
    public static Registration bindWindow(LifecycleOwner owner, WindowSession session) {
        session.dispatcher().checkOwner(); Objects.requireNonNull(owner);
        Lifecycle lifecycle = owner.getLifecycle();
        if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
            session.close(); Registration empty = new Registration(session.dispatcher(), () -> { }); empty.close(); return empty;
        }
        LifecycleEventObserver observer = (source, event) -> {
            if (session.lifecycle().admissionClosed()) return;
            if (event == Lifecycle.Event.ON_DESTROY) session.close();
            else session.setStarted(source.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        };
        Registration registration = new Registration(session.dispatcher(), () -> {
            lifecycle.removeObserver(observer);
            if (!session.lifecycle().admissionClosed()) session.setStarted(false);
        });
        session.own(registration); lifecycle.addObserver(observer);
        if (!session.lifecycle().admissionClosed()) session.setStarted(lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        return registration;
    }
    public static Registration bindViewOwner(LifecycleOwner viewOwner, ViewScope scope) {
        scope.dispatcher().checkOwner(); Objects.requireNonNull(viewOwner);
        Lifecycle lifecycle = viewOwner.getLifecycle();
        if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
            scope.close(); Registration empty = new Registration(scope.dispatcher(), () -> { }); empty.close(); return empty;
        }
        LifecycleEventObserver observer = (source, event) -> {
            if (scope.lifecycle().admissionClosed()) return;
            if (event == Lifecycle.Event.ON_DESTROY) scope.close();
            else scope.setOwnerStarted(source.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        };
        Registration registration = new Registration(scope.dispatcher(), () -> {
            lifecycle.removeObserver(observer);
            if (!scope.lifecycle().admissionClosed()) scope.setOwnerStarted(false);
        });
        scope.own(registration); lifecycle.addObserver(observer);
        if (!scope.lifecycle().admissionClosed()) scope.setOwnerStarted(lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        return registration;
    }
    /** Call after onCreateView; the Fragment's own lifetime is deliberately not used. */
    public static Registration bindFragmentView(Fragment fragment, ViewScope scope) {
        return bindViewOwner(fragment.getViewLifecycleOwner(), scope);
    }
}
