package io.conflictradar.ingestion.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "rss")
public record RssConfig(
        List<RssSource> sources,
        ProcessingConfig processing,
        HttpConfig http,
        RiskAnalysis riskAnalysis
) {

    public List<RssSource> getEnabledSources() {
        return sources.stream()
                .filter(RssSource::enabled)
                .toList();
    }

    //@PostConstruct
    public void init() {
        System.out.println("=== RssConfig bean created ===");
        System.out.println("Sources count: " + sources.size());
        System.out.println("Risk threshold: " + processing.riskThreshold());
    }
}
