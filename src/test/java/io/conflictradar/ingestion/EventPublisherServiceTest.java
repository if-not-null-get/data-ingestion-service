package io.conflictradar.ingestion;

import io.conflictradar.ingestion.api.dto.RssArticle;
import io.conflictradar.ingestion.api.service.EventPublisherService;
import io.conflictradar.ingestion.config.KafkaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventPublisherServiceTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private EventPublisherService service;

    @BeforeEach
    void setUp() {
        var kafkaConfig = new KafkaProperties(
                "test-news-ingested",
                "test-high-risk-detected",
                "test-batch-processed"
        );

        service = new EventPublisherService(kafkaTemplate, kafkaConfig);
    }

    @Test
    void shouldPublishNewsIngestedEvent() {
        RssArticle article = new RssArticle(
                "123", "Test Title", "Description", "https://example.com",
                "Author", LocalDateTime.now(), Set.of(), 0.5
        );

        service.publishNewsIngested(article);

        verify(kafkaTemplate).send(eq("news-ingested"), eq("123"), any(Map.class));
    }

    @Test
    void shouldPublishHighRiskEventForHighRiskArticle() {
        RssArticle highRiskArticle = new RssArticle(
                "456", "War breaks out", "Violence escalates", "https://example.com",
                "Reporter", LocalDateTime.now(), Set.of("war", "violence"), 0.9
        );

        service.publishHighRiskDetected(highRiskArticle);

        verify(kafkaTemplate).send(eq("high-risk-detected"), eq("456"), any(Map.class));
    }

    @Test
    void shouldNotPublishHighRiskEventForLowRiskArticle() {
        RssArticle lowRiskArticle = new RssArticle(
                "789", "Weather forecast", "Sunny day", "https://example.com",
                "Meteorologist", LocalDateTime.now(), Set.of(), 0.1
        );

        service.publishHighRiskDetected(lowRiskArticle);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }
}
