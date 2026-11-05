package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class LifecycleContractTest {
    @Test public void detachRebindAndExternalDomainsInvalidateOnlyTheirOwners() {
        ControlledRuntime runtime = new ControlledRuntime();
        LifecycleScope scope = runtime.activeScope();
        WorkToken old = scope.lease(LifecycleScope.ALL).token();
        scope.setAttached(false);
        assertEquals(LifecycleScope.State.SUSPENDED, scope.state());
        assertFalse(old.isCurrent());
        scope.setAttached(true);
        assertTrue(scope.isActive()); assertFalse(old.isCurrent());
        scope.close();
        assertEquals(LifecycleScope.State.CLOSED, scope.state());
        assertThrows(IllegalStateException.class, () -> scope.setAttached(true));

        ControlledRuntime rebindRuntime = new ControlledRuntime(); LifecycleScope rebindScope = rebindRuntime.activeScope();
        WorkToken binding = rebindScope.lease(LifecycleScope.BINDING).token();
        WorkToken attach = rebindScope.lease(LifecycleScope.ATTACH).token();
        rebindScope.rebind();
        assertFalse(binding.isCurrent()); assertTrue(attach.isCurrent());
        AtomicInteger commits = new AtomicInteger();
        assertEquals(LifecycleScope.CommitResult.STALE, rebindScope.commit(binding, commits::incrementAndGet));
        assertEquals(0, commits.get());

        ControlledRuntime domainRuntime = new ControlledRuntime(); LifecycleScope domainScope = domainRuntime.activeScope();
        EpochDomain theme = new EpochDomain(domainRuntime); EpochDomain resources = new EpochDomain(domainRuntime);
        WorkToken themed = domainScope.lease(0, theme).token();
        WorkToken resourceOnly = domainScope.lease(0, resources).token();
        theme.invalidate();
        assertFalse(themed.isCurrent()); assertTrue(resourceOnly.isCurrent());
    }

    @Test public void closeAdmissionCompletionAndRejectionAreObservable() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        WorkToken token = scope.lease(LifecycleScope.ALL).token();
        AtomicReference<LifecycleScope.CloseRequest> result = new AtomicReference<>();
        ControlledRuntime.background(() -> result.set(scope.requestClose()));
        assertTrue(result.get().accepted()); assertFalse(result.get().completed());
        assertTrue(scope.admissionClosed()); assertFalse(scope.isClosed()); assertFalse(token.isCurrent());
        assertEquals(1, runtime.queuedSignals());
        ControlledRuntime.background(scope::requestClose);
        assertEquals(1, runtime.queuedSignals());
        runtime.pumpSignals();
        assertTrue(result.get().completed()); assertTrue(scope.isClosed());

        ControlledRuntime rejectedRuntime = new ControlledRuntime(); LifecycleScope rejectedScope = rejectedRuntime.activeScope();
        rejectedRuntime.rejectPost = true;
        AtomicReference<LifecycleScope.CloseRequest> rejected = new AtomicReference<>();
        ControlledRuntime.background(() -> rejected.set(rejectedScope.requestClose()));
        assertTrue(rejected.get().accepted()); assertFalse(rejected.get().completed()); assertNotNull(rejected.get().failure());
        rejectedScope.close(); assertTrue(rejected.get().completed());
    }

    @Test public void closeReleasesEveryRegistrationAndDoesNotAffectOtherScopes() {
        ControlledRuntime runtime = new ControlledRuntime();
        LifecycleScope scope = new LifecycleScope(runtime, error -> { throw new IllegalArgumentException("sink"); });
        AtomicInteger releases = new AtomicInteger();
        scope.own(new Registration(runtime, () -> { releases.incrementAndGet(); throw new IllegalStateException("cleanup"); }));
        scope.own(new Registration(runtime, releases::incrementAndGet));
        scope.close(); scope.close();
        assertEquals(2, releases.get()); assertEquals(0, scope.registrationCount());
        assertEquals("cleanup", scope.lastFailure().getMessage());
        assertEquals("sink", scope.lastFailure().getSuppressed()[0].getMessage());

        ControlledRuntime twoRuntime = new ControlledRuntime();
        LifecycleScope left = twoRuntime.activeScope(); LifecycleScope right = twoRuntime.activeScope();
        WorkToken token = right.lease(LifecycleScope.ALL).token(); left.close();
        assertTrue(right.isActive()); assertTrue(token.isCurrent());
    }

    @Test public void commitRevalidatesOnOwnerThreadAndCallbackCloseIsFinal() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        WorkToken token = scope.lease(LifecycleScope.ALL).token(); AtomicInteger count = new AtomicInteger();
        assertEquals(LifecycleScope.CommitResult.COMPLETED, scope.commit(token, () -> { scope.close(); count.incrementAndGet(); }));
        assertEquals(LifecycleScope.CommitResult.STALE, scope.commit(token, count::incrementAndGet));
        assertEquals(1, count.get());

        ControlledRuntime ownerRuntime = new ControlledRuntime(); LifecycleScope ownerScope = ownerRuntime.activeScope();
        WorkToken ownerToken = ownerScope.lease(0).token();
        ControlledRuntime.background(() -> assertThrows(IllegalStateException.class, ownerToken::isCurrent));
        ownerScope.setStarted(false);
        assertEquals(LifecycleScope.CommitResult.INACTIVE, ownerScope.commit(ownerToken, () -> fail("inactive commit")));
    }

    @Test public void leaseCloseAndEpochOverflowInvalidateStamps() {
        ControlledRuntime runtime = new ControlledRuntime(); BindingLease lease = runtime.activeScope().lease(0);
        lease.close(); lease.close(); assertFalse(lease.token().isCurrent());

        ControlledRuntime epochRuntime = new ControlledRuntime(); EpochDomain domain = new EpochDomain(epochRuntime, Long.MAX_VALUE);
        EpochDomain.Stamp old = domain.stamp(); domain.invalidate();
        assertEquals(0, domain.stamp().epoch()); assertFalse(domain.matches(old));
        domain.close(); assertFalse(domain.matches(old));
    }

    @Test public void callbackAndCleanupFailuresCloseScopeAfterEveryRelease() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        AtomicInteger released = new AtomicInteger(); scope.own(new Registration(runtime, released::incrementAndGet));
        assertEquals(LifecycleScope.CommitResult.FAILED, scope.commit(scope.lease(0).token(), () -> { throw new IllegalStateException("callback"); }));
        assertTrue(scope.isClosed()); assertEquals(1, released.get()); assertEquals(1, runtime.errors.size());

        ControlledRuntime fatalRuntime = new ControlledRuntime(); LifecycleScope fatalScope = fatalRuntime.activeScope();
        AtomicInteger releases = new AtomicInteger();
        for (int i = 0; i < 12; i++) fatalScope.own(new Registration(fatalRuntime, () -> { releases.incrementAndGet(); throw new IllegalStateException("ordinary"); }));
        fatalScope.own(new Registration(fatalRuntime, () -> { releases.incrementAndGet(); throw new OutOfMemoryError("injected fatal"); }));
        fatalScope.own(new Registration(fatalRuntime, releases::incrementAndGet));
        assertThrows(OutOfMemoryError.class, fatalScope::close);
        assertEquals(14, releases.get()); assertEquals(0, fatalScope.registrationCount());
        assertTrue(fatalScope.requestClose().completed());
        assertTrue(fatalScope.lastFailure().getSuppressed().length <= 8);
    }

    @Test public void registrationCloseUnlinksOwnerEvenWhenCleanupFails() throws Exception {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 1000; i++) {
            Registration registration = scope.own(new Registration(runtime, count::incrementAndGet));
            assertEquals(1, scope.registrationCount()); registration.close();
            assertEquals(0, scope.registrationCount());
        }
        assertEquals(1000, count.get());

        ControlledRuntime failingRuntime = new ControlledRuntime(); LifecycleScope failingScope = failingRuntime.activeScope();
        Registration registration = failingScope.own(new Registration(failingRuntime, () -> { throw new IllegalStateException("cleanup"); }));
        assertThrows(IllegalStateException.class, () -> failingScope.own(registration));
        assertThrows(IllegalStateException.class, registration::close); assertEquals(0, failingScope.registrationCount());
        java.lang.reflect.Field field = Registration.class.getDeclaredField("ownerRelease"); field.setAccessible(true);
        assertNull(field.get(registration));
    }

    @Test public void bindingCloseHookIsSingleOwnerAndClearedBeforeReentry() throws Exception {
        ControlledRuntime runtime = new ControlledRuntime(); BindingLease lease = runtime.activeScope().lease(0);
        AtomicInteger closed = new AtomicInteger(); lease.onClose(() -> { closed.incrementAndGet(); lease.close(); });
        assertThrows(IllegalStateException.class, () -> lease.onClose(() -> {}));
        lease.close(); lease.close(); assertEquals(1, closed.get());
        java.lang.reflect.Field field = BindingLease.class.getDeclaredField("closeListener"); field.setAccessible(true);
        assertNull(field.get(lease));
    }
}
