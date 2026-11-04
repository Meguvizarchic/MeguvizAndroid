/**
 * Host-only micro benchmark for the RetiringRegistry read path. Pure JVM; no Android, no
 * Robolectric, no device.
 *
 * Two modes share the same tryAcquire/close lock traffic so their delta isolates snapshot():
 *   steady  - N workers loop tryAcquire / snapshot().bind / close (mirrors p2-overhead).
 *   acquire - N workers loop tryAcquire / close only (no snapshot read).
 *
 * Every worker warms up first, then times its own measured window; the reported wall time is
 * the slowest worker window and CPU time is the sum across workers. Both modes perform the
 * same deterministic number of operations before and after a change; only RetiringRegistry.java
 * differs. The tool records throughput, wall time and CPU time; it never gates a release.
 * Invariant single-lease read loops are intentionally absent: the JIT can hoist them.
 *
 * Reproduce (JDK 17+):
 *   javac -d out <android-runtime core sources/*.java> RegistryReadBenchmark.java
 *   java -cp out RegistryReadBenchmark steady 4 12000 2000 5 1
 */
import io.github.jichuo1.meguviz.android.runtime.core.AdapterRegistry;
import io.github.jichuo1.meguviz.android.runtime.core.RetiringRegistry;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class RegistryReadBenchmark {
    private static final String CAPABILITY = "render";
    private static final String VALUE = "provider";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "steady";
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int ops = args.length > 2 ? Integer.parseInt(args[2]) : 12_000;
        int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 2_000;
        int repetitions = args.length > 4 ? Integer.parseInt(args[4]) : 5;
        int warmupRuns = args.length > 5 ? Integer.parseInt(args[5]) : 1;
        if (!mode.equals("steady") && !mode.equals("acquire")) throw new IllegalArgumentException("unknown mode: " + mode);
        System.out.println("config mode=" + mode + " workers=" + workers + " ops=" + ops
                + " warmup=" + warmup + " repetitions=" + repetitions + " warmup_runs=" + warmupRuns
                + " jvm=" + System.getProperty("java.version"));
        for (int warm = 0; warm < warmupRuns; warm++) run(mode, workers, ops, warmup);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            Sample sample = run(mode, workers, ops, warmup);
            System.out.println("RESULT repetition=" + repetition
                    + " ops=" + sample.ops
                    + " elapsed_ns=" + sample.elapsedNanos
                    + " ns_per_op=" + (sample.ops == 0 ? 0 : sample.elapsedNanos / sample.ops)
                    + " cpu_ns=" + sample.cpuNanos
                    + " rejected=" + sample.rejected
                    + " checksum=" + sample.checksum);
        }
    }

    private static Sample run(String mode, int workers, int ops, int warmup) throws Exception {
        AdapterRegistry<String> snapshot = registry();
        RetiringRegistry<String> owner = new RetiringRegistry<>(snapshot, workers, 2, () -> { });
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        List<Future<long[]>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                final int index = worker;
                final boolean read = mode.equals("steady");
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    long warm = 0;
                    for (int i = 0; i < warmup; i++) warm += step(owner, read, index);
                    long wallBefore = System.nanoTime(), cpuBefore = cpuNanos();
                    long success = 0, rejected = 0, sum = warm;
                    for (int i = 0; i < ops; i++) {
                        long row = step(owner, read, index);
                        if ((row & 1L) != 0L) { success++; sum += row >>> 1; } else rejected++;
                    }
                    long elapsed = System.nanoTime() - wallBefore;
                    long cpu = cpuNanos() - cpuBefore;
                    return new long[]{elapsed, cpu, success, rejected, sum};
                }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("workers not ready");
            start.countDown();
            long elapsed = 0, cpu = 0, success = 0, rejected = 0, sum = 0;
            for (Future<long[]> future : futures) {
                long[] row = future.get(60, TimeUnit.SECONDS);
                elapsed = Math.max(elapsed, row[0]);
                cpu += row[1]; success += row[2]; rejected += row[3]; sum += row[4];
            }
            owner.close().tryRelease();
            return new Sample(success, elapsed, cpu, rejected, sum);
        } finally {
            start.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("executor cleanup timeout");
        }
    }

    /** One acquire/use/close cycle; bit 0 = success, remaining bits = checksum. */
    private static long step(RetiringRegistry<String> owner, boolean read, int index) {
        RetiringRegistry.Lease<String> lease = owner.tryAcquire();
        if (lease == null) return 0L;
        try {
            long sum = index + 1;
            if (read) {
                if (!VALUE.equals(lease.snapshot().bind(CAPABILITY))) throw new AssertionError("bad bind");
                sum = sum * 31 + 7;
            }
            return (sum << 1) | 1L;
        } finally {
            lease.close();
        }
    }

    private static long cpuNanos() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    private static AdapterRegistry<String> registry() {
        AdapterRegistry.Descriptor descriptor = new AdapterRegistry.Descriptor("sample", "1", 1, 0, 0,
                Collections.singleton(CAPABILITY), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "any", "sample", "caller");
        return new AdapterRegistry<>(Collections.singletonList(new AdapterRegistry.Provider<>(descriptor, VALUE)),
                Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());
    }

    private static final class Sample {
        final long ops, elapsedNanos, cpuNanos, rejected, checksum;
        Sample(long ops, long elapsedNanos, long cpuNanos, long rejected, long checksum) {
            this.ops = ops; this.elapsedNanos = elapsedNanos; this.cpuNanos = cpuNanos;
            this.rejected = rejected; this.checksum = checksum;
        }
    }
}
