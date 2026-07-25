package com.urlshortener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single shortened URL record.
 */
public class UrlEntry {
    private final String code;
    private final String originalUrl;
    private final long createdAt;
    private final AtomicLong clicks = new AtomicLong(0);

    public UrlEntry(String code, String originalUrl, long createdAt, long initialClicks) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.clicks.set(initialClicks);
    }

    public String getCode() {
        return code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getClicks() {
        return clicks.get();
    }

    public long incrementAndGetClicks() {
        return clicks.incrementAndGet();
    }
}
