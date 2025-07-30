package io.conflictradar.ingestion.api.dto.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Set;

public record NewsIngestedEvent(
        @JsonProperty("articleId") String articleId,
        @JsonProperty("title") String title,
        @JsonProperty("link") String link,
        @JsonProperty("source") String source,
        @JsonProperty("publishedAt") @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime publishedAt,
        @JsonProperty("riskScore") double riskScore,
        @JsonProperty("conflictKeywords") Set<String> conflictKeywords,
        @JsonProperty("processedAt") @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime processedAt
) {
    public static NewsIngestedEvent create(String articleId, String title, String link,
                                           String source, LocalDateTime publishedAt,
                                           double riskScore, Set<String> conflictKeywords) {
        return new NewsIngestedEvent(
                articleId, title, link, source, publishedAt,
                riskScore, conflictKeywords, LocalDateTime.now()
        );
    }
}
