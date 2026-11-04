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
 * Multi-threaded producer example, written as a contract test.
 *
 * Pattern: any thread may call offerState/offerCommand/offerBatch directly. The mailbox is the
 * only cross-thread handoff; no executor, unbounded queue or new interface is introduced. The
 * owner thread keeps all frame scheduling, draining and lifecycle calls.
 *
 * Semantics pinned here (all pre-existing, none added by this example):
 * - states coalesce per slot: the latest admitted offer per key is the one applied;
 * - commands and batches keep FIFO admission order, per producer exactly as submitted;
 * - capacity is a hard backpressure bound: new keys/commands beyond capacity are rejected,
 *   replacement of an existing key is always allowed;
 * - release callbacks run exactly once per terminal item and may run on the offering thread;
 * - a failing action closes only its own mailbox and still releases every payload;
 * - close from the owner thread releases pending cross-thread work without running it.
 */
public final class MailboxBackgroundProducerExampleTest {
    private final ControlledRuntime runtime = new ControlledRuntime();
    private final LifecycleScope scope = runtime.activeScope();
    private final FrameScheduler scheduler = runtime.scheduler(64);
    private final WorkToken token = scope.lease(LifecycleScope.ALL).token();
    private final AtomicInteger releases = new AtomicInteger();

    @Test public void backgroundProducersCoalescePerKeyAndKeepPerProducerFifo() throws Exception {
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 64, 1024, 512);
        BoundedMailbox.Slot shared = box.newSlot();
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
                    box.offerState(shared, token, () -> applied.add("state-" + id + "-" + step), releases::incrementAndGet);
                    box.offerCommand(token, () -> applied.add("cmd-" + id + "-" + step), releases::incrementAndGet);
                }
                box.offerBatch(token, Arrays.asList(
                        () -> applied.add("batch-" + id + "-0"),
                        () -> applied.add("batch-" + id + "-1"),
                        () -> applied.add("batch-" + id + "-2")), releases::incrementAndGet);
            }, "mailbox-producer-" + id));
        }
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread thread : threads) { thread.join(10_000); assertFalse("producer hung", thread.isAlive()); }

        // Hundreds of cross-thread submissions still leave exactly one outstanding wakeup.
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
        assertEquals(producers * perProducer * 2 + producers, releases.get());
        assertEquals(0, box.pendingStateCount());
        assertEquals(0, box.pendingCommandCount());
        assertFalse(runtime.hasFrame());
        assertTrue(runtime.errors.isEmpty());
        box.close();
    }

    @Test public void backgroundStormKeepsOneOutstandingWakeupAndBoundedStorage() {
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 2, 2, 2);
        BoundedMailbox.Slot slot = box.newSlot();
        ConcurrentLinkedQueue<Long> applied = new ConcurrentLinkedQueue<>();
        ControlledRuntime.background(() -> {
            for (int i = 0; i < 10_000; i++) {
                final long value = i;
                assertTrue(box.offerState(slot, token, () -> applied.add(value), releases::incrementAndGet).accepted());
                assertEquals(1, box.pendingStateCount());
            }
        });
        assertEquals(1, runtime.posts);
        assertEquals(1, runtime.queuedSignals());
        runtime.drain();
        assertEquals(1, applied.size());
        assertEquals(9999L, (long) applied.peek());
        assertEquals(10_000, releases.get());
        assertFalse(runtime.hasFrame());
        box.close();
    }

    @Test public void backgroundBackpressureRejectsNewKeysButAllowsReplacement() {
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 2, 2, 8);
        BoundedMailbox.Slot first = box.newSlot(), second = box.newSlot(), third = box.newSlot();
        List<Admission> admissions = Collections.synchronizedList(new ArrayList<>());
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        ControlledRuntime.background(() -> {
            admissions.add(box.offerState(first, token, () -> applied.add("first-1"), releases::incrementAndGet).admission());
            admissions.add(box.offerState(second, token, () -> applied.add("second-1"), releases::incrementAndGet).admission());
            admissions.add(box.offerState(third, token, () -> applied.add("third-1"), releases::incrementAndGet).admission());
            admissions.add(box.offerState(first, token, () -> applied.add("first-2"), releases::incrementAndGet).admission());
            admissions.add(box.offerCommand(token, () -> applied.add("cmd-1"), releases::incrementAndGet).admission());
            admissions.add(box.offerCommand(token, () -> applied.add("cmd-2"), releases::incrementAndGet).admission());
            admissions.add(box.offerCommand(token, () -> applied.add("cmd-3"), releases::incrementAndGet).admission());
        });
        assertEquals(Arrays.asList(Admission.ACCEPTED, Admission.ACCEPTED, Admission.REJECTED_CAPACITY,
                Admission.REPLACED, Admission.ACCEPTED, Admission.ACCEPTED, Admission.REJECTED_CAPACITY), admissions);
        runtime.drain();
        assertEquals(Arrays.asList("cmd-1", "first-2", "cmd-2", "second-1"), applied);
        assertEquals(5, releases.get());
        assertFalse(runtime.hasFrame());
        box.close();
    }

    @Test public void backgroundActionFailureClosesMailboxAndStillReleasesEveryPayload() {
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 4, 4, 8);
        AtomicBoolean laterRan = new AtomicBoolean();
        ControlledRuntime.background(() -> {
            box.offerCommand(token, () -> { throw new IllegalArgumentException("boom"); }, releases::incrementAndGet);
            box.offerCommand(token, () -> laterRan.set(true), releases::incrementAndGet);
        });
        runtime.drain();
        assertTrue(box.isClosed());
        assertFalse("work admitted beside a failure must not run", laterRan.get());
        assertEquals(2, releases.get());
        assertEquals("boom", box.lastFailure().getMessage());
        assertEquals(1, runtime.errors.size());
        assertEquals(0, scheduler.channelCount());
    }

    @Test public void ownerCloseReleasesPendingBackgroundWorkExactlyOnce() {
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 4, 256, 8);
        BoundedMailbox.Slot slot = box.newSlot();
        AtomicBoolean ran = new AtomicBoolean();
        ControlledRuntime.background(() -> {
            for (int i = 0; i < 50; i++) {
                box.offerState(slot, token, () -> ran.set(true), releases::incrementAndGet);
                box.offerCommand(token, () -> ran.set(true), releases::incrementAndGet);
            }
        });
        box.close();
        assertEquals(100, releases.get());
        assertFalse("closed work must never run", ran.get());
        assertTrue(box.isClosed());
        assertEquals(0, scheduler.channelCount());
        assertEquals(0, runtime.queuedSignals());
        assertFalse(runtime.hasFrame());
    }
}
