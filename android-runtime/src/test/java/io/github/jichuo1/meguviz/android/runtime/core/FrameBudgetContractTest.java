package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class FrameBudgetContractTest {
    private static final class BudgetDriver implements FrameDriver {
        Callback pending;
        int requests;
        int removals;
        @Override public boolean request(Callback callback) {
            if (pending != null) throw new AssertionError("duplicate frame");
            pending = callback; requests++; return true;
        }
        @Override public void remove(Callback callback) {
            if (pending == callback) { pending = null; removals++; }
        }
        void frame(long time, long deadline, long presentation) {
            Callback callback = pending; assertNotNull(callback); pending = null;
            callback.onFrame(time, deadline, presentation);
        }
    }

    @Test public void legacyAndCopiedBudgetValuesWorkAcrossRefreshRatesAndLateFrames() {
        long[] value = {0};
        FrameDriver.Callback legacy = time -> value[0] = time;
        legacy.onFrame(7L, 8L, 9L);
        assertEquals(7L, value[0]);
        ControlledRuntime owner = new ControlledRuntime();
        FrameScheduler scheduler = owner.scheduler(2);
        scheduler.openChannel(new Object(), (tick, quota) -> {
            assertEquals("unknown", tick.budgetSource());
            assertNull(tick.deadlineNanos());
            assertNull(tick.expectedPresentationNanos());
            assertNull(tick.workBudgetNanos(tick.frameTimeNanos(), 1));
            return 1;
        }, 1).requestFrame();
        owner.frame(); scheduler.close();

        ControlledRuntime copiedOwner = new ControlledRuntime(); BudgetDriver driver = new BudgetDriver();
        FrameScheduler copiedScheduler = new FrameScheduler(copiedOwner, driver, copiedOwner.errors::add, 4);
        List<FrameTick> captured = new ArrayList<>();
        FrameScheduler.FrameChannel channel = copiedScheduler.openChannel(new Object(), (tick, quota) -> {
            captured.add(tick); return 1;
        }, 1);
        long time = 1_000_000_000L;
        long[] periods = {16_666_667L, 11_111_111L, 8_333_333L};
        for (long period : periods) {
            channel.requestFrame(); driver.frame(time, time + period, time + period + 4);
            time += period;
        }
        assertEquals(3, captured.size());
        for (int i = 0; i < periods.length; i++) {
            FrameTick tick = captured.get(i);
            assertEquals(i + 1, tick.sequence());
            assertEquals("platform-vsync", tick.budgetSource());
            assertEquals(Long.valueOf(tick.frameTimeNanos() + periods[i]), tick.deadlineNanos());
            assertEquals(Long.valueOf(tick.frameTimeNanos() + periods[i] + 4), tick.expectedPresentationNanos());
            assertEquals(Long.valueOf(periods[i] - 1_000_000L), tick.workBudgetNanos(tick.frameTimeNanos(), 1_000_000L));
            assertEquals(Long.valueOf(0), tick.workBudgetNanos(tick.deadlineNanos() + 1, 1_000_000L));
            assertThrows(IllegalArgumentException.class, () -> tick.workBudgetNanos(tick.frameTimeNanos(), 0));
        }
        assertEquals(Long.valueOf(Long.MAX_VALUE - 1),
                new FrameTick(1, 0, Long.MAX_VALUE, Long.MAX_VALUE).workBudgetNanos(Long.MIN_VALUE, 1));
        copiedScheduler.close();
    }

    @Test public void oneDriverKeepsReentryCancellationAndOwnerIsolation() {
        ControlledRuntime owner = new ControlledRuntime(); BudgetDriver driver = new BudgetDriver();
        FrameScheduler scheduler = new FrameScheduler(owner, driver, owner.errors::add, 4);
        List<Long> values = new ArrayList<>(); FrameScheduler.FrameChannel[] first = new FrameScheduler.FrameChannel[1];
        first[0] = scheduler.openChannel(new Object(), (tick, quota) -> {
            values.add(tick.sequence()); if (values.size() == 1) first[0].requestFrame(); return 1;
        }, 1);
        FrameScheduler.FrameChannel second = scheduler.openChannel(new Object(), (tick, quota) -> { fail("cancelled channel"); return 1; }, 1);
        first[0].requestFrame(); second.requestFrame(); second.close();
        assertEquals(1, driver.requests); driver.frame(10, 20, 30);
        assertEquals(2, driver.requests); driver.frame(40, 50, 60);
        assertEquals(Arrays.asList(1L, 2L), values); assertNull(driver.pending);
        first[0].requestFrame(); scheduler.close();
        assertNull(driver.pending); assertEquals(1, driver.removals);
    }
}
