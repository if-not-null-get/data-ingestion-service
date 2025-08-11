package io.conflictradar.ingestion;

import io.conflictradar.ingestion.api.dto.RssArticle;
import io.conflictradar.ingestion.api.service.EventPublisherService;
import io.conflictradar.ingestion.api.service.RssDeduplicationService;
import io.conflictradar.ingestion.api.service.RssParsingService;
import io.conflictradar.ingestion.api.service.ScheduledRssService;
import io.conflictradar.ingestion.config.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledRssServiceTest {

    @Mock
    private RssParsingService rssParsingService;

    @Mock
    private RssDeduplicationService deduplicationService;

    @Mock
    private EventPublisherService eventPublisher;

    private ScheduledRssService service;

    @BeforeEach
    void setUp() {
        List<RssSource> sources = List.of(
                new RssSource("https://bbc.com/rss", "BBC News", 1.0, true),
                new RssSource("https://reuters.com/rss", "Reuters", 0.9, true),
                new RssSource("https://disabled.com/rss", "Disabled Source", 0.8, false)
        );

        ProcessingConfig processing = new ProcessingConfig(
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                0.6,
                true
        );

        RiskAnalysis riskAnalysis = new RiskAnalysis(
                Set.of("war", "conflict", "attack", "terrorism", "terrorist", "violence", "bomb", "nuclear", "genocide"),  // ← добавил: "terrorist", "bomb", "nuclear", "genocide"
                Set.of("war", "terrorism", "terrorist", "bomb"),            // ← добавил: "terrorist"
                Set.of("nuclear", "genocide")
        );

        HttpConfig httpConfig = new HttpConfig(10000, 30000, 3, 1000, List.of("TestAgent"));

        RssConfig rssConfig = new RssConfig(sources, processing, httpConfig, riskAnalysis);
        service = new ScheduledRssService(rssParsingService, deduplicationService, eventPublisher, rssConfig);
    }

    @Test
    @DisplayName("Should process only enabled RSS sources")
    void shouldProcessOnlyEnabledRssSources() {
        RssArticle testArticle = createTestArticle("Test Article", "Normal content");
        when(rssParsingService.parseRssFromUrl(anyString())).thenReturn(List.of(testArticle));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        verify(rssParsingService, times(2)).parseRssFromUrl(anyString()); // Only enabled sources
        verify(rssParsingService).parseRssFromUrl("https://bbc.com/rss");
        verify(rssParsingService).parseRssFromUrl("https://reuters.com/rss");
        verify(rssParsingService, never()).parseRssFromUrl("https://disabled.com/rss"); // Disabled source
    }

    @Test
    @DisplayName("Should detect conflict keywords correctly")
    void shouldDetectConflictKeywordsCorrectly() {


        RssArticle conflictArticle = createTestArticle(
                "War breaks out in region",
                "Military conflict escalates with bomb attacks"
        );

        when(rssParsingService.parseRssFromUrl(anyString())).thenReturn(List.of(conflictArticle));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        ArgumentCaptor<RssArticle> articleCaptor = ArgumentCaptor.forClass(RssArticle.class);
        verify(eventPublisher, atLeastOnce()).publishNewsIngested(articleCaptor.capture());

        RssArticle processedArticle = articleCaptor.getValue();
        assertThat(processedArticle.conflictKeywords()).contains("war", "conflict", "bomb");
        assertThat(processedArticle.riskScore()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Should calculate risk score based on keywords")
    void shouldCalculateRiskScoreBasedOnKeywords() {
        RssArticle highRiskArticle = createTestArticle(
                "Terrorist attack in war zone",
                "Violence escalates as military conflict continues"
        );

        when(rssParsingService.parseRssFromUrl(anyString())).thenReturn(List.of(highRiskArticle));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        ArgumentCaptor<RssArticle> articleCaptor = ArgumentCaptor.forClass(RssArticle.class);
        verify(eventPublisher, atLeastOnce()).publishNewsIngested(articleCaptor.capture());

        RssArticle processedArticle = articleCaptor.getValue();
        assertThat(processedArticle.conflictKeywords()).contains("war", "terrorist", "attack", "violence", "conflict");
        assertThat(processedArticle.riskScore()).isGreaterThan(0.5); // High risk due to multiple keywords
    }

    @Test
    @DisplayName("Should apply high-risk bonus for dangerous keywords")
    void shouldApplyHighRiskBonusForDangerousKeywords() {
        RssArticle terrorismArticle = createTestArticle("Terrorism threat", "Bomb attack planned");
        RssArticle normalArticle = createTestArticle("Economic news", "Trade agreements discussed");

        when(rssParsingService.parseRssFromUrl("https://bbc.com/rss"))
                .thenReturn(List.of(terrorismArticle));
        when(rssParsingService.parseRssFromUrl("https://reuters.com/rss"))
                .thenReturn(List.of(normalArticle));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        ArgumentCaptor<RssArticle> articleCaptor = ArgumentCaptor.forClass(RssArticle.class);
        verify(eventPublisher, times(2)).publishNewsIngested(articleCaptor.capture());

        List<RssArticle> processedArticles = articleCaptor.getAllValues();

        RssArticle highRiskProcessed = processedArticles.stream()
                .filter(a -> a.title().contains("Terrorism"))
                .findFirst().orElseThrow();

        RssArticle normalProcessed = processedArticles.stream()
                .filter(a -> a.title().contains("Economic"))
                .findFirst().orElseThrow();

        assertThat(highRiskProcessed.riskScore()).isGreaterThan(normalProcessed.riskScore());
        assertThat(highRiskProcessed.conflictKeywords()).contains("terrorism", "bomb");
    }

    @Test
    @DisplayName("Should apply critical bonus for extreme keywords")
    void shouldApplyCriticalBonusForExtremeKeywords() {
        RssArticle criticalArticle = createTestArticle(
                "Nuclear threat escalates",
                "Genocide concerns raised by officials"
        );

        when(rssParsingService.parseRssFromUrl("https://bbc.com/rss"))
                .thenReturn(List.of(criticalArticle));
        when(rssParsingService.parseRssFromUrl("https://reuters.com/rss"))
                .thenReturn(List.of());
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        ArgumentCaptor<RssArticle> articleCaptor = ArgumentCaptor.forClass(RssArticle.class);
        verify(eventPublisher, atLeastOnce()).publishNewsIngested(articleCaptor.capture());

        RssArticle processedArticle = articleCaptor.getValue();
        assertThat(processedArticle.conflictKeywords()).contains("nuclear", "genocide");
        assertThat(processedArticle.riskScore()).isGreaterThan(0.6); // Very high risk
    }

    @Test
    @DisplayName("Should apply source weight to risk score")
    void shouldApplySourceWeightToRiskScore() {
        RssArticle article = createTestArticle("War news", "Conflict reported");

        when(rssParsingService.parseRssFromUrl("https://bbc.com/rss"))
                .thenReturn(List.of(article)); // BBC has weight 1.0
        when(rssParsingService.parseRssFromUrl("https://reuters.com/rss"))
                .thenReturn(List.of(article)); // Reuters has weight 0.9
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        ArgumentCaptor<RssArticle> articleCaptor = ArgumentCaptor.forClass(RssArticle.class);
        verify(eventPublisher, times(2)).publishNewsIngested(articleCaptor.capture());

        List<RssArticle> processedArticles = articleCaptor.getAllValues();

        double maxRiskScore = processedArticles.stream()
                .mapToDouble(RssArticle::riskScore)
                .max().orElse(0.0);
        double minRiskScore = processedArticles.stream()
                .mapToDouble(RssArticle::riskScore)
                .min().orElse(0.0);

        assertThat(maxRiskScore).isGreaterThan(minRiskScore);
    }

    @Test
    @DisplayName("Should publish high-risk events when threshold exceeded")
    void shouldPublishHighRiskEventsWhenThresholdExceeded() {
        RssArticle highRiskArticle = createTestArticle(
                "Nuclear war threat terrorism bomb attack",
                "Critical violence escalates"
        );

        when(rssParsingService.parseRssFromUrl(anyString())).thenReturn(List.of(highRiskArticle));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        verify(eventPublisher, atLeastOnce()).publishNewsIngested(any(RssArticle.class));
        verify(eventPublisher, atLeastOnce()).publishHighRiskDetected(any(RssArticle.class));
    }

    @Test
    @DisplayName("Should not publish high-risk events when threshold not exceeded")
    void shouldNotPublishHighRiskEventsWhenThresholdNotExceeded() {
        RssArticle lowRiskArticle = createTestArticle("Economic summit", "Trade discussions continue");

        when(rssParsingService.parseRssFromUrl(anyString())).thenReturn(List.of(lowRiskArticle));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        verify(eventPublisher, atLeastOnce()).publishNewsIngested(any(RssArticle.class));
        verify(eventPublisher, never()).publishHighRiskDetected(any(RssArticle.class));
    }

    @Test
    @DisplayName("Should filter out duplicate articles")
    void shouldFilterOutDuplicateArticles() {
        RssArticle article1 = createTestArticle("News 1", "Content 1");
        RssArticle article2 = createTestArticle("News 2", "Content 2");

        when(rssParsingService.parseRssFromUrl(anyString()))
                .thenReturn(List.of(article1, article2));

        when(deduplicationService.isAlreadyProcessed(article1.link())).thenReturn(false);
        when(deduplicationService.isAlreadyProcessed(article2.link())).thenReturn(true);

        service.parseAllRssFeeds();

        verify(eventPublisher, times(2)).publishNewsIngested(any(RssArticle.class)); // Only for enabled sources
        verify(deduplicationService, times(4)).isAlreadyProcessed(anyString()); // 2 articles × 2 sources
        verify(deduplicationService, times(2)).markAsProcessed(article1.link()); // Only new articles
        verify(deduplicationService, never()).markAsProcessed(article2.link()); // Duplicate not marked
    }

    @Test
    @DisplayName("Should publish batch processed event")
    void shouldPublishBatchProcessedEvent() {
        RssArticle article = createTestArticle("Test", "Content");

        when(rssParsingService.parseRssFromUrl(anyString())).thenReturn(List.of(article));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);

        service.parseAllRssFeeds();

        verify(eventPublisher).publishBatchProcessed(anyString(), eq(2), eq(2)); // 2 sources, 2 new articles
    }

    @Test
    @DisplayName("Should handle RSS parsing failures gracefully")
    void shouldHandleRssParsingFailuresGracefully() {
        when(rssParsingService.parseRssFromUrl("https://bbc.com/rss"))
                .thenThrow(new RuntimeException("Network error"));
        when(rssParsingService.parseRssFromUrl("https://reuters.com/rss"))
                .thenReturn(List.of(createTestArticle("Reuters News", "Content")));
        when(deduplicationService.isAlreadyProcessed(anyString())).thenReturn(false);


        service.parseAllRssFeeds();

        verify(eventPublisher, times(1)).publishNewsIngested(any(RssArticle.class)); // Only Reuters
        verify(eventPublisher).publishBatchProcessed(anyString(), eq(1), eq(1)); // 1 successful article
    }

    private RssArticle createTestArticle(String title, String description) {
        return new RssArticle(
                "test-id-" + System.nanoTime(),
                title,
                description,
                "https://example.com/article/" + System.nanoTime(),
                "Test Author",
                LocalDateTime.now(),
                Set.of(), // Keywords will be filled during analysis
                0.0       // Risk score will be calculated during analysis
        );
    }
}
