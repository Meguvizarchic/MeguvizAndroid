package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RegistryContractTest {
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private AdapterRegistry.Provider<Runnable> provider(String id, int priority, int major, Set<String> dependencies, Set<String> permissions, Runnable call) {
        return new AdapterRegistry.Provider<>(new AdapterRegistry.Descriptor(id, "1.0", major, 0, priority, set("render"),
                set(), dependencies, permissions, "owner", "session", "session closes owned resources"), call);
    }
    @Test public void equalPriorityConflictsAndExplicitOverrideResolution() {
        AdapterRegistry.Provider<Runnable> a = provider("a", 0, 1, set(), set(), () -> {});
        AdapterRegistry.Provider<Runnable> b = provider("b", 0, 1, set(), set(), () -> {});
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(a, b), set(), set(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(b, a), set(), set(), Collections.emptyMap()));

        AtomicInteger calls = new AtomicInteger();
        AdapterRegistry.Provider<Runnable> overrideA = provider("a", 0, 1, set(), set(), () -> fail("unselected"));
        AdapterRegistry.Provider<Runnable> overrideB = provider("b", 0, 1, set(), set(), calls::incrementAndGet);
        AdapterRegistry<Runnable> registry = new AdapterRegistry<>(Arrays.asList(overrideA, overrideB), set(), set(), Collections.singletonMap("render", "b"));
        registry.bind("render").run(); assertEquals(1, calls.get()); assertEquals("b", registry.selectedDescriptor("render").id);
    }
    @Test public void unknownMajorPermissionExpansionAndDependencyCycleAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(provider("a", 0, 2, set(), set(), () -> {})), set(), set(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(provider("a", 0, 1, set(), set("own-window"), () -> {})), set("observe"), set(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(
                provider("a", 1, 1, set("b"), set(), () -> {}), provider("b", 2, 1, set("a"), set(), () -> {})), set(), set(), Collections.emptyMap()));
    }
    @Test public void missingDependencyDuplicateIdCapabilityAndOverrideAreRejected() {
        AdapterRegistry.Provider<Runnable> a = provider("a", 0, 1, set(), set(), () -> {});
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(a, a), set(), set(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(provider("b", 0, 1, set("absent"), set(), () -> {})), set(), set(), Collections.emptyMap()));

        AdapterRegistry.Descriptor descriptor = new AdapterRegistry.Descriptor("a", "1", 1, 0, 0, set("render"), set("theme"), set(), set(), "owner", "session", "caller");
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(new AdapterRegistry.Provider<Runnable>(descriptor, () -> {})), set(), set(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry<>(Arrays.asList(provider("a", 0, 1, set(), set(), () -> {})), set(), set(), Collections.singletonMap("missing", "a")));
    }
    @Test public void frozenTypedHandleRunsTenThousandTimesWithoutCandidateTraversal() {
        AtomicInteger calls = new AtomicInteger();
        AdapterRegistry<Runnable> registry = new AdapterRegistry<>(Arrays.asList(provider("a", 1, 1, set(), set(), calls::incrementAndGet),
                provider("b", 0, 1, set(), set(), () -> fail("lower priority"))), set(), set(), Collections.emptyMap());
        Runnable handle = registry.bind("render"); long before = registry.candidateChecks();
        for (int i = 0; i < 10_000; i++) handle.run();
        assertEquals(10_000, calls.get()); assertEquals(before, registry.candidateChecks()); assertEquals(2, before);
    }
    @Test public void descriptorInputsAreDefensivelyCopied() {
        Set<String> input = set("render");
        AdapterRegistry.Descriptor descriptor = new AdapterRegistry.Descriptor("a", "1", 1, 0, 0, input, set(), set(), set(), "owner", "session", "caller");
        input.clear(); assertEquals(set("render"), descriptor.provides);
        assertThrows(UnsupportedOperationException.class, () -> descriptor.provides.add("other"));
    }
}
