package edu.skope.attachment;

import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service rules against a real database (H2, or MySQL when TEST_DB_URL is set) and a throwaway photo folder. */
@SpringBootTest
class AttachmentServiceTest {
    static final Path STORE;
    static { try { STORE = Files.createTempDirectory("skope-attachments-test"); } catch (Exception e) { throw new IllegalStateException(e); } }

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:attachment_service;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> UUID.randomUUID().toString());
        p.add("AI_MODE", () -> "DEMO"); p.add("EMBEDDING_MODE", () -> "RULE_ONLY");
        p.add("app.attachments.dir", STORE::toString);
    }

    @Autowired AttachmentService service;
    @MockitoSpyBean Db db;
    @MockitoSpyBean ImageSanitizer sanitizer;

    long student1, student2, supervisor, itStaff, academicStaff, itDept, academicDept;

    @AfterAll static void removeStore() throws Exception {
        try (var files = Files.walk(STORE)) { files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }

    @BeforeEach void clean() throws Exception {
        db.update("DELETE FROM attachment_cleanup");
        db.update("DELETE FROM report_attachments"); db.update("DELETE FROM report_events"); db.update("DELETE FROM similar_pairs");
        db.update("DELETE FROM reports"); db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        db.update("UPDATE departments SET active=TRUE");
        try (var files = Files.list(STORE)) { for (Path f : files.toList()) Files.delete(f); }
        student1 = user("student1"); student2 = user("student2"); supervisor = user("admin");
        itDept = Db.id(db.one("SELECT id FROM departments WHERE name='정보전산원'"), "id");
        academicDept = Db.id(db.one("SELECT id FROM departments WHERE name='교무처 교무과'"), "id");
        itStaff = staff("att_it_staff", itDept); academicStaff = staff("att_academic_staff", academicDept);
        db.update("UPDATE users SET active=TRUE,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END,department_id=NULL WHERE username IN ('student1','student2','admin')");
    }

    long user(String name) { return Db.id(db.one("SELECT id FROM users WHERE username=?", name), "id"); }
    long staff(String name, long department) {
        var u = db.one("SELECT id FROM users WHERE username=?", name);
        if (u == null) return db.insert("INSERT INTO users(username,password_hash,display_name,role,department_id) VALUES(?,?,?,?,?)", name, "x", name, "DEPT_ADMIN", department);
        db.update("UPDATE users SET role='DEPT_ADMIN',department_id=?,active=TRUE WHERE id=?", department, Db.id(u, "id"));
        return Db.id(u, "id");
    }
    long report(long student, Long department) {
        return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,assigned_department_id,status) VALUES(?,?,?,?,?,?,?)",
            student, UUID.randomUUID().toString(), "합성 신고: 북악관 502호 와이파이 끊김", "IT", "WIFI", department, "RECEIVED");
    }
    static byte[] jpeg() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }
    static long files() throws Exception { try (var f = Files.list(STORE)) { return f.count(); } }
    long rows(long reportId) { return Db.id(db.one("SELECT COUNT(*) c FROM report_attachments WHERE report_id=?", reportId), "c"); }
    static ApiException rejected(int status, String code, org.junit.jupiter.api.function.Executable action) {
        var e = assertThrows(ApiException.class, action);
        assertEquals(status, e.status, e.getMessage()); assertEquals(code, e.code);
        return e;
    }
    static long id(Map<String,Object> view) { return ((Number) view.get("id")).longValue(); }

    @Test void studentUploadsListsViewsAndDeletesOwnPhoto() throws Exception {
        long r = report(student1, itDept);
        var view = service.upload(student1, r, jpeg());
        assertFalse(view.containsKey("storageKey") || view.containsKey("uploaderId"), "server-only fields stay hidden");
        assertEquals("image/jpeg", view.get("contentType"));
        assertEquals("/api/reports/" + r + "/attachments/" + id(view) + "/content", view.get("contentPath"));
        assertEquals(1, ((List<?>) service.listForStudent(student1, r).get("items")).size());
        var content = service.contentForStudent(student1, r, id(view));
        assertEquals("image/jpeg", content.contentType());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(content.bytes())));
        assertEquals(1, files());

        assertNull(db.one("SELECT storage_key FROM attachment_cleanup"), "a finished upload leaves no ledger entry");
        service.deleteForStudent(student1, r, id(view));
        assertEquals(0, rows(r)); assertEquals(0, files());
        assertNull(db.one("SELECT storage_key FROM attachment_cleanup"), "a successful delete leaves no ledger entry");
        var event = db.one("SELECT * FROM report_events WHERE report_id=? AND event_type='ATTACHMENT_DELETED'", r);
        assertEquals(String.valueOf(id(view)), event.get("oldValue"));
        assertEquals(student1, Db.id(event, "actorId"));
        assertNotNull(event.get("createdAt"));
    }

    @Test void anotherStudentSeesNothingAndChangesNothing() throws Exception {
        long r = report(student1, itDept);
        long a = id(service.upload(student1, r, jpeg()));
        rejected(404, "REPORT_NOT_FOUND", () -> service.upload(student2, r, jpeg()));
        rejected(404, "REPORT_NOT_FOUND", () -> service.listForStudent(student2, r));
        rejected(404, "REPORT_NOT_FOUND", () -> service.contentForStudent(student2, r, a));
        rejected(404, "REPORT_NOT_FOUND", () -> service.deleteForStudent(student2, r, a));
        assertEquals(1, rows(r)); assertEquals(1, files());
        assertNull(db.one("SELECT id FROM report_events WHERE report_id=?", r));
    }

    @Test void attachmentIdMustBelongToTheReportInThePath() throws Exception {
        long mine = report(student1, itDept), other = report(student1, itDept);
        long a = id(service.upload(student1, other, jpeg()));
        rejected(404, "ATTACHMENT_NOT_FOUND", () -> service.contentForStudent(student1, mine, a));
        rejected(404, "ATTACHMENT_NOT_FOUND", () -> service.deleteForStudent(student1, mine, a));
        rejected(404, "ATTACHMENT_NOT_FOUND", () -> service.contentForStaff(supervisor, mine, a));
        assertEquals(1, rows(other)); assertEquals(1, files());
    }

    @Test void resolvedReportRefusesNewPhotosButAllowsOwnDeletionWithHistory() throws Exception {
        long r = report(student1, itDept);
        long a = id(service.upload(student1, r, jpeg()));
        db.update("UPDATE reports SET status='RESOLVED',resolution_text='합성 처리 결과' WHERE id=?", r);
        rejected(409, "ATTACHMENT_REPORT_CLOSED", () -> service.upload(student1, r, jpeg()));
        assertEquals(1, files(), "refused upload left no file");
        service.deleteForStudent(student1, r, a);
        var report = db.one("SELECT status,resolution_text FROM reports WHERE id=?", r);
        assertEquals("RESOLVED", report.get("status"));
        assertEquals("합성 처리 결과", report.get("resolutionText"));
        assertNotNull(db.one("SELECT id FROM report_events WHERE report_id=? AND event_type='ATTACHMENT_DELETED'", r));
        assertEquals(0, files());
    }

    @Test void fourthPhotoIsRefused() throws Exception {
        long r = report(student1, itDept);
        for (int i = 0; i < 3; i++) service.upload(student1, r, jpeg());
        rejected(409, "ATTACHMENT_LIMIT_EXCEEDED", () -> service.upload(student1, r, jpeg()));
        assertEquals(3, rows(r)); assertEquals(3, files());
    }

    @Test void parallelUploadsNeverExceedTheLimit() throws Exception {
        long r = report(student1, itDept);
        byte[] photo = jpeg();
        int threads = 6;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) results.add(pool.submit(() -> {
            start.await();
            try { service.upload(student1, r, photo); return "OK"; }
            catch (ApiException e) { return e.code; }
        }));
        start.countDown();
        var outcomes = new ArrayList<String>();
        for (var f : results) outcomes.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(3, Collections.frequency(outcomes, "OK"), outcomes.toString());
        assertEquals(3, Collections.frequency(outcomes, "ATTACHMENT_LIMIT_EXCEEDED"), outcomes.toString());
        assertEquals(3, rows(r)); assertEquals(3, files());
    }

    /** The early check passes, then the report is resolved while the image is processed: the locked re-check must refuse. */
    @Test void decisionIsTakenAgainUnderTheLock() throws Exception {
        long r = report(student1, itDept);
        doAnswer(inv -> {
            Object clean = inv.callRealMethod();
            db.update("UPDATE reports SET status='RESOLVED' WHERE id=?", r);
            return clean;
        }).when(sanitizer).sanitize(any());
        rejected(409, "ATTACHMENT_REPORT_CLOSED", () -> service.upload(student1, r, jpeg()));
        assertEquals(0, rows(r)); assertEquals(0, files(), "file written before the refusal was removed");
    }

    @Test void databaseFailureRemovesTheFileAndKeepsTheReport() throws Exception {
        long r = report(student1, itDept);
        doThrow(new DataAccessResourceFailureException("synthetic outage"))
            .when(db).insert(startsWith("INSERT INTO report_attachments"), any(Object[].class));
        assertThrows(DataAccessResourceFailureException.class, () -> service.upload(student1, r, jpeg()));
        assertEquals(0, rows(r)); assertEquals(0, files());
        assertNull(db.one("SELECT storage_key FROM attachment_cleanup"), "file removed at once, so nothing left to retry");
        assertEquals("RECEIVED", db.one("SELECT status FROM reports WHERE id=?", r).get("status"));
    }

    @Test void deleteKeepsFileWhenTheDatabaseStepFails() throws Exception {
        long r = report(student1, itDept);
        long a = id(service.upload(student1, r, jpeg()));
        doThrow(new DataAccessResourceFailureException("synthetic outage"))
            .when(db).insert(startsWith("INSERT INTO report_events"), any(Object[].class));
        assertThrows(DataAccessResourceFailureException.class, () -> service.deleteForStudent(student1, r, a));
        assertEquals(1, rows(r), "row delete rolled back with the failed history entry");
        assertEquals(1, files(), "photo still readable");
        assertNotNull(service.contentForStudent(student1, r, a));
    }

    @Test void invalidImageLeavesNothingBehind() throws Exception {
        long r = report(student1, itDept);
        rejected(415, "ATTACHMENT_UNSUPPORTED_TYPE", () -> service.upload(student1, r, "GIF89a".getBytes()));
        rejected(400, "ATTACHMENT_INVALID_IMAGE", () -> service.upload(student1, r, new byte[0]));
        assertEquals(0, rows(r)); assertEquals(0, files());
    }

    @Test void staffSeeOnlyReportsCurrentlyAssignedToThem() throws Exception {
        long r = report(student1, itDept);
        long a = id(service.upload(student1, r, jpeg()));

        var supervisorList = service.listForStaff(supervisor, r);
        var item = (Map<?,?>) ((List<?>) supervisorList.get("items")).getFirst();
        assertEquals("/api/admin/reports/" + r + "/attachments/" + a + "/content", item.get("contentPath"));
        assertNotNull(service.contentForStaff(supervisor, r, a));
        assertNotNull(service.contentForStaff(itStaff, r, a));
        rejected(404, "REPORT_NOT_FOUND", () -> service.listForStaff(academicStaff, r));
        rejected(404, "REPORT_NOT_FOUND", () -> service.contentForStaff(academicStaff, r, a));

        // reassignment as the supervisor's routing change would store it: the previous department loses access at once
        db.update("UPDATE reports SET assigned_department_id=? WHERE id=?", academicDept, r);
        rejected(404, "REPORT_NOT_FOUND", () -> service.contentForStaff(itStaff, r, a));
        assertNotNull(service.contentForStaff(academicStaff, r, a));

        db.update("UPDATE reports SET assigned_department_id=NULL WHERE id=?", r);
        rejected(404, "REPORT_NOT_FOUND", () -> service.listForStaff(academicStaff, r));
        assertNotNull(service.contentForStaff(supervisor, r, a), "unassigned reports stay with the supervisor");
    }

    @Test void nonStaffAndDisabledDepartmentsAreForbidden() throws Exception {
        long r = report(student1, itDept);
        long a = id(service.upload(student1, r, jpeg()));
        rejected(403, "FORBIDDEN", () -> service.contentForStaff(student1, r, a));
        db.update("UPDATE departments SET active=FALSE WHERE id=?", itDept);
        rejected(403, "FORBIDDEN", () -> service.contentForStaff(itStaff, r, a));
    }
}
