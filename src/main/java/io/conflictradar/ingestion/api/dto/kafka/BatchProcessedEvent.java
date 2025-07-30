package io.conflictradar.ingestion.api.dto.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record BatchProcessedEvent(
        @JsonProperty("batchId") String batchId,
        @JsonProperty("source") String source,
        @JsonProperty("totalArticles") int totalArticles,
        @JsonProperty("newArticles") int newArticles,
        @JsonProperty("highRiskArticles") int highRiskArticles,
        @JsonProperty("processingDurationMs") long processingDurationMs,
        @JsonProperty("processedAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime processedAt
) {
    public static BatchProcessedEvent create(String source, int totalArticles,
                                             int newArticles, int highRiskArticles,
                                             long processingDurationMs) {
        return new BatchProcessedEvent(
                "BATCH-" + System.currentTimeMillis(),
                source,
                totalArticles,
                newArticles,
                highRiskArticles,
                processingDurationMs,
                LocalDateTime.now()
        );
    }
}
