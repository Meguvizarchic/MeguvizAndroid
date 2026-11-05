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

    @Test public void drainReusesTheSameBufferInstanceAndLeavesNoWorkBehind() {
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

        commits.clear(); releases.set(0);
        BoundedMailbox workBox = mailbox(8, 8, 8);
        BoundedMailbox.Slot[] slots = new BoundedMailbox.Slot[4];
        for (int i = 0; i < slots.length; i++) slots[i] = workBox.newSlot();
        for (int frame = 0; frame < 6; frame++) {
            for (int i = 0; i < slots.length; i++) {
                final int value = i;
                workBox.offerState(slots[value], token, () -> commits.add(value), releases::incrementAndGet);
            }
            workBox.offerCommand(token, () -> commits.add(100), releases::incrementAndGet);
            workBox.offerCommand(token, () -> commits.add(101), releases::incrementAndGet);
            runtime.frame();
            assertTrue("buffer must be empty after a frame", workBox.drainBatch.isEmpty());
        }
        assertEquals(6 * 6, commits.size());
        assertEquals(6 * 6, releases.get());
        assertEquals(0, workBox.pendingStateCount());
        assertEquals(0, workBox.pendingCommandCount());
        assertFalse(runtime.hasFrame());
    }

    @Test public void pausedAndFatalDrainLeaveReusableBufferClean() {
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

        commits.clear(); releases.set(0);
        BoundedMailbox fatalBox = mailbox(1, 4, 4);
        fatalBox.offerCommand(token, () -> { throw new LinkageError("injected"); }, releases::incrementAndGet);
        assertThrows(LinkageError.class, runtime::frame);
        assertTrue("the reused buffer must be cleared even when a fatal error escapes", fatalBox.drainBatch.isEmpty());
        assertTrue(fatalBox.isClosed());
    }

    @Test public void synchronousAndReentrantDrainKeepBufferAndOrderCorrect() throws Exception {
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

        commits.clear(); releases.set(0);
        BoundedMailbox reentrantBox = mailbox(8, 8, 8);
        Method drain = BoundedMailbox.class.getDeclaredMethod("drain", FrameTick.class, int.class);
        drain.setAccessible(true);
        reentrantBox.offerCommand(token, () -> {
            commits.add(1);
            reentrantBox.offerCommand(token, () -> commits.add(3), releases::incrementAndGet);
            try { drain.invoke(reentrantBox, new FrameTick(99, 1L, null, null), 8); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }, releases::incrementAndGet);
        reentrantBox.offerCommand(token, () -> commits.add(2), releases::incrementAndGet);
        runtime.frame();
        assertEquals("every payload must run exactly once", Arrays.asList(1, 3, 2), commits);
        assertEquals(3, releases.get());
        assertTrue(reentrantBox.drainBatch.isEmpty());
        assertEquals(0, reentrantBox.pendingCommandCount());
        reentrantBox.offerCommand(token, () -> commits.add(4), releases::incrementAndGet);
        runtime.frame();
        assertEquals(Arrays.asList(1, 3, 2, 4), commits);
        assertTrue(reentrantBox.drainBatch.isEmpty());
        assertFalse(runtime.hasFrame());
    }

    @Test public void rotationQuotaAndSnapshotGrowthSurviveChannelChanges() throws Exception {
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

        ControlledRuntime growingRuntime = new ControlledRuntime();
        FrameScheduler growing = growingRuntime.scheduler(64);
        List<FrameScheduler.FrameChannel> growingChannels = new ArrayList<>();
        int[] growingCalls = new int[5];
        for (int round = 0; round < growingCalls.length; round++) {
            final int index = round;
            growingChannels.add(growing.openChannel(new Object(), (tick, quota) -> { growingCalls[index]++; return 1; }, 16));
            for (int frame = 0; frame < 4; frame++) {
                for (FrameScheduler.FrameChannel channel : growingChannels) channel.requestFrame();
                growingRuntime.frame();
                assertSnapshotBufferCleared(growing);
            }
        }
        // Channel i appears in round i and then runs in every remaining round: 4*(5-i) turns.
        assertArrayEquals(new int[] {20, 16, 12, 8, 4}, growingCalls);
    }

    @Test public void fatalChannelCallbackAndConsecutiveReentrantDrainsKeepBuffersClean() throws Exception {
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

        commits.clear(); releases.set(0); runtime.errors.clear();
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
