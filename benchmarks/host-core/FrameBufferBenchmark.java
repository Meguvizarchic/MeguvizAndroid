/**
 * Host-only micro benchmark for the BoundedMailbox.drain / FrameScheduler.onFrame
 * per-frame snapshot buffers. Pure JVM; no Android, no Robolectric, no device.
 *
 * It measures wall time, main-thread allocated bytes and GC collections for a fixed
 * deterministic workload of bounded mailboxes draining through one FrameScheduler.
 * The workload is identical before and after a change; only the two library files
 * differ, so the deltas isolate the snapshot buffer effect. It does NOT claim zero
 * allocation and does NOT claim ART/device frame pacing.
 *
 * Reproduce (JDK 19+ required for Thread.threadId()):
 *   javac -d out <android-runtime core sources/*.java> FrameBufferBenchmark.java
 *   java -cp out FrameBufferBenchmark [mailboxes] [slotsPerMailbox] [frames] [warmupFrames] [repetitions]
 *
 * Exit code 0 always; this tool records numbers, it never gates a release.
 */
import io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import io.github.jichuo1.meguviz.android.runtime.core.WorkToken;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public final class FrameBufferBenchmark {
    private static final int PER_MAILBOX_QUOTA = 8;
    private static final int GLOBAL_QUOTA = 64;

    /** Single-threaded owner dispatcher plus a manually ticked one-shot frame driver. */
    static final class BenchRuntime implements OwnerDispatcher, FrameDriver {
        private final Thread owner = Thread.currentThread();
        private final List<Throwable> errors = new ArrayList<>();
        private FrameDriver.Callback frame;
        private long nanos;
        private long requests;

        @Override public boolean isOwnerThread() { return Thread.currentThread() == owner; }
        @Override public boolean post(Runnable callback) { callback.run(); return true; }
        @Override public void remove(Runnable callback) { }
        @Override public boolean request(FrameDriver.Callback callback) {
            if (frame != null) throw new AssertionError("Duplicate platform frame");
            frame = callback; requests++; return true;
        }
        @Override public void remove(FrameDriver.Callback callback) {
            if (frame == callback) frame = null;
        }
        void tick() {
            FrameDriver.Callback pending = frame;
            if (pending == null) throw new AssertionError("No frame requested");
            frame = null; nanos += 16_666_667L; pending.onFrame(nanos);
        }
        boolean hasFrame() { return frame != null; }
        LifecycleScope activeScope() {
            LifecycleScope scope = new LifecycleScope(this, errors::add);
            scope.setAttached(true); scope.setStarted(true); return scope;
        }
    }

    public static void main(String[] args) {
        int mailboxCount = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int slotsPerMailbox = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 20_000;
        int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 5_000;
        int repetitions = args.length > 4 ? Integer.parseInt(args[4]) : 3;

        System.out.println("config mailboxes=" + mailboxCount + " slots=" + slotsPerMailbox
                + " frames=" + frames + " warmup=" + warmup + " repetitions=" + repetitions
                + " jvm=" + System.getProperty("java.version"));
        for (int repetition = 0; repetition < repetitions; repetition++) {
            BenchRuntime runtime = new BenchRuntime();
            Sample sample = run(runtime, mailboxCount, slotsPerMailbox, frames, warmup);
            System.out.println("RESULT repetition=" + repetition
                    + " frames=" + sample.frames
                    + " executed_ops=" + sample.ops
                    + " elapsed_ns=" + sample.elapsedNanos
                    + " ns_per_frame=" + (sample.elapsedNanos / sample.frames)
                    + " allocated_bytes=" + sample.allocatedBytes
                    + " allocated_bytes_per_frame=" + (sample.allocatedBytes / sample.frames)
                    + " gc_collections=" + sample.gcCollections
                    + " gc_ms=" + (sample.gcNanos / 1_000_000)
                    + " errors=" + runtime.errors.size());
            if (!runtime.errors.isEmpty()) throw new AssertionError(runtime.errors.get(0));
        }
    }

    private static final class Sample {
        long frames; long ops; long elapsedNanos;
        long allocatedBytes; long gcCollections; long gcNanos;
    }

    /** One deterministic pass: every frame offers fresh state/command work, then ticks. */
    private static Sample run(BenchRuntime runtime, int mailboxCount, int slotsPerMailbox, int frames, int warmup) {
        FrameScheduler scheduler = new FrameScheduler(runtime, runtime, runtime.errors::add, GLOBAL_QUOTA);
        List<LifecycleScope> scopes = new ArrayList<>();
        List<BoundedMailbox> boxes = new ArrayList<>();
        List<BoundedMailbox.Slot[]> slots = new ArrayList<>();
        List<WorkToken> tokens = new ArrayList<>();
        long[] executed = new long[mailboxCount];
        for (int m = 0; m < mailboxCount; m++) {
            LifecycleScope scope = runtime.activeScope();
            scopes.add(scope);
            tokens.add(scope.lease(0).token());
            BoundedMailbox box = new BoundedMailbox(scope, scheduler, runtime.errors::add, slotsPerMailbox, 64, PER_MAILBOX_QUOTA);
            boxes.add(box);
            BoundedMailbox.Slot[] owned = new BoundedMailbox.Slot[slotsPerMailbox];
            for (int s = 0; s < slotsPerMailbox; s++) owned[s] = box.newSlot();
            slots.add(owned);
        }

        Sample sample = new Sample();
        com.sun.management.ThreadMXBean threadMX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long allocatedBefore = threadMX.getThreadAllocatedBytes(threadId);
        long gcBefore = gcCount(gcBeans);
        long gcTimeBefore = gcTime(gcBeans);
        long start = System.nanoTime();
        long opsBefore = 0;

        for (int frame = 0; frame < frames; frame++) {
            if (frame == warmup) {
                // Rebase the measurement window at the frame boundary so JIT compilation is excluded.
                allocatedBefore = threadMX.getThreadAllocatedBytes(threadId);
                gcBefore = gcCount(gcBeans);
                gcTimeBefore = gcTime(gcBeans);
                start = System.nanoTime();
                sample.frames = 0;
                opsBefore = 0; for (long value : executed) opsBefore += value;
            }
            for (int m = 0; m < mailboxCount; m++) {
                final int index = m;
                BoundedMailbox box = boxes.get(m);
                BoundedMailbox.Slot[] owned = slots.get(m);
                WorkToken token = tokens.get(m);
                // 3 latest-state offers round-robin over the mailbox slots (coalescing stays bounded).
                for (int s = 0; s < 3; s++) {
                    BoundedMailbox.Slot slot = owned[(frame + s) % slotsPerMailbox];
                    box.offerState(slot, token, () -> executed[index]++, () -> { });
                }
                // 1 FIFO command per frame per mailbox.
                box.offerCommand(token, () -> executed[index]++, () -> { });
            }
            runtime.tick();
            if (frame >= warmup) sample.frames++;
        }

        sample.elapsedNanos = System.nanoTime() - start;
        sample.allocatedBytes = threadMX.getThreadAllocatedBytes(threadId) - allocatedBefore;
        sample.gcCollections = gcCount(gcBeans) - gcBefore;
        sample.gcNanos = gcTime(gcBeans) - gcTimeBefore;
        // executed_ops covers the measured window only, so it is comparable with the frame count.
        long opsTotal = 0; for (long value : executed) opsTotal += value;
        sample.ops = opsTotal - opsBefore;
        for (BoundedMailbox box : boxes) box.close();
        scheduler.close();
        for (LifecycleScope scope : scopes) scope.close();
        if (runtime.hasFrame()) throw new AssertionError("Frame left pending after close");
        return sample;
    }

    /** Collection counts are -1 on collectors without statistics; those are skipped, never summed. */
    private static long gcCount(List<GarbageCollectorMXBean> beans) {
        long total = 0;
        for (GarbageCollectorMXBean bean : beans) {
            long count = bean.getCollectionCount();
            if (count > 0) total += count;
        }
        return total;
    }

    private static long gcTime(List<GarbageCollectorMXBean> beans) {
        long total = 0;
        for (GarbageCollectorMXBean bean : beans) {
            long time = bean.getCollectionTime();
            if (time > 0) total += time;
        }
        return total;
    }
}
