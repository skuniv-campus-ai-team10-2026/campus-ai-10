package edu.skope;

import com.fasterxml.jackson.databind.*;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Photo API over real HTTP with session cookies and CSRF, as the browser will use it. DEMO AI, no paid calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttachmentApiIntegrationTest {
    static final String PASSWORD = UUID.randomUUID().toString();
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path STORE;
    static { try { STORE = Files.createTempDirectory("skope-attachments-api"); } catch (Exception e) { throw new IllegalStateException(e); } }

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:attachment_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> PASSWORD); p.add("AI_MODE", () -> "DEMO"); p.add("EMBEDDING_MODE", () -> "RULE_ONLY");
        p.add("app.attachments.dir", STORE::toString);
    }
    @Autowired Db db; @Autowired Environment env;

    @AfterAll static void removeStore() throws Exception {
        try (var files = Files.walk(STORE)) { files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); }
    }

    @BeforeEach void clean() throws Exception {
        db.update("DELETE FROM attachment_cleanup");
        db.update("DELETE FROM report_attachments"); db.update("DELETE FROM report_events"); db.update("DELETE FROM similar_pairs");
        db.update("DELETE FROM reports"); db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        db.update("UPDATE departments SET active=TRUE");
        try (var files = Files.list(STORE)) { for (Path f : files.toList()) Files.delete(f); }
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        db.update("UPDATE users SET password_hash=?,active=TRUE,department_id=NULL,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END WHERE username IN ('student1','student2','admin')", hash);
        for (var dept : db.all("SELECT id FROM departments")) {
            long id = Db.id(dept, "id"); String name = "dept" + id;
            if (db.one("SELECT id FROM users WHERE username=?", name) == null)
                db.insert("INSERT INTO users(username,password_hash,display_name,role,department_id) VALUES(?,?,?,?,?)", name, hash, name, "DEPT_ADMIN", id);
            else db.update("UPDATE users SET role='DEPT_ADMIN',department_id=?,active=TRUE,password_hash=? WHERE username=?", id, hash, name);
        }
    }

    class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String token, header;
        URI uri(String path) { return URI.create("http://127.0.0.1:" + env.getProperty("local.server.port") + "/api" + path); }
        HttpResponse<byte[]> send(HttpRequest.Builder req) throws Exception { return client.send(req.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray()); }
        HttpResponse<byte[]> call(String method, String path, Object body, boolean csrf) throws Exception {
            var req = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (csrf) req.header(header, token);
            return send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        }
        JsonNode ok(String method, String path, Object body, int code) throws Exception {
            var r = call(method, path, body, !method.equals("GET"));
            assertEquals(code, r.statusCode(), text(r));
            return r.body().length == 0 ? null : JSON.readTree(r.body());
        }
        void csrf() throws Exception { var j = JSON.readTree(send(HttpRequest.newBuilder(uri("/auth/csrf"))).body()); token = j.get("token").asText(); header = j.get("headerName").asText(); }
        Session login(String username) throws Exception { csrf(); ok("POST", "/auth/login", Map.of("username", username, "password", PASSWORD), 200); csrf(); return this; }
        /** multipart/form-data with one "file" part per array; csrfToken null = no header at all. */
        HttpResponse<byte[]> uploadWithToken(String path, String csrfToken, byte[]... files) throws Exception {
            String boundary = "----skope" + UUID.randomUUID(); var body = new ByteArrayOutputStream();
            for (byte[] file : files) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"내 사진.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(file); body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var req = HttpRequest.newBuilder(uri(path)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
            if (csrfToken != null) req.header(header == null ? "X-XSRF-TOKEN" : header, csrfToken);
            return send(req);
        }
        HttpResponse<byte[]> upload(String path, byte[]... files) throws Exception { return uploadWithToken(path, token, files); }
    }

    static String text(HttpResponse<byte[]> r) { return new String(r.body(), StandardCharsets.UTF_8); }
    static JsonNode json(HttpResponse<byte[]> r) throws Exception { return JSON.readTree(r.body()); }
    static void expect(int status, String code, HttpResponse<byte[]> r) throws Exception {
        assertEquals(status, r.statusCode(), text(r));
        if (code != null) assertEquals(code, json(r).get("code").asText(), text(r));
    }
    static byte[] jpeg() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }
    static long files() throws Exception { try (var f = Files.list(STORE)) { return f.count(); } }
    long rows() { return Db.id(db.one("SELECT COUNT(*) c FROM report_attachments"), "c"); }
    long reportCount() { return Db.id(db.one("SELECT COUNT(*) c FROM reports"), "c"); }
    long rule(String subtype) { return Db.id(db.one("SELECT id FROM routing_rules WHERE subcategory=?", subtype), "id"); }
    long dept(String subtype) { return Db.id(db.one("SELECT department_id FROM routing_rules WHERE subcategory=?", subtype), "departmentId"); }
    Session staff(String subtype) throws Exception { return new Session().login("dept" + dept(subtype)); }
    long createReport(Session student) throws Exception {
        var p = new LinkedHashMap<String,Object>();
        p.put("clientRequestId", UUID.randomUUID().toString()); p.put("originalText", "북악관 502호 와이파이가 10분마다 끊겨요.");
        p.put("category", "IT"); p.put("subcategory", "WIFI"); p.put("symptom", "10분마다 연결 끊김");
        p.put("locationText", "북악관 502호"); p.put("building", "북악관"); p.put("floor", "5");
        return student.ok("POST", "/reports", p, 201).get("id").asLong();
    }
    static String base(long reportId) { return "/reports/" + reportId + "/attachments"; }

    @Test void naturalLanguageIntakePhotoTransferAndStudentResultWorkTogether() throws Exception {
        var student = new Session().login("student1");
        String requestId = UUID.randomUUID().toString();
        var accepted = student.ok("POST", "/assistant/intake", Map.of("clientRequestId", requestId,
            "text", "북악관 608호에서 와이파이가 10분마다 끊겨요.", "preferredIntent", "AUTO"), 200);
        assertEquals("ACCEPTED", accepted.get("status").asText());
        long r = accepted.at("/report/id").asLong();
        assertTrue(r > 0);
        var uploaded = student.upload(base(r), jpeg());
        expect(201, null, uploaded);
        long a = json(uploaded).get("id").asLong();
        assertEquals(r, student.ok("POST", "/assistant/intake", Map.of("clientRequestId", requestId,
            "resume", true), 200).at("/report/id").asLong());
        assertEquals(1, reportCount(), "resuming intake must not duplicate a report with a photo");

        String content = "/admin" + base(r) + "/" + a + "/content";
        var it = staff("WIFI");
        assertEquals(200, it.call("GET", content, null, false).statusCode());
        new Session().login("admin").ok("PATCH", "/admin/reports/" + r + "/routing",
            Map.of("routingRuleId", rule("ELECTRIC"), "reason", "합성 통합 검증: 전원 점검 부서로 재배정"), 200);
        expect(404, "REPORT_NOT_FOUND", it.call("GET", content, null, false));
        var electric = staff("ELECTRIC");
        assertEquals(200, electric.call("GET", content, null, false).statusCode());
        electric.ok("PATCH", "/admin/reports/" + r + "/status", Map.of("status", "IN_PROGRESS"), 200);
        String resolution = "합성 통합 검증: 전원과 연결 상태를 확인했습니다.";
        electric.ok("PATCH", "/admin/reports/" + r + "/status",
            Map.of("status", "RESOLVED", "resolutionText", resolution), 200);
        var result = student.ok("GET", "/reports/" + r, null, 200);
        assertEquals("RESOLVED", result.get("status").asText());
        assertEquals(resolution, result.get("resolutionText").asText());
        student.ok("DELETE", base(r) + "/" + a, null, 204);
        assertEquals(0, rows()); assertEquals(0, files());
        assertEquals(0, Db.id(db.one("SELECT COUNT(*) c FROM attachment_cleanup"), "c"));
        assertEquals(resolution, student.ok("GET", "/reports/" + r, null, 200).get("resolutionText").asText());
    }

    @Test void studentUploadsListsViewsAndDeletesOverHttp() throws Exception {
        var student = new Session().login("student1"); long r = createReport(student);
        var created = student.upload(base(r), jpeg());
        expect(201, null, created);
        var view = json(created); long a = view.get("id").asLong();
        assertEquals("image/jpeg", view.get("contentType").asText());
        assertEquals("/api" + base(r) + "/" + a + "/content", view.get("contentPath").asText());
        assertFalse(view.has("storageKey") || view.has("uploaderId") || text(created).contains("내 사진"), "no server-only fields or original name");

        var list = student.ok("GET", base(r), null, 200);
        assertEquals(1, list.get("items").size()); assertEquals(3, list.get("maxCount").asInt());

        var image = student.call("GET", base(r) + "/" + a + "/content", null, false);
        assertEquals(200, image.statusCode());
        assertEquals("image/jpeg", image.headers().firstValue("Content-Type").orElse(""));
        assertEquals("nosniff", image.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(image.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(image.headers().firstValue("Content-Disposition").orElse("").contains("attachment-" + a + ".jpg"));
        assertTrue(image.headers().firstValue("Content-Security-Policy").orElse("").contains("sandbox"));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(image.body())));

        assertNull(student.ok("DELETE", base(r) + "/" + a, null, 204));
        assertEquals(0, student.ok("GET", base(r), null, 200).get("items").size());
        assertEquals(0, files());
        var event = db.one("SELECT * FROM report_events WHERE report_id=? AND event_type='ATTACHMENT_DELETED'", r);
        assertEquals(String.valueOf(a), event.get("oldValue"));
    }

    @Test void loginIsRequired() throws Exception {
        long r = createReport(new Session().login("student1"));
        var anonymous = new Session(); anonymous.csrf();
        expect(401, "UNAUTHENTICATED", anonymous.call("GET", base(r), null, false));
        expect(401, "UNAUTHENTICATED", anonymous.call("GET", base(r) + "/1/content", null, false));
        expect(401, "UNAUTHENTICATED", anonymous.call("GET", "/admin" + base(r), null, false));
        expect(401, "UNAUTHENTICATED", anonymous.upload(base(r), jpeg()));
        assertEquals(0, rows()); assertEquals(0, files());
    }

    @Test void csrfTokenIsRequiredForUploadAndDelete() throws Exception {
        var student = new Session().login("student1"); long r = createReport(student);
        expect(403, "FORBIDDEN", student.uploadWithToken(base(r), null, jpeg()));
        expect(403, "FORBIDDEN", student.uploadWithToken(base(r), "not-the-token", jpeg()));
        assertEquals(0, rows()); assertEquals(0, files());

        long a = json(student.upload(base(r), jpeg())).get("id").asLong();
        expect(403, "FORBIDDEN", student.call("DELETE", base(r) + "/" + a, null, false));
        assertEquals(1, rows()); assertEquals(1, files());
    }

    @Test void otherStudentsGet404AndRolesStayOnTheirOwnPaths() throws Exception {
        var owner = new Session().login("student1"); long r = createReport(owner);
        long a = json(owner.upload(base(r), jpeg())).get("id").asLong();
        var other = new Session().login("student2");
        expect(404, "REPORT_NOT_FOUND", other.upload(base(r), jpeg()));
        expect(404, "REPORT_NOT_FOUND", other.call("GET", base(r), null, false));
        expect(404, "REPORT_NOT_FOUND", other.call("GET", base(r) + "/" + a + "/content", null, false));
        expect(404, "REPORT_NOT_FOUND", other.call("DELETE", base(r) + "/" + a, null, true));
        expect(403, "FORBIDDEN", owner.call("GET", "/admin" + base(r), null, false));
        var admin = new Session().login("admin");
        expect(403, "FORBIDDEN", admin.call("GET", base(r), null, false));
        expect(403, "FORBIDDEN", admin.upload(base(r), jpeg()));
        assertEquals(1, rows()); assertEquals(1, files());
    }

    @Test void staffAccessFollowsTheCurrentAssignmentIncludingTransfers() throws Exception {
        var student = new Session().login("student1"); long r = createReport(student);
        long a = json(student.upload(base(r), jpeg())).get("id").asLong();
        String content = "/admin" + base(r) + "/" + a + "/content";
        var admin = new Session().login("admin");
        var it = staff("WIFI"); var electric = staff("ELECTRIC");

        assertEquals(200, admin.call("GET", content, null, false).statusCode());
        assertEquals("/api/admin" + base(r) + "/" + a + "/content", admin.ok("GET", "/admin" + base(r), null, 200).get("items").get(0).get("contentPath").asText());
        assertEquals(200, it.call("GET", content, null, false).statusCode());
        expect(404, "REPORT_NOT_FOUND", electric.call("GET", content, null, false));

        admin.ok("PATCH", "/admin/reports/" + r + "/routing", Map.of("routingRuleId", rule("ELECTRIC"), "reason", "전기 설비 확인이 먼저 필요합니다."), 200);
        expect(404, "REPORT_NOT_FOUND", it.call("GET", content, null, false));
        expect(404, "REPORT_NOT_FOUND", it.call("GET", "/admin" + base(r), null, false));
        assertEquals(200, electric.call("GET", content, null, false).statusCode());
    }

    @Test void onlyOneRealJpegOrPngPerRequest() throws Exception {
        var student = new Session().login("student1"); long r = createReport(student);
        expect(415, "ATTACHMENT_UNSUPPORTED_TYPE", student.upload(base(r), "GIF89a not really".getBytes(StandardCharsets.ISO_8859_1)));
        expect(400, "ATTACHMENT_INVALID_IMAGE", student.upload(base(r), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2}));
        expect(400, "VALIDATION_ERROR", student.upload(base(r)));
        expect(400, "ATTACHMENT_ONE_PER_REQUEST", student.upload(base(r), jpeg(), jpeg()));
        expect(400, "VALIDATION_ERROR", student.call("POST", base(r), Map.of("file", "abc"), true));
        assertEquals(0, rows()); assertEquals(0, files());
        assertEquals(1, reportCount(), "failed photo uploads never touch or duplicate the report");
        assertEquals("RECEIVED", student.ok("GET", "/reports/" + r, null, 200).get("status").asText());
    }

    @Test void limitAndResolvedRulesOverHttp() throws Exception {
        var student = new Session().login("student1"); long r = createReport(student);
        long first = 0;
        for (int i = 0; i < 3; i++) { var res = student.upload(base(r), jpeg()); expect(201, null, res); if (i == 0) first = json(res).get("id").asLong(); }
        expect(409, "ATTACHMENT_LIMIT_EXCEEDED", student.upload(base(r), jpeg()));

        var it = staff("WIFI");
        it.ok("PATCH", "/admin/reports/" + r + "/status", Map.of("status", "IN_PROGRESS"), 200);
        it.ok("PATCH", "/admin/reports/" + r + "/status", Map.of("status", "RESOLVED", "resolutionText", "합성 조치를 완료했습니다."), 200);
        student.ok("DELETE", base(r) + "/" + first, null, 204);
        expect(409, "ATTACHMENT_REPORT_CLOSED", student.upload(base(r), jpeg()));
        var report = student.ok("GET", "/reports/" + r, null, 200);
        assertEquals("RESOLVED", report.get("status").asText());
        assertEquals("합성 조치를 완료했습니다.", report.get("resolutionText").asText());
        assertEquals(2, rows()); assertEquals(2, files());
    }

    @Test void parallelHttpUploadsKeepTheLimit() throws Exception {
        var student = new Session().login("student1"); long r = createReport(student);
        byte[] photo = jpeg();
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) results.add(pool.submit(() -> { start.await(); return student.upload(base(r), photo).statusCode(); }));
        start.countDown();
        var codes = new ArrayList<Integer>();
        for (var f : results) codes.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(3, Collections.frequency(codes, 201), codes.toString());
        assertEquals(3, Collections.frequency(codes, 409), codes.toString());
        assertEquals(3, rows()); assertEquals(3, files());
    }
}
