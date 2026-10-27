package edu.skope.ai;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Budget gaps on the integrated build (PR #6 + #7), against a local fake provider: 0 paid calls.
 * The fake provider's token counts are invented; they test the bookkeeping, not real tokenisation or prices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationBudgetEdgeTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = UUID.randomUUID().toString();
    enum Mode { NORMAL, FAIL, SLOW, NO_USAGE }
    static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.NORMAL);
    static final AtomicInteger LLM = new AtomicInteger(), EMB = new AtomicInteger();
    static final AtomicLong LAST_LLM_BYTES = new AtomicLong(), LAST_EMB_BYTES = new AtomicLong();
    static final HttpServer STUB;
    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/v1/chat/completions", ex -> {
                LLM.incrementAndGet(); LAST_LLM_BYTES.set(ex.getRequestBody().readAllBytes().length);
                Mode mode = MODE.get();
                if (mode == Mode.SLOW) { try { Thread.sleep(2500); } catch (InterruptedException ignored) {} }
                if (mode == Mode.FAIL) { ex.sendResponseHeaders(500, -1); ex.close(); return; }
                var report = new LinkedHashMap<String, Object>();
                report.put("category", "FACILITY"); report.put("subcategory", "WATER_DISPENSER");
                report.put("locationText", "북악관 3층"); report.put("building", "북악관"); report.put("floor", "3");
                report.put("symptom", "정수기 고장"); report.put("occurredAtText", null); report.put("followUpQuestion", null);
                report.put("missingFields", List.of()); report.put("emergencyHint", false);
                var body = new LinkedHashMap<String, Object>();
                body.put("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", JSON.writeValueAsString(Map.of("intent", "REPORT", "report", report))))));
                if (mode != Mode.NO_USAGE) body.put("usage", Map.of("prompt_tokens", 1000, "completion_tokens", 200, "total_tokens", 1200));
                byte[] bytes = JSON.writeValueAsBytes(body);
                try { ex.getResponseHeaders().add("Content-Type", "application/json"); ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); }
                catch (Exception clientGone) { /* the server already timed out */ }
                ex.close();
            });
            STUB.createContext("/v1/embeddings", ex -> {
                EMB.incrementAndGet(); LAST_EMB_BYTES.set(ex.getRequestBody().readAllBytes().length);
                byte[] bytes = JSON.writeValueAsBytes(Map.of("data", List.of(Map.of("index", 0, "embedding", List.of(0.1, 0.2))), "model", "stub-embedding",
                    "usage", Map.of("prompt_tokens", 50, "total_tokens", 50)));
                ex.getResponseHeaders().add("Content-Type", "application/json"); ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
            });
            STUB.setExecutor(Executors.newFixedThreadPool(12)); STUB.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:int_budget;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> PASSWORD);
        p.add("AI_MODE", () -> "LIVE"); p.add("EMBEDDING_MODE", () -> "LIVE");
        p.add("AI_BASE_URL", () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/v1");
        p.add("AI_API_KEY", () -> "local-stub-not-a-real-key"); p.add("AI_MODEL", () -> "stub-model"); p.add("EMBEDDING_MODEL", () -> "stub-embedding");
        p.add("AI_TIMEOUT_SECONDS", () -> "1"); p.add("AI_MAX_COMPLETION_TOKENS", () -> "1000");
        p.add("AI_BUDGET_MAX_LLM_CALLS", () -> "4"); p.add("AI_BUDGET_MAX_EMBEDDING_CALLS", () -> "3"); p.add("AI_BUDGET_MAX_USD", () -> "1.00");
    }
    @AfterAll static void stop() { STUB.stop(0); }

    @Autowired AiService ai; @Autowired Environment env;
    @MockitoSpyBean Db db;

    @BeforeEach void reset() {
        db.update("DELETE FROM ai_usage_budget");
        db.update("DELETE FROM attachment_cleanup"); db.update("DELETE FROM report_attachments"); db.update("DELETE FROM report_events");
        db.update("DELETE FROM similar_pairs"); db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        db.update("DELETE FROM users WHERE username LIKE 'bud\\_%'");
        db.update("UPDATE users SET password_hash=?,active=TRUE,auth_version=0,department_id=NULL,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END WHERE username IN ('student1','admin')",
            new BCryptPasswordEncoder().encode(PASSWORD));
        LLM.set(0); EMB.set(0); MODE.set(Mode.NORMAL);
    }
    long used(String key) { var r = db.one("SELECT * FROM ai_usage_budget WHERE period_key='TOTAL'"); return r == null ? 0 : ((Number) r.get(key)).longValue(); }
    static String code(org.junit.jupiter.api.function.Executable call) { return assertThrows(ApiException.class, call).code; }
    Map<String, Object> analyze() { return ai.analyze(Map.of("text", "북악관 3층 정수기가 고장났어요")); }

    class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token, header;
        HttpResponse<String> call(String method, String path, Object body) throws Exception {
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + env.getProperty("local.server.port") + "/api" + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (!method.equals("GET") && token != null) req.header(header, token);
            return client.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        void csrf() throws Exception { token = null; var j = JSON.readTree(call("GET", "/auth/csrf", null).body()); token = j.get("token").asText(); header = j.get("headerName").asText(); }
        Session login(String u, String p) throws Exception { csrf(); assertEquals(200, call("POST", "/auth/login", Map.of("username", u, "password", p)).statusCode()); csrf(); return this; }
    }
    String register(String u) throws Exception {
        var s = new Session(); s.csrf();
        assertEquals(201, s.call("POST", "/auth/register", Map.of("username", u, "displayName", u, "password", "bud-password-1234")).statusCode()); return u;
    }

    @Test void newAccountsReloginsAndNewRequestIdsShareOneServiceWideLimit() throws Exception {
        var statuses = new ArrayList<Integer>();
        for (String user : List.of(register("bud_one"), register("bud_two"), register("bud_three"))) {
            for (int login = 0; login < 2; login++) {
                var s = new Session().login(user, "bud-password-1234");
                statuses.add(s.call("POST", "/assistant/analyze", Map.of("text", "북악관 3층 정수기가 고장났어요")).statusCode());
                var intake = s.call("POST", "/assistant/intake", Map.of("clientRequestId", UUID.randomUUID().toString(), "text", "북악관 3층 정수기가 고장났어요"));
                assertEquals(200, intake.statusCode(), intake.body());
            }
        }
        assertEquals(4, LLM.get(), "the whole service reached the provider exactly 4 times " + statuses);
        assertEquals(4, used("llmCalls"));
        assertTrue(statuses.subList(statuses.size() - 3, statuses.size()).stream().allMatch(s -> s == 503), statuses.toString());
    }

    @Test void parallelEmbeddingsStopAtTheirLimit() throws Exception {
        var pool = Executors.newFixedThreadPool(10); var start = new CountDownLatch(1); var outcomes = new ArrayList<Future<String>>();
        for (int i = 0; i < 10; i++) outcomes.add(pool.submit(() -> { start.await(); try { ai.embed("북악관 3층 정수기"); return "OK"; } catch (ApiException e) { return e.code; } }));
        start.countDown(); var codes = new ArrayList<String>(); for (var f : outcomes) codes.add(f.get(60, TimeUnit.SECONDS)); pool.shutdown();
        assertEquals(3, Collections.frequency(codes, "OK"), codes.toString()); assertEquals(3, EMB.get()); assertEquals(3, used("embeddingCalls"));
    }

    @Test void dailyBucketsFollowTheSeoulDateWhateverTheJvmZone() {
        var original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            var daily = new AiBudget(db, "DAILY", 1.00, 1, 1, 0.10, 0.50, 0.02);
            String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
            assertEquals(today, daily.periodKey());
            db.update("INSERT INTO ai_usage_budget(period_key,llm_calls) VALUES(?,1)", LocalDate.parse(today).minusDays(1).toString());
            assertDoesNotThrow(() -> daily.reserve(AiBudget.Kind.LLM, 10, 10), "yesterday's full bucket does not block today");
            assertThrows(AiBudget.Exceeded.class, () -> daily.reserve(AiBudget.Kind.LLM, 10, 10), "today's bucket now full");
        } finally { TimeZone.setDefault(original); }
    }

    @Test void timeoutIsNotRetriedAndKeepsTheReservationAsSpent() {
        MODE.set(Mode.SLOW);
        assertEquals("AI_UNAVAILABLE", code(this::analyze));
        assertEquals(1, LLM.get(), "no automatic retry");
        assertEquals(1, used("llmCalls")); assertEquals(0, used("reservedMicroUsd"));
        long reservation = used("spentMicroUsd");
        System.out.println("BUDGET-EVIDENCE timeout reservation kept as spent micro-USD=" + reservation + " requestBytes=" + LAST_LLM_BYTES.get());
        assertTrue(reservation >= 500, "includes AI_MAX_COMPLETION_TOKENS=1000 x 0.50");
    }

    @Test void providerErrorIsNotRetried() {
        MODE.set(Mode.FAIL);
        assertEquals("AI_UNAVAILABLE", code(this::analyze));
        assertEquals(1, LLM.get());
    }

    @Test void successWithoutUsageNumbersKeepsTheReservationInsteadOfZero() {
        MODE.set(Mode.NO_USAGE);
        assertNotNull(analyze());
        assertEquals(1, used("llmCalls")); assertEquals(0, used("llmInputTokens"), "tokens unknown");
        assertTrue(used("spentMicroUsd") > 0, "cost is not under-counted to zero"); assertEquals(0, used("reservedMicroUsd"));
    }

    @Test void reservationMatchesTheRequestActuallySentAndTheConfiguredOutputCap() {
        analyze();
        long sentBytes = LAST_LLM_BYTES.get(), spent = used("spentMicroUsd");
        assertEquals(200, spent, "settled to the provider's numbers: 1000 x 0.10 + 200 x 0.50");
        // Reserve again and hold it, to read the reservation the next call makes before it is sent.
        var budget = new AiBudget(db, "TOTAL", 1.00, 4, 3, 0.10, 0.50, 0.02);
        var held = budget.reserve(AiBudget.Kind.LLM, sentBytes, 1000);
        System.out.println("BUDGET-EVIDENCE LLM request bytes=" + sentBytes + " reservation micro-USD=" + held.reservedMicroUsd() + " vs settled=" + spent);
        assertTrue(held.reservedMicroUsd() >= 500 + 100, "covers the configured 1000-token output cap and input");
        ai.embed("북악관 3층 정수기가 고장났어요 정수기에서 물이 나오지 않아요");
        System.out.println("BUDGET-EVIDENCE embedding request bytes=" + LAST_EMB_BYTES.get() + " settled embedding tokens=" + used("embeddingTokens"));
    }

    @Test void budgetStoreFailureBeforeSendingMeansNoProviderCall() {
        doThrow(new DataAccessResourceFailureException("synthetic outage")).when(db).update(startsWith("UPDATE ai_usage_budget SET llm_calls"), any(Object[].class));
        assertEquals("AI_UNAVAILABLE", code(this::analyze));
        assertEquals(0, LLM.get());
    }

    /** Reported finding check: a failed settlement leaves the reservation counted until an operator resets it. */
    @Test void failedSettlementLeavesTheReservationCountedUntilReset() {
        doThrow(new DataAccessResourceFailureException("synthetic outage")).when(db).update(startsWith("UPDATE ai_usage_budget SET reserved_micro_usd"), any(Object[].class));
        assertNotNull(analyze(), "the student's request itself succeeds");
        org.mockito.Mockito.reset(db);
        long stuck = used("reservedMicroUsd");
        System.out.println("BUDGET-EVIDENCE reservation left after failed settlement micro-USD=" + stuck + " spent=" + used("spentMicroUsd"));
        assertTrue(stuck > 0); assertEquals(0, used("spentMicroUsd"));
        assertNotNull(analyze()); assertEquals(stuck, used("reservedMicroUsd"), "never released by later calls");
    }

    @Test void afterExhaustionExistingReportsAndAdminProcessingStillWork() throws Exception {
        var student = new Session().login("student1", PASSWORD);
        var manual = new LinkedHashMap<String, Object>();
        manual.put("clientRequestId", UUID.randomUUID().toString()); manual.put("originalText", "북악관 3층 정수기가 고장났어요");
        manual.put("category", "FACILITY"); manual.put("subcategory", "WATER_DISPENSER"); manual.put("symptom", "정수기 고장");
        manual.put("locationText", "북악관 3층"); manual.put("building", "북악관"); manual.put("floor", "3");
        long id = JSON.readTree(student.call("POST", "/reports", manual).body()).get("id").asLong();
        db.update("UPDATE ai_usage_budget SET llm_calls=4,embedding_calls=3 WHERE period_key='TOTAL'");
        if (db.one("SELECT period_key FROM ai_usage_budget WHERE period_key='TOTAL'") == null) db.update("INSERT INTO ai_usage_budget(period_key,llm_calls,embedding_calls) VALUES('TOTAL',4,3)");
        int before = LLM.get() + EMB.get();
        assertEquals(200, student.call("GET", "/reports/" + id, null).statusCode());
        var admin = new Session().login("admin", PASSWORD);
        assertEquals(200, admin.call("PATCH", "/admin/reports/" + id + "/status", Map.of("status", "IN_PROGRESS")).statusCode());
        assertEquals(200, admin.call("PATCH", "/admin/reports/" + id + "/status", Map.of("status", "RESOLVED", "resolutionText", "합성 처리")).statusCode());
        assertEquals("RESOLVED", JSON.readTree(student.call("GET", "/reports/" + id, null).body()).path("status").asText());
        assertEquals(before, LLM.get() + EMB.get(), "no paid call needed for any of it");
    }
}
