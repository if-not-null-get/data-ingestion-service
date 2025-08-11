package io.conflictradar.ingestion.api.util;

import io.conflictradar.ingestion.config.RssConfig;
import org.springframework.stereotype.Component;

@Component
public class RssProps {
    private final int maxAttempts;
    private final long retryDelay;
    private final long scheduleIntervalMs;
    private final long initialDelayMs;

    public RssProps(RssConfig config) {
        this.maxAttempts = config.http().maxRetries();
        this.retryDelay = config.http().retryDelay();
        this.scheduleIntervalMs = config.processing().getScheduleIntervalMs();
        this.initialDelayMs = config.processing().getInitialDelayMs();
    }

    // retry
    public int getMaxAttempts() { return maxAttempts; }
    public long getRetryDelay() { return retryDelay; }

    // schedule
    public long getScheduleIntervalMs() { return scheduleIntervalMs; }
    public long getInitialDelayMs() { return initialDelayMs; }
}
