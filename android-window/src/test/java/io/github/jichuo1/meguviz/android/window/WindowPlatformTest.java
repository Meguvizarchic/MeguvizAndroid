package io.github.jichuo1.meguviz.android.window;

import android.app.Activity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import io.github.jichuo1.meguviz.android.runtime.core.*;
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {27, 29, 30, 37})
public class WindowPlatformTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private Owner owner;
    private Driver driver;
    private FrameScheduler scheduler;
    private WindowSession session;
    private final List<Throwable> errors = new ArrayList<>();
    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        root = new FrameLayout(activity); activity.setContentView(root);
        owner = new Owner(); driver = new Driver(); scheduler = new FrameScheduler(owner, driver, errors::add, 4);
        session = WindowSession.open(owner, scheduler, activity.getWindow(), activity, "test", WindowSession.Permission.OWN_WINDOW, errors::add);
        session.setStarted(true); assertTrue(root.isAttachedToWindow());
    }
    @After public void tearDown() { if (session != null) session.close(); if (scheduler != null) scheduler.close(); if (controller != null) controller.pause().stop().destroy(); }
    @Test public void rebindDetachPreDrawAndCloseRejectOldResults() {
        View view = new View(activity); root.addView(view); ViewScope scope = session.scope(view);
        BindingLease first = scope.binding(ResourceState.ALL); AtomicInteger commits = new AtomicInteger();
        assertEquals(LifecycleScope.CommitResult.COMPLETED, scope.commit(first, commits::incrementAndGet));
        scope.rebind(); assertTrue(first.isClosed()); assertEquals(LifecycleScope.CommitResult.STALE, scope.commit(first, commits::incrementAndGet));
        BindingLease attached = scope.binding(0); root.removeView(view);
        assertEquals(LifecycleScope.State.SUSPENDED, scope.lifecycle().state());
        assertEquals(LifecycleScope.CommitResult.STALE, scope.commit(attached, commits::incrementAndGet));
        root.addView(view); assertEquals(LifecycleScope.State.ACTIVE, scope.lifecycle().state());
        BindingLease current = scope.binding(0); scope.close();
        assertEquals(LifecycleScope.CommitResult.STALE, scope.commit(current, commits::incrementAndGet)); assertEquals(1, commits.get());

        View preDrawView = new View(activity); root.addView(preDrawView); ViewScope preDrawScope = session.scope(preDrawView);
        AtomicInteger calls = new AtomicInteger(); Registration lease = ObserverRegistration.onPreDraw(preDrawScope, calls::incrementAndGet);
        root.getViewTreeObserver().dispatchOnPreDraw(); assertEquals(1, calls.get());
        root.removeView(preDrawView); root.getViewTreeObserver().dispatchOnPreDraw(); assertEquals(1, calls.get());
        root.addView(preDrawView); root.getViewTreeObserver().dispatchOnPreDraw(); assertEquals(2, calls.get());
        lease.close(); preDrawScope.close(); root.getViewTreeObserver().dispatchOnPreDraw(); assertEquals(2, calls.get());
        assertEquals(0, preDrawScope.lifecycle().registrationCount());
    }
    @Test public void callbackLeaseAndSharedSchedulerSurviveSessionClose() {
        Window window = activity.getWindow(); Window.Callback original = window.getCallback();
        WindowCallbackLease lease = WindowCallbackLease.install(session, WindowPlatformTest::wrapper);
        Window.Callback installed = window.getCallback(); assertNotSame(original, installed);
        Window.Callback later = wrapper(installed); window.setCallback(later); lease.close(); assertSame(later, window.getCallback());
        window.setCallback(original);
        WindowCallbackLease own = WindowCallbackLease.install(session, WindowPlatformTest::wrapper); own.close(); assertSame(original, window.getCallback());

        AtomicInteger calls = new AtomicInteger();
        WindowSession.WindowFrameChannel channel = session.openFrameChannel(new Object(), (tick, quota) -> { calls.incrementAndGet(); return 1; }, 1);
        session.setStarted(false); channel.requestFrame(); assertNull(driver.pending);
        session.setStarted(true); assertNotNull(driver.pending); driver.fire(); assertEquals(1, calls.get()); assertNull(driver.pending);
        FrameScheduler.FrameChannel unrelated = scheduler.openChannel(new Object(), (tick, quota) -> { calls.incrementAndGet(); return 1; }, 1);
        unrelated.requestFrame(); session.close(); driver.fire(); assertEquals(2, calls.get());
    }
    @Test public void insetsApplicationAndLocalObserverDoNotConsumeOrAccumulate() {
        View view = new View(activity); root.addView(view); view.setPaddingRelative(1, 2, 3, 4);
        ViewScope scope = session.scope(view); InsetsSlot slot = InsetsSlot.claimExclusive(scope);
        assertThrows(IllegalStateException.class, () -> InsetsSlot.claimExclusive(scope));
        InsetsSlot.LocalLayout padding = slot.applyPaddingToOwnedContainer(WindowInsetsCompat.Type.systemBars());
        AtomicInteger observed = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<InsetsValue> delivered = new java.util.concurrent.atomic.AtomicReference<>();
        slot.observe(snapshot -> {
            assertEquals(activity.getApplicationInfo().targetSdkVersion, snapshot.hostTargetSdk);
            delivered.set(snapshot.combined(WindowInsetsCompat.Type.systemBars())); observed.incrementAndGet();
        });
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder(attachedRootInsets())
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(4, 5, 6, 7)).build();
        assertFalse("Fixture must provide unconsumed input", insets.isConsumed());
        WindowInsetsCompat returned = ViewCompat.dispatchApplyWindowInsets(view, insets);
        assertFalse(returned.isConsumed()); assertNotNull(delivered.get());
        assertEquals(2 + delivered.get().top, view.getPaddingTop());
        assertEquals(4 + delivered.get().bottom, view.getPaddingBottom()); assertTrue(delivered.get().top > 0);
        ViewCompat.dispatchApplyWindowInsets(view, insets); assertEquals(2 + delivered.get().top, view.getPaddingTop());
        view.setPaddingRelative(10, 20, 30, 40); padding.rebaseBaseline(); ViewCompat.dispatchApplyWindowInsets(view, insets);
        assertEquals(20 + delivered.get().top, view.getPaddingTop()); assertTrue(observed.get() >= 3);
        slot.handOffToExternalOwner(); assertEquals(20, view.getPaddingTop());
        AtomicInteger external = new AtomicInteger(); ViewCompat.setOnApplyWindowInsetsListener(view, (target, value) -> { external.incrementAndGet(); return value; });
        scope.close(); ViewCompat.dispatchApplyWindowInsets(view, insets); assertEquals(1, external.get());
        root.removeView(view);

        View first = new View(activity); View sibling = new View(activity); root.addView(first); root.addView(sibling);
        InsetsSlot siblingSlot = InsetsSlot.claimExclusive(session.scope(first));
        AtomicInteger firstCalls = new AtomicInteger(); AtomicInteger siblingCalls = new AtomicInteger();
        siblingSlot.observe(snapshot -> firstCalls.incrementAndGet());
        ViewCompat.setOnApplyWindowInsetsListener(sibling, (target, value) -> { assertFalse(value.isConsumed()); siblingCalls.incrementAndGet(); return value; });
        WindowInsetsCompat input = new WindowInsetsCompat.Builder(attachedRootInsets())
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(2, 3, 4, 5)).build();
        firstCalls.set(0); siblingCalls.set(0); // Discard the real window's initial dispatch during seed acquisition.
        assertFalse(input.isConsumed()); ViewCompat.dispatchApplyWindowInsets(root, input);
        assertEquals(1, firstCalls.get()); assertEquals(1, siblingCalls.get());
    }
    @Test public void closedScopeClaimFailsAndSameViewIsClaimableAgain() {
        View view = new View(activity); root.addView(view);
        ViewScope closed = session.scope(view);
        closed.close();
        assertThrows(IllegalStateException.class, () -> InsetsSlot.claimExclusive(closed));
        ViewScope reopened = session.scope(view);
        InsetsSlot slot = InsetsSlot.claimExclusive(reopened);
        assertNotNull(slot);
        slot.close(); reopened.close();
    }

    @Test public void observePermissionAndFailuresCloseOnlyAffectedScope() {
        WindowSession observed = WindowSession.open(owner, scheduler, activity.getWindow(), activity, "observe", WindowSession.Permission.OBSERVE, errors::add);
        try {
            View view = new View(activity); root.addView(view); InsetsSlot slot = InsetsSlot.claimExclusive(observed.scope(view));
            assertThrows(SecurityException.class, () -> slot.applyPaddingToOwnedContainer(WindowInsetsCompat.Type.systemBars()));
            assertThrows(SecurityException.class, () -> WindowCallbackLease.install(observed, WindowPlatformTest::wrapper));
            assertThrows(IllegalStateException.class, () -> WindowSession.open(owner, scheduler, activity.getWindow(), activity, "duplicate", WindowSession.Permission.OWN_WINDOW, errors::add));
        } finally { observed.close(); }

        View callbackView = new View(activity); root.addView(callbackView); ViewScope callbackScope = session.scope(callbackView);
        AtomicInteger released = new AtomicInteger(); callbackScope.own(new Registration(owner, released::incrementAndGet));
        ObserverRegistration.onPreDraw(callbackScope, () -> { throw new IllegalStateException("callback failure"); });
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertTrue(callbackScope.lifecycle().isClosed()); assertFalse(session.lifecycle().isClosed());
        assertEquals(1, released.get()); assertEquals(1, errors.size()); assertEquals(0, callbackScope.lifecycle().registrationCount());
        root.getViewTreeObserver().dispatchOnPreDraw(); assertEquals(1, errors.size());

        errors.clear();
        View cleanupView = new View(activity); root.addView(cleanupView); ViewScope cleanupScope = session.scope(cleanupView);
        AtomicInteger cleanupReleased = new AtomicInteger();
        cleanupScope.ownBinding(() -> { throw new IllegalStateException("cleanup failure"); });
        cleanupScope.ownBinding((AutoCloseable) cleanupReleased::incrementAndGet);
        cleanupScope.rebind(); assertEquals(1, cleanupReleased.get()); assertTrue(cleanupScope.lifecycle().isClosed()); assertEquals(1, errors.size());
    }
    @Test public void viewMailboxAndRepeatedLeaseCloseDoNotAccumulate() {
        View view = new View(activity); root.addView(view); ViewScope scope = session.scope(view);
        BoundedMailbox mailbox = scope.openMailbox(1, 1, 1); BoundedMailbox.Slot slot = mailbox.newSlot();
        BindingLease binding = scope.binding(0); AtomicInteger commits = new AtomicInteger(); AtomicInteger releases = new AtomicInteger();
        session.setStarted(false);
        BoundedMailbox.Submission queued = mailbox.offerState(slot, binding.token(), commits::incrementAndGet, releases::incrementAndGet);
        assertEquals(BoundedMailbox.Outcome.PENDING, queued.handle().outcome()); assertNull(driver.pending);
        session.setStarted(true); driver.fire(); assertEquals(1, commits.get()); assertEquals(1, releases.get());
        mailbox.offerState(slot, binding.token(), commits::incrementAndGet, releases::incrementAndGet);
        scope.rebind(); assertTrue(mailbox.isClosed()); assertEquals(2, releases.get()); assertNull(driver.pending);

        View leaseView = new View(activity); root.addView(leaseView); ViewScope leaseScope = session.scope(leaseView);
        int registrations = leaseScope.lifecycle().registrationCount();
        for (int i = 0; i < 1000; i++) {
            leaseScope.binding(ResourceState.ALL).close();
            leaseScope.openMailbox(1, 1, 1).close();
        }
        assertEquals(0, leaseScope.ownedBindingCount());
        assertEquals(registrations, leaseScope.lifecycle().registrationCount());
        assertEquals(0, scheduler.channelCount()); assertNull(driver.pending);
        leaseScope.close(); assertEquals(0, leaseScope.lifecycle().registrationCount());
    }
    @Test public void localThemeNamespaceAndWindowOwnerResourceAuthority() {
        android.view.ContextThemeWrapper local = new android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_Light);
        View moduleView = new View(local); View hostView = new View(activity); root.addView(moduleView); root.addView(hostView);
        ViewScope module = session.scope(moduleView, "module"); ViewScope host = session.scope(hostView);
        assertNotSame(session.resources(), module.resources()); assertSame(session.resources(), host.resources());
        assertEquals("module", module.resources().state().namespace());
        BindingLease moduleBinding = module.binding(ResourceState.THEME); BindingLease hostBinding = host.binding(ResourceState.THEME);
        Object sameTheme = local.getTheme(); local.getTheme().applyStyle(android.R.style.Theme_Material, true);
        assertSame(sameTheme, local.getTheme()); module.resources().invalidateTheme();
        assertEquals(LifecycleScope.CommitResult.STALE, module.commit(moduleBinding, () -> fail("old local theme")));
        assertEquals(LifecycleScope.CommitResult.COMPLETED, host.commit(hostBinding, () -> {}));
        session.refreshConfiguration();
        module.close(); assertEquals(LifecycleScope.CommitResult.COMPLETED, host.commit(hostBinding, () -> {}));

        android.view.ContextThemeWrapper ownerLocal = new android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_Light);
        View ownerView = new View(ownerLocal); root.addView(ownerView);
        assertThrows(IllegalArgumentException.class, () -> session.resourcesFor(ownerView));
        ViewScope bound = session.scope(ownerView, "local");
        assertSame(bound.resources(), session.resourcesFor(ownerView));
        assertNotSame(session.resources(), session.resourcesFor(ownerView));
        BindingLease previous = bound.binding(ResourceState.THEME);
        ResourceState.Token windowToken = session.resources().state().token(ResourceState.THEME);
        Object theme = ownerLocal.getTheme(); ownerLocal.getTheme().applyStyle(android.R.style.Theme_Material, true);
        assertSame(theme, ownerLocal.getTheme()); session.resourcesFor(ownerView).invalidateTheme();
        assertEquals(LifecycleScope.CommitResult.STALE, bound.commit(previous, () -> fail("stale local resource")));
        assertTrue(session.resources().state().isCurrent(windowToken));
        assertThrows(IllegalStateException.class, () -> session.scope(ownerView));
        bound.close(); assertThrows(IllegalArgumentException.class, () -> session.resourcesFor(ownerView));
        session.close(); assertThrows(IllegalStateException.class, () -> session.resourcesFor(ownerView));
    }
    @Test public void snapshotAcceptsCurrentSystemBarsUnionIncludingSystemOverlays() {
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 3, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 5))
                .setInsets(WindowInsetsCompat.Type.systemOverlays(), Insets.of(1, 2, 4, 6)).build();
        InsetsSnapshot snapshot = InsetsSnapshot.copy(insets, android.os.Build.VERSION.SDK_INT, 37);
        assertEquals(9, snapshot.types().size());
        assertEquals(WindowInsetsCompat.Type.systemOverlays(), snapshot.types().get(8).type);
        Insets platformUnion = insets.getInsets(WindowInsetsCompat.Type.systemBars());
        assertEquals(new InsetsValue(platformUnion.left, platformUnion.top, platformUnion.right, platformUnion.bottom),
                snapshot.combined(WindowInsetsCompat.Type.systemBars()));
        // types() is materialized lazily on first use and then cached: same instance, same order.
        assertSame(snapshot.types(), snapshot.types());
        // combined() must not depend on whether types() was materialized.
        InsetsSnapshot fresh = InsetsSnapshot.copy(insets, android.os.Build.VERSION.SDK_INT, 37);
        assertEquals(snapshot.combined(WindowInsetsCompat.Type.systemBars()),
                fresh.combined(WindowInsetsCompat.Type.systemBars()));
        assertEquals(snapshot.types().get(8).edges, fresh.types().get(8).edges);
        assertEquals(snapshot.types().get(8).visible, fresh.types().get(8).visible);
    }
    @Test public void acceptedBackgroundCloseRejectsCallbacksBeforeOwnerCleanupWithoutThrowing() throws InterruptedException {
        View view = new View(activity); root.addView(view); ViewScope scope = session.scope(view);
        AtomicInteger callbacks = new AtomicInteger(); ObserverRegistration.onPreDraw(scope, callbacks::incrementAndGet);
        InsetsSlot slot = InsetsSlot.claimExclusive(scope); slot.observe(snapshot -> callbacks.incrementAndGet());
        java.util.concurrent.atomic.AtomicReference<LifecycleScope.CloseRequest> close = new java.util.concurrent.atomic.AtomicReference<>();
        Thread requester = new Thread(() -> close.set(session.requestClose())); requester.start(); requester.join();
        assertTrue(close.get().accepted()); assertFalse(close.get().completed());
        root.getViewTreeObserver().dispatchOnPreDraw();
        ViewCompat.dispatchApplyWindowInsets(view, new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 2, 0, 3)).build());
        assertEquals(0, callbacks.get());
        assertFalse(owner.tasks.isEmpty()); owner.tasks.removeFirst().run(); assertTrue(close.get().completed());
        assertEquals(0, scope.lifecycle().registrationCount());
    }
    private static Window.Callback wrapper(Window.Callback delegate) {
        return (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> method.invoke(delegate, args));
    }
    private WindowInsetsCompat attachedRootInsets() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(root.isAttachedToWindow());
        android.view.WindowInsets platform = root.getRootWindowInsets();
        assertNotNull("Attached window must have dispatched real platform insets", platform);
        WindowInsetsCompat seed = WindowInsetsCompat.toWindowInsetsCompat(platform, root);
        assertFalse("Root insets seed must be unconsumed", seed.isConsumed());
        return seed;
    }
    static final class Owner implements OwnerDispatcher {
        final Thread thread = Thread.currentThread(); final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public boolean isOwnerThread() { return Thread.currentThread() == thread; }
        public boolean post(Runnable callback) { tasks.add(callback); return true; }
        public void remove(Runnable callback) { tasks.remove(callback); }
    }
    static final class Driver implements FrameDriver {
        Callback pending;
        public boolean request(Callback callback) { assertNull(pending); pending = callback; return true; }
        public void remove(Callback callback) { if (pending == callback) pending = null; }
        void fire() { Callback frame = pending; pending = null; assertNotNull(frame); frame.onFrame(16_000_000L); }
    }
}
