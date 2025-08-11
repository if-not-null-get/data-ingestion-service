package io.conflictradar.ingestion.config;

import java.time.Duration;

public record ProcessingConfig(
        Duration scheduleInterval,
        Duration initialDelay,
        double riskThreshold,
        boolean enableScheduling
) {
    public long getScheduleIntervalMs() {
        return scheduleInterval.toMillis();
    }

    public long getInitialDelayMs() {
        return initialDelay.toMillis();
    }
}
