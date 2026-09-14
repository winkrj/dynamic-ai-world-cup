package dev.worldcup.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worldcup.retention.enabled", havingValue = "true")
public class RetentionScheduler {
    private final RetentionService retention;
    public RetentionScheduler(RetentionService retention) { this.retention = retention; }
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void clean() { retention.clean(); }
}
