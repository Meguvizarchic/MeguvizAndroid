package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FrameSchedulerContractTest {
    @Test public void registrationCoalescingAndIndependentOwnerCancellation() {
        ControlledRuntime runtime = new ControlledRuntime(); FrameScheduler scheduler = runtime.scheduler(64);
        AtomicInteger callbacks = new AtomicInteger();
        FrameScheduler.FrameChannel channel = scheduler.openChannel(new Object(), (tick, quota) -> { callbacks.incrementAndGet(); return 1; }, 16);
        assertFalse(runtime.hasFrame());
        for (int i = 0; i < 10_000; i++) channel.requestFrame();
        assertEquals(1, runtime.frameRequests); runtime.frame();
        assertEquals(1, callbacks.get()); assertFalse(runtime.hasFrame());

        ControlledRuntime cancelRuntime = new ControlledRuntime(); FrameScheduler cancelScheduler = cancelRuntime.scheduler(64);
        List<Integer> calls = new ArrayList<>();
        FrameScheduler.FrameChannel left = cancelScheduler.openChannel(new Object(), (tick, quota) -> { calls.add(1); return 1; }, 16);
        FrameScheduler.FrameChannel right = cancelScheduler.openChannel(new Object(), (tick, quota) -> { calls.add(2); return 1; }, 16);
        left.requestFrame(); right.requestFrame(); left.close();
        assertTrue(cancelRuntime.hasFrame()); cancelRuntime.frame(); assertEquals(Arrays.asList(2), calls);
        assertFalse(cancelRuntime.hasFrame());
    }

    @Test public void batchSnapshotCancellationAndReentryCreateOneLaterBatch() {
        ControlledRuntime runtime = new ControlledRuntime(); FrameScheduler scheduler = runtime.scheduler(64);
        FrameScheduler.FrameChannel[] second = new FrameScheduler.FrameChannel[1];
        FrameScheduler.FrameChannel first = scheduler.openChannel(new Object(), (tick, quota) -> { second[0].cancelPending(); return 1; }, 16);
        second[0] = scheduler.openChannel(new Object(), (tick, quota) -> { fail("cancelled selected channel"); return 1; }, 16);
        first.requestFrame(); second[0].requestFrame(); runtime.frame(); assertFalse(runtime.hasFrame());

        ControlledRuntime reentryRuntime = new ControlledRuntime(); FrameScheduler reentryScheduler = reentryRuntime.scheduler(64);
        List<Long> frames = new ArrayList<>(); FrameScheduler.FrameChannel[] channel = new FrameScheduler.FrameChannel[1];
        channel[0] = reentryScheduler.openChannel(new Object(), (tick, quota) -> {
            frames.add(tick.sequence());
            if (frames.size() < 3) { channel[0].requestFrame(); channel[0].requestFrame(); }
            return 1;
        }, 1);
        channel[0].requestFrame(); reentryRuntime.frame(); assertEquals(Arrays.asList(1L), frames);
        reentryRuntime.drain(); assertEquals(Arrays.asList(1L, 2L, 3L), frames); assertEquals(3, reentryRuntime.frameRequests);
    }

    @Test public void sameOwnerSingleChannelAndGlobalQuotaRotation() {
        ControlledRuntime runtime = new ControlledRuntime(); FrameScheduler scheduler = runtime.scheduler(64); Object owner = new Object();
        FrameScheduler.FrameChannel channel = scheduler.openChannel(owner, (tick, quota) -> 1, 1);
        assertThrows(IllegalStateException.class, () -> scheduler.openChannel(owner, (tick, quota) -> 1, 1));
        channel.close(); scheduler.openChannel(owner, (tick, quota) -> 1, 1);

        ControlledRuntime quotaRuntime = new ControlledRuntime(); FrameScheduler quotaScheduler = quotaRuntime.scheduler(1);
        int[] counts = new int[3]; FrameScheduler.FrameChannel[] channels = new FrameScheduler.FrameChannel[3];
        for (int i = 0; i < channels.length; i++) {
            final int index = i;
            channels[i] = quotaScheduler.openChannel(new Object(), (tick, quota) -> {
                assertEquals(1, quota); counts[index]++; channels[index].requestFrame(); return 1;
            }, 16);
            channels[i].requestFrame();
        }
        for (int i = 0; i < 12; i++) quotaRuntime.frame();
        assertArrayEquals(new int[] {4, 4, 4}, counts);
        quotaScheduler.close(); assertFalse(quotaRuntime.hasFrame());
    }

    @Test public void mailboxAlternationAndCallbackFailureKeepOtherOwners() {
        ControlledRuntime runtime = new ControlledRuntime(); FrameScheduler scheduler = runtime.scheduler(2);
        LifecycleScope scope = runtime.activeScope(); WorkToken token = scope.lease(0).token();
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 2, 32, 2);
        AtomicInteger states = new AtomicInteger(); AtomicInteger commands = new AtomicInteger();
        for (int i = 0; i < 32; i++) box.offerCommand(token, commands::incrementAndGet, () -> {});
        box.offerState(box.newSlot(), token, states::incrementAndGet, () -> {});
        runtime.frame(); assertEquals(1, states.get()); assertEquals(1, commands.get());
        box.close();

        ControlledRuntime failureRuntime = new ControlledRuntime(); FrameScheduler failureScheduler = failureRuntime.scheduler(2);
        AtomicInteger healthy = new AtomicInteger();
        FrameScheduler.FrameChannel bad = failureScheduler.openChannel(new Object(), (tick, quota) -> { throw new IllegalStateException("bad"); }, 1);
        FrameScheduler.FrameChannel good = failureScheduler.openChannel(new Object(), (tick, quota) -> { healthy.incrementAndGet(); return 1; }, 1);
        bad.requestFrame(); good.requestFrame(); failureRuntime.drain();
        assertTrue(bad.isClosed()); assertEquals(1, healthy.get()); assertEquals(1, failureRuntime.errors.size());

        // A throwing close listener must not hide the original drain failure.
        ControlledRuntime closeRuntime = new ControlledRuntime(); FrameScheduler closeScheduler = closeRuntime.scheduler(2);
        FrameScheduler.FrameChannel badClose = closeScheduler.openChannel(new Object(),
                (tick, quota) -> { throw new IllegalStateException("drain failure"); }, 1);
        badClose.onClose(() -> { throw new IllegalStateException("close failure"); });
        badClose.requestFrame();
        closeRuntime.frame();
        assertEquals(1, closeRuntime.errors.size());
        assertEquals("drain failure", closeRuntime.errors.get(0).getMessage());
        assertNotNull(closeRuntime.errors.get(0).getSuppressed());
        assertEquals("close failure", closeRuntime.errors.get(0).getSuppressed()[0].getMessage());
    }

    @Test public void fatalErrorPropagatesAndInjectedBrokenFixtureIsDetected() {
        ControlledRuntime runtime = new ControlledRuntime(); FrameScheduler scheduler = runtime.scheduler(2);
        AtomicInteger healthy = new AtomicInteger();
        FrameScheduler.FrameChannel bad = scheduler.openChannel(new Object(), (tick, quota) -> { throw new OutOfMemoryError("injected"); }, 1);
        FrameScheduler.FrameChannel good = scheduler.openChannel(new Object(), (tick, quota) -> { healthy.incrementAndGet(); return 1; }, 1);
        bad.requestFrame(); good.requestFrame();
        assertThrows(OutOfMemoryError.class, runtime::frame); assertTrue(runtime.hasFrame());
        runtime.frame(); assertEquals(1, healthy.get()); assertFalse(runtime.hasFrame());

        ControlledRuntime fixtureRuntime = new ControlledRuntime(); FrameDriver.Callback callback = nanos -> {};
        fixtureRuntime.request(callback);
        assertThrows(AssertionError.class, () -> fixtureRuntime.request(callback));
        fixtureRuntime.remove(callback); assertThrows(AssertionError.class, fixtureRuntime::frame);
    }

    @Test public void closedChannelRetainsNeitherSharedSchedulerNorCallbacks() throws Exception {
        ControlledRuntime runtime = new ControlledRuntime(); FrameScheduler scheduler = runtime.scheduler(2);
        FrameScheduler.FrameChannel channel = scheduler.openChannel(new Object(), (tick, quota) -> 1, 1);
        AtomicInteger closed = new AtomicInteger(); channel.onClose(closed::incrementAndGet); channel.close();
        for (String name : Arrays.asList("scheduler", "identity", "callback", "closeListener")) {
            java.lang.reflect.Field field = channel.getClass().getDeclaredField(name); field.setAccessible(true);
            assertNull("Owned reference " + name, field.get(channel));
        }
        assertEquals(1, closed.get()); channel.close(); assertEquals(1, closed.get());
    }
}
