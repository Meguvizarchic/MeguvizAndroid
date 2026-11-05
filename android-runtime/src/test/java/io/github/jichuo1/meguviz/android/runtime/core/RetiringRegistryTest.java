package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class RetiringRegistryTest {
    private static AdapterRegistry<String> registry(String value) {
        AdapterRegistry.Descriptor descriptor = new AdapterRegistry.Descriptor(value, "1", 1, 0, 0,
                Collections.singleton("render"), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "owner", "session", "caller");
        return new AdapterRegistry<>(Collections.singletonList(new AdapterRegistry.Provider<>(descriptor, value)),
                Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());
    }

    @Test public void replacementAndCloseBoundKeepLeasesUntilDrain() {
        AtomicInteger released = new AtomicInteger();
        RetiringRegistry<String> registry = new RetiringRegistry<>(registry("old"), 2, 2, released::incrementAndGet);
        RetiringRegistry.Lease<String> old = registry.tryAcquire();
        RetiringRegistry.Retirement<String> retirement = registry.replace(registry("new"), () -> {});
        RetiringRegistry.Lease<String> current = registry.tryAcquire();
        assertEquals("old", old.snapshot().bind("render"));
        assertEquals("new", current.snapshot().bind("render"));
        assertFalse(retirement.isDrained());
        assertFalse(retirement.tryRelease());
        assertEquals(0, released.get());
        old.close(); old.close();
        assertThrows(IllegalStateException.class, old::snapshot);
        assertTrue(retirement.isDrained());
        assertTrue(retirement.tryRelease());
        assertFalse(retirement.tryRelease());
        assertEquals(1, released.get());
        current.close();

        AtomicInteger boundReleased = new AtomicInteger();
        RetiringRegistry<String> bound = new RetiringRegistry<>(registry("old"), 1, 2, boundReleased::incrementAndGet);
        RetiringRegistry.Lease<String> boundOld = bound.tryAcquire();
        RetiringRegistry.Retirement<String> retired = bound.replace(registry("new"), boundReleased::incrementAndGet);
        assertNull(bound.tryAcquire());
        assertFalse(bound.isClosed());
        boundOld.close();
        RetiringRegistry.Lease<String> boundCurrent = bound.tryAcquire();
        RetiringRegistry.Retirement<String> closed = bound.close();
        assertTrue(bound.isClosed());
        assertNull(bound.tryAcquire());
        assertNull(bound.close());
        assertThrows(IllegalStateException.class, () -> bound.replace(registry("later"), () -> {}));
        assertFalse(closed.tryRelease());
        boundCurrent.close();
        assertTrue(closed.tryRelease());
        assertEquals(1, boundReleased.get());
        assertTrue(retired.tryRelease());
    }

    @Test public void releaseFailureIsTerminalAndCallbacksRunOutsideTheLock() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<RetiringRegistry<String>> holder = new AtomicReference<>();
            AdapterRegistry<String> old = registry("old");
            RetiringRegistry<String> registry = new RetiringRegistry<>(old, 1, 2, () -> {
                try {
                    Future<RetiringRegistry.Lease<String>> next = executor.submit(holder.get()::tryAcquire);
                    RetiringRegistry.Lease<String> lease = next.get(2, TimeUnit.SECONDS);
                    assertNotNull(lease);
                    lease.close();
                    throw new IllegalArgumentException("release failure");
                } catch (java.util.concurrent.ExecutionException | InterruptedException | java.util.concurrent.TimeoutException error) {
                    throw new AssertionError(error);
                }
            });
            holder.set(registry);
            RetiringRegistry.Retirement<String> first = registry.replace(registry("new"), () -> {});
            assertThrows(IllegalArgumentException.class, first::tryRelease);
            assertFalse(first.tryRelease());
            assertThrows(IllegalArgumentException.class, () -> registry.replace(old, () -> {}));
            assertTrue(first.completeAfterFailure());
            assertFalse(first.completeAfterFailure());
            assertTrue(registry.close().tryRelease());
        } finally { executor.shutdownNow(); }
    }

    @Test public void concurrentAcquisitionAndPublicationDrainAllRetiredSnapshots() throws Exception {
        AtomicInteger releases = new AtomicInteger();
        RetiringRegistry<String> registry = new RetiringRegistry<>(registry("start"), 4, 31, releases::incrementAndGet);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) workers.add(executor.submit(() -> {
                start.await();
                for (int n = 0; n < 1000; n++) {
                    RetiringRegistry.Lease<String> lease = registry.tryAcquire();
                    if (lease != null) {
                        assertNotNull(lease.snapshot().bind("render"));
                        lease.close();
                    }
                }
                return null;
            }));
            List<RetiringRegistry.Retirement<String>> retired = new ArrayList<>();
            start.countDown();
            for (int i = 0; i < 30; i++) retired.add(registry.replace(registry("p" + i), releases::incrementAndGet));
            for (Future<?> worker : workers) worker.get(10, TimeUnit.SECONDS);
            retired.add(registry.close());
            for (RetiringRegistry.Retirement<String> item : retired) {
                assertTrue(item.isDrained());
                assertTrue(item.tryRelease());
            }
            assertEquals(31, releases.get());
        } finally { executor.shutdownNow(); }
    }

    @Test public void invalidBoundsSameSnapshotAndReleaseReuseAreRejected() throws Exception {
        AdapterRegistry<String> snapshot = registry("one");
        assertThrows(IllegalArgumentException.class, () -> new RetiringRegistry<>(snapshot, 0, 2, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new RetiringRegistry<>(snapshot, 1, 1, () -> {}));
        RetiringRegistry<String> registry = new RetiringRegistry<>(snapshot, 1, 2, () -> {});
        assertThrows(IllegalArgumentException.class, () -> registry.replace(snapshot, () -> {}));

        AdapterRegistry<String> a = registry("a");
        AdapterRegistry<String> b = registry("b");
        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch finishRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RetiringRegistry<String> reuse = new RetiringRegistry<>(a, 2, 2, () -> {
                releaseStarted.countDown();
                try {
                    if (!finishRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                } catch (InterruptedException error) { throw new AssertionError(error); }
            });
            RetiringRegistry.Lease<String> lease = reuse.tryAcquire();
            RetiringRegistry.Retirement<String> oldA = reuse.replace(b, () -> {});
            assertThrows(IllegalArgumentException.class, () -> reuse.replace(a, () -> {}));
            assertThrows(IllegalStateException.class, () -> reuse.replace(registry("c"), () -> {}));
            assertFalse(oldA.tryRelease());
            lease.close();
            Future<Boolean> releasing = executor.submit(oldA::tryRelease);
            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> reuse.replace(a, () -> {}));
            assertThrows(IllegalStateException.class, () -> reuse.replace(registry("c"), () -> {}));
            finishRelease.countDown();
            assertTrue(releasing.get(5, TimeUnit.SECONDS));
            RetiringRegistry.Retirement<String> oldB = reuse.replace(a, () -> {});
            assertTrue(oldB.tryRelease());
            assertTrue(reuse.close().tryRelease());
        } finally {
            finishRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void closedLeaseAndRetirementClearSnapshotSlotAndStateFields() throws Exception {
        RetiringRegistry<String> registry = new RetiringRegistry<>(registry("old"), 1, 2, () -> {});
        RetiringRegistry.Lease<String> lease = registry.tryAcquire();
        lease.close();
        assertNull("snapshot reference must be released", field(lease, "snapshot"));
        assertNull("slot reference must be released", field(lease, "slot"));
        assertNull("state reference must be released", field(lease, "state"));
        registry.close().tryRelease();

        AdapterRegistry<String> snapshot = registry("old");
        RetiringRegistry<String> owner = new RetiringRegistry<>(snapshot, 1, 2, () -> {});
        RetiringRegistry.Lease<String> retainedLease = owner.tryAcquire();
        RetiringRegistry.Retirement<String> retired = owner.replace(registry("new"), () -> {});
        Object retiredSlot = field(retired, "slot");
        assertNotNull("retirement must reference the retired slot before release", retiredSlot);
        retainedLease.close();
        assertNotNull("closed lease is deliberately retained", retainedLease);
        assertNull("retained closed lease snapshot must be null", field(retainedLease, "snapshot"));
        assertNull("retained closed lease slot must be null", field(retainedLease, "slot"));
        assertNull("retained closed lease state must be null", field(retainedLease, "state"));
        assertTrue(retired.tryRelease());
        assertNull("retirement slot must be cleared", field(retired, "slot"));
        assertNull("released slot snapshot must be cleared", field(retiredSlot, "snapshot"));
        Map<?, ?> snapshots = (Map<?, ?>) field(field(owner, "state"), "snapshots");
        assertFalse("released snapshot must leave the registry map", snapshots.containsKey(snapshot));
        owner.close().tryRelease();
        assertTrue("registry map must be empty after every slot is released", snapshots.isEmpty());

        AdapterRegistry<String> forgetSnapshot = registry("old");
        RetiringRegistry<String> forgetOwner = new RetiringRegistry<>(forgetSnapshot, 1, 2, () -> {});
        RetiringRegistry.Lease<String> forgetLease = forgetOwner.tryAcquire();
        RetiringRegistry.Retirement<String> forgetRetired = forgetOwner.replace(registry("new"), () -> {});
        forgetLease.close();
        assertTrue(forgetRetired.isDrained());
        assertTrue(forgetRetired.tryRelease());
        assertNull("retirement slot must be cleared", field(forgetRetired, "slot"));
        Map<?, ?> forgetSnapshots = (Map<?, ?>) field(field(forgetOwner, "state"), "snapshots");
        assertFalse("released snapshot must leave the registry map", forgetSnapshots.containsKey(forgetSnapshot));
        forgetOwner.close().tryRelease();
        assertTrue("registry map must be empty after every slot is released", forgetSnapshots.isEmpty());
    }

    @Test public void crossThreadCloseWithHappensBeforeThenSnapshotThrows() throws Exception {
        RetiringRegistry<String> owner = new RetiringRegistry<>(registry("old"), 1, 2, () -> {});
        RetiringRegistry.Lease<String> lease = owner.tryAcquire();
        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = new Thread(() -> { lease.close(); closed.countDown(); });
        closer.start();
        assertTrue("closer must finish", closed.await(2, TimeUnit.SECONDS));
        closer.join(2000);
        assertThrows(IllegalStateException.class, lease::snapshot);
        owner.close().tryRelease();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
