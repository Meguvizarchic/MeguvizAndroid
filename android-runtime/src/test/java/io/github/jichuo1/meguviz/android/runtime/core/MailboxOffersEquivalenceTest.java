package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox.*;

/**
 * Batched admission ({@link BoundedMailbox.Offers}) against the per-item offers.
 *
 * Every equivalence test drives two independent rigs (own dispatcher, scheduler, scope, mailbox)
 * with the same operation script: one through offerState/offerCommand, one through a batch. The
 * full observable trace must be identical: admissions, sequence numbers, outcomes, release and
 * execution order, posted wakeups, platform frame requests and pending counts.
 */
public final class MailboxOffersEquivalenceTest {
    /** One isolated runtime with a mailbox and a trace of everything observable. */
    private static final class Rig {
        final ControlledRuntime runtime = new ControlledRuntime();
        final LifecycleScope scope = runtime.activeScope();
        final FrameScheduler scheduler = runtime.scheduler(64);
        final WorkToken token = scope.lease(LifecycleScope.ALL).token();
        final WorkToken foreignToken = runtime.activeScope().lease(LifecycleScope.ALL).token();
        final BoundedMailbox box;
        final Slot[] slots;
        final Slot foreignSlot;
        /** Mailbox-driven events (release and run callbacks) in the order they happened. */
        final List<String> trace = new ArrayList<>();
        /** Admission results, recorded by the test in offer order; kept apart from the events. */
        final List<String> admissions = new ArrayList<>();
        final List<WorkHandle> handles = new ArrayList<>();
        Rig(int states, int commands, int slotCount) {
            box = new BoundedMailbox(scope, scheduler, runtime.errors::add, states, commands, 4);
            slots = new Slot[slotCount];
            for (int i = 0; i < slotCount; i++) slots[i] = box.newSlot();
            BoundedMailbox other = new BoundedMailbox(scope, runtime.scheduler(4), runtime.errors::add, 1, 1, 1);
            foreignSlot = other.newSlot();
        }
        Runnable action(String label) { return () -> trace.add("run:" + label); }
        Runnable release(String label, boolean failing) {
            return () -> {
                trace.add("release:" + label);
                if (failing) throw new IllegalStateException("release " + label);
            };
        }
        void record(Admission admission, WorkHandle handle) {
            admissions.add("admit:" + admission + (handle == null ? "" : "#" + handle.sequence()));
            handles.add(handle);
        }
        List<String> finish() {
            // Wakeups still queued (including stale ones a failure path left behind) are observable.
            int queued = runtime.queuedSignals();
            runtime.drain();
            List<String> result = new ArrayList<>(admissions);
            result.addAll(trace);
            for (WorkHandle handle : handles) result.add("outcome:" + (handle == null ? "-" : handle.sequence() + ":" + handle.outcome()));
            result.add("queued:" + queued);
            result.add("posts:" + runtime.posts);
            result.add("frames:" + runtime.frameRequests);
            result.add("closed:" + box.isClosed());
            if (!box.isClosed()) {
                result.add("pendingStates:" + box.pendingStateCount());
                result.add("pendingCommands:" + box.pendingCommandCount());
            }
            result.add("errors:" + runtime.errors.size());
            return result;
        }
    }

    /** One scripted offer; kind 0 = state, 1 = command, 2 = foreign slot, 3 = foreign token. */
    private static final class Op {
        final int kind, slot; final String label; final int releaseMode; // 0 shorthand, 1 release, 2 failing release
        Op(int kind, int slot, String label, int releaseMode) {
            this.kind = kind; this.slot = slot; this.label = label; this.releaseMode = releaseMode;
        }
    }

    private static void sequential(Rig rig, List<Op> ops) {
        for (Op op : ops) {
            Submission submission;
            Runnable action = rig.action(op.label);
            Runnable release = op.releaseMode == 0 ? null : rig.release(op.label, op.releaseMode == 2);
            switch (op.kind) {
                case 0: submission = release == null ? rig.box.offerState(rig.slots[op.slot], rig.token, action)
                        : rig.box.offerState(rig.slots[op.slot], rig.token, action, release); break;
                case 1: submission = release == null ? rig.box.offerCommand(rig.token, action)
                        : rig.box.offerCommand(rig.token, action, release); break;
                case 2: submission = rig.box.offerState(rig.foreignSlot, rig.token, action); break;
                default: submission = rig.box.offerCommand(rig.foreignToken, action); break;
            }
            rig.record(submission.admission(), submission.handle());
        }
    }

    private static void batched(Rig rig, Offers offers, List<Op> ops) {
        for (Op op : ops) {
            Runnable action = rig.action(op.label);
            Runnable release = op.releaseMode == 0 ? null : rig.release(op.label, op.releaseMode == 2);
            switch (op.kind) {
                case 0: if (release == null) offers.state(rig.slots[op.slot], rig.token, action);
                        else offers.state(rig.slots[op.slot], rig.token, action, release); break;
                case 1: if (release == null) offers.command(rig.token, action);
                        else offers.command(rig.token, action, release); break;
                case 2: offers.state(rig.foreignSlot, rig.token, action); break;
                default: offers.command(rig.foreignToken, action); break;
            }
        }
        int accepted = offers.submit();
        int counted = 0;
        for (int i = 0; i < offers.size(); i++) {
            rig.record(offers.admission(i), offers.handle(i));
            if (offers.handle(i) != null) counted++;
        }
        assertEquals(counted, accepted);
    }

    private static List<Op> script(Random random, int length, int slotCount, boolean failures) {
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int roll = random.nextInt(20);
            int kind = roll < 10 ? 0 : roll < 18 ? 1 : roll == 18 ? 2 : 3;
            int releaseMode = random.nextInt(3) == 0 ? 0 : 1;
            if (failures && random.nextInt(25) == 0) releaseMode = 2;
            ops.add(new Op(kind, random.nextInt(slotCount), "o" + i, releaseMode));
        }
        return ops;
    }

    /**
     * Sequential equivalence, not a linearizability claim: the two rigs replay the same operation
     * list without another producer interleaving inside a batch. Concurrent admission is covered
     * separately by {@link #concurrentBatchesStayContiguousFifoAndExactlyOnce()}.
     */
    @Test public void randomizedBatchesMatchSequentialOffers() {
        randomized(false);
    }

    @Test public void randomizedBackgroundBatchesMatchSequentialOffers() {
        randomized(true);
    }

    private static void randomized(boolean background) {
        for (int seed = 0; seed < 400; seed++) {
            Random random = new Random(seed);
            int slotCount = 1 + random.nextInt(6);
            int states = 1 + random.nextInt(slotCount + 1);
            int commands = 1 + random.nextInt(12);
            boolean pausedPhase = random.nextInt(4) == 0;
            boolean failures = random.nextInt(3) == 0;
            Rig perItem = new Rig(states, commands, slotCount);
            Rig batch = new Rig(states, commands, slotCount);
            ExecutorService producer = background ? Executors.newSingleThreadExecutor() : null;
            Offers offers = background ? await(producer.submit(batch.box::offers)) : batch.box.offers();
            int rounds = 1 + random.nextInt(4);
            for (int round = 0; round < rounds; round++) {
                List<Op> ops = script(random, 1 + random.nextInt(24), slotCount, failures);
                boolean pause = pausedPhase && round == 1;
                if (pause && !perItem.box.isClosed()) perItem.box.setPaused(true);
                if (pause && !batch.box.isClosed()) batch.box.setPaused(true);
                if (background) {
                    // The batch is producer-confined, so every round runs it on one thread; the
                    // per-item rig uses a fresh thread, which is equally off the owner.
                    ControlledRuntime.background(() -> sequential(perItem, ops));
                    await(producer.submit(() -> batched(batch, offers, ops)));
                } else {
                    sequential(perItem, ops);
                    batched(batch, offers, ops);
                }
                if (pause && !perItem.box.isClosed()) perItem.box.setPaused(false);
                if (pause && !batch.box.isClosed()) batch.box.setPaused(false);
                // Wakeups are not pumped between rounds, so later rounds also run with one pending.
                if (random.nextBoolean()) { perItem.runtime.drain(); batch.runtime.drain(); }
            }
            if (producer != null) producer.shutdownNow();
            assertEquals("seed " + seed + (background ? " (background)" : ""), perItem.finish(), batch.finish());
        }
    }

    private static <T> T await(Future<T> future) {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException error) { throw new AssertionError(error.getCause()); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    @Test public void failingSupersedeReleaseClosesTheMailboxBeforeTheRestOfTheBatch() {
        Rig perItem = new Rig(4, 4, 2);
        Rig batch = new Rig(4, 4, 2);
        List<Op> ops = List.of(
                new Op(0, 0, "a", 2),   // accepted; its release throws when superseded
                new Op(1, 0, "c1", 1),
                new Op(0, 0, "b", 1),   // supersedes a: release throws, mailbox fails closed
                new Op(1, 0, "c2", 1),  // must be REJECTED_CLOSED, never admitted
                new Op(0, 1, "d", 0));
        sequential(perItem, ops);
        batched(batch, batch.box.offers(), ops);
        List<String> expected = perItem.finish();
        assertEquals(expected, batch.finish());
        assertTrue(expected.contains("admit:REJECTED_CLOSED"));
        assertTrue(expected.contains("closed:true"));
        assertEquals(1, perItem.runtime.errors.size());
    }

    @Test public void ownerThreadWakeupFailureClosesBeforeTheRestOfTheBatch() {
        // On the owner thread the wakeup runs inline and requests the frame; when the platform
        // rejects it the mailbox fails closed right there, so later offers are REJECTED_CLOSED,
        // exactly as per item, instead of being admitted and then failed.
        Rig perItem = new Rig(4, 4, 2);
        Rig batch = new Rig(4, 4, 2);
        perItem.runtime.rejectFrame = true; batch.runtime.rejectFrame = true;
        List<Op> ops = List.of(new Op(1, 0, "c1", 1), new Op(0, 0, "s", 1), new Op(1, 0, "c2", 0));
        sequential(perItem, ops);
        batched(batch, batch.box.offers(), ops);
        perItem.runtime.rejectFrame = false; batch.runtime.rejectFrame = false;
        List<String> expected = perItem.finish();
        assertEquals(expected, batch.finish());
        assertEquals(List.of("admit:ACCEPTED#1", "admit:REJECTED_CLOSED", "admit:REJECTED_CLOSED"), expected.subList(0, 3));
    }

    @Test public void pausedCommandsAreReleasedInStagingOrderOnTheProducer() {
        Rig perItem = new Rig(4, 8, 1);
        Rig batch = new Rig(4, 8, 1);
        perItem.box.setPaused(true); batch.box.setPaused(true);
        List<Op> ops = List.of(new Op(1, 0, "c1", 1), new Op(0, 0, "s", 1), new Op(1, 0, "c2", 0), new Op(1, 0, "c3", 1));
        sequential(perItem, ops);
        batched(batch, batch.box.offers(), ops);
        perItem.box.setPaused(false); batch.box.setPaused(false);
        List<String> expected = perItem.finish();
        assertEquals(expected, batch.finish());
        assertEquals(List.of("admit:ACCEPTED#1", "admit:ACCEPTED#2", "admit:ACCEPTED#3", "admit:ACCEPTED#4",
                "release:c1", "release:c3", "run:s", "release:s"), expected.subList(0, 8));
    }

    @Test public void backgroundBatchPostsOneWakeupAndRunsReleasesOutsideTheLock() throws Exception {
        Rig rig = new Rig(8, 256, 4);
        AtomicReference<Offers> holder = new AtomicReference<>();
        AtomicBoolean releaseSawLockFree = new AtomicBoolean();
        ControlledRuntime.background(() -> {
            Offers offers = rig.box.offers();
            holder.set(offers);
            offers.state(rig.slots[0], rig.token, rig.action("first"), () -> {
                // The release of a superseded item runs on the producer; another thread must be
                // able to take the mailbox lock meanwhile (a held monitor would time out here).
                AtomicBoolean admitted = new AtomicBoolean();
                Thread other = new Thread(() -> admitted.set(rig.box.offerCommand(rig.token, () -> { }).accepted()));
                other.start();
                try { other.join(2000); } catch (InterruptedException error) { throw new AssertionError(error); }
                releaseSawLockFree.set(admitted.get());
            });
            offers.state(rig.slots[0], rig.token, rig.action("second"));
            for (int i = 0; i < 100; i++) offers.command(rig.token, rig.action("c" + i));
            assertEquals(102, offers.submit());
        });
        assertTrue(releaseSawLockFree.get());
        assertEquals(1, rig.runtime.posts);
        assertEquals(1, rig.runtime.queuedSignals());
        rig.runtime.drain();
        assertFalse(rig.trace.contains("run:first"));
        assertTrue(rig.trace.contains("run:second"));
        assertEquals(0, rig.box.pendingCommandCount());
        assertNotNull(holder.get());
    }

    @Test public void stagingFailsFastAndTheBatchIsConfinedToItsProducer() {
        Rig rig = new Rig(2, 2, 1);
        Offers offers = rig.box.offers();
        assertThrows(NullPointerException.class, () -> offers.command(rig.token, null));
        assertThrows(NullPointerException.class, () -> offers.state(rig.slots[0], null, () -> { }));
        assertEquals(0, offers.size());
        // Like offerState, a foreign or null slot is UNSUPPORTED before any other argument is read.
        offers.state(rig.foreignSlot, null, null);
        offers.state(null, rig.token, () -> { });
        assertThrows(IllegalStateException.class, () -> offers.admission(0));
        assertEquals(0, offers.submit());
        assertEquals(Admission.UNSUPPORTED, offers.admission(0));
        assertEquals(Admission.UNSUPPORTED, offers.admission(1));
        assertNull(offers.handle(0));
        assertThrows(IndexOutOfBoundsException.class, () -> offers.admission(2));
        assertThrows(IllegalStateException.class, offers::submit);
        // Staging again starts a new batch.
        offers.command(rig.token, rig.action("x"));
        assertEquals(1, offers.size());
        assertThrows(IllegalStateException.class, () -> offers.handle(0));
        AtomicReference<Throwable> foreign = new AtomicReference<>();
        ControlledRuntime.background(() -> {
            try { offers.submit(); } catch (Throwable error) { foreign.set(error); }
        });
        assertTrue(foreign.get() instanceof IllegalStateException);
        assertEquals(0, rig.box.pendingCommandCount());
        offers.clear();
        assertEquals(0, offers.size());
        assertEquals(0, offers.submit());
        // Growth past the initial capacity keeps staging order.
        Rig wide = new Rig(1, 64, 1);
        Offers many = wide.box.offers();
        for (int i = 0; i < 40; i++) many.command(wide.token, wide.action("w" + i));
        assertEquals(40, many.submit());
        for (int i = 0; i < 40; i++) assertEquals(i + 1, many.handle(i).sequence());
        wide.runtime.drain();
        for (int i = 0; i < 40; i++) assertEquals("run:w" + i, wide.trace.get(i));
    }

    @Test public void rejectionsShareImmutableSubmissions() {
        Rig rig = new Rig(1, 1, 2);
        assertTrue(rig.box.offerCommand(rig.token, () -> { }).accepted());
        Submission full = rig.box.offerCommand(rig.token, () -> { });
        assertSame(full, rig.box.offerCommand(rig.token, () -> { }, () -> { }));
        assertEquals(Admission.REJECTED_CAPACITY, full.admission());
        assertNull(full.handle());
        Submission foreign = rig.box.offerState(rig.foreignSlot, rig.token, () -> { });
        assertSame(foreign, rig.box.offerCommand(rig.foreignToken, () -> { }));
        assertEquals(Admission.UNSUPPORTED, foreign.admission());
        rig.box.close();
        Submission closed = rig.box.offerCommand(rig.token, () -> { });
        assertSame(closed, rig.box.offerState(rig.slots[0], rig.token, () -> { }));
        assertEquals(Admission.REJECTED_CLOSED, closed.admission());
        assertFalse(closed.accepted());
    }

    @Test public void concurrentBatchesStayContiguousFifoAndExactlyOnce() throws Exception {
        ControlledRuntime runtime = new ControlledRuntime();
        LifecycleScope scope = runtime.activeScope();
        FrameScheduler scheduler = runtime.scheduler(64);
        WorkToken token = scope.lease(LifecycleScope.ALL).token();
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, 4, 64, 32);
        int producers = 4, batches = 300, batchSize = 5;
        List<Integer> executed = Collections.synchronizedList(new ArrayList<>());
        ConcurrentHashMap<Integer, AtomicInteger> releases = new ConcurrentHashMap<>();
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(producers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int p = 0; p < producers; p++) {
            int producer = p;
            new Thread(() -> {
                try {
                    Offers offers = box.offers();
                    for (int b = 0; b < batches; b++) {
                        for (int i = 0; i < batchSize; i++) {
                            int id = (producer << 20) | (b << 4) | i;
                            offers.command(token, () -> executed.add(id),
                                    () -> releases.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet());
                        }
                        int admitted = offers.submit();
                        // Capacity is all-or-prefix per batch only if no other producer drains between
                        // offers; atomic admission makes a batch either fit in order or be cut at the
                        // first rejection, never interleaved with another producer's commands.
                        accepted.addAndGet(admitted);
                        Thread.yield();
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { done.countDown(); }
            }, "batch-producer-" + p).start();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (done.getCount() > 0 || runtime.queuedSignals() > 0 || runtime.hasFrame()) {
            runtime.pumpSignals();
            if (runtime.hasFrame()) runtime.frame();
            if (System.nanoTime() > deadline) fail("producers did not finish");
        }
        assertNull(failure.get());
        runtime.drain();
        assertEquals(accepted.get(), executed.size());
        assertEquals(0, box.pendingCommandCount());
        assertTrue(runtime.errors.isEmpty());
        // Exactly one release per admitted item.
        for (Integer id : executed) assertEquals(1, releases.get(id).get());
        // Per-producer FIFO and per-batch contiguity: once a batch starts executing, its admitted
        // items follow each other without another producer's items in between.
        int[] last = new int[producers];
        java.util.Arrays.fill(last, -1);
        for (int index = 0; index < executed.size(); index++) {
            int id = executed.get(index);
            int producer = id >>> 20;
            int order = id & 0xFFFFF;
            assertTrue("producer FIFO", order > last[producer]);
            last[producer] = order;
            if ((id & 0xF) != 0) assertEquals("batch contiguity", id - 1, (int) executed.get(index - 1));
        }
    }
}
