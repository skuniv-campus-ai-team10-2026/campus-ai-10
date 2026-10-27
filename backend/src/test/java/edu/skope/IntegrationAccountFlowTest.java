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
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR #6 (accounts) x PR #7 (routing) x existing reports/photos, over real HTTP. DEMO AI, no paid calls.
 * Only the cross-feature links that the two PRs' own tests do not already cover.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationAccountFlowTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = UUID.randomUUID().toString();
    static final Path STORE;
    static { try { STORE = Files.createTempDirectory("skope-int-account"); } catch (Exception e) { throw new IllegalStateException(e); } }

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:int_account;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> PASSWORD); p.add("AI_MODE", () -> "DEMO"); p.add("EMBEDDING_MODE", () -> "RULE_ONLY");
        p.add("app.attachments.dir", STORE::toString);
        p.add("app.routing.demo-enabled", () -> true);
    }
    @Autowired Db db; @Autowired Environment env;

    @BeforeEach void clean() {
        db.update("DELETE FROM attachment_cleanup"); db.update("DELETE FROM report_attachments"); db.update("DELETE FROM report_events");
        db.update("DELETE FROM similar_pairs"); db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        db.update("DELETE FROM users WHERE username LIKE 'int\\_%'");
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        db.update("UPDATE users SET password_hash=?,active=TRUE,auth_version=0,department_id=NULL,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END WHERE username IN ('student1','student2','admin')", hash);
        for (var dept : db.all("SELECT id FROM departments")) {
            long id = Db.id(dept, "id"); String name = "dept" + id;
            if (db.one("SELECT id FROM users WHERE username=?", name) == null)
                db.insert("INSERT INTO users(username,password_hash,display_name,role,department_id) VALUES(?,?,?,?,?)", name, hash, name, "DEPT_ADMIN", id);
            else db.update("UPDATE users SET role='DEPT_ADMIN',department_id=?,active=TRUE,auth_version=0,password_hash=? WHERE username=?", id, hash, name);
        }
    }

    class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String token, header;
        URI uri(String path) { return URI.create("http://127.0.0.1:" + env.getProperty("local.server.port") + "/api" + path); }
        HttpResponse<byte[]> call(String method, String path, Object body) throws Exception {
            var req = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (!method.equals("GET") && token != null) req.header(header, token);
            return client.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        JsonNode ok(String method, String path, Object body, int code) throws Exception {
            var r = call(method, path, body); assertEquals(code, r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
            return r.body().length == 0 ? null : JSON.readTree(r.body());
        }
        void csrf() throws Exception { token = null; var j = JSON.readTree(call("GET", "/auth/csrf", null).body()); token = j.get("token").asText(); header = j.get("headerName").asText(); }
        Session login(String user, String password) throws Exception { csrf(); ok("POST", "/auth/login", Map.of("username", user, "password", password), 200); csrf(); return this; }
        int status(String method, String path) throws Exception { return call(method, path, null).statusCode(); }
        JsonNode upload(long reportId) throws Exception {
            var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "jpeg", out);
            String boundary = "----int" + UUID.randomUUID(); var body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(out.toByteArray()); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var r = client.send(HttpRequest.newBuilder(uri("/reports/" + reportId + "/attachments")).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header(header, token).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, r.statusCode(), r.body()); return JSON.readTree(r.body());
        }
    }
    Session anonymous() throws Exception { var s = new Session(); s.csrf(); return s; }
    String register(String username, String name, String password) throws Exception {
        anonymous().ok("POST", "/auth/register", Map.of("username", username, "displayName", name, "password", password), 201); return username;
    }
    Map<String, Object> report(String subcategory, String text) {
        var p = new LinkedHashMap<String, Object>();
        p.put("clientRequestId", UUID.randomUUID().toString()); p.put("originalText", text);
        p.put("category", edu.skope.domain.Codes.TYPES.get(subcategory)); p.put("subcategory", subcategory); p.put("symptom", text);
        p.put("locationText", "북악관 502호"); p.put("building", "북악관"); p.put("floor", "5"); return p;
    }

    @Test void newAccountReportsHvacThroughToAResolvedResultWithAPhoto() throws Exception {
        String pw = "int-password-" + UUID.randomUUID().toString().substring(0, 8);
        var reg = register("int_newbie", "새내기", pw);
        var fresh = new Session(); fresh.csrf();
        assertEquals(401, fresh.status("GET", "/auth/me"), "registration does not log in");
        var student = new Session().login(reg, pw);
        long id = student.ok("POST", "/reports", report("HVAC", "북악관 502호 에어컨이 안 나와요"), 201).get("id").asLong();
        student.upload(id);
        var intake = student.ok("POST", "/assistant/intake", Map.of("clientRequestId", UUID.randomUUID().toString(), "text", "북악관 502호 정수기가 고장났어요"), 200);
        assertTrue(Set.of("ACCEPTED", "NEEDS_INPUT", "REVIEW_REQUIRED").contains(intake.path("status").asText()), "a new account can use AI intake");

        long office = Db.id(db.one("SELECT department_id FROM routing_rules WHERE subcategory='HVAC'"), "departmentId");
        var staff = new Session().login("dept" + office, PASSWORD);
        var detail = staff.ok("GET", "/admin/reports/" + id, null, 200);
        assertEquals("DEMO", detail.at("/assignedDepartment/assignmentBasis").asText());
        assertTrue(detail.at("/assignedDepartment/contactPhone").isNull());
        assertEquals(1, staff.ok("GET", "/admin/reports/" + id + "/attachments", null, 200).path("items").size());
        staff.ok("PATCH", "/admin/reports/" + id + "/status", Map.of("status", "IN_PROGRESS"), 200);
        staff.ok("PATCH", "/admin/reports/" + id + "/status", Map.of("status", "RESOLVED", "resolutionText", "합성 처리 완료"), 200);
        var result = student.ok("GET", "/reports/" + id, null, 200);
        assertEquals("RESOLVED", result.path("status").asText()); assertEquals("합성 처리 완료", result.path("resolutionText").asText());
        assertEquals(200, student.status("GET", "/reports/" + id + "/attachments"));
    }

    @Test void renamingKeepsTheSessionAndOwnership() throws Exception {
        String pw = "int-password-" + UUID.randomUUID().toString().substring(0, 8);
        var student = new Session().login(register("int_rename", "이름전", pw), pw);
        long id = student.ok("POST", "/reports", report("WIFI", "북악관 502호 와이파이가 끊겨요"), 201).get("id").asLong();
        long owner = Db.id(db.one("SELECT student_id FROM reports WHERE id=?", id), "studentId");
        assertEquals("이름후", student.ok("PATCH", "/auth/me", Map.of("displayName", "이름후"), 200).path("displayName").asText());
        assertEquals(200, student.status("GET", "/reports/" + id));
        assertEquals(1, student.ok("GET", "/reports", null, 200).path("items").size());
        assertEquals(owner, Db.id(db.one("SELECT student_id FROM reports WHERE id=?", id), "studentId"));
    }

    @Test void passwordChangeEndsOnlyOwnSessionsAndKeepsReportsAndPhotos() throws Exception {
        String pw = "int-password-" + UUID.randomUUID().toString().substring(0, 8), next = pw + "-next";
        String user = register("int_pwchange", "비번변경", pw);
        Session first = new Session().login(user, pw), second = new Session().login(user, pw), other = new Session().login("student2", PASSWORD);
        long id = first.ok("POST", "/reports", report("LEAK", "북악관 3층 천장에서 물이 새요"), 201).get("id").asLong();
        long photo = first.upload(id).get("id").asLong();
        long otherReport = other.ok("POST", "/reports", report("WIFI", "북악관 502호 와이파이"), 201).get("id").asLong();
        first.ok("POST", "/auth/password", Map.of("currentPassword", pw, "newPassword", next), 200);
        assertEquals(401, first.status("GET", "/reports")); assertEquals(401, second.status("GET", "/reports"));
        assertEquals(200, other.status("GET", "/reports/" + otherReport), "other user's session untouched");
        var again = new Session().login(user, next);
        assertEquals(200, again.status("GET", "/reports/" + id));
        assertEquals(200, again.status("GET", "/reports/" + id + "/attachments/" + photo + "/content"));
    }

    @Test void requestsCannotGrantRolesOrDepartments() throws Exception {
        long it = Db.id(db.one("SELECT id FROM departments WHERE name='정보전산원'"), "id");
        var anon = anonymous();
        assertEquals(400, anon.call("POST", "/auth/register", Map.of("username", "int_sneaky", "displayName", "x", "password", "int-password-123", "role", "ADMIN")).statusCode());
        assertEquals(400, anon.call("POST", "/auth/register", Map.of("username", "int_sneaky", "displayName", "x", "password", "int-password-123", "departmentId", it)).statusCode());
        assertNull(db.one("SELECT id FROM users WHERE username='int_sneaky'"));
        String pw = "int-password-" + UUID.randomUUID().toString().substring(0, 8);
        var student = new Session().login(register("int_plain", "평범", pw), pw);
        assertEquals(400, student.call("PATCH", "/auth/me", Map.of("displayName", "y", "role", "ADMIN")).statusCode());
        assertEquals(400, student.call("PATCH", "/auth/me", Map.of("displayName", "y", "departmentId", it)).statusCode());
        var row = db.one("SELECT role,department_id FROM users WHERE username='int_plain'");
        assertEquals("STUDENT", row.get("role")); assertNull(row.get("departmentId"));
        assertEquals(403, student.status("GET", "/admin/reports"));
        assertEquals(403, student.status("GET", "/admin/ai-usage"));
    }

    @Test void newAccountsCannotReachOtherStudentsOrDepartments() throws Exception {
        var owner = new Session().login("student1", PASSWORD);
        long id = owner.ok("POST", "/reports", report("WATER_DISPENSER", "북악관 3층 정수기가 고장났어요"), 201).get("id").asLong();
        long photo = owner.upload(id).get("id").asLong();
        String pw = "int-password-" + UUID.randomUUID().toString().substring(0, 8);
        var stranger = new Session().login(register("int_stranger", "남", pw), pw);
        assertEquals(404, stranger.status("GET", "/reports/" + id));
        assertEquals(404, stranger.status("GET", "/reports/" + id + "/attachments/" + photo + "/content"));
        long itDept = Db.id(db.one("SELECT department_id FROM routing_rules WHERE subcategory='WIFI'"), "departmentId");
        var itStaff = new Session().login("dept" + itDept, PASSWORD);
        assertEquals(404, itStaff.status("GET", "/admin/reports/" + id), "water dispenser report belongs to another department");
        assertEquals(404, itStaff.status("GET", "/admin/reports/" + id + "/attachments/" + photo + "/content"));
    }
}
