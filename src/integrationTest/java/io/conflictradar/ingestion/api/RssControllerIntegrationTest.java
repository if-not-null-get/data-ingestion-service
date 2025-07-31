package io.conflictradar.ingestion.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = RssControllerIntegrationTest.Initializer.class)
public class RssControllerIntegrationTest {
    @Autowired
    private TestRestTemplate restTemplate;
    private WireMockServer wireMockServer;

    @TestConfiguration
    static class TestRedisConfig {
        @Bean
        @Primary
        public LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(Initializer.redis.getHost(), Initializer.redis.getMappedPort(6379));
        }
    }

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);

        static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            redis.start();
            kafka.start();

            String redisHost = "spring.redis.host=" + redis.getHost();
            String redisPort = "spring.redis.port=" + redis.getMappedPort(6379);
            String kafkaBootstrap = "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers();

            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, redisHost, redisPort, kafkaBootstrap);
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        setupRssMock();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void shouldReturnHealthStatus() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/rss/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
        assertThat(response.getBody()).contains("ConflictRadar Data Ingestion Service");
    }

    @Test
    void shouldReturnAvailableSources() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/rss/sources", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("BBC World");
    }

    @Test
    void shouldParseRssAndReturnArticles() {
        String mockUrl = "http://localhost:" + wireMockServer.port() + "/rss.xml";
        String url = "/api/v1/rss/feeds?url=" + mockUrl;

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Test Article");
    }

    private void setupRssMock() {
        String rssContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>Test RSS</title>
                    <item>
                        <title>Test Article</title>
                        <link>https://example.com/1</link>
                        <description>Test description</description>
                        <pubDate>Tue, 29 Jul 2025 10:00:00 GMT</pubDate>
                    </item>
                </channel>
            </rss>
            """;

        wireMockServer.stubFor(get(urlEqualTo("/rss.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(rssContent)));
    }
}
