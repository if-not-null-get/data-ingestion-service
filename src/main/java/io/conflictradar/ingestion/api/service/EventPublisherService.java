package io.conflictradar.ingestion.api.service;

import io.conflictradar.ingestion.api.dto.RssArticle;
import io.conflictradar.ingestion.api.dto.kafka.BatchProcessedEvent;
import io.conflictradar.ingestion.api.dto.kafka.HighRiskDetectedEvent;
import io.conflictradar.ingestion.api.dto.kafka.NewsIngestedEvent;
import io.conflictradar.ingestion.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class EventPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(EventPublisherService.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EventPublisherService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishNewsIngested(RssArticle article) {
        try {
            NewsIngestedEvent event = NewsIngestedEvent.create(
                    article.id(),
                    article.title(),
                    article.link(),
                    extractSourceFromLink(article.link()),
                    article.publishedAt(),
                    article.riskScore(),
                    article.conflictKeywords()
            );

            CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(KafkaConfig.NEWS_INGESTED_TOPIC, article.id(), event);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.debug("Sent news ingested event: {} to partition: {}",
                               article.id(), result.getRecordMetadata().partition());
                } else {
                    logger.error("Failed to send news ingested event: {}", article.id(), ex);
                }
            });

        } catch (Exception e) {
            logger.error("Error publishing news ingested event for article: {}", article.id(), e);
        }
    }

    public void publishHighRiskDetected(RssArticle article) {
        try {
            HighRiskDetectedEvent event = HighRiskDetectedEvent.create(
                    article.id(),
                    article.title(),
                    article.riskScore(),
                    article.conflictKeywords(),
                    extractSourceFromLink(article.link())
            );

            CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(KafkaConfig.HIGH_RISK_TOPIC, event.alertId(), event);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.warn("ALERT SENT: High risk event {} for article: {} (risk: {})",
                               event.alertId(), article.id(), article.riskScore());
                } else {
                    logger.error("CRITICAL: Failed to send high risk alert: {}", event.alertId(), ex);
                }
            });

        } catch (Exception e) {
            logger.error("Error publishing high risk event for article: {}", article.id(), e);
        }
    }

    public void publishBatchProcessed(String source, int totalArticles, int newArticles) {
        try {
            int highRiskArticles = (int) (newArticles * 0.1);
            long processingDuration = 1000;

            BatchProcessedEvent event = BatchProcessedEvent.create(
                    source,
                    totalArticles,
                    newArticles,
                    highRiskArticles,
                    processingDuration
            );

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(KafkaConfig.BATCH_PROCESSED_TOPIC, event.batchId(), event);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("Sent batch processed event: {} ({} articles from {})",
                            event.batchId(), totalArticles, source);
                } else {
                    logger.error("Failed to send batch processed event: {}", event.batchId(), ex);
                }
            });

        } catch (Exception e) {
            logger.error("Error publishing batch processed event for source: {}", source, e);
        }
    }

    private String extractSourceFromLink(String link) {
        if (link == null) return "unknown";

        if (link.contains("bbc")) return "BBC";
        if (link.contains("reuters")) return "Reuters";
        if (link.contains("cnn")) return "CNN";

        return "unknown";
    }
}