/**
 * Host-only benchmark: per-item offers versus one {@code BoundedMailbox.Offers} batch.
 * Pure JVM; no Android, no Robolectric, no device. Numbers are HotSpot numbers and say nothing
 * about ART or real frame pacing.
 *
 * Workload: {@code producers} background threads each submit {@code rounds} groups of
 * {@code batch} shorthand commands, either one offerCommand per item ("items") or one staged
 * batch per group ("batch"). One owner thread runs posted wakeups and delivers every requested
 * frame immediately (a zero-wait frame source), so producers contend with a live drain.
 *
 * Reported per run (after a warmup run in the same JVM):
 *   producer_ns_per_offer   wall time of the producer loops divided by offers
 *   producer_bytes_per_offer bytes allocated on producer threads per offer (ThreadMXBean)
 *   wakeups                  wakeups posted to the owner
 *   staging_to_run_p50/p99_us  time from the start of staging the group until its first item runs
 *
 * The latency stamp is taken before staging, so the batch figure also includes staging the other
 * items in the group; it is NOT a pure handoff/wakeup latency and must not be compared with the
 * per-item figure as if it were. Compare wakeups, producer_ns_per_offer and allocations instead.
 *
 * Method limits: one HotSpot JVM, a zero-wait frame source, no ABBA interleave, no p95/bootstrap CI
 * and no raw-output file. This is a directional probe for picking candidates; any number quoted as
 * a result needs an ABAB interleave over multiple processes with raw output saved to docs/perf.
 *
 * Reproduce (JDK 21+):
 *   javac -d out <android-runtime core sources/*.java> MailboxOffersBenchmark.java
 *   java -cp out MailboxOffersBenchmark <items|batch> [producers] [batch] [rounds] [live|solo]
 *
 * "solo" keeps the owner idle until the producers finish, isolating the admission cost from lock
 * contention with a live drain; "live" (default) drains concurrently.
 */
import io.github.jichuo1.meguviz.android.runtime.core.BoundedMailbox;
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.LifecycleScope;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import io.github.jichuo1.meguviz.android.runtime.core.WorkToken;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

public final class MailboxOffersBenchmark {
    static final class Runtime implements OwnerDispatcher, FrameDriver {
        final Thread owner = Thread.currentThread();
        final ConcurrentLinkedQueue<Runnable> posted = new ConcurrentLinkedQueue<>();
        final AtomicLong posts = new AtomicLong();
        FrameDriver.Callback frame;
        long nanos;
        @Override public boolean isOwnerThread() { return Thread.currentThread() == owner; }
        @Override public boolean post(Runnable callback) { posts.incrementAndGet(); posted.add(callback); return true; }
        @Override public void remove(Runnable callback) { posted.remove(callback); }
        @Override public boolean request(FrameDriver.Callback callback) { frame = callback; return true; }
        @Override public void remove(FrameDriver.Callback callback) { if (frame == callback) frame = null; }
        void pump() {
            Runnable next;
            while ((next = posted.poll()) != null) next.run();
            FrameDriver.Callback pending = frame;
            if (pending != null) { frame = null; nanos += 16_666_667L; pending.onFrame(nanos); }
        }
    }

    public static void main(String[] args) throws Exception {
        boolean batched = args[0].equals("batch");
        int producers = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int batch = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 20_000;
        boolean live = args.length <= 4 || !args[4].equals("solo");
        run(batched, producers, batch, rounds / 4, live, false);
        run(batched, producers, batch, rounds, live, true);
    }

    static void run(boolean batched, int producers, int batch, int rounds, boolean live, boolean report) throws Exception {
        Runtime runtime = new Runtime();
        LifecycleScope scope = new LifecycleScope(runtime, error -> { throw new AssertionError(error); });
        scope.setAttached(true); scope.setStarted(true);
        FrameScheduler scheduler = new FrameScheduler(runtime, runtime, error -> { throw new AssertionError(error); }, 1 << 16);
        WorkToken token = scope.lease(LifecycleScope.ALL).token();
        BoundedMailbox box = new BoundedMailbox(scope, scheduler, error -> { throw new AssertionError(error); },
                16, 1 << 20, 1 << 16);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] latencies = new long[producers * rounds];
        AtomicLong allocated = new AtomicLong();
        AtomicLong busyNanos = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        Runnable noop = () -> { };
        for (int p = 0; p < producers; p++) {
            int producer = p;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    BoundedMailbox.Offers offers = box.offers();
                    long id = Thread.currentThread().getId();
                    long bytesBefore = threads.getThreadAllocatedBytes(id);
                    long began = System.nanoTime();
                    for (int r = 0; r < rounds; r++) {
                        int slot = producer * rounds + r;
                        long submitted = System.nanoTime();
                        Runnable first = () -> latencies[slot] = System.nanoTime() - submitted;
                        if (batched) {
                            offers.command(token, first);
                            for (int i = 1; i < batch; i++) offers.command(token, noop);
                            if (offers.submit() != batch) throw new AssertionError("rejected");
                        } else {
                            if (!box.offerCommand(token, first).accepted()) throw new AssertionError("rejected");
                            for (int i = 1; i < batch; i++) if (!box.offerCommand(token, noop).accepted()) throw new AssertionError("rejected");
                        }
                    }
                    busyNanos.addAndGet(System.nanoTime() - began);
                    // The latency lambda is part of the workload in both modes; it is counted too.
                    allocated.addAndGet(threads.getThreadAllocatedBytes(id) - bytesBefore);
                } catch (Throwable error) { error.printStackTrace(); System.exit(2); }
                finally { done.countDown(); }
            });
            thread.start();
        }
        start.countDown();
        if (live) while (done.getCount() > 0) runtime.pump();
        else done.await();
        for (int i = 0; i < 64 && (box.pendingCommandCount() > 0 || runtime.frame != null || !runtime.posted.isEmpty()); i++) runtime.pump();
        if (box.pendingCommandCount() != 0) throw new AssertionError("work left behind");
        if (!report) return;
        long offers = (long) producers * rounds * batch;
        Arrays.sort(latencies);
        System.out.printf("drain=%s mode=%s producers=%d batch=%d rounds=%d producer_ns_per_offer=%.1f producer_bytes_per_offer=%.1f wakeups=%d staging_to_run_p50_us=%.1f staging_to_run_p99_us=%.1f%n",
                live ? "live" : "solo", batched ? "batch" : "items", producers, batch, rounds,
                busyNanos.get() / (double) offers, allocated.get() / (double) offers, runtime.posts.get(),
                latencies[latencies.length / 2] / 1000.0, latencies[(int) (latencies.length * 0.99)] / 1000.0);
        box.close(); scheduler.close(); scope.close();
    }
}
