package io.conflictradar.ingestion.api.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record SourcesInfo(
        Map<String, String> availableSources,
        int totalSources,
        LocalDateTime lastUpdated
) {}
