package io.conflictradar.ingestion.api.dto.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Set;

public record HighRiskDetectedEvent(
        @JsonProperty("alertId") String alertId,
        @JsonProperty("articleId") String articleId,
        @JsonProperty("title") String title,
        @JsonProperty("riskScore") double riskScore,
        @JsonProperty("triggerKeywords") Set<String> triggerKeywords,
        @JsonProperty("source") String source,
        @JsonProperty("detectedAt") @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime detectedAt
) {
    public static HighRiskDetectedEvent create(String articleId, String title,
                                               double riskScore, Set<String> triggerKeywords,
                                               String source) {
        return new HighRiskDetectedEvent(
                "ALERT-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                articleId, title, riskScore, triggerKeywords, source, LocalDateTime.now()
        );
    }
}
