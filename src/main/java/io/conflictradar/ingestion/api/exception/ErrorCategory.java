package io.conflictradar.ingestion.api.exception;

public enum ErrorCategory {
    TIMEOUT,              // Connection/read timeout
    CONNECTION_REFUSED,   // Connection refused
    DNS_ERROR,           // Unknown host
    NETWORK_ERROR,       // Other network issues
    IO_ERROR,            // I/O problems
    INVALID_URL,         // Malformed URL
    NOT_FOUND,           // 404 error
    ACCESS_FORBIDDEN,    // 403 error
    AUTH_REQUIRED,       // 401 error
    SERVER_ERROR,        // 5xx errors
    SERVER_UNAVAILABLE,  // Temporary server issues
    HTTP_ERROR,          // Other HTTP errors
    PARSE_ERROR,         // XML/RSS parsing issues
    RATE_LIMITED,        // 429 Too Many Requests
    UNKNOWN              // Unexpected errors
}
