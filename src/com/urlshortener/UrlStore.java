package com.urlshortener;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory store for short URL mappings.
 */
public class UrlStore {

    private final Map<String, UrlEntry> byCode = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);
    private final Path dataFile;

    public UrlStore(Path dataFile) {
        this.dataFile = dataFile;
        load();
    }

    public boolean isAliasAvailable(String alias) {
        if (alias == null || alias.isBlank()) return true;
        String clean = alias.trim().replaceAll("[^a-zA-Z0-9_-]", "");
        return !byCode.containsKey(clean);
    }

    public String suggestAvailableAlias(String alias) {
        if (alias == null || alias.isBlank()) return "link-1";
        String base = alias.trim().replaceAll("[^a-zA-Z0-9_-]", "");
        if (base.isBlank()) base = "link";
        String candidate = base;
        int suffix = 1;
        while (byCode.containsKey(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    /**
     * Creates a new short entry. Throws IllegalArgumentException if a requested custom alias is taken,
     * allowing the user to choose another custom alias.
     */
    public UrlEntry create(String originalUrl, String customAlias) {
        String code;
        if (customAlias != null && !customAlias.isBlank()) {
            code = customAlias.trim().replaceAll("[^a-zA-Z0-9_-]", "");
            if (code.isBlank()) {
                throw new IllegalArgumentException("Invalid alias format. Use letters, numbers, hyphens or underscores.");
            }
            if (byCode.containsKey(code)) {
                throw new IllegalArgumentException("Alias '" + code + "' is already taken.");
            }
            UrlEntry entry = new UrlEntry(code, originalUrl, System.currentTimeMillis(), 0);
            if (byCode.putIfAbsent(code, entry) != null) {
                throw new IllegalArgumentException("Alias '" + code + "' is already taken.");
            }
            appendToFile(entry);
            return entry;
        } else {
            long id = idCounter.incrementAndGet();
            code = Base62.encode(id);
            while (byCode.containsKey(code)) {
                id = idCounter.incrementAndGet();
                code = Base62.encode(id);
            }
            UrlEntry entry = new UrlEntry(code, originalUrl, System.currentTimeMillis(), 0);
            byCode.put(code, entry);
            appendToFile(entry);
            return entry;
        }
    }

    public UrlEntry get(String code) {
        return byCode.get(code);
    }

    public boolean recordClick(String code) {
        UrlEntry entry = byCode.get(code);
        if (entry == null) {
            return false;
        }
        entry.incrementAndGetClicks();
        return true;
    }

    public int size() {
        return byCode.size();
    }

    public List<UrlEntry> getAll() {
        return byCode.values().stream()
                .sorted(Comparator.comparingLong(UrlEntry::getCreatedAt).reversed())
                .toList();
    }

    public boolean delete(String code) {
        UrlEntry removed = byCode.remove(code);
        if (removed != null) {
            flush();
            return true;
        }
        return false;
    }

    private synchronized void appendToFile(UrlEntry entry) {
        try {
            if (dataFile.getParent() != null) {
                Files.createDirectories(dataFile.getParent());
            }
            String line = entry.getCode() + "|" + entry.getCreatedAt() + "|"
                    + entry.getClicks() + "|" + entry.getOriginalUrl() + System.lineSeparator();
            Files.writeString(dataFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Warning: failed to persist entry " + entry.getCode() + ": " + e.getMessage());
        }
    }

    public synchronized void flush() {
        try {
            if (dataFile.getParent() != null) {
                Files.createDirectories(dataFile.getParent());
            }
            StringBuilder sb = new StringBuilder();
            for (UrlEntry e : byCode.values()) {
                sb.append(e.getCode()).append('|')
                  .append(e.getCreatedAt()).append('|')
                  .append(e.getClicks()).append('|')
                  .append(e.getOriginalUrl()).append(System.lineSeparator());
            }
            Files.writeString(dataFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Warning: failed to flush store: " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(dataFile)) {
            return;
        }
        long maxNumericId = 0;
        try (BufferedReader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", 4);
                if (parts.length != 4) continue;
                String code = parts[0];
                long createdAt = Long.parseLong(parts[1]);
                long clicks = Long.parseLong(parts[2]);
                String url = parts[3];
                byCode.put(code, new UrlEntry(code, url, createdAt, clicks));
                try {
                    maxNumericId = Math.max(maxNumericId, Base62.decode(code));
                } catch (Exception ignored) {}
            }
            idCounter.set(maxNumericId);
            System.out.println("Loaded " + byCode.size() + " existing short URLs from " + dataFile);
        } catch (IOException e) {
            System.err.println("Warning: failed to load existing data: " + e.getMessage());
        }
    }
}
