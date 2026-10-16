package edu.skope.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiQualityCacheTest {
    @TempDir Path directory;
    @Test void modelPromptAndInputEachSeparateCacheIdentity() throws Exception {
        String key = AiQualityCache.key("analysis", "model", "prompt1", Map.of("text", "synthetic"));
        assertNotEquals(key, AiQualityCache.key("analysis", "other", "prompt1", Map.of("text", "synthetic")));
        assertNotEquals(key, AiQualityCache.key("analysis", "model", "prompt2", Map.of("text", "synthetic")));
        assertNotEquals(key, AiQualityCache.key("analysis", "model", "prompt1", Map.of("text", "different")));
    }
    @Test void successAndUsageSurviveRestartWithoutAnotherAttempt() throws Exception {
        var cache = new AiQualityCache(directory);
        String key = AiQualityCache.key("embedding", "model", "v1", "synthetic");
        cache.begin(key, "embedding", "model", "v1");
        cache.complete(key, Map.of("vector", new double[]{1, 0}, "model", "model"), Map.of("inputTokens", 3L));
        var reopened = new AiQualityCache(directory);
        assertEquals(1, reopened.attempts());
        assertEquals("SUCCESS", reopened.read(key).path("status").asText());
        assertEquals(3, reopened.read(key).at("/usage/inputTokens").asInt());
        assertThrows(IllegalStateException.class, () -> reopened.begin(key, "embedding", "model", "v1"));
    }
    @Test void failedAndInterruptedAttemptsRemainVisibleAndCannotBeRetriedImplicitly() throws Exception {
        var cache = new AiQualityCache(directory);
        String first = AiQualityCache.key("analysis", "model", "v1", "first");
        String second = AiQualityCache.key("analysis", "model", "v1", "second");
        cache.begin(first, "analysis", "model", "v1"); cache.fail(first, "AI_UNAVAILABLE");
        cache.begin(second, "analysis", "model", "v1");
        var reopened = new AiQualityCache(directory);
        assertEquals(2, reopened.attempts());
        assertEquals("FAILED", reopened.read(first).path("status").asText());
        assertEquals("ATTEMPTED", reopened.read(second).path("status").asText());
        assertThrows(IllegalStateException.class, () -> reopened.begin(second, "analysis", "model", "v1"));
    }
}
