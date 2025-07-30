package io.conflictradar.ingestion.api.dto;

public record FeedRequest(
        String url,
        boolean analyzeConflicts
) {}
