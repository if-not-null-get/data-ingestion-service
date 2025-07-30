package io.conflictradar.ingestion.api.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class RssDeduplicationService {

    private static final String RSS_ARTICLE_PREFIX = "rss:article:";
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final RedisTemplate<String, String> redisTemplate;

    public RssDeduplicationService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isAlreadyProcessed(String rssUrl) {
        String key = generateKey(rssUrl);

        return redisTemplate.hasKey(key);
    }

    public void markAsProcessed(String rssUrl) {
        String key = generateKey(rssUrl);
        String value = LocalDateTime.now().toString();

        redisTemplate.opsForValue().set(key, value, DEFAULT_TTL);
    }

    public String getProcessedTime(String rssUrl) {
        String key = generateKey(rssUrl);

        return redisTemplate.opsForValue().get(key);
    }

    public void removeProcessedMark(String rssUrl) {
        String key = generateKey(rssUrl);

        redisTemplate.delete(key);
    }

    private String generateKey(String rssUrl) {
        String urlHash = DigestUtils.md5Hex(rssUrl);

        return RSS_ARTICLE_PREFIX + urlHash;
    }

    public long getCachedArticlesCount() {
        return redisTemplate.keys(RSS_ARTICLE_PREFIX + "*").size();
    }
}
