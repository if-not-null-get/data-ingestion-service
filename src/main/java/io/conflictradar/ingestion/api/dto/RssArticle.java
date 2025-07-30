package io.conflictradar.ingestion.api.dto;

import java.time.LocalDateTime;
import java.util.Set;

public record RssArticle(
        String id,
        String title,
        String description,
        String link,
        String author,
        LocalDateTime publishedAt,
        Set<String> conflictKeywords,
        double riskScore
) {}
