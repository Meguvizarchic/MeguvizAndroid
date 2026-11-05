package io.github.jichuo1.meguviz.android.window;

import android.view.Window;
import io.github.jichuo1.meguviz.android.runtime.core.Registration;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class WindowCallbackLease implements AutoCloseable {
    private final Registration registration;
    private WindowCallbackLease(Registration registration) { this.registration = registration; }
    public static WindowCallbackLease install(WindowSession session, UnaryOperator<Window.Callback> wrapper) {
        session.requirePermission(WindowSession.Permission.OWN_WINDOW);
        Window window = session.window(); Window.Callback previous = window.getCallback();
        Window.Callback installed = Objects.requireNonNull(wrapper.apply(previous));
        session.checkOpen();
        if (window.getCallback() != previous) throw new IllegalStateException("Callback changed during wrapper creation");
        window.setCallback(installed);
        Registration registration = new Registration(session.dispatcher(), () -> {
            if (window.getCallback() == installed) window.setCallback(previous);
        });
        try { session.own(registration); }
        catch (Throwable failure) {
            try { if (window.getCallback() == installed) window.setCallback(previous); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        return new WindowCallbackLease(registration);
    }
    @Override public void close() { registration.close(); }
}
