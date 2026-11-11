package io.github.jichuo1.meguviz.android.lifecycle;

import android.view.View;
import android.widget.FrameLayout;
import androidx.activity.ComponentActivity;
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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** The one-call entry must be indistinguishable from WindowSession.open followed by bindWindow. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {27, 29, 30, 37})
public class OpenWindowTest {
    private static int observers(ComponentActivity activity) { return ((LifecycleRegistry) activity.getLifecycle()).getObserverCount(); }

    @Test public void oneCallMatchesOpenThenBindThroughStopStartAndDestroy() {
        ActivityController<ComponentActivity> controller = Robolectric.buildActivity(ComponentActivity.class).setup().visible();
        ComponentActivity activity = controller.get();
        FrameLayout root = new FrameLayout(activity); View first = new View(activity); View second = new View(activity);
        root.addView(first); root.addView(second); activity.setContentView(root);
        int before = observers(activity);
        WindowSession oneCall = LifecycleAdapters.openWindow(activity, WindowSession.Permission.OBSERVE);
        int afterOneCall = observers(activity);
        WindowSession manual = WindowSession.open(activity.getWindow(), activity, activity.getPackageName(), WindowSession.Permission.OBSERVE);
        LifecycleAdapters.bindWindow(activity, manual);
        try {
            // Same construction: one lifecycle observer each, same window, namespace and permission.
            assertEquals(1, afterOneCall - before); assertEquals(1, observers(activity) - afterOneCall);
            assertSame(activity.getWindow(), oneCall.window()); assertSame(manual.window(), oneCall.window());
            assertEquals(activity.getPackageName(), oneCall.resources().state().namespace());
            assertEquals(manual.resources().state().namespace(), oneCall.resources().state().namespace());
            assertEquals(manual.permission(), oneCall.permission());
            ViewScope oneCallScope = oneCall.scope(first); ViewScope manualScope = manual.scope(second);
            BindingLease oneCallLease = oneCallScope.binding(0); BindingLease manualLease = manualScope.binding(0);
            AtomicInteger oneCallCommits = new AtomicInteger(); AtomicInteger manualCommits = new AtomicInteger();
            // Resumed: both sessions were started by the lifecycle, with no onStart forwarding.
            assertTrue(oneCall.lifecycle().isActive()); assertTrue(manual.lifecycle().isActive());
            assertEquals(LifecycleScope.CommitResult.COMPLETED, oneCallScope.commit(oneCallLease, oneCallCommits::incrementAndGet));
            assertEquals(LifecycleScope.CommitResult.COMPLETED, manualScope.commit(manualLease, manualCommits::incrementAndGet));
            controller.pause().stop();
            assertFalse(oneCall.lifecycle().isActive()); assertFalse(manual.lifecycle().isActive());
            assertEquals(LifecycleScope.CommitResult.INACTIVE, oneCallScope.commit(oneCallLease, oneCallCommits::incrementAndGet));
            assertEquals(LifecycleScope.CommitResult.INACTIVE, manualScope.commit(manualLease, manualCommits::incrementAndGet));
            controller.restart().start().resume();
            assertTrue(oneCall.lifecycle().isActive()); assertEquals(manual.lifecycle().isActive(), oneCall.lifecycle().isActive());
            assertEquals(LifecycleScope.CommitResult.COMPLETED, oneCallScope.commit(oneCallLease, oneCallCommits::incrementAndGet));
            assertEquals(LifecycleScope.CommitResult.COMPLETED, manualScope.commit(manualLease, manualCommits::incrementAndGet));
            assertEquals(2, oneCallCommits.get()); assertEquals(manualCommits.get(), oneCallCommits.get());
            controller.pause().stop().destroy();
            // Destroy closes both with no onDestroy forwarding. The Activity drops some of its own
            // observers on destroy too, so only "neither of ours is left" can be asserted here; the
            // exact one-observer release is pinned in refusedSecondOwnerLeavesTheFirstSession...
            assertTrue(oneCall.lifecycle().isClosed()); assertTrue(manual.lifecycle().isClosed());
            assertTrue(observers(activity) <= before);
        } finally { oneCall.close(); manual.close(); }
    }

    @Test public void destroyedActivityIsRefusedBeforeAnythingIsOpened() {
        ActivityController<ComponentActivity> controller = Robolectric.buildActivity(ComponentActivity.class).setup().visible();
        ComponentActivity activity = controller.get();
        controller.pause().stop().destroy();
        int before = observers(activity);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> LifecycleAdapters.openWindow(activity, WindowSession.Permission.OWN_WINDOW));
        assertTrue(refused.getMessage(), refused.getMessage().startsWith("Activity destroyed: " + ComponentActivity.class.getName()));
        assertEquals(before, observers(activity));
        // No session was opened, so the window has no owner left behind.
        WindowSession.open(activity.getWindow(), activity, "probe", WindowSession.Permission.OWN_WINDOW).close();
    }

    @Test public void refusedSecondOwnerLeavesTheFirstSessionAndTheLifecycleUntouched() {
        ActivityController<ComponentActivity> controller = Robolectric.buildActivity(ComponentActivity.class).setup().visible();
        ComponentActivity activity = controller.get();
        WindowSession owner = LifecycleAdapters.openWindow(activity, WindowSession.Permission.OWN_WINDOW);
        try {
            int bound = observers(activity);
            assertThrows(IllegalStateException.class, () -> LifecycleAdapters.openWindow(activity, WindowSession.Permission.OWN_WINDOW));
            assertEquals(bound, observers(activity));
            assertFalse(owner.lifecycle().isClosed()); assertTrue(owner.lifecycle().isActive());
            // Sessions that do not own the window still stack, exactly as with WindowSession.open.
            WindowSession observer = LifecycleAdapters.openWindow(activity, WindowSession.Permission.OBSERVE);
            assertNotSame(owner, observer); assertEquals(bound + 1, observers(activity));
            observer.close(); assertEquals(bound, observers(activity));
        } finally { owner.close(); controller.pause().stop().destroy(); }
    }

    @Test public void explicitNamespaceIsKeptAndOtherThreadsAreRefused() throws Exception {
        ActivityController<ComponentActivity> controller = Robolectric.buildActivity(ComponentActivity.class).setup().visible();
        ComponentActivity activity = controller.get();
        WindowSession session = LifecycleAdapters.openWindow(activity, "module", WindowSession.Permission.APPLY_LOCAL);
        try {
            assertEquals("module", session.resources().state().namespace());
            assertEquals(WindowSession.Permission.APPLY_LOCAL, session.permission());
            int bound = observers(activity);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { LifecycleAdapters.openWindow(activity, WindowSession.Permission.OBSERVE); }
                catch (Throwable thrown) { failure.set(thrown); }
            });
            worker.start(); worker.join();
            assertTrue(String.valueOf(failure.get()), failure.get() instanceof IllegalStateException);
            assertEquals(bound, observers(activity));
            assertThrows(NullPointerException.class, () -> LifecycleAdapters.openWindow((ComponentActivity) null, WindowSession.Permission.OBSERVE));
        } finally { session.close(); controller.pause().stop().destroy(); }
    }
}
