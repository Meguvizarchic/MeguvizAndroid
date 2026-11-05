package io.github.jichuo1.meguviz.android.window;

import android.view.View;
import android.view.ViewTreeObserver;
import io.github.jichuo1.meguviz.android.runtime.core.Registration;
import java.util.Objects;

public final class ObserverRegistration {
    private ObserverRegistration() { }
    public static Registration onPreDraw(ViewScope scope, Runnable callback) {
        scope.checkOpen(); Objects.requireNonNull(callback);
        View view = scope.view();
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            if (scope.lifecycle().isActive() && scope.parentAdmissionOpen()) {
                try { callback.run(); } catch (Throwable failure) { scope.fail(failure); }
            }
            return true;
        };
        ObserverLease<ViewTreeObserver> lease = new ObserverLease<>(new ObserverLease.Access<>() {
            @Override public boolean alive(ViewTreeObserver tree) { return tree.isAlive(); }
            @Override public void add(ViewTreeObserver tree) { tree.addOnPreDrawListener(listener); }
            @Override public void remove(ViewTreeObserver tree) { tree.removeOnPreDrawListener(listener); }
        });
        View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) { lease.attach(attached.getViewTreeObserver()); }
            @Override public void onViewDetachedFromWindow(View detached) { lease.detach(); }
        };
        Registration result = new Registration(scope.dispatcher(), () -> {
            try { lease.close(); } finally { view.removeOnAttachStateChangeListener(attach); }
        });
        scope.own(result);
        try {
            view.addOnAttachStateChangeListener(attach);
            if (view.isAttachedToWindow()) lease.attach(view.getViewTreeObserver());
        } catch (Throwable failure) {
            result.close();
            throw failure;
        }
        return result;
    }
}
