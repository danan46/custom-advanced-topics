import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class LagBackpressureDrill {
    private static final int CAPACITY = 100;
    private static final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);

    private static final AtomicLong produced = new AtomicLong();
    private static final AtomicLong consumed = new AtomicLong();
    private static final AtomicLong rejected = new AtomicLong();

    public static void main(String[] args) throws Exception {
        ExecutorService producer = Executors.newFixedThreadPool(2);
        ExecutorService consumer = Executors.newFixedThreadPool(1);
        ScheduledExecutorService metrics = Executors.newScheduledThreadPool(1);

        // Producer (faster)
        producer.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                String e = "evt-" + produced.incrementAndGet();
                if (!queue.offer(e)) rejected.incrementAndGet(); // backpressure signal
                sleep(5);
            }
        });

        // Consumer (slower, creates lag)
        consumer.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try{
                    String e = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (e != null) {
                        consumed.incrementAndGet();
                        sleep(20);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Metrics loop
        metrics.scheduleAtFixedRate(() -> {
            long lag = produced.get() - consumed.get();
            int q = queue.size();
            double fillPct = (q * 100.0) / CAPACITY;
            System.out.printf("produced=%d consumed=%d lag=%d queue=%d(%.1f%%) rejected=%d%n",
                    produced.get(), consumed.get(), lag, q, fillPct, rejected.get());
        }, 0, 1, TimeUnit.SECONDS);

        Thread.sleep(15000);
        producer.shutdownNow();
        consumer.shutdownNow();
        metrics.shutdownNow();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
