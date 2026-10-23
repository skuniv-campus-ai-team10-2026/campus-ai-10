package edu.skope.attachment;

import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cleanup ledger (attachment_cleanup). A key is written down BEFORE the file work it guards and crossed out
 * only when the file is known to be gone, so a failed delete or a crash never loses track of a file.
 *
 * Protection does not rely on time alone:
 *  - a key referenced by report_attachments is a live photo and is never deleted;
 *  - a key this server is still uploading (in-flight set) is never deleted;
 *  - rows written by another server instance (e.g. before a restart) are only touched after STALE_MINUTES,
 *    in case that instance is still alive. This is why a fresh row can wait until a later attachment action.
 */
@Component
public class AttachmentCleanup {
    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanup.class);
    public static final int BATCH_LIMIT = 20;
    public static final int STALE_MINUTES = 10;
    public static final int RETRY_AFTER_SECONDS = 60;

    public enum Outcome { CLEANED, KEPT_LIVE_FILE, FAILED, SKIPPED, NOT_PENDING }

    private final String instanceId = UUID.randomUUID().toString();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Db db;
    private final AttachmentStorage storage;
    private final TransactionTemplate tx;

    public AttachmentCleanup(Db db, AttachmentStorage storage, PlatformTransactionManager transactions) {
        this.db = db; this.storage = storage;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ---- upload ----

    /** Upload step 1, committed on its own before any byte is written. The key is also marked in-flight. */
    public void registerUpload(String key) {
        inFlight.add(key);
        try {
            tx.executeWithoutResult(s -> insert(key, "UPLOAD"));
        } catch (RuntimeException e) {
            inFlight.remove(key);
            log.warn("Could not record upload {} in the cleanup ledger: {}", key, e.toString());
            throw new ApiException(503, "ATTACHMENT_STORAGE_FAILED", "사진을 저장하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }

    /** Inside the transaction that inserts the attachment row: the file now belongs to a row. */
    public void confirmUpload(String key) {
        if (db.update("DELETE FROM attachment_cleanup WHERE storage_key=?", key) != 1)
            throw new ApiException(503, "ATTACHMENT_STORAGE_FAILED", "사진을 저장하지 못했습니다. 잠시 후 다시 시도하세요.");
    }

    /** The upload request is over (success or failure); from now on retries may look at this key. */
    public void release(String key) { inFlight.remove(key); }

    // ---- delete ----

    /** Inside the transaction that deletes the attachment row, so the row and the ledger entry commit together. */
    public void registerDelete(String key) { insert(key, "DELETE"); }

    // ---- cleaning ----

    /** Try one key right now (the request that owns it). Never throws: a failure simply stays in the ledger. */
    public Outcome cleanNow(String key) {
        try {
            return process(key, true);
        } catch (RuntimeException e) {
            log.warn("Cleanup of {} deferred: {}", key, e.toString());
            return Outcome.FAILED;
        }
    }

    /** Up to BATCH_LIMIT eligible rows, oldest first. Never throws. Returns how many files were confirmed gone. */
    public int runBatch() {
        int cleaned = 0;
        try {
            var candidates = db.all("SELECT storage_key FROM attachment_cleanup "
                + "WHERE (owner_instance=? OR created_at < TIMESTAMPADD(MINUTE, ?, CURRENT_TIMESTAMP(6))) "
                + "AND (last_attempt_at IS NULL OR last_attempt_at < TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))) "
                + "ORDER BY created_at LIMIT ?", instanceId, -STALE_MINUTES, -RETRY_AFTER_SECONDS, BATCH_LIMIT);
            for (var row : candidates) {
                if (process(Db.str(row, "storageKey"), false) == Outcome.CLEANED) cleaned++;
            }
        } catch (RuntimeException e) {
            log.warn("Attachment cleanup batch stopped early: {}", e.toString());
        }
        return cleaned;
    }

    /** Startup retry: rows left by the previous run of the server (older than STALE_MINUTES). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int cleaned = runBatch();
        if (cleaned > 0) log.info("Attachment cleanup at startup removed {} leftover file(s)", cleaned);
    }

    String instanceId() { return instanceId; }

    /** Row lock first, so a retry and the request that owns the key never act on it at the same time. */
    private Outcome process(String key, boolean ownedByCaller) {
        return tx.execute(s -> {
            var row = db.one("SELECT storage_key FROM attachment_cleanup WHERE storage_key=? FOR UPDATE", key);
            if (row == null) return Outcome.NOT_PENDING;
            if (db.one("SELECT id FROM report_attachments WHERE storage_key=?", key) != null) {
                db.update("DELETE FROM attachment_cleanup WHERE storage_key=?", key);
                return Outcome.KEPT_LIVE_FILE;
            }
            if (!ownedByCaller && inFlight.contains(key)) return Outcome.SKIPPED;
            var removal = storage.remove(key);
            if (removal == AttachmentStorage.Removal.FAILED) {
                db.update("UPDATE attachment_cleanup SET attempts=attempts+1,last_error=?,last_attempt_at=CURRENT_TIMESTAMP(6) WHERE storage_key=?",
                    "file delete failed", key);
                return Outcome.FAILED;
            }
            db.update("DELETE FROM attachment_cleanup WHERE storage_key=?", key);
            return Outcome.CLEANED;
        });
    }

    private void insert(String key, String reason) {
        db.update("INSERT INTO attachment_cleanup(storage_key,reason,owner_instance) VALUES(?,?,?)", key, reason, instanceId);
    }
}
