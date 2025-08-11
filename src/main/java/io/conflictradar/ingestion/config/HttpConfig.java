package io.conflictradar.ingestion.config;

import java.util.List;

public record HttpConfig(
        int connectTimeout,
        int readTimeout,
        int maxRetries,
        int retryDelay,
        List<String> userAgents
) {}