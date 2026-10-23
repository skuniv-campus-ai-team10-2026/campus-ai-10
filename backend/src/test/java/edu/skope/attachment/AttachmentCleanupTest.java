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
import org.springframework.transaction.PlatformTransactionManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cleanup ledger against a real database (H2, or MySQL when TEST_DB_URL is set) and a throwaway photo folder.
 * Failures are injected with spies; a "restart" is a second AttachmentCleanup instance with a new instance id,
 * not a killed process (see docs/report-attachments.md for what was checked with a real restart).
 */
@SpringBootTest
class AttachmentCleanupTest {
    static final Path STORE;
    static { try { STORE = Files.createTempDirectory("skope-attachments-cleanup"); } catch (Exception e) { throw new IllegalStateException(e); } }

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:h2:mem:attachment_cleanup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        p.add("DEMO_PASSWORD", () -> UUID.randomUUID().toString());
        p.add("AI_MODE", () -> "DEMO"); p.add("EMBEDDING_MODE", () -> "RULE_ONLY");
        p.add("app.attachments.dir", STORE::toString);
    }

    @Autowired AttachmentService service;
    @Autowired AttachmentCleanup cleanup;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean Db db;
    @MockitoSpyBean AttachmentStorage storage;
    long student;

    @AfterAll static void removeStore() throws Exception {
        try (var files = Files.walk(STORE)) { files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); }
    }

    @BeforeEach void clean() throws Exception {
        db.update("DELETE FROM attachment_cleanup");
        db.update("DELETE FROM report_attachments"); db.update("DELETE FROM report_events"); db.update("DELETE FROM similar_pairs");
        db.update("DELETE FROM reports"); db.update("UPDATE incidents SET previous_incident_id=NULL"); db.update("DELETE FROM incidents");
        try (var files = Files.list(STORE)) { for (Path f : files.toList()) Files.delete(f); }
        student = Db.id(db.one("SELECT id FROM users WHERE username='student1'"), "id");
        db.update("UPDATE users SET active=TRUE,role='STUDENT',department_id=NULL WHERE id=?", student);
    }

    long report() {
        return db.insert("INSERT INTO reports(student_id,client_request_id,original_text,category,subcategory,status) VALUES(?,?,?,?,?,?)",
            student, UUID.randomUUID().toString(), "합성 신고: 북악관 502호 와이파이 끊김", "IT", "WIFI", "RECEIVED");
    }
    static byte[] jpeg() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }
    static long files() throws Exception { try (var f = Files.list(STORE)) { return f.count(); } }
    Map<String,Object> ledger(String key) { return db.one("SELECT * FROM attachment_cleanup WHERE storage_key=?", key); }
    long ledgerSize() { return Db.id(db.one("SELECT COUNT(*) c FROM attachment_cleanup"), "c"); }
    String keyOf(long attachmentId) { return Db.str(db.one("SELECT storage_key FROM report_attachments WHERE id=?", attachmentId), "storageKey"); }
    /** Pretend the last failed attempt was long enough ago for the retry back-off to pass. */
    void backOffPassed(String key) { db.update("UPDATE attachment_cleanup SET last_attempt_at=TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP(6)) WHERE storage_key=?", key); }
    /** A file plus ledger row, the state a server stopped between "ledger committed" and "row committed" leaves behind. */
    String leftover(String owner, int minutesAgo, boolean withFile) {
        String key = storage.newKey();
        if (withFile) storage.write(key, new byte[]{1, 2, 3});
        db.update("INSERT INTO attachment_cleanup(storage_key,reason,owner_instance,created_at) VALUES(?,?,?,TIMESTAMPADD(MINUTE, ?, CURRENT_TIMESTAMP(6)))",
            key, "UPLOAD", owner, -minutesAgo);
        return key;
    }

    @Test void failedFileDeleteAfterRemovingTheRowIsTrackedAndRetried() throws Exception {
        long r = report();
        long a = ((Number) service.upload(student, r, jpeg()).get("id")).longValue();
        String key = keyOf(a);
        doReturn(AttachmentStorage.Removal.FAILED).when(storage).remove(key);

        service.deleteForStudent(student, r, a);   // the photo is gone for everyone, so no error for the student
        assertNull(db.one("SELECT id FROM report_attachments WHERE id=?", a));
        assertNotNull(db.one("SELECT id FROM report_events WHERE report_id=? AND event_type='ATTACHMENT_DELETED'", r));
        var entry = ledger(key);
        assertEquals("DELETE", entry.get("reason"));
        assertEquals(1, ((Number) entry.get("attempts")).intValue());
        assertNotNull(entry.get("lastError")); assertNotNull(entry.get("lastAttemptAt"));
        assertTrue(storage.exists(key), "file is still on disk, but now it is on the ledger");
        assertEquals(404, assertThrows(ApiException.class, () -> service.deleteForStudent(student, r, a)).status, "API delete again is 404; cleanup is the server's job");

        cleanup.runBatch();
        assertEquals(1, ((Number) ledger(key).get("attempts")).intValue(), "a fresh failure waits for the back-off");

        doCallRealMethod().when(storage).remove(key);
        backOffPassed(key);
        assertEquals(1, cleanup.runBatch());
        assertFalse(storage.exists(key)); assertNull(ledger(key));
    }

    @Test void failedCleanupAfterUploadDatabaseFailureIsTrackedAndRetried() throws Exception {
        long r = report();
        doThrow(new DataAccessResourceFailureException("synthetic outage"))
            .when(db).insert(startsWith("INSERT INTO report_attachments"), any(Object[].class));
        doReturn(AttachmentStorage.Removal.FAILED).when(storage).remove(anyString());

        assertThrows(DataAccessResourceFailureException.class, () -> service.upload(student, r, jpeg()));
        assertNull(db.one("SELECT id FROM report_attachments WHERE report_id=?", r));
        assertEquals("RECEIVED", db.one("SELECT status FROM reports WHERE id=?", r).get("status"), "report untouched");
        assertEquals(1, ledgerSize()); assertEquals(1, files());
        String key = Db.str(db.one("SELECT storage_key FROM attachment_cleanup"), "storageKey");
        assertEquals("UPLOAD", ledger(key).get("reason"));
        assertEquals(1, ((Number) ledger(key).get("attempts")).intValue());

        doCallRealMethod().when(storage).remove(anyString());
        backOffPassed(key);
        assertEquals(1, cleanup.runBatch());
        assertEquals(0, ledgerSize()); assertEquals(0, files());
    }

    @Test void restartedServerRemovesOldLeftoversAndLaterActionsRemoveTheRest() throws Exception {
        String previousRun = UUID.randomUUID().toString();
        String old = leftover(previousRun, 30, true), recent = leftover(previousRun, 2, true);

        var restarted = new AttachmentCleanup(db, storage, transactions);   // new instance id, like a new process
        restarted.onStartup();
        assertFalse(storage.exists(old)); assertNull(ledger(old));
        assertTrue(storage.exists(recent), "documented limit: younger than 10 minutes waits");
        assertNotNull(ledger(recent));

        // reprocessing: once it is older than 10 minutes, the next attachment action picks it up
        db.update("UPDATE attachment_cleanup SET created_at=TIMESTAMPADD(MINUTE, -11, CURRENT_TIMESTAMP(6)) WHERE storage_key=?", recent);
        service.upload(student, report(), jpeg());
        assertFalse(storage.exists(recent)); assertNull(ledger(recent));
    }

    @Test void liveAttachmentFilesAreNeverDeletedByTheLedger() throws Exception {
        long r = report();
        long a = ((Number) service.upload(student, r, jpeg()).get("id")).longValue();
        String key = keyOf(a);
        db.update("INSERT INTO attachment_cleanup(storage_key,reason,owner_instance,created_at) VALUES(?,?,?,TIMESTAMPADD(MINUTE, -30, CURRENT_TIMESTAMP(6)))",
            key, "UPLOAD", UUID.randomUUID().toString());

        new AttachmentCleanup(db, storage, transactions).onStartup();
        assertTrue(storage.exists(key), "referenced by report_attachments, so kept");
        assertNull(ledger(key), "stray ledger entry is dropped instead");
        assertNotNull(service.contentForStudent(student, r, a).bytes());
    }

    @Test void uploadInProgressIsProtectedEvenWhenItsEntryLooksOld() throws Exception {
        long r = report();
        var cleanedDuringUpload = new ArrayList<Integer>();
        doAnswer(inv -> {
            inv.callRealMethod();
            String key = inv.getArgument(0);
            db.update("UPDATE attachment_cleanup SET created_at=TIMESTAMPADD(MINUTE, -30, CURRENT_TIMESTAMP(6)) WHERE storage_key=?", key);
            cleanedDuringUpload.add(cleanup.runBatch());   // this server: protected by the in-flight set, not by time
            assertTrue(storage.exists(key), "file being uploaded must survive");
            return null;
        }).when(storage).write(anyString(), any(byte[].class));

        var view = service.upload(student, r, jpeg());
        assertEquals(List.of(0), cleanedDuringUpload);
        assertNotNull(service.contentForStudent(student, r, ((Number) view.get("id")).longValue()).bytes());
        assertEquals(0, ledgerSize());
    }

    /**
     * Documented multi-server limit: another instance cannot see this server's in-flight set and only waits for
     * the 10-minute rule. Even when it does take the entry, the upload fails cleanly instead of saving a row
     * whose file is gone, because the attachment row and the ledger removal must commit together.
     */
    @Test void entryTakenByAnotherServerMakesTheUploadFailWithoutABrokenRow() throws Exception {
        long r = report();
        doAnswer(inv -> {
            inv.callRealMethod();
            String key = inv.getArgument(0);
            db.update("UPDATE attachment_cleanup SET created_at=TIMESTAMPADD(MINUTE, -30, CURRENT_TIMESTAMP(6)) WHERE storage_key=?", key);
            new AttachmentCleanup(db, storage, transactions).runBatch();
            return null;
        }).when(storage).write(anyString(), any(byte[].class));

        var e = assertThrows(ApiException.class, () -> service.upload(student, r, jpeg()));
        assertEquals(503, e.status); assertEquals("ATTACHMENT_STORAGE_FAILED", e.code);
        assertNull(db.one("SELECT id FROM report_attachments WHERE report_id=?", r), "no row pointing at a missing file");
        assertEquals(0, ledgerSize()); assertEquals(0, files());
    }

    @Test void oneRunHandlesAtMostTheBatchLimit() throws Exception {
        String previousRun = UUID.randomUUID().toString();
        for (int i = 0; i < AttachmentCleanup.BATCH_LIMIT + 5; i++) leftover(previousRun, 30, true);
        assertEquals(AttachmentCleanup.BATCH_LIMIT, cleanup.runBatch());
        assertEquals(5, ledgerSize()); assertEquals(5, files());
        assertEquals(5, cleanup.runBatch());
        assertEquals(0, ledgerSize()); assertEquals(0, files());
    }

    @Test void entryWhoseFileIsAlreadyGoneCountsAsCleaned() {
        String key = leftover(UUID.randomUUID().toString(), 30, false);
        assertEquals(1, cleanup.runBatch());
        assertNull(ledger(key));
    }
}
