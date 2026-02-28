import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncPipelineDrill {
    // Backpressure via bounded queue + rejection policy
    private static final ThreadPoolExecutor IO_POOL = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(500),
            new ThreadPoolExecutor.CallerRunsPolicy() // slows producers when saturated
    );

    private static final ExecutorService CPU_POOL =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public static void main(String[] args) {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        AtomicInteger rejectedOrSlowed = new AtomicInteger();

        for (int i = 0; i < 500; i++) {
            int id = i;
            CompletableFuture<String> f = CompletableFuture
                    .supplyAsync(() -> ingest(id, rejectedOrSlowed), IO_POOL)
                    .thenApplyAsync(AsyncPipelineDrill::transform, CPU_POOL)
                    .thenApplyAsync(AsyncPipelineDrill::route, IO_POOL)
                    .exceptionally(ex -> "DLQ:event-" + id + ":" + ex.getMessage());

            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long ok = futures.stream().map(CompletableFuture::join).filter(s -> s.startsWith("ROUTED")).count();
        long dlq = futures.size() - ok;
        System.out.println("Processed=" + futures.size() + " ok=" + ok + " dlq=" + dlq);
        System.out.println("QueueSize=" + IO_POOL.getQueue().size() + " Active=" + IO_POOL.getActiveCount());
        System.out.println("CallerRuns(backpressure hits approx)=" + rejectedOrSlowed.get());

        shutdown();
    }

    static String ingest(int id, AtomicInteger slowed) {
        if (Thread.currentThread().getName().contains("main")) slowed.incrementAndGet();
        sleep(10); // simulate I/O
        return "event-" + id;
    }

    static String transform(String e) {
        // simulate CPU work
        for (int i = 0; i < 10_000; i++) { Math.sqrt(i); }
        return e.toUpperCase();
    }

    static String route(String e) {
        sleep(5); // simulate I/O sink
        if (e.hashCode() % 97 == 0) throw new RuntimeException("sink-failure");
        return "ROUTED:" + e;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    static void shutdown() {
        IO_POOL.shutdown();
        CPU_POOL.shutdown();
    }
}
