import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryTimeoutIdempotencyDrill {
    private static final ExecutorService IO_POOL = Executors.newFixedThreadPool(8);
    private static final ExecutorService CPU_POOL = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
    );
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(1);

    // In-memory idempotency store (demo only)
    private static final Set<String> PROCESSED = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) {
        AtomicInteger retries = new AtomicInteger();
        String eventId = "evt-123";

        CompletableFuture<String> result = processEvent(eventId, retries)
                .exceptionally(ex -> "DLQ:" + eventId + ":" + rootCause(ex));

        System.out.println(result.join());
        System.out.println("Retries=" + retries.get());

        shutdown();
    }

    static CompletableFuture<String> processEvent(String eventId, AtomicInteger retries) {
        // Idempotency guard
        if (!PROCESSED.add(eventId)) {
            return CompletableFuture.completedFuture("SKIP_DUPLICATE:" + eventId);
        }

        return withTimeout(
                retryAsync(() ->
                        CompletableFuture.supplyAsync(() -> ingest(eventId), IO_POOL)
                                .thenApplyAsync(RetryTimeoutIdempotencyDrill::transform, CPU_POOL)
                                .thenComposeAsync(RetryTimeoutIdempotencyDrill::routeAsync, IO_POOL),
                        3, Duration.ofMillis(200), retries
                ),
                Duration.ofSeconds(2)
        );
    }

    static String ingest(String id) {
        sleep(50);
        return "payload-" + id;
    }

    static String transform(String payload) {
        for (int i = 0; i < 20_000; i++) Math.sqrt(i);
        return payload.toUpperCase();
    }

    static CompletableFuture<String> routeAsync(String transformed) {
        return CompletableFuture.supplyAsync(() -> {
            sleep(80);
            if (ThreadLocalRandom.current().nextInt(4) == 0) {
                throw new RuntimeException("transient sink error");
            }
            return "ROUTED:" + transformed;
        }, IO_POOL);
    }

    static <T> CompletableFuture<T> retryAsync(
            SupplierWithFuture<T> op, int maxAttempts, Duration backoff, AtomicInteger retryCounter
    ) {
        CompletableFuture<T> out = new CompletableFuture<>();
        attempt(op, 1, maxAttempts, backoff, retryCounter, out);
        return out;
    }

    static <T> void attempt(
            SupplierWithFuture<T> op, int attempt, int maxAttempts, Duration backoff,
            AtomicInteger retryCounter, CompletableFuture<T> out
    ) {
        op.get().whenComplete((val, ex) -> {
            if (ex == null) {
                out.complete(val);
                return;
            }
            if (attempt >= maxAttempts) {
                out.completeExceptionally(ex);
                return;
            }
            retryCounter.incrementAndGet();
            TIMER.schedule(
                    () -> attempt(op, attempt + 1, maxAttempts, backoff, retryCounter, out),
                    backoff.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        });
    }

    static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> cf, Duration timeout) {
        CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
        TIMER.schedule(
                () -> timeoutFuture.completeExceptionally(new TimeoutException("pipeline timeout")),
                timeout.toMillis(),
                TimeUnit.MILLISECONDS
        );
        return cf.applyToEither(timeoutFuture, v -> v);
    }

    static String rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ":" + t.getMessage();
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void shutdown() {
        IO_POOL.shutdown();
        CPU_POOL.shutdown();
        TIMER.shutdown();
    }

    @FunctionalInterface
    interface SupplierWithFuture<T> {
        CompletableFuture<T> get();
    }
}
