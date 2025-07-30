package io.conflictradar.ingestion.api;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.conflictradar.ingestion.api.dto.FeedRequest;
import io.conflictradar.ingestion.api.dto.RssArticle;
import io.conflictradar.ingestion.api.dto.SourcesInfo;
import io.conflictradar.ingestion.api.service.EventPublisherService;
import io.conflictradar.ingestion.api.service.RssDeduplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/rss")
public class RSSController {

    private static final Logger logger = LoggerFactory.getLogger(RSSController.class);

    private final RssDeduplicationService deduplicationService;
    private final EventPublisherService eventPublisher;

    public RSSController(RssDeduplicationService deduplicationService, EventPublisherService eventPublisher) {
        this.deduplicationService = deduplicationService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean eventPublisherHealthy = eventPublisher.isHealthy();
        var stats = eventPublisher.getStats();

        var healthInfo = Map.of(
            "status", eventPublisherHealthy ? "UP" : "DOWN",
            "service", "ConflictRadar Data Ingestion Service",
            "timestamp", LocalDateTime.now(),
            "messaging", Map.of(
                "healthy", eventPublisherHealthy,
                "totalPublished", stats.totalPublished(),
                "successRate", String.format("%.2f%%", stats.getSuccessRate() * 100)
            )
        );

        return eventPublisherHealthy ?
            ResponseEntity.ok(healthInfo) :
            ResponseEntity.status(503).body(healthInfo);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var stats = eventPublisher.getStats();
        return ResponseEntity.ok(Map.of(
            "message", "Ready to ingest conflict data from news sources",
            "statistics", Map.of(
                "totalEventsPublished", stats.totalPublished(),
                "totalEventsFailed", stats.totalFailed(),
                "averageProcessingTime", String.format("%.2fms", stats.getAverageProcessingTime()),
                "successRate", String.format("%.2f%%", stats.getSuccessRate() * 100)
            )
        ));
    }

    @GetMapping("/feeds")
    public ResponseEntity<List<RssArticle>> getFeeds(@RequestParam String url) {
        try {
            var allArticles = parseRssFromUrl(url);
            var newArticles = filterNewArticles(allArticles);

            newArticles.forEach(article -> {
                RssArticle analyzedArticle = analyzeConflictRisk(article);
                eventPublisher.publishNewsIngested(analyzedArticle);

                if (analyzedArticle.riskScore() > 0.6) {
                    eventPublisher.publishHighRiskDetected(analyzedArticle);
                }
            });

            eventPublisher.publishBatchProcessed("manual-request",
                    allArticles.size(),
                    newArticles.size());

            return ResponseEntity.ok(newArticles);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/feeds")
    public ResponseEntity<List<RssArticle>> createFeedAnalysis(@RequestBody FeedRequest request) {
        try {
            var articles = parseRssFromUrl(request.url());
            var analyzed = articles.stream()
                    .map(this::analyzeConflictRisk)
                    .sorted((a, b) -> Double.compare(b.riskScore(), a.riskScore()))
                    .toList();

            return ResponseEntity.ok(analyzed);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/feeds/bbc")
    public List<RssArticle> getBbcFeed() {
        return parseRssFromUrl("https://feeds.bbci.co.uk/news/world/rss.xml");
    }

    @GetMapping("/feeds/reuters")
    public List<RssArticle> getReutersFeed() {
        return parseRssFromUrl("https://www.reuters.com/rssFeed/worldNews");
    }

    @GetMapping("/feeds/cnn")
    public List<RssArticle> getCnnFeed() {
        return parseRssFromUrl("http://rss.cnn.com/rss/edition.rss");
    }

    @GetMapping("/feeds/{source}/analysis")
    public List<RssArticle> getSourceAnalysis(@PathVariable String source) {
        var url = switch (source.toLowerCase()) {
            case "bbc" -> "https://feeds.bbci.co.uk/news/world/rss.xml";
            case "reuters" -> "https://www.reuters.com/rssFeed/worldNews";
            case "cnn" -> "http://rss.cnn.com/rss/edition.rss";
            default -> throw new IllegalArgumentException("Unknown source: " + source);
        };

        return parseRssFromUrl(url).stream()
                .map(this::analyzeConflictRisk)
                .sorted((a, b) -> Double.compare(b.riskScore(), a.riskScore()))
                .toList();
    }

    @GetMapping("/sources")
    public SourcesInfo getSources() {
        var sources = Map.of(
                "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml",
                "Reuters World", "https://www.reuters.com/rssFeed/worldNews",
                "CNN International", "http://rss.cnn.com/rss/edition.rss"
        );

        return new SourcesInfo(sources, sources.size(), LocalDateTime.now());
    }

    @GetMapping("/scheduled/trigger")
    public ResponseEntity<String> triggerScheduledParsing() {
        // Для тестирования - ручной запуск scheduled парсинга
        // В реальном приложении это было бы через отдельный admin endpoint
        return ResponseEntity.ok("Manual trigger endpoint - use for testing scheduled parsing");
    }

    @GetMapping("/scheduled/status")
    public ResponseEntity<Map<String, Object>> getScheduledStatus() {
        // Простая статистика scheduled парсинга
        return ResponseEntity.ok(Map.of(
                "scheduledParsingEnabled", true,
                "intervalMinutes", 5,
                "sources", List.of("BBC", "Reuters", "CNN"),
                "nextRunInfo", "Runs every 5 minutes automatically"
        ));
    }

    // Private helper methods

    private List<RssArticle> parseRssFromUrl(String url) {
        try {
            URL feedUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) feedUrl.openConnection();
            connection.setRequestProperty("User-Agent", "ConflictRadar/1.0");
            connection.setInstanceFollowRedirects(true);

            var input = new SyndFeedInput();
            var feed = input.build(new XmlReader(connection.getInputStream()));

            return feed.getEntries().stream()
                    .map(this::convertToRecord)
                    .toList();

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse RSS from: " + url, e);
        }
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

    private RssArticle convertToRecord(SyndEntry entry) {
        var publishedAt = entry.getPublishedDate() != null
                ? entry.getPublishedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();

        var description = entry.getDescription() != null
                ? entry.getDescription().getValue()
                : "";

        return new RssArticle(
                UUID.randomUUID().toString(),
                entry.getTitle(),
                description,
                entry.getLink(),
                entry.getAuthor(),
                publishedAt,
                Set.of(),
                0.0
        );
    }

    private RssArticle analyzeConflictRisk(RssArticle article) {
        var conflictKeywords = Set.of(
                "war", "conflict", "attack", "violence", "protest", "crisis",
                "terrorism", "bomb", "shooting", "riot", "strike", "sanctions"
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

        var baseScore = Math.min(conflictKeywords.size() * 0.2, 1.0);

        var highRiskWords = Set.of("war", "terrorism", "bomb", "attack");
        var hasHighRisk = conflictKeywords.stream().anyMatch(highRiskWords::contains);

        if (hasHighRisk) {
            baseScore = Math.min(baseScore + 0.3, 1.0);
        }

        return Math.round(baseScore * 100.0) / 100.0;
    }
}