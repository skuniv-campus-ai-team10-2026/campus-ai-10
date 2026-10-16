package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Synthetic evaluation data only. A persisted attempt prevents accidental paid retries after interruption. */
final class AiQualityCache {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    AiQualityCache(Path directory) throws IOException { this.directory = directory; Files.createDirectories(directory); }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    static String key(String endpoint, String model, String revision, Object input) throws IOException {
        return digest(JSON.writeValueAsBytes(List.of(endpoint, model, revision, input)));
    }
    JsonNode read(String key) throws IOException {
        Path path = file(key);
        return Files.exists(path) ? JSON.readTree(Files.readAllBytes(path)) : null;
    }
    int attempts() throws IOException {
        try (var files = Files.list(directory)) { return (int) files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).count(); }
    }
    void begin(String key, String endpoint, String model, String revision) throws IOException {
        if (read(key) != null) throw new IllegalStateException("Evaluation cache entry already exists");
        write(key, Map.of("status", "ATTEMPTED", "endpoint", endpoint, "requestedModel", model,
                "revision", revision, "attemptedAt", java.time.Instant.now().toString()));
    }
    void complete(String key, Object payload, Map<String, Long> usage) throws IOException {
        var entry = JSON.convertValue(Objects.requireNonNull(read(key)), LinkedHashMap.class);
        entry.put("status", "SUCCESS"); entry.put("payload", payload); entry.put("usage", usage);
        entry.put("completedAt", java.time.Instant.now().toString()); write(key, entry);
    }
    void fail(String key, String errorCode) throws IOException {
        fail(key, errorCode, Map.of());
    }
    void fail(String key, String errorCode, Map<String, Long> usage) throws IOException {
        var entry = JSON.convertValue(Objects.requireNonNull(read(key)), LinkedHashMap.class);
        entry.put("status", "FAILED"); entry.put("errorCode", errorCode); entry.put("usage", usage); write(key, entry);
    }
    private Path file(String key) {
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid cache key");
        return directory.resolve(key + ".json");
    }
    private void write(String key, Object value) throws IOException {
        Path destination = file(key), temporary = directory.resolve(key + ".tmp");
        Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        // Refuse non-atomic replacement: a failed persistence must stop further paid work.
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
