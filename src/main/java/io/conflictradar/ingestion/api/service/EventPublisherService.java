package io.conflictradar.ingestion.api.service;

import io.conflictradar.ingestion.api.dto.RssArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class EventPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(EventPublisherService.class);

    private static final String NEWS_INGESTED_TOPIC = "news-ingested";
    private static final String HIGH_RISK_DETECTED_TOPIC = "high-risk-detected";
    private static final String BATCH_PROCESSED_TOPIC = "batch-processed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EventPublisherService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishNewsIngested(RssArticle article) {
        var event = Map.of(
                "eventType", "NEWS_INGESTED",
                "timestamp", LocalDateTime.now().toString(),
                "article", article,
                "source", "data-ingestion-service"
        );

        sendEvent(NEWS_INGESTED_TOPIC, article.id(), event, "News ingested");
    }

    public void publishHighRiskDetected(RssArticle article) {
        if (article.riskScore() < 0.8) {
            return;
        }

        var event = Map.of(
                "eventType", "HIGH_RISK_DETECTED",
                "timestamp", LocalDateTime.now().toString(),
                "article", article,
                "riskScore", article.riskScore(),
                "conflictKeywords", article.conflictKeywords(),
                "source", "data-ingestion-service"
        );

        sendEvent(HIGH_RISK_DETECTED_TOPIC, article.id(), event, "High risk detected");
    }

    public void publishBatchProcessed(String sourceName, int totalArticles, int newArticles) {
        var event = Map.of(
                "eventType", "BATCH_PROCESSED",
                "timestamp", LocalDateTime.now().toString(),
                "sourceName", sourceName,
                "totalArticles", totalArticles,
                "newArticles", newArticles,
                "duplicatesSkipped", totalArticles - newArticles,
                "source", "data-ingestion-service"
        );

        sendEvent(BATCH_PROCESSED_TOPIC, sourceName, event, "Batch processed");
    }

    private void sendEvent(String topic, String key, Object event, String description) {
        try {
            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(topic, key, event);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("{} event sent successfully to topic: {} with key: {}",
                            description, topic, key);
                } else {
                    logger.error("Failed to send {} event to topic: {} with key: {}. Error: {}",
                            description, topic, key, ex.getMessage());
                }
            });

        } catch (Exception e) {
            logger.error("Exception while sending {} event: {}", description, e.getMessage());
        }
    }

    public Map<String, Object> getPublisherStats() {
        return Map.of(
                "status", "ACTIVE",
                "topics", List.of(NEWS_INGESTED_TOPIC, HIGH_RISK_DETECTED_TOPIC, BATCH_PROCESSED_TOPIC),
                "kafkaTemplate", kafkaTemplate.getClass().getSimpleName()
        );
    }
}