package io.conflictradar.ingestion.api.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.conflictradar.ingestion.api.dto.RssArticle;
import io.conflictradar.ingestion.api.exception.ErrorCategory;
import io.conflictradar.ingestion.config.RssConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.GZIPInputStream;

@Service
public class RssParsingService {

    private static final Logger logger = LoggerFactory.getLogger(RssParsingService.class);

    private int userAgentIndex = 0;
    private final RssConfig rssConfig;

    public RssParsingService(RssConfig rssConfig) {
        this.rssConfig = rssConfig;
    }

    /**
     * Parse RSS with retry logic and comprehensive error handling
     *
     * @param url RSS feed URL
     * @return List of articles (empty if parsing fails)
     */
    @Retryable(
            value = { IOException.class, SocketTimeoutException.class },
            maxAttemptsExpression = "#{@rssProps.maxAttempts}",
            backoff = @Backoff(delayExpression = "#{@rssProps.retryDelay}", multiplier = 2.0, maxDelay = 10000)
    )
    public List<RssArticle> parseRssFromUrl(String url) {
        try {
            logger.debug("Parsing RSS from: {}", url);
            return parseRssWithErrorHandling(url);

        } catch (RssParsingException e) {
            logger.error("RSS parsing failed for {}: {} (category: {})", url, e.getMessage(), e.getCategory());
            return handleParsingError(url, e);

        } catch (Exception e) {
            logger.error("Unexpected error parsing RSS from {}: {}", url, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Parse RSS with detailed error categorization
     */
    private List<RssArticle> parseRssWithErrorHandling(String url) throws RssParsingException {
        HttpURLConnection connection = null;

        try {
            if (url == null || url.trim().isEmpty()) {
                throw new RssParsingException("URL is null or empty", ErrorCategory.INVALID_URL);
            }

            URL feedUrl = new URL(url);
            connection = (HttpURLConnection) feedUrl.openConnection();

            configureConnection(connection);

            connection.connect();

            System.out.println("Response code: " + connection.getResponseCode());
            System.out.println("Content-Type: " + connection.getContentType());

            validateHttpResponse(connection, url);

            return parseRssFeed(connection);

        } catch (MalformedURLException e) {
            throw new RssParsingException("Invalid URL format: " + url, e, ErrorCategory.INVALID_URL);

        } catch (SocketTimeoutException e) {
            throw new RssParsingException("Connection timeout for: " + url, e, ErrorCategory.TIMEOUT);

        } catch (ConnectException e) {
            throw new RssParsingException("Connection refused: " + url, e, ErrorCategory.CONNECTION_REFUSED);

        } catch (UnknownHostException e) {
            throw new RssParsingException("Unknown host: " + url, e, ErrorCategory.DNS_ERROR);

        } catch (SocketException e) {
            throw new RssParsingException("Network error: " + url, e, ErrorCategory.NETWORK_ERROR);

        } catch (IOException e) {
            throw new RssParsingException("I/O error reading: " + url, e, ErrorCategory.IO_ERROR);

        } catch (Exception e) {
            System.out.println("=== FULL STACK TRACE ===");
            e.printStackTrace();
            System.out.println("Exception class: " + e.getClass().getName());
            System.out.println("Exception message: " + e.getMessage());

            if (e.getCause() != null) {
                System.out.println("Caused by: " + e.getCause().getClass().getName());
                System.out.println("Cause message: " + e.getCause().getMessage());
                e.getCause().printStackTrace();
            }

            throw new RssParsingException("Unexpected error: " + url, e, ErrorCategory.UNKNOWN);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Configure HTTP connection with proper headers and timeouts
     */
    private void configureConnection(HttpURLConnection connection) {
        connection.setConnectTimeout(rssConfig.http().connectTimeout());
        connection.setReadTimeout(rssConfig.http().readTimeout());

        // Set headers to avoid blocking
        connection.setRequestProperty("User-Agent", getNextUserAgent());
        connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Connection", "close");

        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(false);
    }

    /**
     * Validate HTTP response and handle different status codes
     */
    private void validateHttpResponse(HttpURLConnection connection, String url) throws IOException, RssParsingException {
        int responseCode = connection.getResponseCode();
        String responseMessage = connection.getResponseMessage();

        switch (responseCode) {
            case HttpURLConnection.HTTP_OK:
                // Check content type
                String contentType = connection.getContentType();
                if (contentType != null && !isValidRssContentType(contentType)) {
                    logger.warn("Unexpected content type for {}: {}", url, contentType);
                }
                break;

            case HttpURLConnection.HTTP_NOT_FOUND:
                throw new RssParsingException("RSS feed not found (404): " + url, ErrorCategory.NOT_FOUND);

            case HttpURLConnection.HTTP_FORBIDDEN:
                throw new RssParsingException("Access forbidden (403): " + url, ErrorCategory.ACCESS_FORBIDDEN);

            case HttpURLConnection.HTTP_UNAUTHORIZED:
                throw new RssParsingException("Authentication required (401): " + url, ErrorCategory.AUTH_REQUIRED);

            case 429:
                throw new RssParsingException("Rate limited (429): " + url, ErrorCategory.RATE_LIMITED);

            case HttpURLConnection.HTTP_INTERNAL_ERROR:
                throw new RssParsingException("Server error (500): " + url, ErrorCategory.SERVER_ERROR);

            case HttpURLConnection.HTTP_BAD_GATEWAY:
            case HttpURLConnection.HTTP_UNAVAILABLE:
            case HttpURLConnection.HTTP_GATEWAY_TIMEOUT:
                throw new RssParsingException("Server temporarily unavailable (" + responseCode + "): " + url,
                        ErrorCategory.SERVER_UNAVAILABLE);

            default:
                if (responseCode >= 400) {
                    throw new RssParsingException(
                            String.format("HTTP error %d (%s): %s", responseCode, responseMessage, url),
                            ErrorCategory.HTTP_ERROR
                    );
                }
        }
    }

    private List<RssArticle> parseRssFeed(HttpURLConnection connection) throws RssParsingException {

        try {
            System.out.println("=== parseRssFeed started ===");

            InputStream inputStream = connection.getInputStream();

            // Проверь Content-Encoding для GZIP
            String encoding = connection.getContentEncoding();
            if ("gzip".equals(encoding)) {
                inputStream = new GZIPInputStream(inputStream);
            }

            // Читай весь контент один раз
            String xmlContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("Received XML: " + xmlContent);

            // Парси из строки через StringReader
            var input = new SyndFeedInput();
            var feed = input.build(new StringReader(xmlContent));

            if (feed == null) {
                throw new RssParsingException("RSS feed is null", ErrorCategory.PARSE_ERROR);
            }

            if (feed.getEntries() == null || feed.getEntries().isEmpty()) {
                logger.warn("RSS feed has no entries");
                return Collections.emptyList();
            }

            return feed.getEntries().stream()
                    .map(this::convertToArticle)
                    .filter(Objects::nonNull)
                    .toList();

        } catch (com.rometools.rome.io.FeedException e) {
            throw new RssParsingException("RSS parsing error: " + e.getMessage(), e, ErrorCategory.PARSE_ERROR);
        } catch (IOException e) {
            throw new RssParsingException("I/O error reading RSS: " + e.getMessage(), e, ErrorCategory.IO_ERROR);
        }
    }

    private RssArticle convertToArticle(SyndEntry entry) {
        try {
            if (entry == null) {
                return null;
            }

            var publishedAt = entry.getPublishedDate() != null
                    ? entry.getPublishedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                    : LocalDateTime.now();

            var description = entry.getDescription() != null
                    ? cleanText(entry.getDescription().getValue())
                    : "";

            var title = entry.getTitle() != null ? cleanText(entry.getTitle()) : "Untitled";
            var link = entry.getLink() != null ? entry.getLink().trim() : "";
            var author = entry.getAuthor() != null ? cleanText(entry.getAuthor()) : "Unknown";

            if (title.isBlank() || link.isBlank()) {
                logger.debug("Skipping article with missing title or link: title='{}', link='{}'", title, link);
                return null;
            }

            return new RssArticle(
                    UUID.randomUUID().toString(),
                    title,
                    description,
                    link,
                    author,
                    publishedAt,
                    Set.of(), // Keywords added during analysis
                    0.0       // Risk score calculated during analysis
            );

        } catch (Exception e) {
            logger.warn("Failed to convert RSS entry to article: {}", e.getMessage());
            return null;
        }
    }

    private List<RssArticle> handleParsingError(String url, RssParsingException e) {
        return switch (e.getCategory()) {
            case TIMEOUT, CONNECTION_REFUSED, NETWORK_ERROR, SERVER_UNAVAILABLE -> {
                logger.warn("Temporary error for {}: {}", url, e.getMessage());
                yield Collections.emptyList();
            }
            case NOT_FOUND, ACCESS_FORBIDDEN, AUTH_REQUIRED -> {
                logger.error("Permanent error for {}: {}", url, e.getMessage());
                yield Collections.emptyList();
            }
            case RATE_LIMITED -> {
                logger.warn("Rate limited for {}: {}", url, e.getMessage());
                yield Collections.emptyList();
            }
            case PARSE_ERROR -> {
                logger.warn("Parse error for {}: {}", url, e.getMessage());
                yield Collections.emptyList();
            }
            default -> {
                logger.error("Unknown error for {}: {}", url, e.getMessage());
                yield Collections.emptyList();
            }
        };
    }

    private String getNextUserAgent() {
        List<String> userAgents = rssConfig.http().userAgents();
        String userAgent = userAgents.get(userAgentIndex);
        userAgentIndex = (userAgentIndex + 1) % userAgents.size();
        return userAgent;
    }

    private boolean isValidRssContentType(String contentType) {
        if (contentType == null) return true;

        String lowerContentType = contentType.toLowerCase();
        return lowerContentType.contains("xml") ||
                lowerContentType.contains("rss") ||
                lowerContentType.contains("atom") ||
                lowerContentType.contains("text");
    }

    private String cleanText(String text) {
        if (text == null) return "";

        return text
                .replaceAll("<[^>]+>", " ")     // Remove HTML tags
                .replaceAll("&[a-zA-Z0-9#]+;", " ") // Remove HTML entities
                .replaceAll("\\s+", " ")        // Normalize whitespace
                .trim();
    }

    public static class RssParsingException extends Exception {
        private final ErrorCategory category;

        public RssParsingException(String message, ErrorCategory category) {
            super(message);
            this.category = category;
        }

        public RssParsingException(String message, Throwable cause, ErrorCategory category) {
            super(message, cause);
            this.category = category;
        }

        public ErrorCategory getCategory() {
            return category;
        }
    }
}