package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** No inference: replay previously measured LIVE outputs through explicit deterministic changes. */
@EnabledIfEnvironmentVariable(named = "SKOPE_RUN_QUALITY_REPLAY", matches = "1")
class AiQualityReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path RESULTS = Path.of("..", "docs", "evaluation");
    private static final Path CACHE = Path.of("..", ".runtime", "ai-quality-cache", "v2");
    private static final String FIXTURE_SHA = "7ea381d2fedb7a2ab0f21237457acc5ad51dd049a6ba4086aa13566e9f3a923a";

    @Test void replayMeasuredOutputsWithoutProviderAccess() throws Exception {
        assertTrue(Files.isDirectory(CACHE), "Existing LIVE cache is required");
        try (var channel = FileChannel.open(CACHE.resolve("evaluation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            assertNotNull(lock, "Another quality evaluation is running");
            byte[] liveBytes = Files.readAllBytes(RESULTS.resolve("quality-v2-live-results.json"));
            byte[] originalBytes = Files.readAllBytes(RESULTS.resolve("holdout-v1-live-results.json"));
            byte[] fixtureBytes = Files.readAllBytes(RESULTS.resolve("holdout-v1.json"));
            assertEquals(FIXTURE_SHA, AiQualityCache.digest(fixtureBytes));
            JsonNode live = JSON.readTree(liveBytes), original = JSON.readTree(originalBytes), fixture = JSON.readTree(fixtureBytes);
            assertEquals("LIVE", live.path("requestedMode").asText());
            assertEquals(FIXTURE_SHA, live.path("fixtureSha256").asText());
            assertEquals(30, live.path("sentences").path("rows").size());
            var cacheBefore = cacheHashes();
            Map<String, Object> result = map("requestedMode", "OFFLINE_REPLAY_OF_LIVE_RESULTS", "externalCallsAttempted", 0,
                    "evaluatedAt", OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(), "fixtureSha256", FIXTURE_SHA,
                    "sourceLiveResult", "quality-v2-live-results.json", "sourceLiveSha256", AiQualityCache.digest(liveBytes),
                    "originalBaselineSha256", AiQualityCache.digest(originalBytes),
                    "currentAnalysisSourceSha256", AiQualityCache.digest(Files.readAllBytes(Path.of("src/main/java/edu/skope/ai/AiService.java"))),
                    "currentSimilaritySourceSha256", AiQualityCache.digest(Files.readAllBytes(Path.of("src/main/java/edu/skope/service/SimilarityService.java"))),
                    "scope", "No new LLM inference. Only AiService.normalizeFloor is applied to previously measured final LIVE outputs. Existing embedding vectors are reused through current SimilarityService on H2. This is not prompt revalidation or browser/MySQL E2E.");
            result.put("sentences", replaySentences(live.path("sentences").path("rows")));
            var evaluator = new AiQualityTest(); var ai = new OfflineVectors(live.at("/providerMeasurement/embeddingModel").asText());
            result.put("pairs", evaluator.pairs(fixture.path("pairs"), original.at("/pairs/rows"), ai));
            result.put("currentPolicyThresholdSweep", evaluator.sweep());
            result.put("embeddingReplayRequests", ai.requests);
            result.put("before", map("originalSentencesCorrect", original.at("/sentences/allFieldsCorrect").asInt(),
                    "updatedPromptLiveSentencesCorrect", live.at("/sentences/allFieldsCorrect").asInt(),
                    "pairs", summary(live.path("pairs"))));
            result.put("demo", map("status", "NOT_RUN_IN_THIS_REPLAY", "note", "Cached provider outputs are LIVE evidence, never DEMO. RULE_ONLY fallback is unverified for this LIVE replay."));
            assertEquals(cacheBefore, cacheHashes(), "Replay must not modify persisted provider results");
            assertArrayEquals(liveBytes, Files.readAllBytes(RESULTS.resolve("quality-v2-live-results.json")));
            assertArrayEquals(originalBytes, Files.readAllBytes(RESULTS.resolve("holdout-v1-live-results.json")));
            result.put("inputArtifactsUnchanged", true);
            JSON.writerWithDefaultPrettyPrinter().writeValue(RESULTS.resolve("quality-v2-replay-results.json").toFile(), result);
            Files.writeString(RESULTS.resolve("quality-v2-replay-results.md"), markdown(result), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> replaySentences(JsonNode rows) {
        List<Map<String, Object>> measured = new ArrayList<>(); int correct = 0, changed = 0;
        for (JsonNode source : rows) {
            assertTrue(source.has("actual") && source.has("checks"), "Only successful measured provider responses can be replayed");
            ObjectNode actual = source.path("actual").deepCopy(); ObjectNode report = (ObjectNode) actual.path("report");
            String beforeFloor = report.path("floor").isNull() ? null : report.path("floor").asText();
            String afterFloor = AiService.normalizeFloor(beforeFloor);
            if (afterFloor == null) report.putNull("floor"); else report.put("floor", afterFloor);
            boolean normalized = !Objects.equals(beforeFloor, afterFloor); if (normalized) changed++;
            ObjectNode checks = source.path("checks").deepCopy();
            checks.put("floor", Objects.equals(source.at("/expected/floor").isNull() ? null : source.at("/expected/floor").asText(), afterFloor));
            List<String> failed = new ArrayList<>(); checks.fields().forEachRemaining(e -> { if (!e.getValue().asBoolean()) failed.add(e.getKey()); });
            boolean pass = failed.isEmpty(); if (pass) correct++;
            measured.add(map("id", source.path("id").asText(), "input", source.path("input"), "expected", source.path("expected"),
                    "originalStatus", source.path("beforeStatus"), "liveStatus", source.path("status"), "storedLiveActual", source.path("actual"),
                    "actual", actual, "checks", checks, "failedChecks", failed, "normalizationApplied", normalized,
                    "status", pass ? "PASS" : "FAIL", "source", "CACHED_LIVE_OUTPUT_PLUS_PRODUCTION_FLOOR_NORMALIZER"));
        }
        return map("count", measured.size(), "allFieldsCorrect", correct, "outputsChangedByNormalization", changed,
                "newModelCalls", 0, "rows", measured);
    }

    private static final class OfflineVectors extends AiService {
        private final AiQualityCache cache; private final String model; int requests;
        OfflineVectors(String model) throws Exception {
            super("LIVE", "https://api.openai.com/v1", "", "unused-offline", "LIVE", model, 1);
            this.model = model; cache = new AiQualityCache(CACHE);
        }
        @Override public Map<String, Object> analyze(Map<String, Object> input) { throw new AssertionError("Offline replay cannot invoke LLM analysis"); }
        @Override public Embedding embed(String input) {
            requests++;
            try {
                JsonNode entry = cache.read(AiQualityCache.key("embedding", model, "embedding-input-v1", input));
                assertNotNull(entry, "Missing saved embedding; no network fallback permitted");
                assertEquals("SUCCESS", entry.path("status").asText(), "Only successful cached embeddings can be replayed");
                return new Embedding(JSON.convertValue(entry.at("/payload/vector"), double[].class), entry.at("/payload/model").asText());
            } catch (java.io.IOException error) { throw new AssertionError("Cannot read immutable embedding cache", error); }
        }
    }
    private static Map<String, String> cacheHashes() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.list(CACHE)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) hashes.put(path.getFileName().toString(), AiQualityCache.digest(Files.readAllBytes(path)));
        }
        return hashes;
    }
    private static JsonNode summary(JsonNode value) { ObjectNode result = value.deepCopy(); result.remove("rows"); return result; }
    private static String markdown(Map<String, Object> result) throws Exception {
        JsonNode data = JSON.valueToTree(result); StringBuilder text = new StringBuilder("# AI quality v2 — OFFLINE 재처리\n\n")
                .append("기존 실제 LIVE 출력과 벡터를 재사용했습니다. 추가 API 호출 0회이며 새 LLM 추론·프롬프트 재평가가 아닙니다. 문장은 실제 운영 코드의 floor 정규화만 재처리했고, 유사 후보는 현행 SimilarityService를 H2에서 실행했습니다. DEMO를 사용하지 않았습니다.\n\n");
        for (String section : List.of("before", "sentences", "pairs")) text.append("## ").append(section).append("\n\n```json\n")
                .append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary(data.path(section)))).append("\n```\n\n");
        return text.append("상세 before/after와 게이트·점수·결정 근거는 동명의 JSON에 있습니다. 같은 개발셋을 재사용한 결과이며 독립 품질 검증은 아닙니다. 기존 결과 파일과 공급자 캐시 해시가 변경되지 않았음을 확인했습니다.\n").toString();
    }
    private static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
}
