package de.zolynski.kv.benchmarks;

import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreConfig;
import de.zolynski.kv.shard.store.ConcurrentHashMapStore;
import de.zolynski.kv.shard.store.ConcurrentStripedLinkedListStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/// Compares the handwritten store against the JDK baseline.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
public class StoreBenchmark {

    ///  Large enough that the working set leaves L2.
    private static final int ENTRIES = 500_000;

    @Param({"chained", "concurrentHashMap"})
    public String implementation;

    private Store store;
    private String[] keys;

    @State(Scope.Thread)
    public static class Threadlocal {
        /// Per-thread RNG so we don't measure a contested random number generator instead.
        final SplittableRandom random = new SplittableRandom(Thread.currentThread().threadId());
        int writeCounter;
    }

    @Setup(Level.Trial)
    public void setUp() {
        store = switch (implementation) {
            case "chained" -> new ConcurrentStripedLinkedListStore(StoreConfig.unbounded());
            case "concurrentHashMap" -> new ConcurrentHashMapStore(StoreConfig.unbounded());
            default -> throw new IllegalArgumentException(implementation);
        };
        keys = new String[ENTRIES];
        for (int i = 0; i < ENTRIES; i++) {
            keys[i] = "user:" + i + ":profile";
            store.put(keys[i], "value-" + i);
        }
    }

    /// Pure read throughput.
    @Benchmark
    public void get(Threadlocal local, Blackhole blackhole) {
        blackhole.consume(store.get(keys[local.random.nextInt(ENTRIES)]));
    }

    /// Reads of keys that are not present.
    @Benchmark
    public void getMissing(Threadlocal local, Blackhole blackhole) {
        blackhole.consume(store.get("absent:" + local.random.nextInt(ENTRIES)));
    }

    /// Overwrites existing keys to keep store size stable.
    @Benchmark
    public void put(Threadlocal local, Blackhole blackhole) {
        int index = local.random.nextInt(ENTRIES);
        blackhole.consume(store.put(keys[index], "updated-" + local.writeCounter++));
    }

    /// 90:10 read/write, which is closer to how a cache-shaped workload actually behaves.
    @Benchmark
    public void mixed90Read(Threadlocal local, Blackhole blackhole) {
        int index = local.random.nextInt(ENTRIES);
        if (local.random.nextInt(10) == 0) {
            blackhole.consume(store.put(keys[index], "updated-" + local.writeCounter++));
        } else {
            blackhole.consume(store.get(keys[index]));
        }
    }
}
