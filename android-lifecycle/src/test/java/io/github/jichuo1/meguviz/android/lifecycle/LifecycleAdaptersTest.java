package io.github.jichuo1.meguviz.android.lifecycle;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.widget.FrameLayout;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.BindingLease;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.window.ViewScope;
import io.github.jichuo1.meguviz.android.window.WindowSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {27, 29, 30, 37})
public class LifecycleAdaptersTest {
    static final class Owner implements LifecycleOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        @Override public Lifecycle getLifecycle() { return lifecycle; }
    }
    @Test public void stopStartViewDestroyAndDialogDismissHaveSeparateOwnership() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible(); Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity); View view = new View(activity); root.addView(view); activity.setContentView(root);
        WindowSession session = WindowSession.open(activity.getWindow(), activity, "lifecycle", WindowSession.Permission.OBSERVE);
        try {
            Owner activityOwner = new Owner(); Owner viewOwner = new Owner();
            LifecycleAdapters.bindWindow(activityOwner, session); ViewScope scope = session.scope(view);
            LifecycleAdapters.bindViewOwner(viewOwner, scope);
            activityOwner.lifecycle.setCurrentState(Lifecycle.State.STARTED); viewOwner.lifecycle.setCurrentState(Lifecycle.State.STARTED);
            BindingLease binding = scope.binding(0); AtomicInteger commits = new AtomicInteger();
            assertEquals(LifecycleScope.CommitResult.COMPLETED, scope.commit(binding, commits::incrementAndGet));
            activityOwner.lifecycle.setCurrentState(Lifecycle.State.CREATED);
            assertEquals(LifecycleScope.CommitResult.INACTIVE, scope.commit(binding, commits::incrementAndGet));
            activityOwner.lifecycle.setCurrentState(Lifecycle.State.STARTED);
            assertEquals(LifecycleScope.CommitResult.COMPLETED, scope.commit(binding, commits::incrementAndGet));
            viewOwner.lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
            assertTrue(scope.lifecycle().isClosed()); assertFalse(session.lifecycle().isClosed()); assertEquals(0, viewOwner.lifecycle.getObserverCount());
            assertEquals(LifecycleScope.CommitResult.STALE, scope.commit(binding, commits::incrementAndGet));
            activityOwner.lifecycle.setCurrentState(Lifecycle.State.DESTROYED); assertTrue(session.lifecycle().isClosed());
            assertEquals(0, activityOwner.lifecycle.getObserverCount()); assertEquals(2, commits.get());
        } finally { session.close(); controller.pause().stop().destroy(); }

        ActivityController<Activity> dialogController = Robolectric.buildActivity(Activity.class).setup().visible(); Activity dialogActivity = dialogController.get();
        WindowSession parent = WindowSession.open(dialogActivity.getWindow(), dialogActivity, "activity", WindowSession.Permission.OBSERVE);
        Dialog dialog = new Dialog(dialogActivity); dialog.show();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        WindowSession child = WindowSession.open(dialog.getWindow(), dialog.getContext(), "dialog", WindowSession.Permission.OWN_WINDOW);
        ManualWindowOwner manual = new ManualWindowOwner(child);
        try {
            manual.onStart(); assertTrue(child.lifecycle().isActive());
            dialog.setOnDismissListener(value -> manual.onDismiss());
            dialog.dismiss(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(child.lifecycle().isClosed()); assertFalse(parent.lifecycle().isClosed());
        } finally { child.close(); parent.close(); dialogController.pause().stop().destroy(); }
    }
}
