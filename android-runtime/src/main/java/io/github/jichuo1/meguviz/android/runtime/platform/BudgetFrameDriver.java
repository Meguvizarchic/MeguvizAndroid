package io.github.jichuo1.meguviz.android.runtime.platform;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.Choreographer;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import java.util.Objects;

/** Deadline-aware frame metadata on API 33+, with the single-argument driver as fallback. */
public final class BudgetFrameDriver implements FrameDriver {
    private final FrameDriver delegate;

    public BudgetFrameDriver(OwnerDispatcher owner, boolean enabled) {
        Objects.requireNonNull(owner).checkOwner();
        delegate = enabled && Build.VERSION.SDK_INT >= 33 ? new VsyncDriver(owner) : new ChoreographerDriver(owner);
    }

    @Override public boolean request(Callback callback) { return delegate.request(callback); }
    @Override public void remove(Callback callback) { delegate.remove(callback); }

    /**
     * True when the platform actually supplied a deadline.
     *
     * <p>A non-positive value means the timeline could not provide one (for example after a
     * SurfaceFlinger restart). Reporting it as "unknown" keeps the consumer on its fixed work
     * budget; passing it through would let consumers derive a zero budget and stall the frame.
     * This mirrors {@code ChoreographerFrameDriver.VsyncDriver} in MeguvizUI, which applies the
     * same test before copying the deadline into its frame budget.
     */
    static boolean isUsableDeadline(long deadlineNanos) {
        return deadlineNanos > 0;
    }

    @TargetApi(33)
    static final class VsyncDriver implements FrameDriver {
        interface Queue {
            void post(Choreographer.VsyncCallback callback);
            void remove(Choreographer.VsyncCallback callback);
        }
        private final OwnerDispatcher owner;
        private final Queue queue;
        private Pending pending;

        private final class Pending implements Choreographer.VsyncCallback {
            private Callback callback;
            Pending(Callback callback) { this.callback = callback; }
            @Override public void onVsync(Choreographer.FrameData data) {
                if (pending != this) { callback = null; return; }
                pending = null;
                Callback target = callback; callback = null;
                if (target == null) return;
                long frame = 0L;
                long deadline, presentation;
                try {
                    // The frame time is read here too: a metadata failure must never escape into the
                    // platform callback, where it would leave FrameScheduler.scheduled set and stall
                    // the scheduler. On failure the frame is reported as unknown.
                    frame = data.getFrameTimeNanos();
                    Choreographer.FrameTimeline timeline = data.getPreferredFrameTimeline();
                    deadline = timeline.getDeadlineNanos();
                    if (!isUsableDeadline(deadline)) { target.onFrame(frame); return; }
                    // The presentation time is copied through for consumers, but it is not a
                    // scheduling input: requiring it would throw away a perfectly usable deadline on
                    // a platform that reports no presentation time.
                    presentation = timeline.getExpectedPresentationTimeNanos();
                } catch (RuntimeException failure) {
                    // The timeline can be unavailable (for example after a SurfaceFlinger restart) or
                    // no longer valid. Report the frame as unknown rather than letting a metadata
                    // failure drop the frame or stall the scheduler on a derived zero budget.
                    // Errors propagate, matching UiScheduler.apply/checkPending.
                    target.onFrame(frame); return;
                }
                target.onFrame(frame, deadline, presentation);
            }
        }

        VsyncDriver(OwnerDispatcher owner) {
            this.owner = Objects.requireNonNull(owner); owner.checkOwner();
            Choreographer choreographer = Choreographer.getInstance();
            queue = new Queue() {
                @Override public void post(Choreographer.VsyncCallback callback) { choreographer.postVsyncCallback(callback); }
                @Override public void remove(Choreographer.VsyncCallback callback) { choreographer.removeVsyncCallback(callback); }
            };
        }
        VsyncDriver(OwnerDispatcher owner, Queue queue) {
            this.owner = Objects.requireNonNull(owner); owner.checkOwner();
            this.queue = Objects.requireNonNull(queue);
        }
        @Override public boolean request(Callback callback) {
            owner.checkOwner(); Objects.requireNonNull(callback);
            if (pending != null) {
                if (pending.callback != callback) throw new IllegalStateException("Driver already owned by another scheduler");
                return true;
            }
            Pending request = new Pending(callback);
            pending = request;
            try { queue.post(request); }
            catch (RuntimeException | Error failure) {
                if (pending == request) pending = null;
                request.callback = null;
                throw failure;
            }
            return true;
        }
        @Override public void remove(Callback callback) {
            owner.checkOwner();
            Pending request = pending;
            if (request != null && request.callback == callback) {
                pending = null;
                request.callback = null;
                queue.remove(request);
            }
        }
    }
}
