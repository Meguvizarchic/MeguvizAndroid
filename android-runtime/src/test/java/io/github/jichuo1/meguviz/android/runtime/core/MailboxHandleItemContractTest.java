package io.github.jichuo1.meguviz.android.runtime.core;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox.*;

/**
 * Contract for the handle-is-the-item representation: the {@link WorkHandle} returned to the caller
 * is the same object the mailbox queues, so no second per-offer item allocation exists. These tests
 * pin the representation and the termination invariants the representation relies on: the release
 * callback survives on the handle until it runs exactly once, and the payload is detached afterwards.
 */
public class MailboxHandleItemContractTest {
    private final ControlledRuntime runtime = new ControlledRuntime();
    private final LifecycleScope scope = runtime.activeScope();
    private final FrameScheduler scheduler = runtime.scheduler(64);
    private final WorkToken token = scope.lease(LifecycleScope.ALL).token();
    private final AtomicInteger releases = new AtomicInteger();

    private BoundedMailbox mailbox(int states, int commands, int quota) {
        return new BoundedMailbox(scope, scheduler, runtime.errors::add, states, commands, quota);
    }

    @Test public void returnedHandleIsTheQueuedItem() throws Exception {
        BoundedMailbox box = mailbox(2, 2, 8);
        BoundedMailbox.Slot slot = box.newSlot();
        WorkHandle state = box.offerState(slot, token, () -> { }, releases::incrementAndGet).handle();
        IdentityHashMap<?, ?> latest = reflect(box, "latest");
        assertSame("the returned handle must be the latest-state entry itself", state, latest.get(slot));

        WorkHandle command = box.offerCommand(token, () -> { }, releases::incrementAndGet).handle();
        ArrayDeque<?> commands = reflect(box, "commands");
        assertSame("the returned handle must be the queued command itself", command, commands.peekLast());
        box.close();
    }

    @Test public void terminationClearsPayloadAndRunsReleaseExactlyOnce() throws Exception {
        BoundedMailbox box = mailbox(1, 1, 1);
        BoundedMailbox.Slot slot = box.newSlot();
        WorkHandle handle = box.offerState(slot, token, () -> { }, releases::incrementAndGet).handle();
        runtime.frame();
        assertEquals(Outcome.COMPLETED, handle.outcome());
        assertEquals(1, releases.get());
        assertNull("slot must be detached", field(handle, "slot"));
        assertNull("token must be detached", field(handle, "token"));
        assertNull("action must be detached", field(handle, "action"));
        assertNull("release must be consumed", field(handle, "release"));
        assertNull("mailbox must be detached", field(handle, "mailbox"));
        assertEquals(Cancellation.ALREADY_TERMINAL, handle.cancel());
        assertEquals("a terminal handle must not run the release again", 1, releases.get());
        box.close();
    }

    @Test public void supersededHandleCarriesReleaseAndIsDetached() throws Exception {
        BoundedMailbox box = mailbox(1, 1, 1);
        BoundedMailbox.Slot slot = box.newSlot();
        WorkHandle first = box.offerState(slot, token, () -> fail("superseded"), releases::incrementAndGet).handle();
        WorkHandle second = box.offerState(slot, token, () -> { }, releases::incrementAndGet).handle();
        assertEquals(Outcome.SUPERSEDED, first.outcome());
        assertEquals(1, releases.get());
        assertNull("superseded slot must be detached", field(first, "slot"));
        assertNull("superseded mailbox must be detached", field(first, "mailbox"));
        assertEquals(Cancellation.ALREADY_TERMINAL, first.cancel());
        assertEquals(1, releases.get());

        runtime.frame();
        assertEquals(Outcome.COMPLETED, second.outcome());
        assertEquals(2, releases.get());
        box.close();
    }

    @Test public void cancelledHandleCarriesReleaseAndIsDetached() throws Exception {
        BoundedMailbox box = mailbox(1, 1, 1);
        BoundedMailbox.Slot slot = box.newSlot();
        WorkHandle handle = box.offerState(slot, token, () -> fail("cancelled"), releases::incrementAndGet).handle();
        assertEquals(Cancellation.CANCELLED, handle.cancel());
        assertEquals(Outcome.CANCELLED, handle.outcome());
        assertEquals(1, releases.get());
        assertNull("cancelled slot must be detached", field(handle, "slot"));
        assertNull("cancelled release must be consumed", field(handle, "release"));
        assertEquals(Cancellation.ALREADY_TERMINAL, handle.cancel());
        assertEquals(1, releases.get());
        box.close();
    }

    @SuppressWarnings("unchecked")
    private static <T> T reflect(Object target, String name) throws Exception {
        return (T) field(target, name);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
