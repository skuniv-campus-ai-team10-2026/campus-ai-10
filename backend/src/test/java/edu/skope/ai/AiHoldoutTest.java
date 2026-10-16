package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import edu.skope.service.IncidentService;
import edu.skope.service.SimilarityService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in measurement. Quality failures are reported individually, never relabeled or hidden. */
@Tag("holdout")
@EnabledIfEnvironmentVariable(named = "SKOPE_RUN_HOLDOUT", matches = "1")
class AiHoldoutTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String FROZEN_SHA256 = "7ea381d2fedb7a2ab0f21237457acc5ad51dd049a6ba4086aa13566e9f3a923a";
    private static final Path DIRECTORY = Path.of("..", "docs", "evaluation");

    @Test void measureFrozenHoldoutWithoutChangingTheLabels() throws Exception {
        byte[] fixtureBytes = Files.readAllBytes(DIRECTORY.resolve("holdout-v1.json"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixtureBytes));
        assertEquals(FROZEN_SHA256, hash, "Frozen labels changed. Create a new version instead of editing them.");
        JsonNode fixture = JSON.readTree(fixtureBytes);
        assertEquals(30, fixture.path("sentences").size());
        assertEquals(20, fixture.path("pairs").size());
        assertEquals(6, fixture.path("incidents").size());
        String mode = env("SKOPE_HOLDOUT_MODE", "DEMO");
        assertTrue(Set.of("DEMO", "LIVE").contains(mode));
        boolean configured = !env("AI_API_KEY", "").isBlank() && !env("AI_MODEL", "").isBlank()
                && !env("EMBEDDING_MODEL", "").isBlank();
        // A LIVE run without all credentials/models measures neither AI path and makes zero provider calls.
        boolean aiAvailable = mode.equals("DEMO") || configured;
        MeasuredAi ai = aiAvailable ? new MeasuredAi(mode) : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixtureId", fixture.path("id").textValue());
        result.put("fixtureSha256", hash);
        result.put("evaluatedAt", OffsetDateTime.now(SEOUL).toString());
        result.put("requestedMode", mode);
        result.put("labelProvenance", fixture.path("labelProvenance").textValue());
        result.put("database", "isolated H2 in-memory fixtures; no MySQL or school DB touched");
        result.put("status", aiAvailable ? "MEASURED" : "AI_SKIPPED_MISSING_CONFIGURATION");
        result.put("sentences", aiAvailable ? sentences(fixture.path("sentences"), ai) : skipped("AI key or model configuration missing"));
        result.put("pairs", aiAvailable ? pairs(fixture.path("pairs"), ai) : skipped("AI key or model configuration missing"));
        result.put("incidents", incidents(fixture.path("incidents")));
        result.put("providerMeasurement", ai == null ? Map.of("calls", 0, "status", "SKIPPED") : ai.measurement());
        result.put("billing", "Provider token counts are usage telemetry. Monetary billing is not verified; no prices are hardcoded.");
        result.put("qualityGate", "This JUnit test verifies fixture integrity and produces metrics. BUILD SUCCESSFUL does not mean every expected field or candidate was correct.");
        Files.createDirectories(DIRECTORY);
        String stem = "holdout-v1-" + mode.toLowerCase(Locale.ROOT) + "-results";
        JSON.writerWithDefaultPrettyPrinter().writeValue(DIRECTORY.resolve(stem + ".json").toFile(), result);
        Files.writeString(DIRECTORY.resolve(stem + ".md"), markdown(result), StandardCharsets.UTF_8);
    }

    private Map<String, Object> sentences(JsonNode fixtures, MeasuredAi ai) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> correct = new LinkedHashMap<>();
        for (String name : List.of("intent", "category", "subcategory", "building", "floor", "emergencyHint", "symptomPresence", "locationPresence")) correct.put(name, 0);
        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        int all = 0, errors = 0, unexpectedAuthorityFields = 0;
        for (JsonNode sample : fixtures) {
            Map<String, Object> row = baseRow(sample);
            long started = System.nanoTime();
            try {
                Map<String, Object> input = JSON.convertValue(sample.path("input"), Map.class);
                Map<String, Object> output = ai.analyze(input);
                Map<String, Object> report = (Map<String, Object>) output.get("report");
                Map<String, Boolean> checks = new LinkedHashMap<>();
                JsonNode expected = sample.path("expected");
                checks.put("intent", same(expected.path("intent"), output.get("intent")));
                for (String name : List.of("category", "subcategory", "building", "floor", "emergencyHint"))
                    checks.put(name, same(expected.path(name), report.get(name)));
                checks.put("symptomPresence", !expected.path("symptomRequired").asBoolean() || nonblank(report.get("symptom")));
                checks.put("locationPresence", !expected.path("locationRequired").asBoolean() || nonblank(report.get("locationText")));
                boolean authoritySafe = !output.containsKey("routingPreview") && !output.containsKey("questionAnswer")
                        && !report.containsKey("departmentName") && !report.containsKey("contactPhone") && !report.containsKey("sourceUrl");
                if (!authoritySafe) unexpectedAuthorityFields++;
                checks.put("authorityBoundary", authoritySafe);
                boolean pass = checks.values().stream().allMatch(Boolean::booleanValue);
                if (pass) all++;
                checks.forEach((k, v) -> { if (v && correct.containsKey(k)) correct.compute(k, (key, n) -> n + 1); });
                confusion.computeIfAbsent(expected.path("intent").asText(), key -> new TreeMap<>())
                        .merge(Objects.toString(output.get("intent"), "MISSING"), 1, Integer::sum);
                row.put("input", input); row.put("expected", expected); row.put("actual", output);
                row.put("checks", checks); row.put("status", pass ? "PASS" : "FAIL");
                row.put("failedChecks", checks.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
            } catch (RuntimeException error) {
                errors++;
                row.put("input", sample.path("input")); row.put("expected", sample.path("expected"));
                row.put("status", "ERROR"); row.put("errorCode", safeError(error));
                confusion.computeIfAbsent(sample.at("/expected/intent").asText(), key -> new TreeMap<>()).merge("ERROR", 1, Integer::sum);
            }
            row.put("elapsedMs", elapsed(started)); rows.add(row);
        }
        return map("status", "MEASURED", "count", rows.size(), "allFieldsCorrect", all, "fieldCorrect", correct,
                "errors", errors, "unexpectedAuthorityFieldCount", unexpectedAuthorityFields, "intentConfusionMatrix", confusion, "rows", rows);
    }

    private Map<String, Object> pairs(JsonNode fixtures, MeasuredAi ai) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int tp = 0, fp = 0, tn = 0, fn = 0, errors = 0, storedLiveVectors = 0;
        for (JsonNode sample : fixtures) {
            Map<String, Object> row = baseRow(sample);
            row.put("expectedCandidate", sample.path("expectedCandidate").asBoolean());
            row.put("left", sample.path("left")); row.put("right", sample.path("right"));
            long started = System.nanoTime();
            try (Fixture fixture = new Fixture()) {
                LocalDateTime anchor = LocalDateTime.now(SEOUL).minusDays(60).withNano(0);
                long left = fixture.insert(sample.path("left"), anchor);
                long right = fixture.insert(sample.path("right"), anchor);
                SimilarityService service = new SimilarityService(fixture.db, ai, .82, 30);
                service.index(left); service.index(right);
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) service.similar(left).get("items");
                boolean actual = !candidates.isEmpty(), expected = sample.path("expectedCandidate").asBoolean();
                int stored = (fixture.report(left).get("embeddingJson") == null ? 0 : 1)
                        + (fixture.report(right).get("embeddingJson") == null ? 0 : 1);
                storedLiveVectors += stored;
                boolean liveVerified = !ai.requestedMode.equals("LIVE") || stored == 2;
                if (!liveVerified) errors++;
                else if (actual && expected) tp++; else if (actual) fp++; else if (expected) fn++; else tn++;
                row.put("actualCandidate", actual); row.put("status", !liveVerified ? "UNVERIFIED_FALLBACK" : actual == expected ? "PASS" : "FAIL");
                row.put("embeddingsStored", stored);
                row.put("score", candidates.isEmpty() ? null : candidates.getFirst().get("similarityScore"));
                row.put("candidateMode", candidates.isEmpty() ? null : candidates.getFirst().get("aiMode"));
                row.put("candidateReason", candidates.isEmpty() ? "No candidate after metadata/time/similarity filtering" : candidates.getFirst().get("reasonText"));
                row.put("incidentAssignedAutomatically", fixture.report(left).get("incidentId") != null || fixture.report(right).get("incidentId") != null);
                row.put("cause", actual == expected ? "matches frozen relevance label" : actual
                        ? "false positive: candidate passed current metadata/semantic filters" : "false negative: relevant pair did not pass current filters");
            } catch (RuntimeException error) {
                errors++; row.put("status", "ERROR"); row.put("errorCode", safeError(error));
            }
            row.put("elapsedMs", elapsed(started)); rows.add(row);
        }
        return map("status", "MEASURED", "count", rows.size(), "truePositive", tp, "falsePositive", fp, "trueNegative", tn,
                "falseNegative", fn, "errors", errors, "precision", tp + fp == 0 ? null : (double) tp / (tp + fp),
                "recall", tp + fn == 0 ? null : (double) tp / (tp + fn), "storedEmbeddingCount", storedLiveVectors,
                "threshold", .82, "windowDays", 30, "rows", rows);
    }

    private Map<String, Object> incidents(JsonNode fixtures) {
        List<Map<String, Object>> rows = new ArrayList<>(); int passCount = 0;
        for (JsonNode sample : fixtures) {
            Map<String, Object> row = baseRow(sample); row.put("expected", sample.path("expected"));
            try (Fixture f = new Fixture()) {
                Map<String, Object> actual = runScenario(sample.path("kind").asText(), f);
                List<String> failed = new ArrayList<>();
                sample.path("expected").fields().forEachRemaining(e -> { if (!same(e.getValue(), actual.get(e.getKey()))) failed.add(e.getKey()); });
                boolean pass = failed.isEmpty(); if (pass) passCount++;
                row.put("actual", actual); row.put("status", pass ? "PASS" : "FAIL"); row.put("failedChecks", failed);
            } catch (RuntimeException error) { row.put("status", "ERROR"); row.put("errorCode", safeError(error)); }
            rows.add(row);
        }
        return map("status", "MEASURED_H2_SERVICE_INVARIANTS", "count", rows.size(), "passCount", passCount, "rows", rows);
    }

    private Map<String, Object> runScenario(String kind, Fixture f) {
        if (kind.equals("MERGE_THREE_CLOSE_ALL")) {
            long a = f.wifi(null), b = f.wifi(null), c = f.wifi(null);
            f.tx(() -> f.incidents.reviewPair(f.pair(a, b), "SAME_INCIDENT", f.admin));
            f.tx(() -> f.incidents.reviewPair(f.pair(b, c), "SAME_INCIDENT", f.admin));
            f.start(a); f.start(b); f.start(c); f.resolve(a); f.resolve(b);
            String partial = f.incidents.detail(f.incidentId(a)).get("status").toString();
            f.resolve(c);
            return map("reportCount", 3, "incidentCount", f.incidentCount(), "statusAfterTwoResolved", partial,
                    "statusAfterAllResolved", f.incidents.detail(f.incidentId(a)).get("status"));
        }
        if (kind.equals("TWO_OPEN_INCIDENT_CONFLICT")) {
            long a = f.wifi(null), b = f.wifi(null); f.start(a); f.start(b); long pair = f.pair(a, b);
            String code = failureCode(() -> f.tx(() -> f.incidents.reviewPair(pair, "SAME_INCIDENT", f.admin)));
            return map("errorCode", code, "incidentCount", f.incidentCount());
        }
        if (kind.equals("DISTINCT_DECISION_PREVENTS_LINK")) {
            long a = f.wifi(null), b = f.wifi(null), pair = f.pair(a, b);
            f.tx(() -> f.incidents.reviewPair(pair, "RELATED_DISTINCT", f.admin)); f.start(a);
            String code = failureCode(() -> f.tx(() -> f.incidents.link(b, f.incidentId(a), f.admin)));
            return map("errorCode", code, "unlinkedReport", f.report(b).get("incidentId") == null,
                    "pairDecision", f.db.one("SELECT decision FROM similar_pairs WHERE id=?", pair).get("decision"));
        }
        long previous = f.pastIncident();
        LocalDate occurrence = kind.equals("UNKNOWN_TIME_RECURRENCE") ? null
                : kind.equals("SAME_DAY_RECURRENCE_REJECTED") ? Fixture.today().minusDays(3) : Fixture.today().minusDays(1);
        long report = f.wifi(occurrence); f.start(report); long current = f.incidentId(report);
        if (kind.equals("SAME_DAY_RECURRENCE_REJECTED")) {
            String code = failureCode(() -> f.tx(() -> f.incidents.recurrence(current, previous, "CONFIRMED", f.admin)));
            return map("errorCode", code, "recurrenceDecision", f.incidents.detail(current).get("recurrenceDecision"));
        }
        if (!Set.of("CONFIRMED_LATER_RECURRENCE", "UNKNOWN_TIME_RECURRENCE").contains(kind)) throw new IllegalArgumentException("Unknown frozen scenario");
        Map<String, Object> result = f.tx(() -> f.incidents.recurrence(current, previous, "CONFIRMED", f.admin));
        return map("recurrenceDecision", result.get("recurrenceDecision"), "timeBasis", result.get("timeBasis"),
                "separateIncident", current != previous, "occurredOn", f.report(report).get("occurredOn"));
    }

    private static class MeasuredAi extends AiService {
        private final String requestedMode;
        private int analyzeCalls, analyzeSuccesses, embeddingCalls, embeddingSuccesses;
        private int consecutiveFailures;
        private final Map<String, long[]> tokenUsage = new TreeMap<>();
        MeasuredAi(String mode) {
            super(mode, env("AI_BASE_URL", "https://api.openai.com/v1"), env("AI_API_KEY", ""), env("AI_MODEL", ""),
                    mode.equals("LIVE") ? "LIVE" : "RULE_ONLY", env("EMBEDDING_MODEL", ""), integerEnv("AI_TIMEOUT_SECONDS", 20),
                    integerEnv("AI_MAX_COMPLETION_TOKENS", 1536), env("AI_REASONING_EFFORT", ""));
            requestedMode = mode;
        }
        @Override public Map<String, Object> analyze(Map<String, Object> input) {
            ensureBudget(); analyzeCalls++;
            try { Map<String, Object> result = super.analyze(input); analyzeSuccesses++; consecutiveFailures = 0; return result; }
            catch (RuntimeException error) { consecutiveFailures++; throw error; }
        }
        @Override public Embedding embed(String input) {
            ensureBudget(); embeddingCalls++;
            try { Embedding value = super.embed(input); if (value != null) embeddingSuccesses++; consecutiveFailures = 0; return value; }
            catch (RuntimeException error) { consecutiveFailures++; throw error; }
        }
        private void ensureBudget() {
            if (requestedMode.equals("LIVE") && (consecutiveFailures >= 3 || analyzeCalls + embeddingCalls >= 70))
                throw new ApiException(503, "VERIFICATION_CALL_LIMIT", "Verification call budget reached");
        }
        @Override protected void onUsage(String endpoint, long inputTokens, long outputTokens, long totalTokens) {
            long[] totals = tokenUsage.computeIfAbsent(endpoint, name -> new long[4]);
            totals[0]++; totals[1] += inputTokens; totals[2] += outputTokens; totals[3] += totalTokens;
        }
        Map<String, Object> measurement() {
            Map<String, Object> usage = new TreeMap<>();
            tokenUsage.forEach((endpoint, totals) -> usage.put(endpoint, map("responsesWithUsage", totals[0], "inputTokens", totals[1], "outputTokens", totals[2], "totalTokens", totals[3])));
            return map("mode", requestedMode, "model", requestedMode.equals("LIVE") ? env("AI_MODEL", "") : null,
                    "embeddingModel", requestedMode.equals("LIVE") ? env("EMBEDDING_MODEL", "") : null,
                    "analyzeAttempts", analyzeCalls, "analyzeSuccesses", analyzeSuccesses, "embeddingAttempts", embeddingCalls,
                    "embeddingSuccesses", embeddingSuccesses, "externalCallsAttempted", requestedMode.equals("LIVE") ? analyzeCalls + embeddingCalls : 0,
                    "maximumExternalCalls", 70, "circuitOpenedAfterConsecutiveFailures", consecutiveFailures >= 3,
                    "usage", usage, "usageStatus", tokenUsage.isEmpty() ? "NO_PROVIDER_USAGE_REPORTED" : "PROVIDER_RESPONSE_TELEMETRY");
        }
    }

    private static class Fixture implements AutoCloseable {
        final Db db; final IncidentService incidents; final TransactionTemplate transaction; final long student, admin;
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:holdout_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__core.sql"), new ClassPathResource("db/migration/V2__incidents.sql"), new ClassPathResource("db/migration/V3__intake_context.sql")).execute(source);
            db = new Db(new JdbcTemplate(source)); incidents = new IncidentService(db);
            transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            student = db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)", "holdout-student", "not-a-login-hash", "합성 학생", "STUDENT");
            admin = db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)", "holdout-admin", "not-a-login-hash", "합성 관리자", "ADMIN");
        }
        long insert(JsonNode data, LocalDateTime anchor) {
            return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,floor,location_text,symptom,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    student, UUID.randomUUID().toString(), text(data, "text"), text(data, "category"), text(data, "subcategory"),
                    text(data, "building"), text(data, "floor"), text(data, "locationText"), text(data, "text"), Timestamp.valueOf(anchor.plusDays(data.path("dayOffset").asInt())));
        }
        long wifi(LocalDate date) {
            return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,floor,location_text,symptom,occurred_on) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    student, UUID.randomUUID().toString(), "새빛관 604호 무선망 재연결 반복", "IT", "WIFI", "새빛관", "6", "새빛관 604호", "무선망 재연결 반복", date);
        }
        long pair(long a, long b) { return db.insert("INSERT INTO similar_pairs(left_report_id,right_report_id,reason_text) VALUES(?,?,?)", Math.min(a, b), Math.max(a, b), "Holdout synthetic administrator scenario"); }
        Map<String, Object> report(long id) { return db.one("SELECT * FROM reports WHERE id=?", id); }
        long incidentId(long report) { return Db.id(report(report), "incidentId"); }
        long incidentCount() { return Db.id(db.one("SELECT COUNT(*) AS total FROM incidents"), "total"); }
        <T> T tx(Supplier<T> work) { return transaction.execute(status -> work.get()); }
        void start(long report) { tx(() -> { incidents.start(report, admin); db.update("UPDATE reports SET status='IN_PROGRESS' WHERE id=?", report); return null; }); }
        void resolve(long report) { tx(() -> { db.one("SELECT id FROM reports WHERE id=? FOR UPDATE", report); db.update("UPDATE reports SET status='RESOLVED',resolution_text='합성 처리 완료' WHERE id=?", report); incidents.refresh(incidentId(report)); return null; }); }
        long pastIncident() {
            long report = wifi(today().minusDays(5)); start(report); resolve(report); long incident = incidentId(report);
            db.update("UPDATE incidents SET created_at=?,closed_at=? WHERE id=?", Timestamp.valueOf(today().minusDays(5).atTime(9, 0)), Timestamp.valueOf(today().minusDays(3).atTime(15, 0)), incident);
            db.update("UPDATE reports SET created_at=? WHERE id=?", Timestamp.valueOf(today().minusDays(5).atTime(9, 0)), report);
            return incident;
        }
        static LocalDate today() { return LocalDate.now(SEOUL); }
        @Override public void close() { db.jdbc.execute("SHUTDOWN"); }
    }

    private static String markdown(Map<String, Object> result) throws Exception {
        JsonNode data = JSON.valueToTree(result);
        StringBuilder out = new StringBuilder("# Holdout v1 — ").append(data.path("requestedMode").asText()).append("\n\n")
                .append("고정 라벨 SHA-256: `").append(FROZEN_SHA256).append("`\n\n")
                .append("실행: ").append(data.path("evaluatedAt").asText()).append(". ").append(data.path("status").asText()).append("\n\n")
                .append("구현 경험이 있는 에이전트가 별도로 작성한 합성셋이며 사람의 독립 검수가 아닙니다. 결과에 맞춰 라벨이나 코드를 수정하지 않았습니다. 문장·쌍 품질 실패는 아래에 그대로 표시합니다. JUnit 성공은 측정 파일 생성 성공을 뜻합니다.\n\n");
        for (String section : List.of("sentences", "pairs", "incidents")) {
            JsonNode value = data.path(section);
            out.append("## ").append(section).append("\n\n상태: ").append(value.path("status").asText()).append("\n\n");
            if (!value.has("rows")) { out.append(value.path("reason").asText()).append("\n\n"); continue; }
            var summary = value.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) summary).remove("rows");
            out.append("```json\n").append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary)).append("\n```\n\n")
                    .append("| 사례 | 기대 근거 | 결과 | 실패 필드/원인 |\n|---|---|---|---|\n");
            for (JsonNode row : value.path("rows")) {
                String failures = row.has("errorCode") ? row.path("errorCode").asText()
                        : row.has("failedChecks") ? row.path("failedChecks").toString() : row.path("cause").asText();
                out.append("| ").append(row.path("id").asText()).append(" | ").append(row.path("reason").asText().replace("|", "/"))
                        .append(" | ").append(row.path("status").asText()).append(" | ").append(failures.replace("|", "/")).append(" |\n");
            }
            out.append("\n");
        }
        out.append("## 공급자 측정\n\n```json\n").append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result.get("providerMeasurement"))).append("\n```\n\n")
                .append("실제 입력·기대·출력·시간은 동명의 JSON에 보존했습니다. 사건 시나리오는 격리 H2 서비스 검증이며 실제 AI 추론/실제 MySQL 성능이 아닙니다. 토큰 정보는 API usage 텔레메트리이며 실제 결제 명세가 아닙니다.\n");
        return out.toString();
    }
    private static Map<String, Object> baseRow(JsonNode sample) { return map("id", sample.path("id").asText(), "reason", sample.path("reason").asText()); }
    private static Map<String, Object> skipped(String reason) { return map("status", "SKIPPED", "reason", reason, "externalCalls", 0); }
    private static String text(JsonNode n, String key) { return n.path(key).isNull() || n.path(key).isMissingNode() ? null : n.path(key).asText(); }
    private static boolean same(JsonNode expected, Object actual) {
        if (expected.isNull()) return actual == null;
        if (expected.isNumber() && actual instanceof Number n) return expected.decimalValue().compareTo(new java.math.BigDecimal(n.toString())) == 0;
        return Objects.equals(JSON.convertValue(expected, Object.class), actual);
    }
    private static boolean nonblank(Object value) { return value instanceof String text && !text.isBlank(); }
    private static String safeError(RuntimeException error) { return error instanceof ApiException api ? api.code : error.getClass().getSimpleName(); }
    private static String failureCode(Runnable work) { try { work.run(); return "NO_ERROR"; } catch (ApiException error) { return error.code; } }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    private static int integerEnv(String key, int fallback) { String value = env(key, ""); return value.isBlank() ? fallback : Integer.parseInt(value); }
    private static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
}
