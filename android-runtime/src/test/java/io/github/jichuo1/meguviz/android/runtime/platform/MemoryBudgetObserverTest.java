package io.github.jichuo1.meguviz.android.runtime.platform;

import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class MemoryBudgetObserverTest {
    @Test public void fullVersionIsRequiredAndSnapshotKeepsValuesIndependent() {
        assertFalse(MemoryBudgetObserver.versionSupported(27, Integer.MAX_VALUE));
        assertFalse(MemoryBudgetObserver.versionSupported(36, 3_700_002));
        assertFalse(MemoryBudgetObserver.versionSupported(37, 3_700_000));
        assertFalse(MemoryBudgetObserver.versionSupported(37, 3_700_001));
        assertFalse(MemoryBudgetObserver.versionSupported(38, -1));
        assertTrue(MemoryBudgetObserver.versionSupported(37, 3_700_002));
        assertTrue(MemoryBudgetObserver.versionSupported(38, 3_800_000));

        MemoryBudgetObserver.Snapshot value = new MemoryBudgetObserver.Snapshot(10, 20, 30, 40);
        assertEquals(10, value.processBudgetBytes);
        assertEquals(20, value.processUsageBytes);
        assertEquals(30, value.packageBudgetBytes);
        assertEquals(40, value.packageUsageBytes);
    }

    @Test public void observationApiCannotMutateBudgets() {
        for (Method method : MemoryBudgetObserver.class.getMethods()) {
            assertFalse(method.getName().startsWith("set") || method.getName().startsWith("clear"));
        }
    }
}
