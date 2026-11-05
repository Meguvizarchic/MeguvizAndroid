package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox.*;

public class MailboxContractTest {
    private final ControlledRuntime runtime = new ControlledRuntime();
    private final LifecycleScope scope = runtime.activeScope();
    private final FrameScheduler scheduler = runtime.scheduler(64);
    private final WorkToken token = scope.lease(LifecycleScope.ALL).token();
    private final AtomicInteger releases = new AtomicInteger();
    private final List<Integer> commits = new ArrayList<>();

    private BoundedMailbox mailbox(int states, int commands, int quota) {
        return new BoundedMailbox(scope, scheduler, runtime.errors::add, states, commands, quota);
    }

    @Test public void statesCoalesceCommandsKeepFifoAndBatchesKeepOrder() {
        BoundedMailbox box = mailbox(2, 3, 16); Slot slot = box.newSlot();
        WorkHandle old = box.offerState(slot, token, () -> commits.add(1), releases::incrementAndGet).handle();
        box.offerCommand(token, () -> commits.add(10), releases::incrementAndGet);
        Submission newest = box.offerState(slot, token, () -> commits.add(2), releases::incrementAndGet);
        box.offerCommand(token, () -> commits.add(20), releases::incrementAndGet);
        assertEquals(Admission.REPLACED, newest.admission()); assertEquals(Outcome.SUPERSEDED, old.outcome());
        runtime.drain();
        assertFalse(commits.contains(1)); assertTrue(commits.contains(2));
        assertTrue(commits.indexOf(10) < commits.indexOf(20)); assertEquals(4, releases.get());
        assertEquals(0, box.pendingStateCount()); assertFalse(runtime.hasFrame());

        commits.clear(); releases.set(0);
        BoundedMailbox batch = mailbox(1, 1, 1);
        WorkHandle handle = batch.offerBatch(token, Arrays.asList(() -> commits.add(1),
                () -> { throw new IllegalStateException("partial"); }, () -> commits.add(3)), releases::incrementAndGet).handle();
        runtime.drain(); assertEquals(Arrays.asList(1), commits);
        assertEquals(Outcome.FAILED, handle.outcome()); assertEquals(1, releases.get());
    }

    @Test public void capacityAdmissionAndBackgroundStormStayBounded() {
        BoundedMailbox box = mailbox(1, 1, 1); Slot slot = box.newSlot();
        box.offerState(slot, token, () -> {}, releases::incrementAndGet);
        assertEquals(Admission.REJECTED_CAPACITY, box.offerState(box.newSlot(), token, () -> {}, releases::incrementAndGet).admission());
        assertEquals(Admission.REPLACED, box.offerState(slot, token, () -> {}, releases::incrementAndGet).admission());
        box.offerCommand(token, () -> {}, releases::incrementAndGet);
        assertEquals(Admission.REJECTED_CAPACITY, box.offerCommand(token, () -> {}, releases::incrementAndGet).admission());
        assertEquals(1, box.pendingStateCount()); assertEquals(1, box.pendingCommandCount());
        box.close(); assertEquals(3, releases.get());

        releases.set(0);
        BoundedMailbox storm = mailbox(2, 2, 2); Slot stormSlot = storm.newSlot();
        ControlledRuntime.background(() -> {
            for (int i = 0; i < 10_000; i++) {
                final int value = i;
                assertTrue(storm.offerState(stormSlot, token, () -> commits.add(value), releases::incrementAndGet).accepted());
                assertEquals(1, storm.pendingStateCount());
            }
        });
        assertEquals(1, runtime.posts); assertEquals(1, runtime.queuedSignals());
        runtime.drain(); assertEquals(Arrays.asList(9999), commits); assertEquals(10_000, releases.get());
        assertFalse(runtime.hasFrame());
    }

    /**
     * Deterministic regression for the shared-Signal stall. The seam runs after the owner's idle
     * decision and before the wake cancellation, which is exactly the window where a producer can
     * re-arm the same Signal instance. Removing that instance by identity would delete the fresh
     * wakeup and leave the mailbox permanently unable to request a frame.
     */
    @Test public void idleWakeCancellationKeepsAConcurrentProducerWakeup() {
        BoundedMailbox box = mailbox(1, 4, 4); Slot slot = box.newSlot();
        WorkHandle first = box.offerCommand(token, () -> fail("cancelled"), () -> { }).handle();
        // The producer re-arms the shared wakeup inside the race window. It must run on a
        // producer thread so the wakeup is queued (not delivered inline on the owner).
        box.idleWakeProbe = () -> ControlledRuntime.background(
                () -> box.offerCommand(token, () -> commits.add(7), () -> { }));
        assertEquals(Cancellation.CANCELLED, first.cancel());
        box.idleWakeProbe = null;

        // The producer's fresh wakeup must still be queued: deleting the shared Signal by identity
        // would leave signalPending set with no runnable, and every later offer would be ignored.
        assertEquals("the re-armed wakeup must survive the idle-wake cancellation",
                1, runtime.queuedSignals());

        // The re-armed wakeup must still be able to request a frame and deliver the new command.
        runtime.drain();
        assertEquals(Arrays.asList(7), commits);
        assertFalse(box.isClosed());
        assertEquals(0, box.pendingCommandCount());
    }

    @Test public void cancellationReleasesCapacityAndPreventsLateCallback() {
        BoundedMailbox box = mailbox(1, 1, 1);
        WorkHandle first = box.offerCommand(token, () -> fail("cancelled"), releases::incrementAndGet).handle();
        assertEquals(Cancellation.CANCELLED, first.cancel());
        assertEquals(Cancellation.ALREADY_TERMINAL, first.cancel());
        assertEquals(1, releases.get()); assertEquals(0, box.pendingCommandCount());
        assertFalse(runtime.hasFrame());
        assertTrue(box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet).accepted());
        runtime.drain(); assertEquals(Arrays.asList(2), commits); assertEquals(2, releases.get());

        // Cancelling the idle wake must not delete a wakeup that a concurrent producer re-armed
        // on the shared Signal instance; the mailbox must keep requesting frames afterwards.
        releases.set(0); commits.clear();
        BoundedMailbox reused = mailbox(1, 1, 1);
        WorkHandle cancelled = reused.offerCommand(token, () -> fail("cancelled"), releases::incrementAndGet).handle();
        assertEquals(Cancellation.CANCELLED, cancelled.cancel());
        assertFalse(runtime.hasFrame());
        assertTrue(reused.offerCommand(token, () -> commits.add(3), releases::incrementAndGet).accepted());
        runtime.drain();
        assertEquals(Arrays.asList(3), commits);

        releases.set(0);
        BoundedMailbox extracted = mailbox(1, 1, 1);
        WorkHandle handle = extracted.offerCommand(token, () -> fail("cancelled extracted"), releases::incrementAndGet).handle();
        extracted.afterExtract = () -> ControlledRuntime.background(() -> assertEquals(Cancellation.CANCELLED, handle.cancel()));
        runtime.frame(); assertEquals(Outcome.CANCELLED, handle.outcome());
        assertEquals(1, releases.get()); assertFalse(runtime.hasFrame());
    }

    @Test public void runningCancelIsNotPreemptiveAndPausedSubmitTerminatesInactive() {
        BoundedMailbox box = mailbox(1, 1, 1); AtomicReference<WorkHandle> handle = new AtomicReference<>();
        handle.set(box.offerCommand(token, () -> {
            ControlledRuntime.background(() -> assertEquals(Cancellation.ALREADY_RUNNING, handle.get().cancel()));
            commits.add(1);
        }, releases::incrementAndGet).handle());
        runtime.drain(); assertEquals(Outcome.COMPLETED, handle.get().outcome()); assertEquals(Arrays.asList(1), commits);

        BoundedMailbox paused = mailbox(1, 1, 1); paused.setPaused(true);
        AtomicReference<Submission> result = new AtomicReference<>();
        ControlledRuntime.background(() -> result.set(paused.offerCommand(token, () -> fail("paused command"), releases::incrementAndGet)));
        assertTrue(result.get().accepted()); assertEquals(Outcome.INACTIVE, result.get().handle().outcome());
        assertEquals(2, releases.get()); assertEquals(0, paused.pendingCommandCount());
        assertEquals(0, runtime.queuedSignals()); paused.setPaused(false); assertFalse(runtime.hasFrame());
    }

    @Test public void rebindAndExtractionTimeSupersessionReleasePayloads() {
        BoundedMailbox box = mailbox(1, 2, 2);
        WorkHandle old = box.offerCommand(token, () -> fail("stale"), releases::incrementAndGet).handle();
        scope.rebind(); runtime.drain();
        assertEquals(Outcome.STALE, old.outcome()); assertEquals(1, releases.get());

        releases.set(0);
        LifecycleScope extractScope = runtime.activeScope();
        WorkToken extractToken = extractScope.lease(LifecycleScope.ALL).token();
        BoundedMailbox extracted = new BoundedMailbox(extractScope, scheduler, runtime.errors::add, 1, 2, 2);
        Slot slot = extracted.newSlot();
        WorkHandle superseded = extracted.offerState(slot, extractToken, () -> fail("superseded extracted"), releases::incrementAndGet).handle();
        extracted.afterExtract = () -> {
            extracted.afterExtract = null;
            ControlledRuntime.background(() -> assertEquals(Admission.REPLACED,
                    extracted.offerState(slot, extractToken, () -> commits.add(2), releases::incrementAndGet).admission()));
        };
        runtime.frame();
        assertEquals(Outcome.SUPERSEDED, superseded.outcome()); assertTrue(commits.isEmpty());
        runtime.frame(); assertEquals(Arrays.asList(2), commits); assertEquals(2, releases.get());
    }

    @Test public void scopeCloseClearsStateAndCloseHookRunsAfterOwnerCleanup() {
        BoundedMailbox box = mailbox(1, 2, 2);
        WorkHandle old = box.offerCommand(token, () -> fail("closed"), releases::incrementAndGet).handle();
        assertEquals(1, scope.registrationCount()); scope.close();
        assertEquals(Outcome.CLOSED, old.outcome()); assertEquals(1, releases.get());
        assertEquals(0, scope.registrationCount()); assertEquals(0, scheduler.channelCount()); assertFalse(runtime.hasFrame());
        assertEquals(Admission.REJECTED_CLOSED, box.offerCommand(token, () -> {}, releases::incrementAndGet).admission());
        assertEquals(1, releases.get()); assertTrue(runtime.errors.isEmpty());

        LifecycleScope hookScope = runtime.activeScope();
        WorkToken hookToken = hookScope.lease(LifecycleScope.ALL).token();
        BoundedMailbox rejected = new BoundedMailbox(hookScope, scheduler, runtime.errors::add, 1, 1, 1);
        AtomicInteger closed = new AtomicInteger();
        rejected.onClose(closed::incrementAndGet); runtime.rejectPost = true;
        ControlledRuntime.background(() -> rejected.offerCommand(hookToken, () -> {}, releases::incrementAndGet));
        assertTrue(rejected.isClosed()); assertEquals(0, closed.get());
        rejected.close(); assertEquals(1, closed.get()); rejected.close(); assertEquals(1, closed.get());
        runtime.rejectPost = false;
    }

    @Test public void closedScopeConstructorReleasesOpenedChannel() {
        LifecycleScope closed = runtime.activeScope();
        closed.close();
        assertThrows(IllegalStateException.class,
                () -> new BoundedMailbox(closed, scheduler, runtime.errors::add, 1, 1, 1));
        assertEquals(0, scheduler.channelCount());
        assertTrue(runtime.errors.isEmpty());
    }

    @Test public void frameBoundaryReentryAndPublicationKeepWakeup() {
        BoundedMailbox box = mailbox(1, 4, 4);
        box.offerCommand(token, () -> {
            commits.add(1); box.offerCommand(token, () -> commits.add(2), releases::incrementAndGet);
        }, releases::incrementAndGet);
        runtime.frame(); assertEquals(Arrays.asList(1), commits); assertTrue(runtime.hasFrame());
        runtime.frame(); assertEquals(Arrays.asList(1, 2), commits); assertFalse(runtime.hasFrame());

        commits.clear();
        BoundedMailbox boundary = mailbox(1, 2, 2);
        boundary.offerCommand(token, () -> commits.add(1), releases::incrementAndGet);
        boundary.beforeFinish = () -> {
            boundary.beforeFinish = null;
            ControlledRuntime.background(() -> boundary.offerCommand(token, () -> commits.add(2), releases::incrementAndGet));
        };
        runtime.frame(); assertTrue(runtime.hasFrame()); runtime.frame();
        assertEquals(Arrays.asList(1, 2), commits); assertFalse(runtime.hasFrame());
        ControlledRuntime.background(() -> boundary.offerCommand(token, () -> commits.add(3), releases::incrementAndGet));
        assertEquals(1, runtime.queuedSignals()); runtime.drain();
        assertEquals(Arrays.asList(1, 2, 3), commits); assertFalse(runtime.hasFrame());
    }

    @Test public void platformAndDispatcherRejectionsTerminateOwnedWork() {
        BoundedMailbox box = mailbox(1, 1, 1); runtime.rejectPost = true;
        AtomicReference<Submission> offered = new AtomicReference<>();
        ControlledRuntime.background(() -> offered.set(box.offerCommand(token, () -> fail("no dispatcher"), releases::incrementAndGet)));
        assertTrue(offered.get().accepted()); assertEquals(Outcome.FAILED, offered.get().handle().outcome());
        assertEquals(1, releases.get()); assertTrue(box.isClosed());
        // rejectPost is still true here, so the deferred owner cleanup cannot be posted and the
        // scope close path remains the fallback; close() releases the channel explicitly.
        box.close(); assertEquals(0, scheduler.channelCount()); assertEquals(0, scope.registrationCount());
        runtime.rejectPost = false;

        // A producer-thread failure with a working dispatcher releases the channel and
        // registration from the posted owner cleanup, without an explicit close(). A release
        // that throws while superseding an item is the producer-side failure path.
        releases.set(0);
        BoundedMailbox deferred = mailbox(1, 1, 1); Slot deferredSlot = deferred.newSlot();
        deferred.offerState(deferredSlot, token, () -> { }, () -> { throw new IllegalStateException("release failed"); });
        AtomicReference<Submission> deferredOffered = new AtomicReference<>();
        ControlledRuntime.background(() -> deferredOffered.set(
                deferred.offerState(deferredSlot, token, () -> { }, releases::incrementAndGet)));
        assertTrue(deferred.isClosed());
        runtime.pumpSignals();
        assertEquals(0, scheduler.channelCount()); assertEquals(0, scope.registrationCount());
        runtime.rejectPost = false;

        releases.set(0);
        BoundedMailbox rejected = mailbox(1, 1, 1); runtime.rejectFrame = true;
        WorkHandle handle = rejected.offerCommand(token, () -> fail("rejected frame"), releases::incrementAndGet).handle();
        assertEquals(Outcome.FAILED, handle.outcome()); assertEquals(1, releases.get()); assertTrue(rejected.isClosed());
        assertEquals(0, scheduler.channelCount());
        runtime.rejectFrame = false;
    }

    @Test public void failuresCloseOnlyTheAffectedMailboxAndKeepOtherReleases() {
        BoundedMailbox left = mailbox(1, 3, 3);
        LifecycleScope rightScope = runtime.activeScope();
        BoundedMailbox right = new BoundedMailbox(rightScope, scheduler, runtime.errors::add, 1, 2, 2);
        WorkHandle bad = left.offerCommand(token, () -> { throw new IllegalStateException("bad"); }, releases::incrementAndGet).handle();
        WorkHandle queued = left.offerCommand(token, () -> fail("after failure"), releases::incrementAndGet).handle();
        right.offerCommand(rightScope.lease(0).token(), () -> commits.add(2), releases::incrementAndGet);
        runtime.drain();
        assertEquals(Outcome.FAILED, bad.outcome()); assertEquals(Outcome.FAILED, queued.outcome());
        assertTrue(left.isClosed()); assertFalse(right.isClosed()); assertEquals(Arrays.asList(2), commits); assertEquals(3, releases.get());

        BoundedMailbox failing = mailbox(1, 3, 3);
        failing.offerCommand(token, () -> {}, () -> { releases.incrementAndGet(); throw new IllegalArgumentException("release"); });
        failing.offerCommand(token, () -> fail("closed by release"), releases::incrementAndGet);
        failing.offerCommand(token, () -> fail("closed by release"), releases::incrementAndGet);
        runtime.drain(); assertEquals(6, releases.get()); assertTrue(failing.isClosed());
        assertEquals("release", failing.lastFailure().getMessage());
    }

    @Test public void sinkAndFatalFailuresAreRecordedWithoutLosingReleases() {
        AtomicInteger reports = new AtomicInteger();
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, failure -> { reports.incrementAndGet(); throw new IllegalStateException("sink"); }, 1, 1, 1);
        box.offerCommand(token, () -> { throw new IllegalArgumentException("work"); }, releases::incrementAndGet);
        runtime.drain(); assertEquals(1, reports.get()); assertEquals("work", box.lastFailure().getMessage());
        assertEquals("sink", box.lastFailure().getSuppressed()[0].getMessage());

        BoundedMailbox fatal = mailbox(1, 16, 1);
        for (int i = 0; i < 12; i++) fatal.offerCommand(token, () -> {}, () -> { releases.incrementAndGet(); throw new IllegalStateException("ordinary"); });
        fatal.offerCommand(token, () -> {}, () -> { releases.incrementAndGet(); throw new LinkageError("injected fatal"); });
        fatal.offerCommand(token, () -> {}, releases::incrementAndGet);
        assertThrows(LinkageError.class, fatal::close);
        assertEquals(15, releases.get()); assertEquals(0, fatal.pendingCommandCount());
        assertEquals(0, scheduler.channelCount()); assertEquals(0, scope.registrationCount());
        assertTrue(fatal.lastFailure().getSuppressed().length <= 8);
    }

    @Test public void pauseKeepsLatestStateAndResumesWithoutDiscardingExtractedWork() {
        BoundedMailbox box = mailbox(1, 2, 2); Slot slot = box.newSlot();
        box.offerState(slot, token, () -> commits.add(1), releases::incrementAndGet);
        WorkHandle command = box.offerCommand(token, () -> fail("paused command"), releases::incrementAndGet).handle();
        box.setPaused(true); assertEquals(Outcome.INACTIVE, command.outcome()); assertFalse(runtime.hasFrame());
        ControlledRuntime.background(() -> box.offerState(slot, token, () -> commits.add(2), releases::incrementAndGet));
        assertEquals(0, runtime.queuedSignals()); assertFalse(runtime.hasFrame());
        box.setPaused(false); runtime.drain(); assertEquals(Arrays.asList(2), commits); assertEquals(3, releases.get());

        BoundedMailbox batched = mailbox(1, 1, 2); Slot batchedSlot = batched.newSlot();
        batched.offerCommand(token, () -> batched.setPaused(true), releases::incrementAndGet);
        WorkHandle state = batched.offerState(batchedSlot, token, () -> commits.add(2), releases::incrementAndGet).handle();
        runtime.frame(); assertEquals(Outcome.PENDING, state.outcome()); assertFalse(runtime.hasFrame());
        batched.setPaused(false); runtime.drain(); assertEquals(Outcome.COMPLETED, state.outcome());
        assertEquals(Arrays.asList(2, 2), commits);
    }

    @Test public void foreignSlotAndForeignTokenAreRejectedWithoutOwnershipTransfer() {
        BoundedMailbox left = mailbox(1, 1, 1); BoundedMailbox right = mailbox(1, 1, 1);
        assertEquals(Admission.UNSUPPORTED, left.offerState(right.newSlot(), token, () -> {}, releases::incrementAndGet).admission());
        WorkToken foreign = runtime.activeScope().lease(0).token();
        assertEquals(Admission.UNSUPPORTED, left.offerCommand(foreign, () -> {}, releases::incrementAndGet).admission());
        assertEquals(0, releases.get());
    }
}
