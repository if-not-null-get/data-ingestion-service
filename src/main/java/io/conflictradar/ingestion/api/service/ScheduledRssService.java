package io.conflictradar.ingestion.api.service;

import io.conflictradar.ingestion.api.dto.RssArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class ScheduledRssService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduledRssService.class);

    private final List<String> RSS_SOURCES = List.of(
            "https://feeds.bbci.co.uk/news/world/rss.xml",
            "https://www.reuters.com/rssFeed/worldNews",
            "http://rss.cnn.com/rss/edition.rss"
    );

    private final RssDeduplicationService deduplicationService;
    private final EventPublisherService eventPublisher;
    private final RssParsingService rssParsingService;

    public ScheduledRssService(RssParsingService rssParsingService,
                               RssDeduplicationService deduplicationService,
                               EventPublisherService eventPublisher) {
        this.rssParsingService = rssParsingService;
        this.deduplicationService = deduplicationService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedRate = 300000, initialDelay = 30000)
    public void parseAllRssFeeds() {
        logger.info("Starting scheduled RSS parsing for {} sources", RSS_SOURCES.size());
        long startTime = System.currentTimeMillis();

        int totalArticles = 0;
        int totalNewArticles = 0;

        for (String rssUrl : RSS_SOURCES) {
            try {
                List<RssArticle> allArticles = rssParsingService.parseRssFromUrl(rssUrl);
                List<RssArticle> newArticles = filterNewArticles(allArticles);

                for (RssArticle article : newArticles) {
                    RssArticle analyzedArticle = analyzeConflictRisk(article);

                    eventPublisher.publishNewsIngested(analyzedArticle);

                    if (analyzedArticle.riskScore() > 0.6) {
                        eventPublisher.publishHighRiskDetected(analyzedArticle);
                    }
                }

                totalArticles += allArticles.size();
                totalNewArticles += newArticles.size();

                logger.info("Processed {}: {} total, {} new articles",
                        extractSourceName(rssUrl), allArticles.size(), newArticles.size());

            } catch (Exception e) {
                logger.error("Failed to parse RSS from {}: {}", rssUrl, e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        eventPublisher.publishBatchProcessed("scheduled-batch", totalArticles, totalNewArticles);

        logger.info("Scheduled RSS parsing completed: {} total, {} new articles in {}ms",
                totalArticles, totalNewArticles, duration);
    }


    private List<RssArticle> filterNewArticles(List<RssArticle> articles) {
        return articles.stream()
                .filter(article -> {
                    if (deduplicationService.isAlreadyProcessed(article.link())) {
                        return false;
                    } else {
                        deduplicationService.markAsProcessed(article.link());
                        return true;
                    }
                })
                .toList();
    }

    private RssArticle analyzeConflictRisk(RssArticle article) {
        var conflictKeywords = Set.of(
                "war", "conflict", "attack", "violence", "protest", "crisis",
                "terrorism", "bomb", "shooting", "riot", "strike", "sanctions",
                "military", "battle", "invasion", "occupation", "rebellion"
        );

        var text = (article.title() + " " + article.description()).toLowerCase();

        var foundKeywords = conflictKeywords.stream()
                .filter(text::contains)
                .collect(java.util.stream.Collectors.toSet());

        var riskScore = calculateRiskScore(foundKeywords, text);

        return new RssArticle(
                article.id(),
                article.title(),
                article.description(),
                article.link(),
                article.author(),
                article.publishedAt(),
                foundKeywords,
                riskScore
        );
    }

    private double calculateRiskScore(Set<String> conflictKeywords, String text) {
        if (conflictKeywords.isEmpty()) return 0.0;

        var baseScore = Math.min(conflictKeywords.size() * 0.15, 0.8);

        var highRiskWords = Set.of("war", "terrorism", "bomb", "attack", "invasion", "battle");
        var criticalWords = Set.of("nuclear", "chemical", "genocide", "massacre");

        boolean hasHighRisk = conflictKeywords.stream().anyMatch(highRiskWords::contains);
        boolean hasCritical = conflictKeywords.stream().anyMatch(criticalWords::contains);

        if (hasCritical) {
            baseScore = Math.min(baseScore + 0.4, 1.0);
        } else if (hasHighRisk) {
            baseScore = Math.min(baseScore + 0.25, 1.0);
        }

        return Math.round(baseScore * 100.0) / 100.0;
    }

    private String extractSourceName(String url) {
        if (url.contains("bbc")) return "BBC";
        if (url.contains("reuters")) return "Reuters";
        if (url.contains("cnn")) return "CNN";
        return "Unknown";
    }
}
