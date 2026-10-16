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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen synthetic follow-up quality checks. Opt-in only; no automatic paid retry. */
@EnabledIfEnvironmentVariable(named = "SKOPE_RUN_FOLLOWUP", matches = "1")
class FollowUpVerificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIRECTORY = Path.of("..", "docs", "evaluation");
    private static final Path CACHE = Path.of("..", ".runtime", "ai-quality-cache", "followup-v1");
    private static final String FROZEN = "bad04470eaf7ac57bbc791446dbed59ed69ff6179fbccf881cc8ee48d037d0a9";
    private static final double INPUT_PRICE = .10, OUTPUT_PRICE = .50, EMBEDDING_PRICE = .02, MAX_USD = 1.0;
    private static final int MAX_ANALYSIS = 20, MAX_EMBEDDINGS = 4, MAX_COMPLETION = 1536;
    // These frozen examples informed implementation review before LIVE; never count them as unseen quality inputs.
    private static final Set<String> DEVELOPMENT_SEEN_GROUPS = Set.of("N02", "N03", "N10");
    private static final List<Path> SOURCES = List.of(Path.of("src/main/java/edu/skope/ai/AiService.java"),
            Path.of("src/main/java/edu/skope/ai/FollowUpPolicy.java"), Path.of("src/main/java/edu/skope/ai/EmergencySignals.java"),
            Path.of("src/main/java/edu/skope/service/SimilarityService.java"));

    @Test void measureFrozenFollowUpScenariosAndLiveMeaningPairs() throws Exception {
        assertEquals(FROZEN, AiQualityCache.digest(Files.readAllBytes(DIRECTORY.resolve("followup-v1.json"))));
        assertEquals(FROZEN, env("FOLLOWUP_FIXTURE_SHA256", ""), "Launcher must explicitly acknowledge frozen labels");
        Files.createDirectories(CACHE);
        try (var channel = FileChannel.open(CACHE.resolve("evaluation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            assertNotNull(lock, "Another follow-up evaluation owns the paid-call lock");
            JsonNode fixture = JSON.readTree(Files.readAllBytes(DIRECTORY.resolve("followup-v1.json")));
            var ai = new MeasuredAi();
            Map<String, Object> result = map("fixtureId", "followup-v1", "fixtureSha256", FROZEN,
                    "evaluatedAt", OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(), "requestedMode", ai.modeRequested,
                    "promptVersion", AiService.PROMPT_VERSION, "policyVersion", AiService.POLICY_VERSION,
                    "sourceHashes", ai.sourceHashes, "model", ai.analysisModel, "embeddingModel", ai.vectorModel,
                    "scope", "Actual production AiService analysis/merge and SimilarityService with isolated H2. No browser or MySQL revalidation; no DEMO. Seeded previous reports are fixed synthetic inputs, not claimed previous model output.",
                    "labelLimitation", fixture.path("developmentSeparation"), "developerSeenGroupIds", DEVELOPMENT_SEEN_GROUPS.stream().sorted().toList(),
                    "cohortNote", "N02/N03/N10 were used in implementation discussions or local regression design before LIVE; their frozen labels remain unchanged, but results are DEVELOPMENT_SEEN and excluded from new-quality aggregates.",
                    "preflight", ai.preflight(fixture));
            Path output = DIRECTORY.resolve("followup-v1-results.json");
            if (Files.exists(output)) {
                JsonNode previous = JSON.readTree(Files.readAllBytes(output));
                result.put("previousRunSummary", map("evaluatedAt", previous.path("evaluatedAt"),
                        "sourceHashes", previous.path("sourceHashes"), "policyVersion", previous.path("policyVersion"), "summary", previous.path("summary"),
                        "failedCases", previous.path("analysisRows").findValues("status").isEmpty() ? List.of() :
                                java.util.stream.StreamSupport.stream(previous.path("analysisRows").spliterator(), false)
                                        .filter(row -> "FAIL".equals(row.path("status").asText())).map(row -> map("id", row.path("id"),
                                                "actual", row.path("actual"), "failedChecks", row.path("failedChecks"), "causes", row.path("causes"), "cacheKey", row.path("cacheKey"))).toList(),
                        "providerMeasurement", previous.path("providerMeasurement"),
                        "note", "Original raw provider envelopes remain immutable in the cache; this file presents the latest deterministic policy evaluation."));
            }
            List<Map<String, Object>> rows = new ArrayList<>(); result.put("analysisRows", rows);
            for (JsonNode group : fixture.path("groups")) {
                Map<String, Object> prior = group.has("seed") ? convert(group.path("seed")) : null;
                Map<String, Object> state = group.has("seedState") ? convert(group.path("seedState")) : null;
                boolean dependencyAvailable = true;
                for (JsonNode sample : group.path("steps")) {
                    Map<String, Object> input = map("text", sample.path("text").asText(), "preferredIntent", sample.path("preferredIntent").asText());
                    Map<String, Object> row = map("id", sample.path("id").asText(), "group", group.path("id").asText(),
                            "cohort", DEVELOPMENT_SEEN_GROUPS.contains(group.path("id").asText()) ? "DEVELOPMENT_SEEN" : group.path("cohort").asText(),
                            "expected", sample.path("expected"), "previousReport", cloneValue(prior), "previousState", cloneValue(state));
                    if (sample.has("answer")) {
                        var follow = map("field", sample.path("field").asText(), "answer", sample.path("answer").asText(), "previousReport", prior);
                        if (state != null) follow.put("state", state);
                        input.put("followUp", follow);
                    }
                    row.put("input", cloneValue(input));
                    if (!dependencyAvailable) {
                        row.put("status", "UNVERIFIED"); row.put("cause", "PREVIOUS_PROVIDER_OR_HARNESS_FAILURE"); rows.add(row); continue;
                    }
                    try {
                        ai.lastRaw = null; ai.lastKey = ""; ai.lastProviderModel = ""; ai.lastSource = "NO_PROVIDER_CALL";
                        var merged = ai.analyze(input);
                        Map<String, Boolean> checks = checks(sample.path("expected"), merged, prior);
                        Map<String, Boolean> rawChecks = ai.lastRaw == null ? Map.of() : checks(sample.path("expected"), ai.lastRaw, prior);
                        List<String> failed = checks.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
                        Map<String, String> causes = new LinkedHashMap<>();
                        for (String field : failed) {
                            boolean policyCheck = field.startsWith("preserve:") || field.startsWith("question") || field.startsWith("missing:")
                                    || field.equals("unresolvedLocation") || field.equals("maxTurns");
                            causes.put(field, policyCheck ? "SERVER_POLICY_OR_MERGE" : Boolean.TRUE.equals(rawChecks.get(field))
                                    ? "SERVER_POSTPROCESSING_CHANGED_CORRECT_MODEL_FIELD" : "LLM_OUTPUT_OR_EXPLICIT_EVIDENCE_EXTRACTION");
                        }
                        row.put("rawModelOutput", cloneValue(ai.lastRaw)); row.put("actual", cloneValue(merged));
                        row.put("checks", checks); row.put("rawFieldChecks", rawChecks); row.put("failedChecks", failed); row.put("causes", causes);
                        row.put("status", failed.isEmpty() ? "PASS" : "FAIL"); row.put("source", ai.lastSource); row.put("cacheKey", ai.lastKey);
                        row.put("actualProviderModel", ai.lastProviderModel);
                        prior = convert(JSON.valueToTree(merged.get("report"))); state = convert(JSON.valueToTree(merged.get("followUpState")));
                    } catch (RuntimeException error) {
                        row.put("status", "UNVERIFIED"); row.put("cause", safe(error)); row.put("source", ai.lastSource);
                        dependencyAvailable = false; ai.circuitOpen = true;
                    }
                    rows.add(row); result.put("providerMeasurement", ai.measurement()); checkpoint(output, result);
                }
            }
            result.put("pairs", pairs(fixture.path("pairs"), ai));
            result.put("developmentReuse", previousPairs());
            var newQuality = rows.stream().filter(r -> "NEW_QUALITY".equals(r.get("cohort"))).toList();
            var development = rows.stream().filter(r -> "DEVELOPMENT_SEEN".equals(r.get("cohort"))).toList();
            result.put("summary", map("analysisCount", rows.size(), "analysisPass", count(rows, "PASS"),
                    "analysisFail", count(rows, "FAIL"), "analysisUnverified", count(rows, "UNVERIFIED"),
                    "newQuality", map("count", newQuality.size(), "pass", count(newQuality, "PASS"), "fail", count(newQuality, "FAIL"),
                            "unverified", count(newQuality, "UNVERIFIED"), "exactScenarioPassRate", newQuality.isEmpty() ? 0 : (double) count(newQuality, "PASS") / newQuality.size()),
                    "developmentSeen", map("count", development.size(), "pass", count(development, "PASS"), "fail", count(development, "FAIL"), "unverified", count(development, "UNVERIFIED")),
                    "qualityGate", "JUnit PASS means evidence collection completed; inspect per-case PASS/FAIL for quality. Same cases replayed after fixes are development regressions, not a new independent accuracy estimate."));
            result.put("providerMeasurement", ai.measurement()); ai.verifyPins(); checkpoint(output, result);
        }
    }

    private static Map<String, Boolean> checks(JsonNode expected, Map<String, Object> actual, Map<String, Object> previous) {
        JsonNode tree = JSON.valueToTree(actual); Map<String, Boolean> checks = new LinkedHashMap<>();
        expected.path("equals").fields().forEachRemaining(e -> checks.put("equals:" + e.getKey(), e.getValue().equals(tree.at(e.getKey()))));
        for (String mode : List.of("contains", "excludes")) expected.path(mode).fields().forEachRemaining(e -> {
            String value = normalized(tree.at(e.getKey()).asText(""));
            for (JsonNode part : e.getValue()) checks.put(mode + ":" + e.getKey() + ":" + part.asText(),
                    mode.equals("contains") == value.contains(normalized(part.asText())));
        });
        for (JsonNode field : expected.path("preserve")) checks.put("preserve:" + field.asText(), previous != null
                && Objects.equals(previous.get(field.asText()), actualReport(actual).get(field.asText())));
        if (expected.has("question")) checks.put("question", expected.path("question").asBoolean()
                == !tree.at("/report/followUpQuestion").asText("").isBlank());
        for (JsonNode field : expected.path("missingContains")) {
            boolean found = false; for (JsonNode missing : tree.at("/report/missingFields")) if (missing.equals(field)) found = true;
            checks.put("missing:" + field.asText(), found);
        }
        if (expected.path("unresolvedLocation").asBoolean()) {
            String floor = tree.at("/report/floor").asText(""); String place = tree.at("/report/locationText").asText("");
            checks.put("unresolvedLocation", floor.isBlank() && !place.contains("507") && !place.contains("406")
                    && !tree.at("/report/followUpQuestion").asText("").isBlank());
        }
        if (expected.has("maxTurns")) checks.put("maxTurns", tree.at("/followUpState/turns").asInt(-1) == expected.path("maxTurns").asInt()
                && tree.at("/report/followUpQuestion").asText("").isBlank());
        return checks;
    }

    private static Map<String, Object> pairs(JsonNode samples, MeasuredAi ai) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(); int tp = 0, fp = 0, tn = 0, fn = 0, unverified = 0;
        for (JsonNode sample : samples) {
            Map<String, Object> row = map("id", sample.path("id").asText(), "left", sample.path("left"), "right", sample.path("right"),
                    "expectedCandidate", sample.path("expectedCandidate").asBoolean());
            try (PairFixture fixture = new PairFixture()) {
                long leftId = fixture.insert(sample.path("left")), rightId = fixture.insert(sample.path("right"));
                var service = new SimilarityService(fixture.db, ai, .82, 30); service.index(leftId); service.index(rightId);
                Map<String, Object> left = fixture.report(leftId), right = fixture.report(rightId);
                boolean available = left.get("embeddingJson") != null && right.get("embeddingJson") != null;
                var diagnostic = SimilarityService.diagnose(left, right, .82, 30);
                boolean actual = !((List<?>) service.similar(leftId).get("items")).isEmpty(), expected = sample.path("expectedCandidate").asBoolean();
                row.put("actualCandidate", actual); row.put("diagnostic", diagnostic); row.put("embeddingsAvailable", available);
                row.put("status", available ? actual == expected ? "PASS" : "FAIL" : "UNVERIFIED");
                row.put("automaticIncidentLink", left.get("incidentId") != null || right.get("incidentId") != null);
                assertEquals(diagnostic.get("candidate"), actual); assertFalse((Boolean) row.get("automaticIncidentLink"));
                if (!available) unverified++; else if (actual && expected) tp++; else if (actual) fp++; else if (expected) fn++; else tn++;
            } catch (RuntimeException error) { row.put("status", "UNVERIFIED"); row.put("cause", safe(error)); unverified++; }
            rows.add(row);
        }
        return map("tp", tp, "fp", fp, "tn", tn, "fn", fn, "unverified", unverified,
                "precision", tp + fp == 0 ? null : (double) tp / (tp + fp), "recall", tp + fn == 0 ? null : (double) tp / (tp + fn),
                "threshold", .82, "conditionalWifiThreshold", .70, "rows", rows);
    }

    private static Map<String, Object> previousPairs() throws Exception {
        Path path = DIRECTORY.resolve("fresh-v1-results.json"); byte[] original = Files.readAllBytes(path); JsonNode previous = JSON.readTree(original);
        // Stored original scores are sufficient for the requested recall analysis; no provider/H2 rerun is needed.
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (JsonNode row : previous.path("pairs")) pairs.add(map("id", row.path("id"), "expectedCandidate", row.path("expectedCandidate"),
                "actualCandidate", row.path("actualCandidate"), "status", row.path("status"), "rawCosine", row.at("/diagnostic/rawCosine"),
                "excludedBy", row.at("/diagnostic/excludedBy"), "decisionBasis", row.at("/diagnostic/decisionBasis")));
        return map("cohort", "DEVELOPMENT_REUSE", "source", "fresh-v1-results.json", "sourceSha256", AiQualityCache.digest(original),
                "externalCalls", 0, "originalPairResults", pairs,
                "note", "Original frozen ten-pair scores and exclusions reused unchanged. No threshold selection or new independent claim.");
    }

    private static final class MeasuredAi extends AiService {
        final String modeRequested = env("FOLLOWUP_MODE", "OFFLINE"), analysisModel = env("AI_MODEL", "gpt-6-luna"), vectorModel = env("EMBEDDING_MODEL", "text-embedding-3-small");
        final AiQualityCache cache; final Map<String, String> sourceHashes = new LinkedHashMap<>();
        final Map<String, long[]> newUsage = new TreeMap<>(); Map<String, Long> currentUsage = Map.of();
        Map<String, Object> lastRaw; String lastSource = "", lastKey = "", lastProviderModel = ""; int analysisCalls, embeddingCalls, cacheHits, failures;
        boolean circuitOpen;
        MeasuredAi() throws Exception {
            super("LIVE", env("AI_BASE_URL", "https://api.openai.com/v1"), env("FOLLOWUP_MODE", "OFFLINE").equals("OFFLINE") ? "offline-cache-only" : env("AI_API_KEY", ""), env("AI_MODEL", "gpt-6-luna"),
                    "LIVE", env("EMBEDDING_MODEL", "text-embedding-3-small"), 60, MAX_COMPLETION, env("AI_REASONING_EFFORT", "none"));
            assertTrue(Set.of("LIVE", "OFFLINE").contains(modeRequested));
            assertEquals("https://api.openai.com/v1", env("AI_BASE_URL", "https://api.openai.com/v1"));
            assertEquals("gpt-6-luna", analysisModel); assertEquals("text-embedding-3-small", vectorModel);
            cache = new AiQualityCache(CACHE);
            for (Path source : SOURCES) if (Files.exists(source)) sourceHashes.put(source.toString(), AiQualityCache.digest(Files.readAllBytes(source)));
        }
        @Override protected JsonNode complete(Map<String, Object> body) {
            String revision = AiService.PROMPT_VERSION + "|raw-envelope-v1";
            long inputBound;
            try { inputBound = JSON.writeValueAsBytes(body).length + 1024L; } catch (Exception error) { throw new IllegalStateException("BODY_ESTIMATE_FAILED"); }
            JsonNode response = get("analysis", analysisModel, revision, body, inputBound, MAX_COMPLETION,
                    () -> super.complete(body));
            lastProviderModel = response.path("model").asText("");
            return response;
        }
        @Override public Embedding embed(String input) {
            JsonNode value = get("embedding", vectorModel, "embedding-input-v1", input,
                    input.getBytes(StandardCharsets.UTF_8).length + 256L, 0, () -> {
                        Embedding embedding = super.embed(input); return JSON.valueToTree(map("vector", embedding.vector(), "model", embedding.model()));
                    });
            return new Embedding(JSON.convertValue(value.path("vector"), double[].class), value.path("model").asText());
        }
        @Override protected void onAnalysis(Map<String, Object> input, Map<String, Object> raw, Map<String, Object> merged) { lastRaw = convert(JSON.valueToTree(raw)); }
        @Override protected void onUsage(String endpoint, long input, long output, long total) {
            currentUsage = Map.of("inputTokens", input, "outputTokens", output, "totalTokens", total);
            long[] usage = newUsage.computeIfAbsent(endpoint, ignored -> new long[4]); usage[0]++; usage[1] += input; usage[2] += output; usage[3] += total;
        }
        private JsonNode get(String endpoint, String model, String revision, Object input, long inputBound, long outputBound, Supplier<JsonNode> work) {
            try {
                verifyPins(); String key = AiQualityCache.key(endpoint, model, revision, canonical(JSON.valueToTree(input))); lastKey = key;
                JsonNode entry = cache.read(key);
                if (entry != null) {
                    if (!"SUCCESS".equals(entry.path("status").asText())) { circuitOpen = true; throw new ApiException(503, "CACHED_FAILED_OR_INTERRUPTED_ATTEMPT", "No paid retry"); }
                    cacheHits++; lastSource = "CACHED_LIVE_RAW_PROVIDER_RESPONSE"; return entry.at("/payload/response");
                }
                if (!modeRequested.equals("LIVE")) throw new ApiException(503, "OFFLINE_CACHE_MISS", "No network fallback permitted");
                if (env("AI_API_KEY", "").isBlank()) throw new ApiException(503, "AI_CONFIGURATION_MISSING", "Missing provider key");
                var ledger = ledger();
                if (circuitOpen || (Boolean) ledger.get("hasIncompleteOrFailed")) throw new ApiException(503, "PAID_CIRCUIT_OPEN", "A prior failed attempt stops new calls");
                int limit = endpoint.equals("analysis") ? MAX_ANALYSIS : MAX_EMBEDDINGS;
                if (((Number) ledger.get(endpoint + "Attempts")).intValue() >= limit) throw new ApiException(503, "PAID_CALL_LIMIT", "Cumulative call limit reached");
                double reserve = 2 * cost(endpoint, inputBound, outputBound);
                if (((Number) ledger.get("conservativeCostUsd")).doubleValue() + reserve > MAX_USD)
                    throw new ApiException(503, "PAID_COST_LIMIT", "Conservative reservation exceeds one dollar");
                cache.begin(key, endpoint, model, revision); currentUsage = Map.of();
                if (endpoint.equals("analysis")) analysisCalls++; else embeddingCalls++;
                try {
                    JsonNode response = work.get();
                    if (currentUsage.isEmpty()) throw new ApiException(503, "USAGE_MISSING", "Cannot continue without provider usage evidence");
                    cache.complete(key, map("response", response, "inputTokenUpperBound", inputBound, "outputTokenUpperBound", outputBound,
                            "conservativeReservationUsd", reserve), currentUsage);
                    lastSource = "NEW_LIVE_PROVIDER_RESPONSE"; return response;
                } catch (RuntimeException error) { failures++; circuitOpen = true; cache.fail(key, safe(error), currentUsage); throw error; }
            } catch (java.io.IOException error) { circuitOpen = true; throw new IllegalStateException("FOLLOWUP_CACHE_PERSISTENCE_FAILED"); }
        }
        void verifyPins() throws java.io.IOException {
            if (!FROZEN.equals(AiQualityCache.digest(Files.readAllBytes(DIRECTORY.resolve("followup-v1.json"))))) throw new IllegalStateException("FROZEN_FIXTURE_CHANGED");
            for (var entry : sourceHashes.entrySet()) if (!entry.getValue().equals(AiQualityCache.digest(Files.readAllBytes(Path.of(entry.getKey())))))
                throw new IllegalStateException("SOURCE_CHANGED_DURING_MEASUREMENT");
        }
        Map<String, Object> preflight(JsonNode fixture) throws Exception {
            // 40,000 input bytes/tokens plus output cap per analysis is deliberately above this small fixture.
            double estimate = 2 * (fixture.path("plannedAnalysisCalls").asInt() * cost("analysis", 40_000, MAX_COMPLETION)
                    + fixture.path("plannedUniqueEmbeddingCalls").asInt() * cost("embedding", 12_256, 0));
            assertTrue(estimate < MAX_USD, "Planned conservative estimate exceeds user budget");
            return map("plannedAnalysisCalls", fixture.path("plannedAnalysisCalls").asInt(), "plannedUniqueEmbeddingCalls", 3,
                    "conservativeEstimatedMaximumUsd", estimate, "budgetUsd", MAX_USD, "automaticPaidRetries", 0,
                    "pricingVerifiedDate", "2026-10-14", "inputUsdPerMillion", INPUT_PRICE, "outputUsdPerMillion", OUTPUT_PRICE,
                    "embeddingUsdPerMillion", EMBEDDING_PRICE, "guardMultiplier", 2,
                    "pricingSources", List.of("https://developers.openai.com/api/docs/models/gpt-6-luna", "https://developers.openai.com/api/docs/models/text-embedding-3-small"));
        }
        Map<String, Object> ledger() throws java.io.IOException {
            int analysis = 0, embedding = 0; boolean incomplete = false; double actual = 0, conservative = 0;
            long input = 0, output = 0, total = 0;
            try (var paths = Files.list(CACHE)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) {
                    JsonNode entry = JSON.readTree(Files.readAllBytes(path)); String endpoint = entry.path("endpoint").asText();
                    if (endpoint.equals("analysis")) analysis++; else if (endpoint.equals("embedding")) embedding++;
                    incomplete |= !"SUCCESS".equals(entry.path("status").asText());
                    long in = entry.at("/usage/inputTokens").asLong(), out = entry.at("/usage/outputTokens").asLong();
                    input += in; output += out; total += entry.at("/usage/totalTokens").asLong();
                    double paid = cost(endpoint, in, out); actual += paid;
                    conservative += entry.at("/payload/conservativeReservationUsd").asDouble(2 * paid);
                }
            }
            return map("analysisAttempts", analysis, "embeddingAttempts", embedding, "hasIncompleteOrFailed", incomplete,
                    "estimatedCostUsd", actual, "conservativeCostUsd", conservative,
                    "inputTokens", input, "outputTokens", output, "totalTokens", total);
        }
        Map<String, Object> measurement() throws java.io.IOException {
            Map<String, Object> usage = new TreeMap<>(); newUsage.forEach((endpoint, n) -> usage.put(endpoint,
                    map("responses", n[0], "inputTokens", n[1], "outputTokens", n[2], "totalTokens", n[3])));
            return map("newAnalysisCalls", analysisCalls, "newEmbeddingCalls", embeddingCalls, "newExternalCalls", analysisCalls + embeddingCalls,
                    "newCallFailures", failures, "cacheHits", cacheHits, "newUsage", usage, "cumulative", ledger(),
                    "maximumAnalysisCalls", MAX_ANALYSIS, "maximumEmbeddingCalls", MAX_EMBEDDINGS, "budgetUsd", MAX_USD,
                    "automaticPaidRetries", 0, "cacheDirectory", ".runtime/ai-quality-cache/followup-v1");
        }
    }

    private static final class PairFixture implements AutoCloseable {
        final Db db; final long student;
        PairFixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:followup_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__core.sql"), new ClassPathResource("db/migration/V2__incidents.sql"), new ClassPathResource("db/migration/V3__intake_context.sql")).execute(source);
            db = new Db(new JdbcTemplate(source)); student = db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)", "followup-student", "not-a-login-hash", "합성 검증 학생", "STUDENT");
        }
        long insert(JsonNode sample) {
            return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,floor,location_text,symptom,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    student, UUID.randomUUID().toString(), text(sample, "text"), text(sample, "category"), text(sample, "subcategory"), text(sample, "building"),
                    text(sample, "floor"), text(sample, "locationText"), text(sample, "symptom"), Timestamp.valueOf(LocalDateTime.of(2026, 10, 14, 9, 0).plusDays(sample.path("dayOffset").asInt())));
        }
        Map<String, Object> report(long id) { return db.one("SELECT * FROM reports WHERE id=?", id); }
        @Override public void close() { db.jdbc.execute("SHUTDOWN"); }
    }
    private static double cost(String endpoint, long input, long output) { return (input * (endpoint.equals("embedding") ? EMBEDDING_PRICE : INPUT_PRICE) + output * OUTPUT_PRICE) / 1_000_000d; }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null || value.isBlank() ? fallback : value.strip(); }
    private static String text(JsonNode node, String field) { return node.path(field).isNull() ? null : node.path(field).asText(); }
    private static String normalized(String text) { return text.replaceAll("\\s+", ""); }
    private static String safe(RuntimeException error) { return error instanceof ApiException api ? api.code : error.getClass().getSimpleName(); }
    private static long count(List<Map<String, Object>> rows, String status) { return rows.stream().filter(r -> status.equals(r.get("status"))).count(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> actualReport(Map<String, Object> actual) { return (Map<String, Object>) actual.getOrDefault("report", Map.of()); }
    @SuppressWarnings("unchecked") private static Map<String, Object> convert(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : JSON.convertValue(node, LinkedHashMap.class); }
    private static Object cloneValue(Object value) { return value == null ? null : JSON.valueToTree(value); }
    private static Object canonical(JsonNode node) {
        if (node.isObject()) { Map<String, Object> out = new TreeMap<>(); node.fields().forEachRemaining(e -> out.put(e.getKey(), canonical(e.getValue()))); return out; }
        if (node.isArray()) { List<Object> out = new ArrayList<>(); node.forEach(n -> out.add(canonical(n))); return out; }
        return JSON.convertValue(node, Object.class);
    }
    private static Map<String, Object> map(Object... entries) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]); return result; }
    private static void checkpoint(Path output, Map<String, Object> result) throws java.io.IOException {
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
