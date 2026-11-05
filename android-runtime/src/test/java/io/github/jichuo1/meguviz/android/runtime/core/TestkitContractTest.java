package io.github.jichuo1.meguviz.android.runtime.core;

import io.github.jichuo1.meguviz.android.testkit.ControlledExecutor;
import io.github.jichuo1.meguviz.android.testkit.TestClock;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.Assert.*;

public class TestkitContractTest {
    @Test public void reverseExecutorCompletionAndOverflowRejection() {
        ControlledRuntime runtime = new ControlledRuntime(); LifecycleScope scope = runtime.activeScope();
        ControlledExecutor executor = new ControlledExecutor(2); List<Integer> committed = new ArrayList<>();
        WorkToken old = scope.lease(LifecycleScope.ALL).token();
        executor.execute(() -> scope.commit(old, () -> committed.add(1)));
        scope.rebind(); WorkToken current = scope.lease(LifecycleScope.ALL).token();
        executor.execute(() -> scope.commit(current, () -> committed.add(2)));
        executor.runAt(1); executor.runAt(0); assertEquals(Arrays.asList(2), committed);
        assertEquals(0, executor.pendingCount());

        ControlledExecutor overflow = new ControlledExecutor(1);
        overflow.execute(() -> fail("cancelled"));
        assertThrows(RejectedExecutionException.class, () -> overflow.execute(() -> {}));
        overflow.cancelAll(); assertEquals(0, overflow.pendingCount());
    }
    @Test public void virtualClockRejectsBackwardsTimeAndOverflow() {
        TestClock clock = new TestClock(); assertEquals(0, clock.nowNanos());
        assertEquals(10, clock.advanceNanos(10));
        assertThrows(IllegalArgumentException.class, () -> clock.advanceNanos(-1));
        clock.advanceNanos(Long.MAX_VALUE - 10);
        assertThrows(IllegalArgumentException.class, () -> clock.advanceNanos(1));
    }
}
