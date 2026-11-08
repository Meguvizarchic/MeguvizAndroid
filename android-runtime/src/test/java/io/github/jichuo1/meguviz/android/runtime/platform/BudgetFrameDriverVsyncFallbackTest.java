package io.github.jichuo1.meguviz.android.runtime.platform;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.view.Choreographer;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * The API 33+ path must survive unusable platform metadata. The real {@link Choreographer.FrameData}
 * and {@link Choreographer.FrameTimeline} constructors are hidden from the compile-time stub, so the
 * fixture builds them through reflection against the pinned Robolectric framework. Every fallback
 * must reach the one-argument callback so the consumer keeps its fixed work budget.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
@TargetApi(33)
@SuppressLint("UseRequiresApi") // @Config(sdk = 37) is the API guard for this class.
public class BudgetFrameDriverVsyncFallbackTest {
    private static final OwnerDispatcher OWNER = new OwnerDispatcher() {
        @Override public boolean isOwnerThread() { return true; }
        @Override public boolean post(Runnable callback) { return false; }
        @Override public void remove(Runnable callback) { }
    };

    private static final class VsyncQueue implements BudgetFrameDriver.VsyncDriver.Queue {
        final List<Choreographer.VsyncCallback> queued = new ArrayList<>();
        @Override public void post(Choreographer.VsyncCallback callback) { queued.add(callback); }
        @Override public void remove(Choreographer.VsyncCallback callback) { queued.remove(callback); }
        Choreographer.VsyncCallback extract() { return queued.remove(0); }
    }

    private static final class Delivery {
        final AtomicInteger legacy = new AtomicInteger();
        final AtomicInteger copied = new AtomicInteger();
        final long[] values = new long[3];
        final FrameDriver.Callback callback = new FrameDriver.Callback() {
            @Override public void onFrame(long frameTimeNanos) {
                legacy.incrementAndGet(); values[0] = frameTimeNanos;
            }
            @Override public void onFrame(long frameTimeNanos, long deadlineNanos, long expectedPresentationNanos) {
                copied.incrementAndGet();
                values[0] = frameTimeNanos; values[1] = deadlineNanos; values[2] = expectedPresentationNanos;
            }
        };
    }

    @Test public void nonPositiveDeadlineAndPresentationAreReportedAsUnknown() {
        Choreographer.FrameData data = frameData(1_000L);
        // A freshly built FrameData carries the framework's -1 "unavailable" timeline values.
        Delivery delivery = deliver(data);
        assertEquals(1, delivery.legacy.get());
        assertEquals(0, delivery.copied.get());
        assertEquals(1_000L, delivery.values[0]);
    }

    @Test public void usableDeadlineIsDeliveredEvenWithoutAPresentationTime() {
        // The presentation time is copied through for consumers but is not a scheduling input, so a
        // platform that reports none must not cost us a usable deadline.
        Choreographer.FrameData data = frameData(2_000L);
        Choreographer.FrameTimeline timeline = timeline(3L, -1L, 5_000L);
        setPreferredTimeline(data, timeline);
        Delivery delivery = deliver(data);
        assertEquals(0, delivery.legacy.get());
        assertEquals(1, delivery.copied.get());
        // values are {frameTime, deadline, presentation}; the platform's -1 presentation is copied
        // through rather than used to discard the usable deadline.
        assertArrayEquals(new long[] {2_000L, 5_000L, -1L}, delivery.values);
    }

    @Test public void preferredFrameTimelineFailureFallsBackToUnknown() {
        Choreographer.FrameData data = frameData(4_000L);
        clearPreferredTimelines(data);
        Delivery delivery = deliver(data);
        assertEquals(1, delivery.legacy.get());
        assertEquals(0, delivery.copied.get());
        assertEquals(4_000L, delivery.values[0]);
    }

    @Test public void timelineGetterFailureFallsBackToUnknown() {
        Choreographer.FrameData data = frameData(6_000L);
        // A timeline that was never marked in-callback throws IllegalStateException from its getters.
        setPreferredTimeline(data, newTimeline());
        Delivery delivery = deliver(data);
        assertEquals(1, delivery.legacy.get());
        assertEquals(0, delivery.copied.get());
        assertEquals(6_000L, delivery.values[0]);
    }

    @Test public void usableTimelineDeliversDeadlineAndPresentationAsOnePair() {
        Choreographer.FrameData data = frameData(8_000L);
        setPreferredTimeline(data, timeline(9L, 12_000L, 10_000L));
        Delivery delivery = deliver(data);
        assertEquals(0, delivery.legacy.get());
        assertEquals(1, delivery.copied.get());
        assertArrayEquals(new long[] {8_000L, 10_000L, 12_000L}, delivery.values);
    }

    private static Delivery deliver(Choreographer.FrameData data) {
        VsyncQueue queue = new VsyncQueue();
        BudgetFrameDriver.VsyncDriver driver = new BudgetFrameDriver.VsyncDriver(OWNER, queue);
        Delivery delivery = new Delivery();
        assertTrue(driver.request(delivery.callback));
        queue.extract().onVsync(data);
        driver.remove(delivery.callback);
        return delivery;
    }

    private static Choreographer.FrameData frameData(long frameTimeNanos) {
        try {
            Constructor<Choreographer.FrameData> constructor =
                    Choreographer.FrameData.class.getDeclaredConstructor(long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(frameTimeNanos);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("API 37 FrameData(long) is unavailable", failure);
        }
    }

    private static Choreographer.FrameTimeline newTimeline() {
        try {
            Constructor<Choreographer.FrameTimeline> constructor =
                    Choreographer.FrameTimeline.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("API 37 FrameTimeline constructor is unavailable", failure);
        }
    }

    /** A timeline marked in-callback and carrying the given vsync id, presentation time and deadline. */
    private static Choreographer.FrameTimeline timeline(long vsyncId, long presentationNanos, long deadlineNanos) {
        Choreographer.FrameTimeline timeline = newTimeline();
        try {
            Method update = Choreographer.FrameTimeline.class.getDeclaredMethod(
                    "update", long.class, long.class, long.class);
            update.setAccessible(true);
            update.invoke(timeline, vsyncId, presentationNanos, deadlineNanos);
            Method inCallback = Choreographer.FrameTimeline.class.getDeclaredMethod("setInCallback", boolean.class);
            inCallback.setAccessible(true);
            inCallback.invoke(timeline, true);
            return timeline;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("API 37 FrameTimeline mutation is unavailable", failure);
        }
    }

    private static void setPreferredTimeline(Choreographer.FrameData data, Choreographer.FrameTimeline timeline) {
        try {
            Field timelines = Choreographer.FrameData.class.getDeclaredField("mFrameTimelines");
            timelines.setAccessible(true);
            timelines.set(data, new Choreographer.FrameTimeline[] {timeline});
            Field index = Choreographer.FrameData.class.getDeclaredField("mPreferredFrameTimelineIndex");
            index.setAccessible(true);
            index.setInt(data, 0);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("API 37 FrameData mutation is unavailable", failure);
        }
    }

    private static void clearPreferredTimelines(Choreographer.FrameData data) {
        try {
            Field timelines = Choreographer.FrameData.class.getDeclaredField("mFrameTimelines");
            timelines.setAccessible(true);
            timelines.set(data, null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("API 37 FrameData mutation is unavailable", failure);
        }
    }
}
