package edu.skope.ai;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The budget in the running application, against a local fake OpenAI server (loopback HTTP).
 * AI_MODE=LIVE so the real request path is used, but no request ever leaves this machine: 0 paid calls.
 * "Restart" below is a second AiBudget object over the same database; the class keeps no counters in memory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiBudgetIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = UUID.randomUUID().toString();
    static final AtomicInteger LLM_HITS = new AtomicInteger(), EMBEDDING_HITS = new AtomicInteger();
    static final AtomicBoolean PROVIDER_FAILS = new AtomicBoolean();
    static final HttpServer STUB;

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/v1/chat/completions", ex -> {
                LLM_HITS.incrementAndGet();
                ex.getRequestBody().readAllBytes();
                if (PROVIDER_FAILS.get()) { ex.sendResponseHeaders(500, -1); ex.close(); return; }
                var report = new LinkedHashMap<String, Object>();
                report.put("category", "FACILITY"); report.put("subcategory", "HVAC");
                report.put("locationText", "북악관 502호"); report.put("building", "북악관"); report.put("floor", "5");
                report.put("symptom", "에어컨이 작동하지 않음"); report.put("occurredAtText", null); report.put("followUpQuestion", null);
                report.put("missingFields", List.of()); report.put("emergencyHint", false);
                String content = JSON.writeValueAsString(Map.of("intent", "REPORT", "report", report));
                byte[] body = JSON.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", content))),
                    "usage", Map.of("prompt_tokens", 1000, "completion_tokens", 200, "total_tokens", 1200)));
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
            });
            STUB.createContext("/v1/embeddings", ex -> {
                EMBEDDING_HITS.incrementAndGet();
                ex.getRequestBody().readAllBytes();
                byte[] body = JSON.writeValueAsBytes(Map.of(
                    "data", List.of(Map.of("index", 0, "embedding", List.of(0.1, 0.2, 0.3))), "model", "stub-embedding",
                    "usage", Map.of("prompt_tokens", 50, "total_tokens", 50)));
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
            });
            STUB.setExecutor(Executors.newFixedThreadPool(8));
            STUB.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:ai_budget;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> PASSWORD);
        p.add("AI_MODE", () -> "LIVE"); p.add("EMBEDDING_MODE", () -> "LIVE");
        p.add("AI_BASE_URL", () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/v1");
        p.add("AI_API_KEY", () -> "local-stub-not-a-real-key");
        p.add("AI_MODEL", () -> "stub-model"); p.add("EMBEDDING_MODEL", () -> "stub-embedding");
        p.add("AI_BUDGET_PERIOD", () -> "TOTAL");
        p.add("AI_BUDGET_MAX_LLM_CALLS", () -> "3"); p.add("AI_BUDGET_MAX_EMBEDDING_CALLS", () -> "2");
        p.add("AI_BUDGET_MAX_USD", () -> "1.00");
    }

    @AfterAll static void stopStub() { STUB.stop(0); }

    @Autowired AiService ai; @Autowired AiBudget budget; @Autowired Db db; @Autowired Environment env;

    @BeforeEach void reset() {
        db.update("DELETE FROM ai_usage_budget");
        db.update("DELETE FROM attachment_cleanup"); db.update("DELETE FROM report_attachments");
        db.update("DELETE FROM report_events"); db.update("DELETE FROM similar_pairs"); db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        db.update("UPDATE users SET password_hash=?,active=TRUE,department_id=NULL,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END WHERE username IN ('student1','admin')", hash);
        long office = Db.id(db.one("SELECT id FROM departments WHERE name='총무처 건설관리과'"), "id");
        if (db.one("SELECT id FROM users WHERE username='budget_staff'") == null)
            db.insert("INSERT INTO users(username,password_hash,display_name,role,department_id) VALUES(?,?,?,?,?)", "budget_staff", hash, "budget_staff", "DEPT_ADMIN", office);
        else db.update("UPDATE users SET password_hash=?,role='DEPT_ADMIN',department_id=?,active=TRUE WHERE username='budget_staff'", hash, office);
        LLM_HITS.set(0); EMBEDDING_HITS.set(0); PROVIDER_FAILS.set(false);
    }

    Map<String, Object> usage() { return db.one("SELECT * FROM ai_usage_budget WHERE period_key='TOTAL'"); }
    long used(String key) { return ((Number) usage().get(key)).longValue(); }
    static void unavailable(org.junit.jupiter.api.function.Executable call) {
        assertEquals("AI_UNAVAILABLE", assertThrows(ApiException.class, call).code);
    }
    void exhaust() {
        db.update("INSERT INTO ai_usage_budget(period_key,llm_calls,embedding_calls) VALUES('TOTAL',3,2)");
    }

    @Test void llmCallsStopAtTheLimitWithoutReachingTheProvider() {
        for (int i = 0; i < 3; i++) {
            @SuppressWarnings("unchecked") var report = (Map<String, Object>) ai.analyze(Map.of("text", "북악관 502호 에어컨이 안 나와요")).get("report");
            assertEquals("HVAC", report.get("subcategory"), "a live answer with a new type passes validation");
        }
        unavailable(() -> ai.analyze(Map.of("text", "북악관 502호 에어컨이 안 나와요")));
        assertEquals(3, LLM_HITS.get(), "the refused call never left the server");
        assertEquals(3, used("llmCalls")); assertEquals(3000, used("llmInputTokens")); assertEquals(600, used("llmOutputTokens"));
        assertEquals(600, used("spentMicroUsd"), "3 x (1000 x 0.10 + 200 x 0.50) micro-USD");
        assertEquals(0, used("reservedMicroUsd")); assertEquals(1, used("blockedCalls"));
        assertEquals(true, budget.status().get("llmBlocked"));
    }

    @Test void embeddingCallsHaveTheirOwnLimit() {
        assertNotNull(ai.embed("북악관 502호 에어컨 고장")); assertNotNull(ai.embed("북악관 503호 에어컨 고장"));
        unavailable(() -> ai.embed("북악관 504호 에어컨 고장"));
        assertEquals(2, EMBEDDING_HITS.get());
        assertEquals(2, used("embeddingCalls")); assertEquals(100, used("embeddingTokens")); assertEquals(0, used("llmCalls"));
    }

    @Test void usageSurvivesARestartBecauseItLivesOnlyInTheDatabase() {
        for (int i = 0; i < 3; i++) ai.analyze(Map.of("text", "북악관 502호 에어컨이 안 나와요"));
        var restarted = new AiBudget(db, "TOTAL", 1.00, 3, 2, 0.10, 0.50, 0.02);
        @SuppressWarnings("unchecked") var counted = (Map<String, Object>) restarted.status().get("used");
        assertEquals(3L, ((Number) counted.get("llmCalls")).longValue());
        assertThrows(AiBudget.Exceeded.class, () -> restarted.reserve(AiBudget.Kind.LLM, 10, 10));
    }

    @Test void parallelRequestsCannotOvershootTheLimit() throws Exception {
        var pool = Executors.newFixedThreadPool(10); var start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) results.add(pool.submit(() -> {
            start.await();
            try { ai.analyze(Map.of("text", "북악관 502호 에어컨이 안 나와요")); return "OK"; }
            catch (ApiException e) { return e.code; }
        }));
        start.countDown();
        var outcomes = new ArrayList<String>();
        for (var f : results) outcomes.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(3, Collections.frequency(outcomes, "OK"), outcomes.toString());
        assertEquals(7, Collections.frequency(outcomes, "AI_UNAVAILABLE"), outcomes.toString());
        assertEquals(3, LLM_HITS.get()); assertEquals(3, used("llmCalls"));
    }

    @Test void aFailedProviderResponseStillCountsItsReservation() {
        PROVIDER_FAILS.set(true);
        unavailable(() -> ai.analyze(Map.of("text", "북악관 502호 에어컨이 안 나와요")));
        assertEquals(1, LLM_HITS.get()); assertEquals(1, used("llmCalls"));
        assertTrue(used("spentMicroUsd") > 0, "kept as spent, the provider may have charged");
        assertEquals(0, used("reservedMicroUsd"));
    }

    // ---- over HTTP, as the student and the supervisor use it ----

    class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String token, header;
        HttpResponse<String> call(String method, String path, Object body) throws Exception {
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + env.getProperty("local.server.port") + "/api" + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (token != null && !method.equals("GET")) req.header(header, token);
            return client.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        JsonNode ok(String method, String path, Object body, int code) throws Exception {
            var r = call(method, path, body); assertEquals(code, r.statusCode(), r.body()); return JSON.readTree(r.body());
        }
        void csrf() throws Exception { token = null; var j = JSON.readTree(call("GET", "/auth/csrf", null).body()); token = j.get("token").asText(); header = j.get("headerName").asText(); }
        Session login(String user) throws Exception { csrf(); ok("POST", "/auth/login", Map.of("username", user, "password", PASSWORD), 200); csrf(); return this; }
    }

    @Test void exhaustedBudgetStillLetsStudentsSubmitForReviewAndManually() throws Exception {
        exhaust();
        var student = new Session().login("student1");
        String key = UUID.randomUUID().toString();
        var first = student.ok("POST", "/assistant/intake", Map.of("clientRequestId", key, "text", "북악관 502호 에어컨이 안 나와요"), 200);
        assertEquals("REVIEW_REQUIRED", first.path("status").asText());
        assertTrue(first.path("message").asText().contains("관리자 검토"));
        var saved = student.ok("POST", "/assistant/intake", Map.of("clientRequestId", key, "revision", first.path("revision").asInt(), "submitForReview", true), 200);
        assertEquals("ACCEPTED", saved.path("status").asText());
        long reportId = saved.at("/report/id").asLong();

        var manual = new LinkedHashMap<String, Object>();
        manual.put("clientRequestId", UUID.randomUUID().toString()); manual.put("originalText", "북악관 302호 의자가 부러졌어요");
        manual.put("category", "FACILITY"); manual.put("subcategory", "FURNITURE"); manual.put("symptom", "의자 다리 부러짐");
        manual.put("locationText", "북악관 302호"); manual.put("building", "북악관"); manual.put("floor", "3");
        student.ok("POST", "/reports", manual, 201);

        assertEquals(0, LLM_HITS.get()); assertEquals(0, EMBEDDING_HITS.get(), "embedding refused too; similarity fell back to rules");
        assertEquals(2, ((Number) db.one("SELECT COUNT(*) c FROM reports").get("c")).longValue());
        var admin = new Session().login("admin");
        assertEquals("REVIEW_HOLD", admin.ok("GET", "/admin/reports/" + reportId, null, 200).path("unassignedReason").asText());
    }

    @Test void onlyTheSupervisorCanReadUsage() throws Exception {
        exhaust();
        var view = new Session().login("admin").ok("GET", "/admin/ai-usage", null, 200);
        assertTrue(view.path("llmBlocked").asBoolean()); assertTrue(view.path("embeddingBlocked").asBoolean());
        assertEquals(3, view.at("/limits/maxLlmCalls").asInt()); assertEquals("LIVE", view.path("aiMode").asText());
        assertEquals(403, new Session().login("budget_staff").call("GET", "/admin/ai-usage", null).statusCode());
        assertEquals(403, new Session().login("student1").call("GET", "/admin/ai-usage", null).statusCode());
        var anonymous = new Session(); anonymous.csrf();
        assertEquals(401, anonymous.call("GET", "/admin/ai-usage", null).statusCode());
    }
}
