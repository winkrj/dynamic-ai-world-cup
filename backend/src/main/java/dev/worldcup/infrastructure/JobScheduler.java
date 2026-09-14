package dev.worldcup.infrastructure;

import dev.worldcup.generation.GenerationWorker;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worldcup.worker.enabled", havingValue = "true")
public class JobScheduler {
    private final GenerationWorker worker;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().name("candidate-worker-", 0).daemon().factory());
    public JobScheduler(GenerationWorker worker) { this.worker = worker; }
    @Scheduled(fixedDelayString = "${worldcup.worker.poll-ms}")
    public void poll() {
        worker.recoverExpired();
        for (int i = 0; i < 2; i++) {
            try { executor.execute(worker::runOne); }
            catch (RejectedExecutionException busy) { break; }
        }
    }
    @PreDestroy void stop() { executor.shutdownNow(); }
}
