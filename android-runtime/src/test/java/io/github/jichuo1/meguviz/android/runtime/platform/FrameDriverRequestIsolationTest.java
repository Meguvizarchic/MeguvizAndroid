package io.github.jichuo1.meguviz.android.runtime.platform;

import android.annotation.TargetApi;
import android.view.Choreographer;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
@TargetApi(33)
public class FrameDriverRequestIsolationTest {
    private static final OwnerDispatcher OWNER = new OwnerDispatcher() {
        @Override public boolean isOwnerThread() { return true; }
        @Override public boolean post(Runnable callback) { return false; }
        @Override public void remove(Runnable callback) { }
    };

    private static final class FrameQueue implements ChoreographerDriver.Queue {
        final List<Choreographer.FrameCallback> queued = new ArrayList<>();
        int posts; boolean failPost, failRemove;
        @Override public void post(Choreographer.FrameCallback callback) {
            posts++; queued.add(callback);
            if (failPost) { failPost = false; throw new IllegalStateException("post failed"); }
        }
        @Override public void remove(Choreographer.FrameCallback callback) {
            if (failRemove) { failRemove = false; throw new IllegalStateException("remove failed"); }
            queued.remove(callback);
        }
        Choreographer.FrameCallback extract() { return queued.remove(0); }
    }

    private static final class VsyncQueue implements BudgetFrameDriver.VsyncDriver.Queue {
        final List<Choreographer.VsyncCallback> queued = new ArrayList<>();
        int posts; boolean failPost, failRemove;
        @Override public void post(Choreographer.VsyncCallback callback) {
            posts++; queued.add(callback);
            if (failPost) { failPost = false; throw new IllegalStateException("post failed"); }
        }
        @Override public void remove(Choreographer.VsyncCallback callback) {
            if (failRemove) { failRemove = false; throw new IllegalStateException("remove failed"); }
            queued.remove(callback);
        }
        Choreographer.VsyncCallback extract() { return queued.remove(0); }
    }

    @Test public void extractedFrameAndVsyncCallbacksCannotConsumeReplacement() {
        FrameQueue queue = new FrameQueue();
        ChoreographerDriver driver = new ChoreographerDriver(OWNER, queue);
        AtomicInteger oldCalls = new AtomicInteger(), newCalls = new AtomicInteger();
        FrameDriver.Callback old = time -> oldCalls.incrementAndGet(), next = time -> newCalls.incrementAndGet();
        assertTrue(driver.request(old));
        Choreographer.FrameCallback extracted = queue.extract();
        driver.remove(old);
        assertTrue(driver.request(next));
        extracted.doFrame(10L); extracted.doFrame(11L);
        assertEquals(0, oldCalls.get()); assertEquals(0, newCalls.get());
        assertTrue(driver.request(next)); assertEquals(2, queue.posts);
        queue.extract().doFrame(20L);
        assertEquals(1, newCalls.get()); assertEquals(0, oldCalls.get());

        VsyncQueue vsyncQueue = new VsyncQueue();
        BudgetFrameDriver.VsyncDriver vsyncDriver = new BudgetFrameDriver.VsyncDriver(OWNER, vsyncQueue);
        FrameDriver.Callback failingOld = time -> fail("old callback delivered");
        FrameDriver.Callback failingNext = time -> fail("replacement delivered by old frame");
        FrameDriver.Callback other = time -> { };
        assertTrue(vsyncDriver.request(failingOld));
        Choreographer.VsyncCallback extractedVsync = vsyncQueue.extract();
        vsyncDriver.remove(failingOld);
        assertTrue(vsyncDriver.request(failingNext));
        // Stale platform payload is never accessed, even if it is no longer valid.
        extractedVsync.onVsync(null); extractedVsync.onVsync(null);
        assertTrue(vsyncDriver.request(failingNext)); assertEquals(2, vsyncQueue.posts);
        assertThrows(IllegalStateException.class, () -> vsyncDriver.request(other));
        vsyncDriver.remove(failingNext);
        assertTrue(vsyncDriver.request(other)); assertEquals(3, vsyncQueue.posts);
        vsyncDriver.remove(other);
    }

    @Test public void failedPostInvalidatesBothKindsOfPlatformCallback() {
        FrameQueue frames = new FrameQueue(); frames.failPost = true;
        ChoreographerDriver frameDriver = new ChoreographerDriver(OWNER, frames);
        AtomicInteger delivered = new AtomicInteger();
        FrameDriver.Callback callback = time -> delivered.incrementAndGet();
        assertThrows(IllegalStateException.class, () -> frameDriver.request(callback));
        Choreographer.FrameCallback oldFrame = frames.extract();
        assertTrue(frameDriver.request(callback)); oldFrame.doFrame(1L);
        assertEquals(0, delivered.get()); frames.extract().doFrame(2L); assertEquals(1, delivered.get());

        VsyncQueue vsyncs = new VsyncQueue(); vsyncs.failPost = true;
        BudgetFrameDriver.VsyncDriver vsyncDriver = new BudgetFrameDriver.VsyncDriver(OWNER, vsyncs);
        assertThrows(IllegalStateException.class, () -> vsyncDriver.request(callback));
        Choreographer.VsyncCallback oldVsync = vsyncs.extract();
        assertTrue(vsyncDriver.request(callback)); oldVsync.onVsync(null);
        assertTrue(vsyncDriver.request(callback)); assertEquals(2, vsyncs.posts);
        vsyncDriver.remove(callback);
    }

    @Test public void failedRemoveStillInvalidatesOldCallbacksAndAllowsReplacement() {
        FrameQueue frames = new FrameQueue();
        ChoreographerDriver frameDriver = new ChoreographerDriver(OWNER, frames);
        AtomicInteger delivered = new AtomicInteger();
        FrameDriver.Callback old = time -> fail("old callback delivered"), next = time -> delivered.incrementAndGet();
        assertTrue(frameDriver.request(old)); Choreographer.FrameCallback oldFrame = frames.extract();
        frames.failRemove = true;
        assertThrows(IllegalStateException.class, () -> frameDriver.remove(old));
        assertTrue(frameDriver.request(next)); oldFrame.doFrame(1L);
        assertEquals(0, delivered.get()); frames.extract().doFrame(2L); assertEquals(1, delivered.get());

        VsyncQueue vsyncs = new VsyncQueue();
        BudgetFrameDriver.VsyncDriver vsyncDriver = new BudgetFrameDriver.VsyncDriver(OWNER, vsyncs);
        assertTrue(vsyncDriver.request(old)); Choreographer.VsyncCallback oldVsync = vsyncs.extract();
        vsyncs.failRemove = true;
        assertThrows(IllegalStateException.class, () -> vsyncDriver.remove(old));
        assertTrue(vsyncDriver.request(next)); oldVsync.onVsync(null);
        assertTrue(vsyncDriver.request(next)); assertEquals(2, vsyncs.posts);
        vsyncDriver.remove(next);
    }
}
