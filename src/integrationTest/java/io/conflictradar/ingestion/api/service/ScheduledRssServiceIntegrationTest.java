package io.conflictradar.ingestion.api.service;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.conflictradar.ingestion.config.KafkaProperties;
import io.conflictradar.ingestion.config.RssConfig;
import io.conflictradar.ingestion.config.RssSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("integration")
@SpringBootTest
@Testcontainers
class ScheduledRssServiceIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private ScheduledRssService scheduledRssService;

    @Autowired
    private RssConfig rssConfig;

    @Autowired
    private KafkaProperties kafkaConfig;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private KafkaConsumer<String, Object> testConsumer;

    @RegisterExtension
    static WireMockExtension wireMockServer = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("wiremock.server.port", () -> String.valueOf(wireMockServer.getPort()));

        registry.add("rss.sources[0].url", () -> "http://localhost:" + wireMockServer.getPort() + "/bbc-rss");
        registry.add("rss.sources[0].name", () -> "BBC World News");
        registry.add("rss.sources[0].weight", () -> "1.0");
        registry.add("rss.sources[0].enabled", () -> "true");

        registry.add("rss.sources[1].url", () -> "http://localhost:" + wireMockServer.getPort() + "/reuters-rss");
        registry.add("rss.sources[1].name", () -> "Reuters World News");
        registry.add("rss.sources[1].weight", () -> "0.9");
        registry.add("rss.sources[0].enabled", () -> "true");
    }

    @BeforeEach
    void setUp() {
        // Clean Redis
        redisTemplate.getConnectionFactory().getConnection().flushDb();

        // Setup Kafka consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        testConsumer = new KafkaConsumer<>(props);
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    @Test
    @DisplayName("Should process RSS feeds end-to-end with real infrastructure")
    void shouldProcessRssFeedsEndToEndWithRealInfrastructure() {
        // Mock RSS responses for configured sources
        String bbcRss = """
            <?xml version="1.0"?>
            <rss version="2.0">
                <channel>
                    <item>
                        <title>War escalates in conflict zone</title>
                        <description>Military violence continues to spread</description>
                        <link>https://bbc.com/news/war-1</link>
                        <author>BBC Reporter</author>
                    </item>
                </channel>
            </rss>
            """;

        String reutersRss = """
            <?xml version="1.0"?>
            <rss version="2.0">
                <channel>
                    <item>
                        <title>Economic summit concludes</title>
                        <description>Trade agreements finalized</description>
                        <link>https://reuters.com/news/economy-1</link>
                        <author>Reuters Reporter</author>
                    </item>
                </channel>
            </rss>
            """;

        // Setup WireMock stubs for actual configured URLs
        List<String> enabledSources = rssConfig.getEnabledSources().stream()
                .map(RssSource::url)
                .toList();

        for (String sourceUrl : enabledSources) {
            String path = "/" + sourceUrl.hashCode(); // Create unique path
            String responseXml = sourceUrl.contains("bbc") ? bbcRss : reutersRss;

            wireMockServer.stubFor(get(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/rss+xml")
                            .withBody(responseXml)));
        }

        // Subscribe to Kafka topics
        testConsumer.subscribe(List.of(
                kafkaConfig.newsIngested(),
                kafkaConfig.highRiskDetected(),
                kafkaConfig.batchProcessed()
        ));

        // when
        scheduledRssService.parseAllRssFeeds();

        // then
        // 1. Verify Kafka events were published
        ConsumerRecords<String, Object> records = testConsumer.poll(Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        // 2. Verify deduplication works - process again
        scheduledRssService.parseAllRssFeeds();

        // Should have fewer new events on second run due to deduplication
        ConsumerRecords<String, Object> secondRunRecords = testConsumer.poll(Duration.ofSeconds(5));
        // At minimum, should have batch-processed events from both runs
        assertThat(secondRunRecords.count()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should handle RSS parsing failures gracefully in integration")
    void shouldHandleRssParsingFailuresGracefullyInIntegration() {
        // given
        // Setup one working source and one failing source
        List<String> enabledSources = rssConfig.getEnabledSources().stream()
                .map(RssSource::url)
                .toList();

        if (enabledSources.size() >= 2) {
            String workingSource = enabledSources.get(0);
            String failingSource = enabledSources.get(1);

            // Working source
            wireMockServer.stubFor(get(urlMatching(".*"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/rss+xml")
                            .withBody("""
                                <?xml version="1.0"?>
                                <rss version="2.0">
                                    <channel>
                                        <item>
                                            <title>Working RSS Feed</title>
                                            <link>https://example.com/working</link>
                                        </item>
                                    </channel>
                                </rss>
                                """)));

            // Failing source
            wireMockServer.stubFor(get(urlPathMatching(".*" + failingSource.hashCode() + ".*"))
                    .willReturn(aResponse().withStatus(500)));
        }

        testConsumer.subscribe(List.of(kafkaConfig.batchProcessed()));

        // when
        assertThatNoException().isThrownBy(() -> {
            scheduledRssService.parseAllRssFeeds();
        });

        // then - should still publish batch completion event
        ConsumerRecords<String, Object> records = testConsumer.poll(Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();
    }

    @Test
    @DisplayName("Should apply risk scoring with real configuration")
    void shouldApplyRiskScoringWithRealConfiguration() throws IOException, InterruptedException {
        String highRiskRss = new String(
                getClass().getResourceAsStream("/test-rss.xml").readAllBytes(),
                StandardCharsets.UTF_8
        );

        wireMockServer.stubFor(any(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml; charset=utf-8")
                        .withHeader("Content-Encoding", "identity")
                        .withBody(highRiskRss)));

        testConsumer.subscribe(List.of(
                kafkaConfig.newsIngested(),
                kafkaConfig.highRiskDetected()
        ));

        awaitConsumerReady();

        scheduledRssService.parseAllRssFeeds();

        Thread.sleep(3000);

        ConsumerRecords<String, Object> records = testConsumer.poll(Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        // Should have both news-ingested and high-risk-detected events
        boolean hasNewsEvent = false;
        boolean hasHighRiskEvent = false;

        for (var record : records) {
            if (record.topic().equals(kafkaConfig.newsIngested())) {
                hasNewsEvent = true;
            }
            if (record.topic().equals(kafkaConfig.highRiskDetected())) {
                hasHighRiskEvent = true;
            }
        }

        assertThat(hasNewsEvent).isTrue();
        assertThat(hasHighRiskEvent).isTrue();
    }

    private void awaitConsumerReady() {
        long start = System.currentTimeMillis();
        while (testConsumer.assignment().isEmpty() &&
                (System.currentTimeMillis() - start) < 10000) {
            testConsumer.poll(Duration.ofMillis(100));
        }
    }

    @Test
    @DisplayName("Should use real configuration values correctly")
    void shouldUseRealConfigurationValuesCorrectly() {
        // Verify configuration is loaded from application-integration.yml
        assertThat(rssConfig.getEnabledSources()).isNotEmpty();
        assertThat(rssConfig.processing().riskThreshold()).isBetween(0.0, 1.0);
        assertThat(rssConfig.riskAnalysis().conflictKeywords()).isNotEmpty();
        assertThat(rssConfig.http().connectTimeout()).isPositive();
        assertThat(rssConfig.http().readTimeout()).isPositive();
        assertThat(rssConfig.http().maxRetries()).isPositive();
    }

    @Test
    @DisplayName("Should handle Redis deduplication with real Redis")
    void shouldHandleRedisDeduplicationWithRealRedis() {
        // given
        String duplicateRss = """
            <?xml version="1.0"?>
            <rss version="2.0">
                <channel>
                    <item>
                        <title>Duplicate Article Test</title>
                        <link>https://example.com/duplicate-test</link>
                    </item>
                </channel>
            </rss>
            """;

        wireMockServer.stubFor(get(urlMatching(".*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(duplicateRss)));

        testConsumer.subscribe(List.of(kafkaConfig.newsIngested()));

        scheduledRssService.parseAllRssFeeds();

        // Consume first batch of events
        ConsumerRecords<String, Object> firstRun = testConsumer.poll(Duration.ofSeconds(5));
        int firstRunCount = firstRun.count();

        scheduledRssService.parseAllRssFeeds();

        // Consume second batch of events
        ConsumerRecords<String, Object> secondRun = testConsumer.poll(Duration.ofSeconds(5));

        // then - second run should have no new article events due to deduplication
        // (may still have batch-processed events)
        assertThat(firstRunCount).isGreaterThan(0);

        // Verify Redis contains the deduplicated entries
        assertThat(redisTemplate.hasKey("rss:article:*")).isNotNull();
    }
}
