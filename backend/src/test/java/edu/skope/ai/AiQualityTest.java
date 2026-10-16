package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import edu.skope.service.SimilarityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A same-set development comparison, explicitly not a fresh independent holdout. */
@EnabledIfEnvironmentVariable(named = "SKOPE_RUN_QUALITY", matches = "1")
class AiQualityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIRECTORY = Path.of("..", "docs", "evaluation");
    private static final String FROZEN = "7ea381d2fedb7a2ab0f21237457acc5ad51dd049a6ba4086aa13566e9f3a923a";
    private final List<Map<String, Object>> pairInputs = new ArrayList<>();

    @Test void comparePreservedLiveBaselineAndCachedQualityRevision() throws Exception {
        Path cacheDirectory = Path.of("..", ".runtime", "ai-quality-cache", "v2");
        Files.createDirectories(cacheDirectory);
        try (var channel = FileChannel.open(cacheDirectory.resolve("evaluation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            assertNotNull(lock, "Another quality evaluation is running; no concurrent paid calls are allowed");
            measure();
        }
    }

    private void measure() throws Exception {
        byte[] fixtureBytes = Files.readAllBytes(DIRECTORY.resolve("holdout-v1.json"));
        byte[] beforeBytes = Files.readAllBytes(DIRECTORY.resolve("holdout-v1-live-results.json"));
        assertEquals(FROZEN, AiQualityCache.digest(fixtureBytes));
        JsonNode fixture = JSON.readTree(fixtureBytes), before = JSON.readTree(beforeBytes);
        assertEquals(FROZEN, before.path("fixtureSha256").asText());
        assertEquals("LIVE", before.path("requestedMode").asText());
        assertEquals(30, fixture.path("sentences").size()); assertEquals(20, fixture.path("pairs").size());
        String mode = env("SKOPE_QUALITY_MODE", "OFFLINE");
        assertTrue(Set.of("LIVE", "OFFLINE").contains(mode));
        String revision = AiQualityCache.digest(Files.readAllBytes(Path.of("src/main/java/edu/skope/ai/AiService.java")));
        CachedAi ai = new CachedAi(mode, revision);
        Map<String, Object> result = map("fixtureId", "holdout-v1", "fixtureSha256", FROZEN,
                "baselineSource", "holdout-v1-live-results.json", "baselineSha256", AiQualityCache.digest(beforeBytes),
                "evaluatedAt", OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(), "requestedMode", mode,
                "analysisImplementationSha256", revision,
                "similarityImplementationSha256", AiQualityCache.digest(Files.readAllBytes(Path.of("src/main/java/edu/skope/service/SimilarityService.java"))),
                "scope", "Same frozen synthetic development set reused after inspecting errors. Not independent generalization evidence. H2 service fixtures, not browser/MySQL.",
                "embeddingProvenance", "Original 40 vectors were lost when the prior H2 fixtures shut down. New unique input vectors are persisted and reused. Reconstructed scores are not recovered original scores.",
                "before", map("sentences", withoutRows(before.path("sentences")), "pairs", withoutRows(before.path("pairs"))));
        result.put("sentences", sentences(fixture.path("sentences"), before.path("sentences").path("rows"), ai));
        result.put("pairs", pairs(fixture.path("pairs"), before.path("pairs").path("rows"), ai));
        result.put("thresholdSweep", sweep());
        result.put("providerMeasurement", ai.measurement());
        result.put("qualityGate", "Test success confirms measurement generation, not all quality checks passing. Threshold sweep is descriptive development-set evidence and never selects a production threshold automatically.");
        Path output = DIRECTORY.resolve("quality-v2-" + mode.toLowerCase(Locale.ROOT) + "-results.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        Files.writeString(DIRECTORY.resolve("quality-v2-" + mode.toLowerCase(Locale.ROOT) + "-results.md"), markdown(result), StandardCharsets.UTF_8);
        assertEquals(AiQualityCache.digest(beforeBytes), AiQualityCache.digest(Files.readAllBytes(DIRECTORY.resolve("holdout-v1-live-results.json"))));
    }

    private Map<String, Object> sentences(JsonNode fixtures, JsonNode previousRows, CachedAi ai) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> correct = new LinkedHashMap<>();
        List<String> fields = List.of("intent", "category", "subcategory", "building", "floor", "emergencyHint", "symptomPresence", "locationPresence", "authorityBoundary");
        fields.forEach(field -> correct.put(field, 0)); int passes = 0, errors = 0;
        for (JsonNode sample : fixtures) {
            String id = sample.path("id").asText(); JsonNode previous = byId(previousRows, id);
            Map<String, Object> row = map("id", id, "input", sample.path("input"), "expected", sample.path("expected"),
                    "beforeStatus", previous.path("status"), "beforeActual", previous.path("actual"), "beforeFailedChecks", previous.path("failedChecks"));
            long start = System.nanoTime();
            try {
                Map<String, Object> actual = ai.analyze(JSON.convertValue(sample.path("input"), Map.class));
                Map<String, Object> report = (Map<String, Object>) actual.get("report"); JsonNode expected = sample.path("expected");
                Map<String, Boolean> checks = new LinkedHashMap<>(); checks.put("intent", same(expected.path("intent"), actual.get("intent")));
                for (String field : List.of("category", "subcategory", "building", "floor", "emergencyHint")) checks.put(field, same(expected.path(field), report.get(field)));
                checks.put("symptomPresence", !expected.path("symptomRequired").asBoolean() || nonblank(report.get("symptom")));
                checks.put("locationPresence", !expected.path("locationRequired").asBoolean() || nonblank(report.get("locationText")));
                checks.put("authorityBoundary", !actual.containsKey("routingPreview") && !actual.containsKey("questionAnswer")
                        && !report.containsKey("departmentName") && !report.containsKey("contactPhone") && !report.containsKey("sourceUrl"));
                boolean pass = checks.values().stream().allMatch(Boolean::booleanValue); if (pass) passes++;
                checks.forEach((field, value) -> { if (value) correct.merge(field, 1, Integer::sum); });
                row.put("actual", actual); row.put("checks", checks); row.put("status", pass ? "PASS" : "FAIL");
                row.put("source", ai.lastSource); row.put("change", change(previous.path("status").asText().equals("PASS"), pass));
                row.put("failedChecks", checks.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
            } catch (RuntimeException error) { errors++; row.put("status", "UNVERIFIED"); row.put("errorCode", safe(error)); }
            row.put("elapsedMs", (System.nanoTime() - start) / 1_000_000); rows.add(row);
        }
        return map("count", rows.size(), "allFieldsCorrect", passes, "errors", errors, "fieldCorrect", correct, "rows", rows);
    }

    Map<String, Object> pairs(JsonNode fixtures, JsonNode previousRows, AiService ai) {
        List<Map<String, Object>> rows = new ArrayList<>(); int tp = 0, fp = 0, tn = 0, fn = 0, errors = 0;
        for (JsonNode sample : fixtures) {
            String id = sample.path("id").asText(); boolean expected = sample.path("expectedCandidate").asBoolean(); JsonNode old = byId(previousRows, id);
            Map<String, Object> row = map("id", id, "expectedCandidate", expected, "left", sample.path("left"), "right", sample.path("right"),
                    "originalBeforeCandidate", old.path("actualCandidate"), "originalBeforeScore", old.path("score"), "beforeStatus", old.path("status"));
            try (Fixture fixture = new Fixture()) {
                long left = fixture.insert(sample.path("left")), right = fixture.insert(sample.path("right"));
                var service = new SimilarityService(fixture.db, ai, .82, 30); service.index(left); service.index(right);
                Map<String, Object> a = fixture.report(left), b = fixture.report(right);
                var diagnostic = SimilarityService.diagnose(a, b, .82, 30); row.put("diagnostic", diagnostic);
                boolean available = a.get("embeddingJson") != null && b.get("embeddingJson") != null;
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) service.similar(left).get("items");
                boolean actual = !candidates.isEmpty(); row.put("actualCandidate", actual);
                row.put("candidateMode", actual ? candidates.getFirst().get("aiMode") : null);
                row.put("candidateReason", actual ? candidates.getFirst().get("reasonText") : null);
                row.put("embeddingsAvailable", available); row.put("status", !available ? "UNVERIFIED" : actual == expected ? "PASS" : "FAIL");
                if (!available) errors++; else {
                    if (actual && expected) tp++; else if (actual) fp++; else if (expected) fn++; else tn++;
                    pairInputs.add(map("left", a, "right", b, "expected", expected));
                    row.put("change", change(old.path("status").asText().equals("PASS"), actual == expected));
                    assertEquals(diagnostic.get("candidate"), actual, "Diagnostic diverges from actual persisted candidate: " + id);
                }
                row.put("incidentAssignedAutomatically", a.get("incidentId") != null || b.get("incidentId") != null);
            } catch (RuntimeException error) { errors++; row.put("status", "UNVERIFIED"); row.put("errorCode", safe(error)); }
            rows.add(row);
        }
        Map<String, Object> result = metrics(tp, fp, tn, fn, errors); result.put("rows", rows); result.put("threshold", .82); result.put("windowDays", 30); return result;
    }

    List<Map<String, Object>> sweep() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (double threshold : new double[]{.50, .55, .60, .65, .70, .72, .74, .76, .78, .80, .82, .84, .86, .88, .90, .95}) {
            int tp = 0, fp = 0, tn = 0, fn = 0;
            for (Map<String, Object> pair : pairInputs) {
                var diagnostic = SimilarityService.diagnose((Map<String, Object>) pair.get("left"), (Map<String, Object>) pair.get("right"), threshold, 30);
                boolean candidate = Boolean.TRUE.equals(diagnostic.get("candidate")), expected = Boolean.TRUE.equals(pair.get("expected"));
                if (candidate && expected) tp++; else if (candidate) fp++; else if (expected) fn++; else tn++;
            }
            var row = metrics(tp, fp, tn, fn, 20 - pairInputs.size()); row.put("threshold", threshold); row.put("externalCalls", 0); rows.add(row);
        }
        return rows;
    }

    private static final class CachedAi extends AiService {
        final String requestedMode, revision, analysisModel, vectorModel;
        final AiQualityCache cache; final Map<String, long[]> usage = new TreeMap<>();
        Map<String, Long> currentUsage = Map.of(); String lastSource = ""; int analysisCalls, embeddingCalls, analysisHits, embeddingHits, failures, consecutiveFailures;
        boolean persistenceFailed;
        CachedAi(String mode, String revision) throws Exception {
            super("LIVE", env("AI_BASE_URL", "https://api.openai.com/v1"), env("AI_API_KEY", ""), env("AI_MODEL", "gpt-6-luna"),
                    "LIVE", env("EMBEDDING_MODEL", "text-embedding-3-small"), integer("AI_TIMEOUT_SECONDS", 30), integer("AI_MAX_COMPLETION_TOKENS", 1536), env("AI_REASONING_EFFORT", "none"));
            this.requestedMode = mode; this.revision = revision; analysisModel = env("AI_MODEL", "gpt-6-luna"); vectorModel = env("EMBEDDING_MODEL", "text-embedding-3-small");
            cache = new AiQualityCache(Path.of("..", ".runtime", "ai-quality-cache", "v2"));
        }
        @Override public Map<String, Object> analyze(Map<String, Object> input) {
            String settingsRevision = revision + "|reasoning=" + env("AI_REASONING_EFFORT", "none")
                    + "|maxCompletionTokens=" + integer("AI_MAX_COMPLETION_TOKENS", 1536);
            return JSON.convertValue(get("analysis", analysisModel, settingsRevision, input, () -> super.analyze(input)), Map.class);
        }
        @Override public Embedding embed(String input) {
            JsonNode value = get("embedding", vectorModel, "embedding-input-v1", input, () -> {
                Embedding result = super.embed(input); return map("vector", result.vector(), "model", result.model());
            });
            return new Embedding(JSON.convertValue(value.path("vector"), double[].class), value.path("model").asText());
        }
        private JsonNode get(String endpoint, String model, String version, Object input, java.util.function.Supplier<Object> work) {
            String key = "";
            try {
                key = AiQualityCache.key(endpoint, model, version, input); JsonNode entry = cache.read(key);
                if (entry != null) {
                    if (!entry.path("status").asText().equals("SUCCESS")) throw new ApiException(503, "CACHED_FAILED_OR_INTERRUPTED_ATTEMPT", "Previous attempt retained; no automatic retry");
                    if (endpoint.equals("analysis")) analysisHits++; else embeddingHits++;
                    lastSource = "CACHED_LIVE_PROVIDER_RESULT"; return entry.path("payload");
                }
                if (!requestedMode.equals("LIVE")) throw new ApiException(503, "OFFLINE_CACHE_MISS", "No live result cached");
                if (persistenceFailed || consecutiveFailures >= 3 || cache.attempts() >= 51) throw new ApiException(503, "QUALITY_CALL_LIMIT", "Persisted call budget reached or circuit open");
                if (env("AI_API_KEY", "").isBlank()) throw new ApiException(503, "AI_CONFIGURATION_MISSING", "No provider key configured");
                cache.begin(key, endpoint, model, version); currentUsage = Map.of();
                if (endpoint.equals("analysis")) analysisCalls++; else embeddingCalls++;
                try {
                    Object payload = work.get(); cache.complete(key, payload, currentUsage); consecutiveFailures = 0;
                    lastSource = "NEW_LIVE_PROVIDER_RESULT"; return JSON.valueToTree(payload);
                } catch (RuntimeException error) { failures++; consecutiveFailures++; cache.fail(key, safe(error), currentUsage); throw error; }
            } catch (java.io.IOException persistenceError) { persistenceFailed = true; throw new IllegalStateException("QUALITY_CACHE_PERSISTENCE_FAILED"); }
        }
        @Override protected void onUsage(String endpoint, long input, long output, long total) {
            currentUsage = Map.of("inputTokens", input, "outputTokens", output, "totalTokens", total);
            long[] counter = usage.computeIfAbsent(endpoint, ignored -> new long[4]); counter[0]++; counter[1] += input; counter[2] += output; counter[3] += total;
        }
        Map<String, Object> measurement() throws Exception {
            Map<String, Object> summaries = new TreeMap<>(); usage.forEach((endpoint, n) -> summaries.put(endpoint, map("responsesWithUsage", n[0], "inputTokens", n[1], "outputTokens", n[2], "totalTokens", n[3])));
            return map("mode", requestedMode, "model", analysisModel, "embeddingModel", vectorModel,
                    "analyzeAttempts", analysisCalls, "embeddingAttempts", embeddingCalls, "externalCallsAttempted", analysisCalls + embeddingCalls,
                    "analysisCacheHits", analysisHits, "embeddingCacheHits", embeddingHits, "newCallFailures", failures,
                    "maximumCumulativeExternalCalls", 51, "persistedAttemptsIncludingPreviousRuns", cache.attempts(),
                    "cacheDirectory", ".runtime/ai-quality-cache/v2", "newCallsUsage", summaries,
                    "usageNote", "Only new provider responses counted here; cache hits incur no new API call or token charge. Persisted cache entries retain original response usage.");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Db db; final long student;
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:quality_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__core.sql"), new ClassPathResource("db/migration/V2__incidents.sql"), new ClassPathResource("db/migration/V3__intake_context.sql")).execute(source);
            db = new Db(new JdbcTemplate(source)); student = db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)", "quality-student", "not-a-login-hash", "합성 학생", "STUDENT");
        }
        long insert(JsonNode data) {
            return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,floor,location_text,symptom,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    student, UUID.randomUUID().toString(), text(data, "text"), text(data, "category"), text(data, "subcategory"), text(data, "building"), text(data, "floor"), text(data, "locationText"), text(data, "text"),
                    Timestamp.valueOf(LocalDateTime.of(2026, 8, 1, 9, 0).plusDays(data.path("dayOffset").asInt())));
        }
        Map<String, Object> report(long id) { return db.one("SELECT * FROM reports WHERE id=?", id); }
        @Override public void close() { db.jdbc.execute("SHUTDOWN"); }
    }

    private static String markdown(Map<String, Object> result) throws Exception {
        JsonNode data = JSON.valueToTree(result); StringBuilder out = new StringBuilder("# AI quality v2 — ").append(data.path("requestedMode").asText()).append("\n\n")
                .append("실행: ").append(data.path("evaluatedAt").asText()).append("\n\n기존 LIVE 결과를 보존하고 같은 합성 평가셋을 재사용했습니다. 독립 품질 검증이 아닌 오류 분석 후 개발셋 비교입니다. 캐시는 실제 공급자 결과이며 DEMO가 아닙니다. JUnit 성공과 개별 품질 PASS를 구분합니다.\n\n")
                .append("원래 벡터는 보존되지 않았으므로 새 고유 입력 벡터로 원점수를 재구성했습니다. 원래 실행의 점수라고 해석하지 마세요.\n\n");
        for (String section : List.of("before", "sentences", "pairs", "thresholdSweep", "providerMeasurement")) {
            JsonNode item = data.path(section); out.append("## ").append(section).append("\n\n");
            if (item.has("rows")) {
                out.append("```json\n").append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(withoutRows(item))).append("\n```\n\n");
                out.append("| ID | 수정 전 | 수정 후 | 변화 | 실패 필드 / 제외 원인 |\n|---|---|---|---|---|\n");
                for (JsonNode row : item.path("rows")) out.append("| ").append(row.path("id").asText()).append(" | ").append(row.path("beforeStatus").asText()).append(" | ")
                        .append(row.path("status").asText()).append(" | ").append(row.path("change").asText()).append(" | ")
                        .append(row.has("failedChecks") ? row.path("failedChecks") : row.has("errorCode") ? row.path("errorCode") : row.at("/diagnostic/excludedBy")).append(" |\n");
                out.append("\n");
            } else out.append("```json\n").append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(item)).append("\n```\n\n");
        }
        return out.append("쌍별 rawCosine, 장소 정규화, 유형/시간 게이트, 전체 before/after 출력은 동명의 JSON에 있습니다. 임계값 sweep은 추가 API 호출 없이 계산하며 자동으로 운영 임계값을 변경하지 않습니다.\n").toString();
    }
    private static Map<String, Object> metrics(int tp, int fp, int tn, int fn, int errors) { return map("count", tp + fp + tn + fn + errors, "truePositive", tp, "falsePositive", fp, "trueNegative", tn, "falseNegative", fn, "errors", errors,
            "precision", tp + fp == 0 ? null : (double) tp / (tp + fp), "recall", tp + fn == 0 ? null : (double) tp / (tp + fn)); }
    private static JsonNode withoutRows(JsonNode node) { var copy = node.deepCopy(); if (copy.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("rows"); return copy; }
    private static JsonNode byId(JsonNode rows, String id) { for (JsonNode row : rows) if (row.path("id").asText().equals(id)) return row; throw new IllegalStateException("Missing preserved baseline row"); }
    private static String change(boolean before, boolean after) { return before ? after ? "UNCHANGED_PASS" : "REGRESSION" : after ? "IMPROVED" : "UNRESOLVED"; }
    private static String safe(RuntimeException error) { return error instanceof ApiException api ? api.code : error.getClass().getSimpleName(); }
    private static String text(JsonNode n, String key) { return n.path(key).isTextual() ? n.path(key).asText() : null; }
    private static boolean same(JsonNode expected, Object actual) { return Objects.equals(JSON.convertValue(expected, Object.class), actual); }
    private static boolean nonblank(Object value) { return value instanceof String text && !text.isBlank(); }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null || value.isBlank() ? fallback : value; }
    private static int integer(String key, int fallback) { return Integer.parseInt(env(key, Integer.toString(fallback))); }
    private static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
}
