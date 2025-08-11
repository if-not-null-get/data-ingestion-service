package io.conflictradar.ingestion.config;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public record RiskAnalysis(
        Set<String> conflictKeywords,
        Set<String> highRiskKeywords,
        Set<String> criticalKeywords
) {
    public Set<String> findKeywords(String text) {
        if (text == null) return Set.of();

        String lowerText = text.toLowerCase();

        Set<String> allPossibleKeywords = new HashSet<>();
        allPossibleKeywords.addAll(conflictKeywords);
        allPossibleKeywords.addAll(highRiskKeywords);
        allPossibleKeywords.addAll(criticalKeywords);

        return allPossibleKeywords.stream()
                .filter(lowerText::contains)
                .collect(java.util.stream.Collectors.toSet());
    }
}
