package io.conflictradar.ingestion.api.service;

import io.conflictradar.ingestion.api.dto.RssArticle;
import io.conflictradar.ingestion.config.RssConfig;
import io.conflictradar.ingestion.config.RssSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class ScheduledRssService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduledRssService.class);

    private final RssDeduplicationService deduplicationService;
    private final EventPublisherService eventPublisher;
    private final RssParsingService rssParsingService;
    private final RssConfig rssConfig;

    public ScheduledRssService(RssParsingService rssParsingService,
                               RssDeduplicationService deduplicationService,
                               EventPublisherService eventPublisher,
                               RssConfig rssConfig) {
        this.rssParsingService = rssParsingService;
        this.deduplicationService = deduplicationService;
        this.eventPublisher = eventPublisher;
        this.rssConfig = rssConfig;
    }

    @Scheduled(
            fixedRateString = "#{@rssProps.scheduleIntervalMs}",
            initialDelayString = "#{@rssProps.initialDelayMs}"
    )
    public void parseAllRssFeeds() {
        List<RssSource> enabledSources = rssConfig.getEnabledSources();

        logger.info("Starting scheduled RSS parsing for {} enabled sources", enabledSources.size());
        long startTime = System.currentTimeMillis();

        int totalArticles = 0;
        int totalNewArticles = 0;

        for (RssSource source : enabledSources) {
            try {
                logger.debug("Parsing RSS from: {} ({})", source.name(), source.url());

                List<RssArticle> allArticles = rssParsingService.parseRssFromUrl(source.url());
                List<RssArticle> newArticles = filterNewArticles(allArticles);

                for (RssArticle article : newArticles) {
                    RssArticle analyzedArticle = analyzeConflictRisk(article, source);

                    eventPublisher.publishNewsIngested(analyzedArticle);

                    if (analyzedArticle.riskScore() > rssConfig.processing().riskThreshold()) {
                        eventPublisher.publishHighRiskDetected(analyzedArticle);
                    }
                }

                totalArticles += allArticles.size();
                totalNewArticles += newArticles.size();

                logger.info("Processed {} (weight: {}): {} total, {} new articles",
                        source.getSimpleName(), source.weight(),
                        allArticles.size(), newArticles.size());

            } catch (Exception e) {
                logger.error("Failed to parse RSS from {}: {}", source.name(), e.getMessage());
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

    private RssArticle analyzeConflictRisk(RssArticle article, RssSource source) {
        String text = (article.title() + " " + article.description()).toLowerCase();

        var foundKeywords = rssConfig.riskAnalysis().findKeywords(text);
        var riskScore = calculateRiskScore(foundKeywords, source.weight());

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

    private double calculateRiskScore(Set<String> conflictKeywords, double sourceWeight) {
        if (conflictKeywords.isEmpty()) return 0.0;

        var baseScore = Math.min(conflictKeywords.size() * 0.15, 0.8);

        boolean hasHighRisk = conflictKeywords.stream()
                .anyMatch(keyword -> rssConfig.riskAnalysis().highRiskKeywords().contains(keyword));

        boolean hasCritical = conflictKeywords.stream()
                .anyMatch(keyword -> rssConfig.riskAnalysis().criticalKeywords().contains(keyword));

        if (hasCritical) {
            baseScore = Math.min(baseScore + 0.4, 1.0);
        } else if (hasHighRisk) {
            baseScore = Math.min(baseScore + 0.25, 1.0);
        }

        baseScore = baseScore * sourceWeight;

        return Math.round(baseScore * 100.0) / 100.0;
    }
}