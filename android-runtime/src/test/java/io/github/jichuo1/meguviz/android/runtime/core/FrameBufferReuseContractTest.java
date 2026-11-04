package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * Contract tests for the reused per-frame snapshot buffers in BoundedMailbox.drain and
 * FrameScheduler.onFrame. They pin the invariants the reuse relies on:
 * the instance buffer is never shared by a reentrant drain/delivery, it is cleared before
 * any wakeup or reentrant frame can observe it again, and rotation plus quota survive
 * channels closing or opening inside a frame callback.
 */
public class FrameBufferReuseContractTest {
    private final ControlledRuntime runtime = new ControlledRuntime();
    private final LifecycleScope scope = runtime.activeScope();
    private final FrameScheduler scheduler = runtime.scheduler(64);
    private final WorkToken token = scope.lease(LifecycleScope.ALL).token();
    private final AtomicInteger releases = new AtomicInteger();
    private final List<Integer> commits = new ArrayList<>();

    private BoundedMailbox mailbox(int states, int commands, int quota) {
        return new BoundedMailbox(scope, scheduler, runtime.errors::add, states, commands, quota);
    }

    @Test public void drainReusesTheSameBufferInstanceAcrossFrames() {
        BoundedMailbox box = mailbox(4, 4, 8);
        List<?> buffer = box.drainBatch;
        box.offerCommand(token, () -> commits.add(1), releases::incrementAndGet);
        runtime.frame();
        assertSame("the extraction batch must be reused, not reallocated", buffer, box.drainBatch);
        assertTrue("the reused buffer must be empty between frames", box.drainBatch.isEmpty());
        box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet);
        runtime.frame();
        assertSame(buffer, box.drainBatch);
        assertTrue(box.drainBatch.isEmpty());
        assertEquals(Arrays.asList(1, 2), commits);
        assertEquals(2, releases.get());
        assertFalse(runtime.hasFrame());
    }

    @Test public void drainReusedBufferLeavesNoWorkBehindAcrossFrames() {
        BoundedMailbox box = mailbox(8, 8, 8);
        BoundedMailbox.Slot[] slots = new BoundedMailbox.Slot[4];
        for (int i = 0; i < slots.length; i++) slots[i] = box.newSlot();
        for (int frame = 0; frame < 6; frame++) {
            for (int i = 0; i < slots.length; i++) {
                final int value = i;
                box.offerState(slots[value], token, () -> commits.add(value), releases::incrementAndGet);
            }
            box.offerCommand(token, () -> commits.add(100), releases::incrementAndGet);
            box.offerCommand(token, () -> commits.add(101), releases::incrementAndGet);
            runtime.frame();
            assertTrue("buffer must be empty after a frame", box.drainBatch.isEmpty());
        }
        assertEquals(6 * 6, commits.size());
        assertEquals(6 * 6, releases.get());
        assertEquals(0, box.pendingStateCount());
        assertEquals(0, box.pendingCommandCount());
        assertFalse(runtime.hasFrame());
    }

    @Test public void pausedMailboxDuringDrainLeavesReusableBufferClean() {
        BoundedMailbox box = mailbox(4, 4, 8); BoundedMailbox.Slot slot = box.newSlot();
        box.offerCommand(token, () -> box.setPaused(true), releases::incrementAndGet);
        box.offerState(slot, token, () -> commits.add(2), releases::incrementAndGet);
        runtime.frame();
        assertTrue(box.drainBatch.isEmpty());
        box.setPaused(false);
        box.offerCommand(token, () -> commits.add(3), releases::incrementAndGet);
        runtime.drain();
        // The command/state alternation carried across the pause, so the command goes first.
        assertEquals(Arrays.asList(3, 2), commits);
        assertTrue(box.drainBatch.isEmpty());
        assertFalse(runtime.hasFrame());
    }

    @Test public void fatalErrorDuringDrainStillClearsReusedBuffer() {
        BoundedMailbox box = mailbox(1, 4, 4);
        box.offerCommand(token, () -> { throw new LinkageError("injected"); }, releases::incrementAndGet);
        assertThrows(LinkageError.class, runtime::frame);
        assertTrue("the reused buffer must be cleared even when a fatal error escapes", box.drainBatch.isEmpty());
        assertTrue(box.isClosed());
    }

    /**
     * A driver that delivers the frame synchronously inside request() is outside the FrameDriver
     * contract, but it must still not corrupt or double-use the reused buffers.
     */
    @Test public void synchronousDriverDeliveryKeepsBufferAndOrderCorrect() {
        SynchronousDriver driver = new SynchronousDriver();
        FrameScheduler local = new FrameScheduler(runtime, driver, runtime.errors::add, 64);
        BoundedMailbox box = new BoundedMailbox(scope, local, runtime.errors::add, 8, 8, 8);
        driver.armed = true;
        box.offerCommand(token, () -> {
            commits.add(1);
            box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet);
        }, releases::incrementAndGet);
        assertEquals(Arrays.asList(1), commits);
        assertTrue("the next frame must stay pending, not be delivered inline", driver.hasFrame());
        assertTrue(box.drainBatch.isEmpty());
        driver.tick();
        assertEquals(Arrays.asList(1, 2), commits);
        assertFalse(driver.hasFrame());
        assertTrue(box.drainBatch.isEmpty());
        box.offerCommand(token, () -> commits.add(3), releases::incrementAndGet);
        driver.tick();
        assertEquals(Arrays.asList(1, 2, 3), commits);
        assertEquals(3, releases.get());
        box.close(); local.close();
    }

    /**
     * White-box: drives the reentrant drain path that the public contract makes unreachable, to
     * prove the instance buffer falls back to a local list instead of being corrupted mid-iteration.
     */
    @Test public void reentrantDrainFallsBackToLocalBatchWithoutCorruptingTheInstanceBuffer() throws Exception {
        BoundedMailbox box = mailbox(8, 8, 8);
        Method drain = BoundedMailbox.class.getDeclaredMethod("drain", FrameTick.class, int.class);
        drain.setAccessible(true);
        box.offerCommand(token, () -> {
            commits.add(1);
            box.offerCommand(token, () -> commits.add(3), releases::incrementAndGet);
            try { drain.invoke(box, new FrameTick(99, 1L, null, null), 8); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }, releases::incrementAndGet);
        box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet);
        runtime.frame();
        assertEquals("every payload must run exactly once", Arrays.asList(1, 3, 2), commits);
        assertEquals(3, releases.get());
        assertTrue(box.drainBatch.isEmpty());
        assertEquals(0, box.pendingCommandCount());
        box.offerCommand(token, () -> commits.add(4), releases::incrementAndGet);
        runtime.frame();
        assertEquals(Arrays.asList(1, 3, 2, 4), commits);
        assertTrue(box.drainBatch.isEmpty());
        assertFalse(runtime.hasFrame());
    }

    @Test public void rotationAndQuotaSurviveChannelCloseAndOpenInsideFrame() throws Exception {
        ControlledRuntime local = new ControlledRuntime();
        FrameScheduler rotating = local.scheduler(64);
        int[] calls = new int[4];
        FrameScheduler.FrameChannel[] channels = new FrameScheduler.FrameChannel[4];
        channels[0] = rotating.openChannel("A", (tick, quota) -> {
            calls[0]++;
            channels[1].close();
            if (channels[3] == null) {
                channels[3] = rotating.openChannel("D", (t2, q2) -> { calls[3]++; return 1; }, 16);
                channels[3].requestFrame();
            }
            return 1;
        }, 16);
        channels[1] = rotating.openChannel("B", (tick, quota) -> { calls[1]++; return 1; }, 16);
        channels[2] = rotating.openChannel("C", (tick, quota) -> { calls[2]++; return 1; }, 16);
        channels[0].requestFrame(); channels[1].requestFrame(); channels[2].requestFrame();
        for (int frame = 0; frame < 6; frame++) {
            channels[0].requestFrame(); channels[2].requestFrame();
            if (channels[3] != null) channels[3].requestFrame();
            local.frame();
            assertSnapshotBufferCleared(rotating);
        }
        assertEquals("a channel closed mid-frame must never run again", 0, calls[1]);
        assertEquals(6, calls[0]);
        assertEquals(6, calls[2]);
        assertEquals(5, calls[3]);
    }

    @Test public void snapshotBufferGrowsWhenChannelsAppearAndKeepsRotation() throws Exception {
        ControlledRuntime local = new ControlledRuntime();
        FrameScheduler growing = local.scheduler(64);
        List<FrameScheduler.FrameChannel> channels = new ArrayList<>();
        int[] calls = new int[5];
        for (int round = 0; round < calls.length; round++) {
            final int index = round;
            channels.add(growing.openChannel(new Object(), (tick, quota) -> { calls[index]++; return 1; }, 16));
            for (int frame = 0; frame < 4; frame++) {
                for (FrameScheduler.FrameChannel channel : channels) channel.requestFrame();
                local.frame();
                assertSnapshotBufferCleared(growing);
            }
        }
        // Channel i appears in round i and then runs in every remaining round: 4*(5-i) turns.
        assertArrayEquals(new int[] {20, 16, 12, 8, 4}, calls);
    }

    @Test public void fatalChannelCallbackClearsSchedulerSnapshotBuffer() throws Exception {
        ControlledRuntime local = new ControlledRuntime();
        FrameScheduler failing = local.scheduler(2);
        AtomicInteger healthy = new AtomicInteger();
        FrameScheduler.FrameChannel bad = failing.openChannel(new Object(), (tick, quota) -> { throw new OutOfMemoryError("injected"); }, 1);
        FrameScheduler.FrameChannel good = failing.openChannel(new Object(), (tick, quota) -> { healthy.incrementAndGet(); return 1; }, 1);
        bad.requestFrame(); good.requestFrame();
        assertThrows(OutOfMemoryError.class, local::frame);
        assertSnapshotBufferCleared(failing);
        assertTrue("the unentered owner must stay scheduled", local.hasFrame());
        local.frame();
        assertEquals(1, healthy.get());
        assertSnapshotBufferCleared(failing);
    }

    /**
     * Regression: two consecutive reentrant drains from different outer actions. The first
     * reentrant return must not reset the draining flag, or the second reentrant call reuses and
     * clears the instance buffer the outer loop is still walking (ConcurrentModificationException
     * and lost items). Green on HEAD and on the fixed sources; red on an unconditional
     * draining=false exit.
     */
    @Test public void consecutiveReentrantDrainsDoNotResetDrainingForTheOuterBatch() throws Exception {
        BoundedMailbox box = mailbox(8, 8, 8);
        Method drain = BoundedMailbox.class.getDeclaredMethod("drain", FrameTick.class, int.class);
        drain.setAccessible(true);
        box.offerCommand(token, () -> {
            commits.add(1);
            try { drain.invoke(box, new FrameTick(99, 1L, null, null), 8); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }, releases::incrementAndGet);
        box.offerCommand(token, () -> {
            commits.add(2);
            try { drain.invoke(box, new FrameTick(98, 2L, null, null), 8); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }, releases::incrementAndGet);
        box.offerCommand(token, () -> commits.add(3), releases::incrementAndGet);
        runtime.frame();
        assertEquals(Arrays.asList(1, 2, 3), commits);
        assertTrue(runtime.errors.isEmpty());
        assertEquals(3, releases.get());
        assertTrue(box.drainBatch.isEmpty());
        assertFalse(runtime.hasFrame());
    }

    @Test public void schedulerSnapshotEntriesAreReusedAcrossFrames() throws Exception {
        ControlledRuntime local = new ControlledRuntime();
        FrameScheduler rotating = local.scheduler(64);
        List<FrameScheduler.FrameChannel> channels = new ArrayList<>();
        int[] calls = new int[2];
        for (int index = 0; index < calls.length; index++) {
            final int id = index;
            channels.add(rotating.openChannel(new Object(), (tick, quota) -> { calls[id]++; return 1; }, 16));
        }
        for (FrameScheduler.FrameChannel channel : channels) channel.requestFrame();
        local.frame();
        List<Object> first = snapshotEntryIdentities(rotating);
        for (int frame = 0; frame < 3; frame++) {
            for (FrameScheduler.FrameChannel channel : channels) channel.requestFrame();
            local.frame();
            assertSnapshotBufferCleared(rotating);
        }
        assertEquals("snapshot entries must be the same reused instances", first, snapshotEntryIdentities(rotating));
        assertArrayEquals(new int[] {4, 4}, calls);
    }

    private static List<Object> snapshotEntryIdentities(FrameScheduler scheduler) throws Exception {
        java.lang.reflect.Field field = FrameScheduler.class.getDeclaredField("batchEntries");
        field.setAccessible(true);
        Object[] entries = (Object[]) field.get(scheduler);
        List<Object> identities = new ArrayList<>();
        for (Object entry : entries) identities.add(entry);
        return identities;
    }

    private static void assertSnapshotBufferCleared(FrameScheduler scheduler) throws Exception {
        java.lang.reflect.Field field = FrameScheduler.class.getDeclaredField("batchEntries");
        field.setAccessible(true);
        Object[] entries = (Object[]) field.get(scheduler);
        for (Object entry : entries) {
            if (entry == null) continue;
            java.lang.reflect.Field channel = entry.getClass().getDeclaredField("channel");
            channel.setAccessible(true);
            assertNull("a snapshot entry must not retain a channel after the frame", channel.get(entry));
        }
    }

    private static final class SynchronousDriver implements FrameDriver {
        private FrameDriver.Callback pending;
        private boolean armed;
        private long nanos;
        @Override public boolean request(FrameDriver.Callback callback) {
            if (armed) { armed = false; nanos += 16_666_667L; callback.onFrame(nanos); return true; }
            if (pending != null) throw new AssertionError("Duplicate platform frame");
            pending = callback; return true;
        }
        @Override public void remove(FrameDriver.Callback callback) { if (pending == callback) pending = null; }
        void tick() {
            FrameDriver.Callback callback = pending;
            if (callback == null) throw new AssertionError("No frame requested");
            pending = null; nanos += 16_666_667L; callback.onFrame(nanos);
        }
        boolean hasFrame() { return pending != null; }
    }
}
