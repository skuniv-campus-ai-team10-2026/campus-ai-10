package edu.skope.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import edu.skope.service.SimilarityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in only: frozen new synthetic inputs, capped provider use, isolated MySQL. */
@EnabledIfEnvironmentVariable(named = "SKOPE_RUN_FRESH", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@Import(FreshVerificationTest.Configuration.class)
class FreshVerificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIRECTORY = Path.of("..", "docs", "evaluation");
    private static final Path FIXTURE = DIRECTORY.resolve("fresh-v1.json");
    private static final Path CACHE = Path.of("..", ".runtime", "ai-quality-cache", "fresh-v1");
    private static final Path OUTPUT = DIRECTORY.resolve("fresh-v1-results.json");
    private static final Path ANALYSIS_SOURCE = Path.of("src/main/java/edu/skope/ai/AiService.java");
    private static final Path SIMILARITY_SOURCE = Path.of("src/main/java/edu/skope/service/SimilarityService.java");
    private static final String PASSWORD = env("FRESH_PASSWORD", UUID.randomUUID().toString());
    private static final String SCHEMA = "skope_fresh_verification_20261014";
    private static final String RESOLUTION = "[합성 신규 검증] 무선망 연결 점검과 재접속 확인 완료";
    @Autowired Db db;
    @Autowired Environment environment;
    @Autowired CachedAi ai;
    @Autowired SimilarityService similarity;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Map<String, Object>> sentenceRows = new ArrayList<>(), pairRows = new ArrayList<>(), checks = new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        String url = env("FRESH_DB_URL", "");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/" + SCHEMA + "(?:\\?[^#]*)?"))
            throw new IllegalStateException("FRESH_DB_URL must target the explicitly isolated loopback schema");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> env("FRESH_DB_USERNAME", ""));
        properties.add("spring.datasource.password", () -> env("FRESH_DB_PASSWORD", ""));
        properties.add("DEMO_PASSWORD", () -> PASSWORD);
        properties.add("server.servlet.session.cookie.secure", () -> false);
        properties.add("AI_MODE", () -> "LIVE");
        properties.add("EMBEDDING_MODE", () -> "LIVE");
    }

    @TestConfiguration static class Configuration {
        @Bean @Primary CachedAi freshCachedAi() throws Exception { return new CachedAi(); }
    }

    @Test void verifyFrozenFreshInputsAndFinalHttpMysqlLinkage() throws Exception {
        Files.createDirectories(CACHE);
        try (var channel = FileChannel.open(CACHE.resolve("evaluation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            assertNotNull(lock, "Another fresh verification is running");
            Throwable technicalFailure = null;
            boolean measurementStarted = false;
            try {
                ai.authorize();
                JsonNode fixture = JSON.readTree(Files.readAllBytes(FIXTURE));
                assertEquals(15, fixture.path("sentences").size());
                assertEquals(10, fixture.path("pairs").size());
                assertEquals(SCHEMA, db.one("SELECT DATABASE() schema_name").get("schemaName"));
                assertEquals(0, countReports(), "Fresh schema must contain no reports; this runner never deletes existing reports");
                // Only this guarded, dedicated schema receives random credentials for isolated HTTP sessions.
                db.update("UPDATE users SET password_hash=?,active=TRUE", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD));
                evidence.putAll(map("fixtureId", fixture.path("id"), "fixtureSha256", ai.fixtureHash,
                        "startedAt", now(), "requestedMode", ai.requestedMode, "databaseSchema", SCHEMA,
                        "analysisSourceSha256", ai.analysisHash, "similaritySourceSha256", ai.similarityHash,
                        "labelLimitation", "New synthetic labels fixed before calls by Codex; not independently reviewed by a human. No threshold tuning or production changes.",
                        "scope", "Real authenticated HTTP and isolated MySQL. Pair policy uses production SimilarityService with saved real embeddings in isolated H2. No browser/UI assertion.",
                        "maximumProviderAttempts", 35, "maximumAnalysisAttempts", 15, "maximumEmbeddingAttempts", 20,
                        "sentences", sentenceRows, "pairs", pairRows, "integrationAndExceptions", checks));
                measurementStarted = true;
                checkpoint();
                var student = new Session().login("student1");
                var admin = new Session().login("admin");
                var meta = student.ok("GET", "/meta", null, 200);
                check("finalConfiguration", meta.path("aiMode").asText().equals("LIVE") && meta.path("embeddingMode").asText().equals("LIVE")
                        && meta.path("databaseMode").asText().equals("MYSQL"), "HTTP", meta);

                // Send these first so fresh provider results and vectors traverse the final HTTP/MySQL path.
                List<Long> reportIds = new ArrayList<>();
                JsonNode firstPair = fixture.path("pairs").get(0);
                for (int i = 0; i < 2; i++) {
                    var row = sentence(fixture.path("sentences").get(i), student);
                    if (row.get("actual") instanceof JsonNode actual && actual.path("report").isObject()) {
                        JsonNode expectedInput = firstPair.path(i == 0 ? "left" : "right");
                        var payload = confirmedReport(expectedInput, nullable(actual.at("/report/floor")));
                        var submitted = student.call("POST", "/reports", payload, true);
                        if (submitted.status == 201) {
                            long id = submitted.body.path("id").asLong(); reportIds.add(id);
                            var stored = db.one("SELECT * FROM reports WHERE id=?", id);
                            check("report" + (i + 1) + "SavedWithActualFloor", Objects.equals(stored.get("floor"), nullable(actual.at("/report/floor")))
                                            && stored.get("embeddingJson") != null,
                                    ai.lastEmbeddingSource, map("id", id, "actualAnalysis", actual, "confirmedPayload", payload,
                                            "db", safeReport(stored), "note", "Fixed fixture location/type/symptom are explicit user confirmation; floor is unmodified actual postprocessed AI output."));
                        } else check("report" + (i + 1) + "SavedWithActualFloor", false, "HTTP", map("httpStatus", submitted.status, "errorCode", submitted.body.path("code")));
                    } else check("report" + (i + 1) + "SavedWithActualFloor", false, "UNVERIFIED", map("reason", "Actual analysis unavailable; no synthetic replacement submitted"));
                    checkpoint();
                }
                if (reportIds.size() == 2) linkage(reportIds, student, admin);
                else check("liveSimilarityAndAdminLinkage", false, "UNVERIFIED", map("reason", "Two actual analyses/reports were not available"));
                for (int i = 2; i < fixture.path("sentences").size(); i++) sentence(fixture.path("sentences").get(i), student);
                for (JsonNode pair : fixture.path("pairs")) pair(pair);
                faults(student, admin, firstPair);
                checks.add(map("name", "existingDuplicateConcurrencyAndTimeoutCoverage", "status", "SKIPPED_ALREADY_COVERED",
                        "source", "Existing MySQL ApiIntegrationTest duplicate/concurrent tests and AiServiceTest controlled upstream timeout tests; no redundant paid calls."));
                ai.verifyPins();
                evidence.put("fixtureAndProductionSourcesUnchanged", true);
                evidence.put("status", "MEASUREMENT_COMPLETED");
            } catch (Exception | AssertionError failure) {
                technicalFailure = failure; evidence.put("status", "TECHNICAL_FAILURE"); evidence.put("technicalError", safe(failure));
            } finally {
                ai.faultAnalysis = null; ai.faultEmbedding = null;
                // A one-shot rerun against nonempty MySQL must not overwrite previous evidence.
                if (measurementStarted) {
                    evidence.put("finishedAt", now());
                    evidence.put("sentenceMetrics", sentenceMetrics());
                    evidence.put("pairMetrics", pairMetrics());
                    evidence.put("providerMeasurement", ai.measurement());
                    checkpoint();
                }
            }
            if (technicalFailure != null) throw new IllegalStateException("Fresh verification technical failure; sanitized checkpoint saved", technicalFailure);
        }
    }

    private Map<String, Object> sentence(JsonNode sample, Session session) throws Exception {
        var row = map("id", sample.path("id"), "input", sample.path("input"), "expected", sample.path("expected"));
        sentenceRows.add(row); long start = System.nanoTime(); ai.lastAnalysis = null;
        try {
            var response = session.call("POST", "/assistant/analyze", sample.path("input"), true);
            row.put("httpStatus", response.status); row.put("httpActual", response.body);
            if (response.status != 200 || ai.lastAnalysis == null) {
                row.put("status", "UNVERIFIED"); row.put("errorCode", response.body.path("code"));
            } else {
                JsonNode actual = ai.lastAnalysis.deepCopy(), expected = sample.path("expected");
                var fields = new LinkedHashMap<String, Boolean>();
                fields.put("intent", Objects.equals(nullable(expected.path("intent")), nullable(actual.path("intent"))));
                for (String field : List.of("category", "subcategory", "building", "floor"))
                    fields.put(field, Objects.equals(nullable(expected.path(field)), nullable(actual.path("report").path(field))));
                fields.put("emergencyHint", expected.path("emergencyHint").asBoolean() == actual.at("/report/emergencyHint").asBoolean());
                fields.put("symptomPresence", !expected.path("symptomRequired").asBoolean() || nonblank(actual.at("/report/symptom")));
                fields.put("locationPresence", !expected.path("locationRequired").asBoolean() || nonblank(actual.at("/report/locationText")));
                fields.put("liveMode", "LIVE".equals(response.body.path("aiMode").asText()));
                row.put("actual", actual); row.put("source", ai.lastAnalysisSource); row.put("checks", fields);
                row.put("failedChecks", fields.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
                row.put("status", fields.values().stream().allMatch(Boolean::booleanValue) ? "PASS" : "FAIL");
                row.put("note", "Actual is AiService result after production normalization; HTTP controller intentionally hides report fields for QUESTION. No additional normalization by evaluator.");
            }
        } catch (Exception failure) { row.put("status", "UNVERIFIED"); row.put("errorCode", safe(failure)); }
        row.put("elapsedMs", (System.nanoTime() - start) / 1_000_000); checkpoint(); return row;
    }

    private void linkage(List<Long> ids, Session student, Session admin) throws Exception {
        long left = ids.get(0), right = ids.get(1);
        var a = db.one("SELECT * FROM reports WHERE id=?", left); var b = db.one("SELECT * FROM reports WHERE id=?", right);
        check("noAutomaticIncidentBeforeReview", a.get("incidentId") == null && b.get("incidentId") == null, "MYSQL", map("reportIds", ids));
        JsonNode candidates = admin.ok("GET", "/admin/reports/" + left + "/similar", null, 200);
        JsonNode candidate = null;
        for (JsonNode item : candidates.path("items")) if (item.path("relatedReportId").asLong() == right) candidate = item;
        boolean found = candidate != null && candidate.path("aiMode").asText().equals("LIVE");
        var diagnostic = SimilarityService.diagnose(a, b, .82, 30);
        check("liveCandidatePersisted", found, "LIVE_MYSQL", map("candidates", candidates,
                "diagnostic", diagnostic));
        boolean support = "CONNECTIVITY_SYMPTOM_SUPPORT".equals(diagnostic.get("decisionBasis"));
        checks.add(map("name", "conditionalSupportBranchInMysql", "status", found && support ? "PASS" : "UNVERIFIED_REAL_MYSQL",
                "source", "LIVE_MYSQL", "detail", map("diagnostic", diagnostic, "note", "No invented score; base-threshold candidate does not verify conditional branch.")));
        Long pairId = candidate == null ? null : candidate.path("pairId").asLong();
        if (pairId != null) {
            var forbidden = student.call("PATCH", "/admin/similar-pairs/" + pairId + "/review", map("decision", "SAME_INCIDENT"), true);
            var stillCandidate = db.one("SELECT decision FROM similar_pairs WHERE id=?", pairId);
            check("studentCannotBypassAdministratorReview", forbidden.status == 403 && "CANDIDATE".equals(stillCandidate.get("decision"))
                            && db.one("SELECT incident_id FROM reports WHERE id=?", left).get("incidentId") == null,
                    "HTTP_MYSQL", map("httpStatus", forbidden.status, "decisionAfterAttempt", stillCandidate.get("decision")));
            var review = admin.call("PATCH", "/admin/similar-pairs/" + pairId + "/review", map("decision", "SAME_INCIDENT"), true);
            a = db.one("SELECT * FROM reports WHERE id=?", left); b = db.one("SELECT * FROM reports WHERE id=?", right);
            check("administratorSameIncidentDecision", review.status == 200 && a.get("incidentId") != null && Objects.equals(a.get("incidentId"), b.get("incidentId")),
                    "HTTP_MYSQL", map("httpStatus", review.status, "result", review.body));
        }
        for (long id : ids) {
            var progress = admin.call("PATCH", "/admin/reports/" + id + "/status", map("status", "IN_PROGRESS"), true);
            var done = admin.call("PATCH", "/admin/reports/" + id + "/status", map("status", "RESOLVED", "resolutionText", RESOLUTION), true);
            var result = student.call("GET", "/reports/" + id, null, false);
            check("studentResult" + id, progress.status == 200 && done.status == 200 && result.status == 200
                            && result.body.path("status").asText().equals("RESOLVED") && result.body.path("resolutionText").asText().equals(RESOLUTION),
                    "HTTP_MYSQL", map("progressHttpStatus", progress.status, "resolvedHttpStatus", done.status,
                            "studentResult", result.body, "db", safeReport(db.one("SELECT * FROM reports WHERE id=?", id))));
        }
        int calls = ai.totalNewCalls();
        var reviewedBefore = pairId == null ? null : db.one("SELECT * FROM similar_pairs WHERE id=?", pairId);
        similarity.index(left); similarity.index(right);
        var reviewedAfter = pairId == null ? null : db.one("SELECT * FROM similar_pairs WHERE id=?", pairId);
        check("storedVectorReindexWithoutNewCalls", ai.totalNewCalls() == calls, "MYSQL_STORED_LIVE_EMBEDDINGS", map("callsBefore", calls, "callsAfter", ai.totalNewCalls()));
        if (pairId != null) check("reindexPreservesAdminDecision", Objects.equals(reviewedBefore, reviewedAfter)
                        && "SAME_INCIDENT".equals(reviewedAfter.get("decision")), "MYSQL", map("before", reviewedBefore, "after", reviewedAfter));
        checkpoint();
    }

    private void pair(JsonNode sample) throws Exception {
        var row = map("id", sample.path("id"), "expectedCandidate", sample.path("expectedCandidate"), "left", sample.path("left"), "right", sample.path("right"));
        pairRows.add(row);
        try (var fixture = new PairFixture()) {
            long left = fixture.insert(sample.path("left")), right = fixture.insert(sample.path("right"));
            var service = new SimilarityService(fixture.db, ai, .82, 30);
            service.index(left); String leftSource = ai.lastEmbeddingSource;
            service.index(right); String rightSource = ai.lastEmbeddingSource;
            var a = fixture.db.one("SELECT * FROM reports WHERE id=?", left); var b = fixture.db.one("SELECT * FROM reports WHERE id=?", right);
            var diagnostic = SimilarityService.diagnose(a, b, .82, 30);
            var candidates = JSON.valueToTree(service.similar(left));
            boolean available = a.get("embeddingJson") != null && b.get("embeddingJson") != null;
            boolean actual = !candidates.path("items").isEmpty(), expected = sample.path("expectedCandidate").asBoolean();
            row.putAll(map("diagnostic", diagnostic, "actualCandidate", actual, "embeddingsAvailable", available,
                    "source", map("left", leftSource, "right", rightSource), "persistedCandidates", candidates,
                    "incidentAssignedAutomatically", a.get("incidentId") != null || b.get("incidentId") != null,
                    "status", !available ? "UNVERIFIED" : actual == expected ? "PASS" : "FAIL"));
            if (available && !Objects.equals(diagnostic.get("candidate"), actual)) row.put("diagnosticMismatch", true);
        } catch (Exception failure) { row.put("status", "UNVERIFIED"); row.put("errorCode", safe(failure)); }
        checkpoint();
    }

    private void faults(Session student, Session admin, JsonNode firstPair) throws Exception {
        long before = countReports(); int calls = ai.totalNewCalls();
        for (int delay : new int[]{0, 2000}) {
            try (var fault = new LocalFailure(delay == 0 ? 429 : 200, delay)) {
                ai.faultAnalysis = fault.delegate;
                long started = System.nanoTime();
                var failed = student.call("POST", "/assistant/analyze", map("text", "가람관 와이파이 분석 오류 처리 검증", "preferredIntent", "AUTO"), true);
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                check(delay == 0 ? "upstream429MapsToHttp503WithoutWrite" : "upstreamTimeoutMapsToHttp503WithoutWrite",
                        failed.status == 503 && failed.body.path("code").asText().equals("AI_UNAVAILABLE") && before == countReports()
                                && calls == ai.totalNewCalls() && fault.requests.get() == 1 && (delay == 0 || elapsed < 1900),
                        "CONTROLLED_LOCAL_HTTP_PROVIDER_NO_PAID_CALL", map("httpStatus", failed.status, "body", failed.body,
                                "reportsBefore", before, "reportsAfter", countReports(), "elapsedMs", elapsed,
                                "localUpstreamRequests", fault.requests.get(), "configuredClientTimeoutSeconds", 1, "upstreamDelayMs", delay));
            } finally { ai.faultAnalysis = null; }
        }
        try (var fault = new LocalFailure(503, 0)) {
            ai.faultEmbedding = fault.delegate;
            var payload = confirmedReport(firstPair.path("left"), nullable(firstPair.at("/left/floor")));
            var response = student.call("POST", "/reports", payload, true);
            Map<String, Object> stored = response.status == 201 ? db.one("SELECT * FROM reports WHERE id=?", response.body.path("id").asLong()) : null;
            JsonNode candidates = stored == null ? JSON.createObjectNode() : admin.ok("GET", "/admin/reports/" + Db.id(stored, "id") + "/similar", null, 200);
            boolean rulesOnly = candidates.path("items").isArray() && !candidates.path("items").isEmpty();
            for (JsonNode candidate : candidates.path("items")) rulesOnly &= candidate.path("similarityScore").isNull() && "RULE_ONLY".equals(candidate.path("aiMode").asText());
            check("embeddingFailurePreservesAcceptedReport", response.status == 201 && countReports() == before + 1 && stored != null
                            && stored.get("embeddingJson") == null && stored.get("incidentId") == null && calls == ai.totalNewCalls() && fault.requests.get() == 1,
                    "CONTROLLED_LOCAL_HTTP_PROVIDER_MYSQL", map("httpStatus", response.status, "db", stored == null ? null : safeReport(stored), "candidates", candidates, "localUpstreamRequests", fault.requests.get()));
            check("embeddingFailureExplicitRuleOnlyCandidate", rulesOnly, "CONTROLLED_LOCAL_HTTP_PROVIDER_MYSQL", map("candidates", candidates));
        } finally { ai.faultEmbedding = null; }
        checkpoint();
    }

    private Map<String, Object> sentenceMetrics() {
        var fieldCorrect = new LinkedHashMap<String, Integer>(); int correct = 0, unavailable = 0;
        for (var row : sentenceRows) {
            if ("PASS".equals(row.get("status"))) correct++;
            if ("UNVERIFIED".equals(row.get("status"))) unavailable++;
            if (row.get("checks") instanceof Map<?, ?> fields) fields.forEach((name, pass) -> { if (Boolean.TRUE.equals(pass)) fieldCorrect.merge(name.toString(), 1, Integer::sum); });
        }
        return map("count", sentenceRows.size(), "allFieldsCorrect", correct, "unverified", unavailable, "accuracy", sentenceRows.isEmpty() ? null : (double) correct / sentenceRows.size(), "fieldCorrect", fieldCorrect);
    }

    private Map<String, Object> pairMetrics() {
        int tp = 0, fp = 0, tn = 0, fn = 0, unverified = 0;
        for (var row : pairRows) {
            if ("UNVERIFIED".equals(row.get("status"))) { unverified++; continue; }
            boolean expected = ((JsonNode) row.get("expectedCandidate")).asBoolean(), actual = Boolean.TRUE.equals(row.get("actualCandidate"));
            if (actual && expected) tp++; else if (actual) fp++; else if (expected) fn++; else tn++;
        }
        return map("count", pairRows.size(), "tp", tp, "fp", fp, "tn", tn, "fn", fn, "unverified", unverified,
                "precision", tp + fp == 0 ? null : (double) tp / (tp + fp), "recall", tp + fn == 0 ? null : (double) tp / (tp + fn),
                "baseThreshold", .82, "connectivitySupportThreshold", .70, "windowDays", 30);
    }

    private void check(String name, boolean pass, String source, Object detail) {
        checks.add(map("name", name, "status", pass ? "PASS" : "FAIL", "source", source, "detail", detail));
    }
    private long countReports() { return Db.id(db.one("SELECT COUNT(*) total FROM reports"), "total"); }
    private void checkpoint() throws Exception {
        evidence.put("providerMeasurement", ai.measurement());
        Path temporary = OUTPUT.resolveSibling(OUTPUT.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), evidence);
        Files.move(temporary, OUTPUT, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static Map<String, Object> confirmedReport(JsonNode input, String floor) {
        return map("clientRequestId", UUID.randomUUID().toString(), "originalText", nullable(input.path("text")),
                "category", nullable(input.path("category")), "subcategory", nullable(input.path("subcategory")),
                "building", nullable(input.path("building")), "floor", floor, "locationText", nullable(input.path("locationText")), "symptom", nullable(input.path("symptom")));
    }
    private static Map<String, Object> safeReport(Map<String, Object> row) throws Exception {
        return map("id", row.get("id"), "status", row.get("status"), "floor", row.get("floor"), "incidentId", row.get("incidentId"),
                "resolutionText", row.get("resolutionText"), "embeddingModel", row.get("embeddingModel"),
                "embeddingDimensions", row.get("embeddingJson") == null ? 0 : JSON.readTree(row.get("embeddingJson").toString()).size());
    }

    private record Response(int status, JsonNode body) { }
    private final class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String token, header;
        Response call(String method, String path, Object body, boolean withCsrf) throws Exception {
            if (withCsrf && token == null) csrf();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + "/api" + path))
                    .timeout(Duration.ofSeconds(150)).header("Content-Type", "application/json");
            if (withCsrf) request.header(header, token);
            var result = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            return new Response(result.statusCode(), JSON.readTree(result.body()));
        }
        void csrf() throws Exception { var result = ok("GET", "/auth/csrf", null, 200); token = result.path("token").asText(); header = result.path("headerName").asText(); }
        Session login(String username) throws Exception { csrf(); ok("POST", "/auth/login", map("username", username, "password", PASSWORD), 200); token = null; csrf(); return this; }
        JsonNode ok(String method, String path, Object body, int expected) throws Exception {
            var result = call(method, path, body, !method.equals("GET"));
            if (expected != result.status) throw new IllegalStateException("LOCAL_HTTP_" + result.status + "_EXPECTED_" + expected);
            return result.body;
        }
    }

    private static final class PairFixture implements AutoCloseable {
        final Db db; final long student;
        PairFixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:fresh_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__core.sql"), new ClassPathResource("db/migration/V2__incidents.sql"), new ClassPathResource("db/migration/V3__intake_context.sql")).execute(source);
            db = new Db(new JdbcTemplate(source));
            student = db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,?)", "fresh-student", "not-a-login-hash", "합성 학생", "STUDENT");
        }
        long insert(JsonNode input) {
            return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,building,floor,location_text,symptom,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    student, UUID.randomUUID().toString(), nullable(input.path("text")), nullable(input.path("category")), nullable(input.path("subcategory")), nullable(input.path("building")),
                    nullable(input.path("floor")), nullable(input.path("locationText")), nullable(input.path("symptom")),
                    Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 9, 0).plusDays(input.path("dayOffset").asInt())));
        }
        @Override public void close() { db.jdbc.execute("SHUTDOWN"); }
    }

    /** Actual production HTTP timeout/error handling against a loopback stub, never the paid provider. */
    private static final class LocalFailure implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger requests = new AtomicInteger();
        final AiService delegate;
        LocalFailure(int status, int delayMs) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1", exchange -> {
                requests.incrementAndGet();
                try {
                    exchange.getRequestBody().readAllBytes();
                    if (delayMs > 0) Thread.sleep(delayMs);
                    byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            delegate = new AiService("LIVE", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "local-stub-not-a-secret",
                    "local-stub", "LIVE", "local-stub", 1);
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }

    static final class CachedAi extends AiService {
        final AiQualityCache cache;
        final String requestedMode = env("FRESH_MODE", "OFFLINE"), analysisModel = env("AI_MODEL", "gpt-6-luna"), vectorModel = env("EMBEDDING_MODEL", "text-embedding-3-small");
        String fixtureHash, analysisHash, similarityHash;
        volatile AiService faultAnalysis, faultEmbedding;
        volatile JsonNode lastAnalysis;
        volatile String lastAnalysisSource = "NONE", lastEmbeddingSource = "NONE";
        boolean authorized, circuitOpen, persistenceFailed;
        int analysisCalls, embeddingCalls, analysisHits, embeddingHits, failures;
        Map<String, Long> currentUsage = Map.of();
        final Map<String, long[]> usage = new TreeMap<>();
        CachedAi() throws Exception {
            super("LIVE", "https://api.openai.com/v1", env("AI_API_KEY", ""), env("AI_MODEL", "gpt-6-luna"), "LIVE",
                    env("EMBEDDING_MODEL", "text-embedding-3-small"), integer("AI_TIMEOUT_SECONDS", 30), integer("AI_MAX_COMPLETION_TOKENS", 1536), env("AI_REASONING_EFFORT", "none"));
            cache = new AiQualityCache(CACHE);
        }
        synchronized void authorize() throws Exception {
            if (!Set.of("LIVE", "OFFLINE").contains(requestedMode)) throw new IllegalStateException("INVALID_FRESH_MODE");
            String pinned = env("FRESH_FIXTURE_SHA256", ""); fixtureHash = hash(FIXTURE);
            if (!pinned.matches("[a-f0-9]{64}") || !pinned.equals(fixtureHash)) throw new IllegalStateException("FROZEN_FIXTURE_HASH_REQUIRED");
            analysisHash = hash(ANALYSIS_SOURCE); similarityHash = hash(SIMILARITY_SOURCE);
            if (!env("AI_BASE_URL", "https://api.openai.com/v1").replaceAll("/$", "").equals("https://api.openai.com/v1")) throw new IllegalStateException("OFFICIAL_PROVIDER_REQUIRED");
            circuitOpen = entries().stream().anyMatch(entry -> !entry.path("status").asText().equals("SUCCESS"));
            authorized = true;
        }
        synchronized void verifyPins() throws Exception {
            if (!authorized || !Objects.equals(fixtureHash, hash(FIXTURE)) || !Objects.equals(analysisHash, hash(ANALYSIS_SOURCE)) || !Objects.equals(similarityHash, hash(SIMILARITY_SOURCE)))
                throw new IllegalStateException("FIXTURE_OR_PRODUCTION_SOURCE_CHANGED");
        }
        @Override public synchronized Map<String, Object> analyze(Map<String, Object> input) {
            if (faultAnalysis != null) { lastAnalysisSource = "CONTROLLED_LOCAL_HTTP_PROVIDER"; return faultAnalysis.analyze(input); }
            String revision = analysisHash + "|" + fixtureHash + "|reasoning=" + env("AI_REASONING_EFFORT", "none") + "|maxCompletionTokens=" + integer("AI_MAX_COMPLETION_TOKENS", 1536);
            JsonNode value = get("analysis", analysisModel, revision, input, () -> super.analyze(input));
            lastAnalysis = value.deepCopy(); return JSON.convertValue(value, Map.class);
        }
        @Override public synchronized Embedding embed(String input) {
            if (faultEmbedding != null) { lastEmbeddingSource = "CONTROLLED_LOCAL_HTTP_PROVIDER"; return faultEmbedding.embed(input); }
            JsonNode value = get("embedding", vectorModel, "embedding-input-v1", input, () -> { var result = super.embed(input); return map("vector", result.vector(), "model", result.model()); });
            return new Embedding(JSON.convertValue(value.path("vector"), double[].class), value.path("model").asText());
        }
        private JsonNode get(String endpoint, String model, String revision, Object input, Supplier<Object> work) {
            try {
                verifyPins();
                if (endpoint.equals("analysis")) lastAnalysisSource = "UNVERIFIED"; else lastEmbeddingSource = "UNVERIFIED";
                String key = AiQualityCache.key(endpoint, model, revision, input); var entry = cache.read(key);
                if (entry != null) {
                    if (!entry.path("status").asText().equals("SUCCESS")) { circuitOpen = true; throw new ApiException(503, "CACHED_FAILED_OR_INTERRUPTED_ATTEMPT", "No failed or interrupted paid call is retried"); }
                    if (endpoint.equals("analysis")) { analysisHits++; lastAnalysisSource = "CACHED_LIVE_PROVIDER_RESULT"; }
                    else { embeddingHits++; lastEmbeddingSource = "CACHED_LIVE_PROVIDER_RESULT"; }
                    return entry.path("payload");
                }
                if (!requestedMode.equals("LIVE")) throw new ApiException(503, "OFFLINE_CACHE_MISS", "No network fallback");
                var all = entries(); long endpointCount = all.stream().filter(e -> e.path("endpoint").asText().equals(endpoint)).count();
                if (circuitOpen || persistenceFailed || all.size() >= 35 || endpointCount >= (endpoint.equals("analysis") ? 15 : 20)) throw new ApiException(503, "FRESH_CALL_LIMIT", "Persisted budget reached or circuit open");
                if (env("AI_API_KEY", "").isBlank()) throw new ApiException(503, "AI_CONFIGURATION_MISSING", "Provider key not configured");
                cache.begin(key, endpoint, model, revision); currentUsage = Map.of();
                if (endpoint.equals("analysis")) analysisCalls++; else embeddingCalls++;
                try {
                    Object payload = work.get(); cache.complete(key, payload, currentUsage);
                    if (endpoint.equals("analysis")) lastAnalysisSource = "NEW_LIVE_PROVIDER_RESULT"; else lastEmbeddingSource = "NEW_LIVE_PROVIDER_RESULT";
                    return JSON.valueToTree(payload);
                } catch (RuntimeException failure) { failures++; circuitOpen = true; cache.fail(key, safe(failure), currentUsage); throw failure; }
            } catch (ApiException failure) { throw failure; }
            catch (Exception failure) { persistenceFailed = true; circuitOpen = true; throw new IllegalStateException("FRESH_CACHE_OR_PIN_FAILURE"); }
        }
        @Override protected synchronized void onUsage(String endpoint, long input, long output, long total) {
            currentUsage = Map.of("inputTokens", input, "outputTokens", output, "totalTokens", total);
            var counter = usage.computeIfAbsent(endpoint, ignored -> new long[4]); counter[0]++; counter[1] += input; counter[2] += output; counter[3] += total;
        }
        synchronized int totalNewCalls() { return analysisCalls + embeddingCalls; }
        synchronized Map<String, Object> measurement() throws Exception {
            var summaries = new TreeMap<String, Object>(); usage.forEach((endpoint, n) -> summaries.put(endpoint, map("responsesWithUsage", n[0], "inputTokens", n[1], "outputTokens", n[2], "totalTokens", n[3])));
            var persisted = entries(); var persistedUsage = new TreeMap<String, long[]>();
            for (var entry : persisted) { var counter = persistedUsage.computeIfAbsent(entry.path("endpoint").asText(), ignored -> new long[3]); counter[0] += entry.at("/usage/inputTokens").asLong(); counter[1] += entry.at("/usage/outputTokens").asLong(); counter[2] += entry.at("/usage/totalTokens").asLong(); }
            var totalUsage = new TreeMap<String, Object>(); persistedUsage.forEach((endpoint, n) -> totalUsage.put(endpoint, map("inputTokens", n[0], "outputTokens", n[1], "totalTokens", n[2])));
            return map("mode", requestedMode, "model", analysisModel, "embeddingModel", vectorModel,
                    "analysisAttempts", analysisCalls, "embeddingAttempts", embeddingCalls, "externalCallsAttempted", totalNewCalls(),
                    "analysisCacheHits", analysisHits, "embeddingCacheHits", embeddingHits, "newCallFailures", failures,
                    "maximumCumulativeExternalCalls", 35, "maximumAnalysisCalls", 15, "maximumEmbeddingCalls", 20,
                    "persistedAttemptsIncludingPreviousRuns", persisted.size(), "newCallsUsage", summaries, "persistedUsage", totalUsage,
                    "circuitOpen", circuitOpen, "automaticRetries", 0, "cacheDirectory", ".runtime/ai-quality-cache/fresh-v1",
                    "usageNote", "Token counts from actual provider response usage only; cached reuse and controlled faults make no paid calls. No invoice or currency estimate.");
        }
        private List<JsonNode> entries() throws Exception {
            var result = new ArrayList<JsonNode>();
            try (var paths = Files.list(CACHE)) { for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) result.add(JSON.readTree(Files.readAllBytes(path))); }
            return result;
        }
    }

    private static String hash(Path path) throws Exception { return AiQualityCache.digest(Files.readAllBytes(path)); }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    private static int integer(String key, int fallback) { return Integer.parseInt(env(key, Integer.toString(fallback))); }
    private static String nullable(JsonNode node) { return node.isMissingNode() || node.isNull() ? null : node.asText(); }
    private static boolean nonblank(JsonNode node) { return node.isTextual() && !node.asText().isBlank(); }
    private static String safe(Throwable failure) { return failure instanceof ApiException api ? api.code : failure.getClass().getSimpleName(); }
    private static String now() { return OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(); }
    private static Map<String, Object> map(Object... pairs) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
}
