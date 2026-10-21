package edu.skope.attachment;

import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import edu.skope.service.DepartmentAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who may touch which photo, and keeping the database row and the file in step.
 * Students: only their own reports. Staff: whatever DepartmentAccess allows right now (re-read from the DB each call).
 * Every lookup uses report id AND attachment id together, so a foreign attachment id behind an own report id is 404.
 */
@Service
public class AttachmentService {
    public static final int MAX_PER_REPORT = 3;
    private final Db db;
    private final DepartmentAccess access;
    private final ImageSanitizer sanitizer;
    private final AttachmentStorage storage;
    private final TransactionTemplate tx;

    public AttachmentService(Db db, DepartmentAccess access, ImageSanitizer sanitizer, AttachmentStorage storage,
                             PlatformTransactionManager transactions) {
        this.db = db; this.access = access; this.sanitizer = sanitizer; this.storage = storage;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Content(byte[] bytes, String contentType) {}

    // ---- student ----

    public Map<String,Object> upload(long studentId, long reportId, byte[] file) {
        // 1) cheap early answer, so a refused upload does not spend time on image work
        checkCanAdd(ownReport(studentId, reportId, false), reportId);
        // 2) slow image work outside any lock
        var clean = sanitizer.sanitize(file);
        // 3) file first ...
        String key = storage.newKey();
        storage.write(key, clean.bytes());
        long id;
        try {
            // 4) ... then the row, deciding again while holding the report lock so parallel uploads queue up
            id = tx.execute(status -> {
                checkCanAdd(ownReport(studentId, reportId, true), reportId);
                return db.insert("INSERT INTO report_attachments(report_id,uploader_id,storage_key,content_type,size_bytes,width,height) VALUES(?,?,?,?,?,?,?)",
                    reportId, studentId, key, clean.contentType(), clean.bytes().length, clean.width(), clean.height());
            });
        } catch (RuntimeException e) {
            // 5) any refusal, SQL error or failed commit: the file must not outlive the missing row
            storage.delete(key);
            throw e;
        }
        // committed: from here on the file belongs to a row and is never cleaned up by this method
        return view(row(reportId, id), false);
    }

    public Map<String,Object> listForStudent(long studentId, long reportId) {
        ownReport(studentId, reportId, false);
        return list(reportId, false);
    }

    public Content contentForStudent(long studentId, long reportId, long attachmentId) {
        ownReport(studentId, reportId, false);
        return content(row(reportId, attachmentId));
    }

    /** Allowed after RESOLVED too. The row goes in the same transaction as the history entry; the file goes after commit. */
    public void deleteForStudent(long studentId, long reportId, long attachmentId) {
        String key = tx.execute(status -> {
            ownReport(studentId, reportId, true);
            var attachment = row(reportId, attachmentId);
            if (Db.id(attachment, "uploaderId") != studentId) throw notFound();
            db.update("DELETE FROM report_attachments WHERE id=? AND report_id=?", attachmentId, reportId);
            db.insert("INSERT INTO report_events(report_id,actor_id,event_type,old_value,new_value,note) VALUES(?,?,?,?,?,?)",
                reportId, studentId, "ATTACHMENT_DELETED", String.valueOf(attachmentId), null, null);
            return Db.str(attachment, "storageKey");
        });
        storage.delete(key);
    }

    // ---- staff (supervisor or department) ----

    public Map<String,Object> listForStaff(long actorId, long reportId) {
        access.requireReport(actorId, reportId);
        return list(reportId, true);
    }

    public Content contentForStaff(long actorId, long reportId, long attachmentId) {
        access.requireReport(actorId, reportId);
        return content(row(reportId, attachmentId));
    }

    // ---- helpers ----

    private Map<String,Object> ownReport(long studentId, long reportId, boolean lock) {
        var report = db.one("SELECT id,status FROM reports WHERE id=? AND student_id=?" + (lock ? " FOR UPDATE" : ""), reportId, studentId);
        if (report == null) throw new ApiException(404, "REPORT_NOT_FOUND", "신고를 찾을 수 없습니다.");
        return report;
    }

    private void checkCanAdd(Map<String,Object> report, long reportId) {
        if ("RESOLVED".equals(report.get("status")))
            throw new ApiException(409, "ATTACHMENT_REPORT_CLOSED", "처리가 완료된 신고에는 사진을 추가할 수 없습니다.");
        long count = Db.id(db.one("SELECT COUNT(*) c FROM report_attachments WHERE report_id=?", reportId), "c");
        if (count >= MAX_PER_REPORT)
            throw new ApiException(409, "ATTACHMENT_LIMIT_EXCEEDED", "사진은 신고 하나에 " + MAX_PER_REPORT + "장까지 올릴 수 있습니다.");
    }

    private Map<String,Object> row(long reportId, long attachmentId) {
        var row = db.one("SELECT * FROM report_attachments WHERE id=? AND report_id=?", attachmentId, reportId);
        if (row == null) throw notFound();
        return row;
    }

    private Map<String,Object> list(long reportId, boolean staff) {
        List<Map<String,Object>> items = db.all("SELECT * FROM report_attachments WHERE report_id=? ORDER BY created_at,id", reportId)
            .stream().map(r -> view(r, staff)).toList();
        return Map.of("items", items, "maxCount", MAX_PER_REPORT);
    }

    private Content content(Map<String,Object> row) {
        return new Content(storage.read(Db.str(row, "storageKey")), Db.str(row, "contentType"));
    }

    /** The storage key and uploader never leave the server. */
    private static Map<String,Object> view(Map<String,Object> r, boolean staff) {
        long reportId = Db.id(r, "reportId"), id = Db.id(r, "id");
        var out = new LinkedHashMap<String,Object>();
        out.put("id", id);
        out.put("reportId", reportId);
        out.put("contentType", r.get("contentType"));
        out.put("sizeBytes", r.get("sizeBytes"));
        out.put("width", r.get("width"));
        out.put("height", r.get("height"));
        out.put("createdAt", r.get("createdAt"));
        out.put("contentPath", (staff ? "/api/admin/reports/" : "/api/reports/") + reportId + "/attachments/" + id + "/content");
        return out;
    }

    private static ApiException notFound() {
        return new ApiException(404, "ATTACHMENT_NOT_FOUND", "사진을 찾을 수 없습니다.");
    }
}
