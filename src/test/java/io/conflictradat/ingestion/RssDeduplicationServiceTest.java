package io.conflictradat.ingestion;

import io.conflictradar.ingestion.api.service.RssDeduplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RssDeduplicationServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    private RssDeduplicationService service;

    @BeforeEach
    void setUp() {
        service = new RssDeduplicationService(redisTemplate);
    }

    @Test
    void shouldReturnTrueForAlreadyProcessedArticle() {
        String url = "https://example.com/news/123";
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        boolean result = service.isAlreadyProcessed(url);

        assertThat(result).isTrue();
        verify(redisTemplate).hasKey(startsWith("rss:article:"));
    }

    @Test
    void shouldReturnFalseForNewArticle() {
        String url = "https://example.com/news/456";
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        boolean result = service.isAlreadyProcessed(url);

        assertThat(result).isFalse();
    }

    @Test
    void shouldMarkArticleAsProcessedWithCorrectTTL() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        String url = "https://example.com/news/789";

        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.markAsProcessed(url);

        verify(valueOps).set(
                startsWith("rss:article:"),
                anyString(),
                eq(Duration.ofDays(7))
        );
    }
}
