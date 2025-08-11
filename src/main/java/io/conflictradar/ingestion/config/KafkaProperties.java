package io.conflictradar.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kafka.topics")
public record KafkaProperties(
        String newsIngested,
        String highRiskDetected,
        String batchProcessed
) {}
