package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.junit.Assert.*;
import static io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox.*;

/**
 * Contract for the no-cleanup shorthand overloads.
 *
 * The no-release offerState/offerCommand/offerBatch overloads are pure delegating aliases of the
 * legacy release-bearing entry points: identical admission, state coalescing, command FIFO,
 * rejection, close, stale-token and batch-order semantics. The only difference is that the caller
 * supplies no release callback; the mailbox substitutes one shared no-op, so terminal release still
 * happens exactly once (inherited from the legacy path) while the caller has no cleanup burden.
 */
public final class MailboxConvenienceContractTest {
    private final ControlledRuntime runtime = new ControlledRuntime();
    private final LifecycleScope scope = runtime.activeScope();
    private final FrameScheduler scheduler = runtime.scheduler(64);
    private final WorkToken token = scope.lease(LifecycleScope.ALL).token();
    private final AtomicInteger releases = new AtomicInteger();
    private final List<Integer> commits = new ArrayList<>();

    private BoundedMailbox mailbox(int states, int commands, int quota) {
        return new BoundedMailbox(scope, scheduler, runtime.errors::add, states, commands, quota);
    }

    @Test public void shorthandAdmissionCoalescingCapacityAndFifoMatchLegacy() {
        BoundedMailbox legacy = mailbox(2, 4, 16);
        BoundedMailbox shorthand = mailbox(2, 4, 16);
        Slot legacySlot = legacy.newSlot();
        Slot shortSlot = shorthand.newSlot();
        List<Integer> legacyCommits = new ArrayList<>();
        List<Integer> shortCommits = new ArrayList<>();

        Submission l1 = legacy.offerState(legacySlot, token, () -> legacyCommits.add(1), releases::incrementAndGet);
        Submission s1 = shorthand.offerState(shortSlot, token, () -> shortCommits.add(1));
        Submission l2 = legacy.offerState(legacySlot, token, () -> legacyCommits.add(2), releases::incrementAndGet);
        Submission s2 = shorthand.offerState(shortSlot, token, () -> shortCommits.add(2));
        Submission lc = legacy.offerCommand(token, () -> legacyCommits.add(10), releases::incrementAndGet);
        Submission sc = shorthand.offerCommand(token, () -> shortCommits.add(10));
        Submission lb = legacy.offerBatch(token, Arrays.asList(() -> legacyCommits.add(20), () -> legacyCommits.add(21)), releases::incrementAndGet);
        Submission sb = shorthand.offerBatch(token, Arrays.asList(() -> shortCommits.add(20), () -> shortCommits.add(21)));

        assertEquals(Admission.ACCEPTED, l1.admission());
        assertEquals(l1.admission(), s1.admission());
        assertEquals(Admission.REPLACED, l2.admission());
        assertEquals(l2.admission(), s2.admission());
        assertEquals(Outcome.SUPERSEDED, l1.handle().outcome());
        assertEquals(l1.handle().outcome(), s1.handle().outcome());
        assertEquals(lc.admission(), sc.admission());
        assertEquals(lb.admission(), sb.admission());

        runtime.drain();
        // Drain alternates command/state turns: command 10 precedes the surviving state 2.
        assertEquals(Arrays.asList(10, 2, 20, 21), legacyCommits);
        assertEquals(legacyCommits, shortCommits);
        assertEquals(Outcome.SUPERSEDED, s1.handle().outcome());
        assertEquals(Outcome.COMPLETED, s2.handle().outcome());
        assertEquals(Outcome.COMPLETED, sc.handle().outcome());
        assertEquals(Outcome.COMPLETED, sb.handle().outcome());
        assertEquals(4, releases.get()); // legacy only: one release per terminal item
        assertEquals(0, shorthand.pendingStateCount());
        assertEquals(0, shorthand.pendingCommandCount());
        assertFalse(runtime.hasFrame());

        releases.set(0);
        BoundedMailbox legacyRejects = mailbox(1, 1, 1);
        BoundedMailbox shorthandRejects = mailbox(1, 1, 1);
        Slot legacyRejectSlot = legacyRejects.newSlot();
        Slot shortRejectSlot = shorthandRejects.newSlot();
        List<Admission> legacyAdmissions = new ArrayList<>();
        List<Admission> shortAdmissions = new ArrayList<>();

        legacyAdmissions.add(legacyRejects.offerState(legacyRejectSlot, token, () -> {}, releases::incrementAndGet).admission());
        shortAdmissions.add(shorthandRejects.offerState(shortRejectSlot, token, () -> {}).admission());
        legacyAdmissions.add(legacyRejects.offerState(legacyRejects.newSlot(), token, () -> {}, releases::incrementAndGet).admission());
        shortAdmissions.add(shorthandRejects.offerState(shorthandRejects.newSlot(), token, () -> {}).admission());
        legacyAdmissions.add(legacyRejects.offerState(legacyRejectSlot, token, () -> {}, releases::incrementAndGet).admission());
        shortAdmissions.add(shorthandRejects.offerState(shortRejectSlot, token, () -> {}).admission());
        legacyAdmissions.add(legacyRejects.offerCommand(token, () -> {}, releases::incrementAndGet).admission());
        shortAdmissions.add(shorthandRejects.offerCommand(token, () -> {}).admission());
        legacyAdmissions.add(legacyRejects.offerCommand(token, () -> {}, releases::incrementAndGet).admission());
        shortAdmissions.add(shorthandRejects.offerCommand(token, () -> {}).admission());
        legacyAdmissions.add(legacyRejects.offerBatch(token, Arrays.asList(() -> {}, () -> {}), releases::incrementAndGet).admission());
        shortAdmissions.add(shorthandRejects.offerBatch(token, Arrays.asList(() -> {}, () -> {})).admission());

        assertEquals(legacyAdmissions, shortAdmissions);
        assertEquals(Arrays.asList(Admission.ACCEPTED, Admission.REJECTED_CAPACITY, Admission.REPLACED,
                Admission.ACCEPTED, Admission.REJECTED_CAPACITY, Admission.REJECTED_CAPACITY), shortAdmissions);
        runtime.drain();
        assertFalse(legacyRejects.isClosed());
        assertFalse(shorthandRejects.isClosed());
        assertEquals(3, releases.get()); // legacy only: superseded + state + command
        assertEquals(0, shorthandRejects.pendingStateCount());
        assertEquals(0, shorthandRejects.pendingCommandCount());
    }

    @Test public void shorthandCloseAndPartialBatchFailureMatchLegacy() {
        BoundedMailbox legacy = mailbox(1, 4, 4);
        BoundedMailbox shorthand = mailbox(1, 4, 4);
        Slot legacySlot = legacy.newSlot();
        Slot shortSlot = shorthand.newSlot();
        AtomicBoolean legacyRan = new AtomicBoolean();
        AtomicBoolean shortRan = new AtomicBoolean();
        legacy.offerState(legacySlot, token, () -> legacyRan.set(true), releases::incrementAndGet);
        legacy.offerCommand(token, () -> legacyRan.set(true), releases::incrementAndGet);
        shorthand.offerState(shortSlot, token, () -> shortRan.set(true));
        shorthand.offerCommand(token, () -> shortRan.set(true));
        shorthand.offerBatch(token, Arrays.asList(() -> shortRan.set(true)));

        legacy.close();
        shorthand.close();

        assertTrue(legacy.isClosed());
        assertTrue(shorthand.isClosed());
        assertFalse(legacyRan.get());
        assertFalse(shortRan.get());
        assertEquals(2, releases.get()); // legacy only: exactly once per terminal item
        assertEquals(0, shorthand.pendingStateCount());
        assertEquals(0, shorthand.pendingCommandCount());
        assertEquals(0, scheduler.channelCount());
        assertFalse(runtime.hasFrame());

        releases.set(0); runtime.errors.clear();
        BoundedMailbox failingLegacy = mailbox(1, 1, 1);
        BoundedMailbox failingShorthand = mailbox(1, 1, 1);
        List<Integer> legacyCommits = new ArrayList<>();
        List<Integer> shortCommits = new ArrayList<>();
        WorkHandle lh = failingLegacy.offerBatch(token, Arrays.asList(
                () -> legacyCommits.add(1),
                () -> { throw new IllegalStateException("partial"); },
                () -> legacyCommits.add(3)), releases::incrementAndGet).handle();
        WorkHandle sh = failingShorthand.offerBatch(token, Arrays.asList(
                () -> shortCommits.add(1),
                () -> { throw new IllegalStateException("partial"); },
                () -> shortCommits.add(3))).handle();

        runtime.drain();

        assertEquals(Arrays.asList(1), legacyCommits);
        assertEquals(legacyCommits, shortCommits);
        assertEquals(Outcome.FAILED, lh.outcome());
        assertEquals(lh.outcome(), sh.outcome());
        assertEquals("partial", sh.failure().getMessage());
        assertTrue(failingLegacy.isClosed());
        assertTrue(failingShorthand.isClosed());
        assertEquals(1, releases.get()); // legacy only
        assertEquals(2, runtime.errors.size());
    }

    @Test public void shorthandForeignOwnershipAndBatchValidationMatchLegacy() {
        BoundedMailbox left = mailbox(1, 1, 1);
        BoundedMailbox right = mailbox(1, 1, 1);
        assertEquals(Admission.UNSUPPORTED, left.offerState(right.newSlot(), token, () -> {}).admission());
        WorkToken foreign = runtime.activeScope().lease(0).token();
        assertEquals(Admission.UNSUPPORTED, left.offerCommand(foreign, () -> {}).admission());
        assertEquals(Admission.UNSUPPORTED, left.offerBatch(foreign, Arrays.asList(() -> {})).admission());
        assertEquals(0, releases.get());
        assertFalse(left.isClosed());

        BoundedMailbox box = mailbox(1, 1, 1);
        assertThrows(NullPointerException.class, () -> box.offerBatch(token, null));
        assertThrows(NullPointerException.class, () -> box.offerBatch(token, Arrays.asList(() -> {}, null)));
        assertThrows(IllegalArgumentException.class, () -> box.offerBatch(token, Collections.emptyList()));
        assertThrows(NullPointerException.class, () -> box.offerCommand(token, null));
        assertEquals(Admission.UNSUPPORTED, box.offerState(null, token, () -> {}).admission());
        assertEquals(0, releases.get());
        assertFalse(box.isClosed());
    }

    @Test public void shorthandBatchSnapshotAndStaleTokenMatchLegacy() {
        BoundedMailbox box = mailbox(1, 1, 1);
        List<Runnable> callbacks = new ArrayList<>();
        callbacks.add(() -> commits.add(1));
        WorkHandle handle = box.offerBatch(token, callbacks).handle();
        callbacks.clear();
        callbacks.add(() -> commits.add(99));

        runtime.drain();

        assertEquals(Arrays.asList(1), commits);
        assertEquals(Outcome.COMPLETED, handle.outcome());
        assertEquals(0, releases.get());

        releases.set(0);
        ControlledRuntime staleRuntime = new ControlledRuntime();
        LifecycleScope staleScope = staleRuntime.activeScope();
        FrameScheduler staleScheduler = staleRuntime.scheduler(64);
        WorkToken staleToken = staleScope.lease(LifecycleScope.ALL).token();
        BoundedMailbox legacy = new BoundedMailbox(staleScope, staleScheduler, staleRuntime.errors::add, 1, 2, 2);
        BoundedMailbox shorthand = new BoundedMailbox(staleScope, staleScheduler, staleRuntime.errors::add, 1, 2, 2);
        AtomicBoolean legacyRan = new AtomicBoolean();
        AtomicBoolean shortRan = new AtomicBoolean();
        WorkHandle lh = legacy.offerCommand(staleToken, () -> legacyRan.set(true), releases::incrementAndGet).handle();
        WorkHandle sh = shorthand.offerCommand(staleToken, () -> shortRan.set(true)).handle();

        staleScope.rebind();
        staleRuntime.drain();

        assertEquals(Outcome.STALE, lh.outcome());
        assertEquals(lh.outcome(), sh.outcome());
        assertFalse(legacyRan.get());
        assertFalse(shortRan.get());
        assertEquals(1, releases.get()); // legacy only
    }

    @Test public void shorthandMultiProducerCoalescesAndKeepsPerProducerFifo() throws Exception {
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 64, 1024, 512);
        Slot shared = box.newSlot();
        int producers = 4, perProducer = 200;
        ConcurrentLinkedQueue<String> applied = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int producer = 0; producer < producers; producer++) {
            final int id = producer;
            threads.add(new Thread(() -> {
                try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
                for (int i = 0; i < perProducer; i++) {
                    final int step = i;
                    box.offerState(shared, token, () -> applied.add("state-" + id + "-" + step));
                    box.offerCommand(token, () -> applied.add("cmd-" + id + "-" + step));
                }
                box.offerBatch(token, Arrays.asList(
                        () -> applied.add("batch-" + id + "-0"),
                        () -> applied.add("batch-" + id + "-1"),
                        () -> applied.add("batch-" + id + "-2")));
            }, "mailbox-convenience-producer-" + id));
        }
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread thread : threads) { thread.join(10_000); assertFalse("producer hung", thread.isAlive()); }

        assertEquals(1, runtime.posts);
        assertEquals(1, runtime.queuedSignals());
        runtime.drain();

        List<String> log = new ArrayList<>(applied);
        List<String> states = log.stream().filter(value -> value.startsWith("state-")).collect(Collectors.toList());
        assertEquals("each key applies its latest value exactly once", 1, states.size());
        assertTrue("the survivor is the last admitted offer", states.get(0).endsWith("-" + (perProducer - 1)));
        List<String> commands = log.stream().filter(value -> value.startsWith("cmd-")).collect(Collectors.toList());
        assertEquals(producers * perProducer, commands.size());
        for (int id = 0; id < producers; id++) {
            final int producer = id;
            List<String> mine = commands.stream().filter(value -> value.startsWith("cmd-" + producer + "-")).collect(Collectors.toList());
            assertEquals(perProducer, mine.size());
            for (int i = 0; i < perProducer; i++) assertEquals("cmd-" + producer + "-" + i, mine.get(i));
        }
        for (int id = 0; id < producers; id++) {
            final int producer = id;
            List<String> mine = log.stream().filter(value -> value.startsWith("batch-" + producer + "-")).collect(Collectors.toList());
            assertEquals(Arrays.asList("batch-" + producer + "-0", "batch-" + producer + "-1", "batch-" + producer + "-2"), mine);
        }
        assertEquals(0, releases.get()); // no caller release was ever supplied
        assertEquals(0, box.pendingStateCount());
        assertEquals(0, box.pendingCommandCount());
        assertFalse(runtime.hasFrame());
        assertTrue(runtime.errors.isEmpty());
        box.close();
    }

    @Test public void shorthandSupersededAndCancelledOutcomesMatchLegacy() {
        BoundedMailbox legacy = mailbox(1, 1, 1);
        BoundedMailbox shorthand = mailbox(1, 1, 1);
        Slot legacySlot = legacy.newSlot();
        Slot shortSlot = shorthand.newSlot();
        WorkHandle l1 = legacy.offerState(legacySlot, token, () -> fail("legacy superseded"), releases::incrementAndGet).handle();
        WorkHandle s1 = shorthand.offerState(shortSlot, token, () -> fail("short superseded")).handle();
        Submission l2s = legacy.offerState(legacySlot, token, () -> {}, releases::incrementAndGet);
        Submission s2s = shorthand.offerState(shortSlot, token, () -> {});
        WorkHandle l2 = l2s.handle();
        WorkHandle s2 = s2s.handle();

        assertEquals(Admission.REPLACED, l2s.admission());
        assertEquals(l2s.admission(), s2s.admission());
        assertEquals(Outcome.SUPERSEDED, l1.outcome());
        assertEquals(l1.outcome(), s1.outcome());
        assertEquals(Cancellation.CANCELLED, l2.cancel());
        assertEquals(Cancellation.CANCELLED, s2.cancel());
        assertEquals(Cancellation.ALREADY_TERMINAL, s2.cancel());
        assertEquals(2, releases.get()); // legacy: superseded + cancelled
        assertEquals(0, legacy.pendingStateCount());
        assertEquals(0, shorthand.pendingStateCount());
        assertFalse(runtime.hasFrame());
    }
}
