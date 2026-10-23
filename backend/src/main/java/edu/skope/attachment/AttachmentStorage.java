package edu.skope.attachment;

import edu.skope.service.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Local file storage for attachment bytes. It knows nothing about reports or users: callers decide who may
 * touch a key. Files are named only by server-generated UUID keys and live outside every static resource path.
 */
@Component
public class AttachmentStorage {
    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String PARTIAL = ".part";
    private final Path root;

    public AttachmentStorage(@Value("${app.attachments.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        removePartialFiles();
    }

    public String newKey() { return UUID.randomUUID().toString(); }

    /** Writes to key.part first and renames it when complete, so a failure never leaves a half-written photo. */
    public void write(String key, byte[] bytes) {
        Path target = path(key);
        Path partial = root.resolve(key + PARTIAL);
        try {
            Files.createDirectories(root);
            Files.write(partial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // A plain rename inside one folder; unlike ATOMIC_MOVE on Windows it refuses to replace an existing file.
            Files.move(partial, target);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partial);
            log.warn("Attachment write failed for key {}: {}", key, e.toString());
            throw new ApiException(503, "ATTACHMENT_STORAGE_FAILED", "사진을 저장하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(path(key));
        } catch (NoSuchFileException e) {
            log.warn("Attachment file missing for key {}", key);
            throw new ApiException(404, "ATTACHMENT_NOT_FOUND", "사진을 찾을 수 없습니다.");
        } catch (IOException e) {
            throw new ApiException(503, "ATTACHMENT_STORAGE_FAILED", "사진을 불러오지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }

    /** DELETED and ALREADY_GONE both mean "the file is gone"; only FAILED needs another attempt. */
    public enum Removal { DELETED, ALREADY_GONE, FAILED }

    /** Never throws: the caller decides what a FAILED result means (AttachmentCleanup keeps it for retry). */
    public Removal remove(String key) {
        Path file;
        try {
            file = path(key);
        } catch (IllegalArgumentException e) {
            return Removal.FAILED;
        }
        try {
            return Files.deleteIfExists(file) ? Removal.DELETED : Removal.ALREADY_GONE;
        } catch (IOException e) {
            log.warn("Attachment file could not be deleted: {} ({})", key, e.toString());
            return Removal.FAILED;
        }
    }

    public boolean exists(String key) { return Files.isRegularFile(path(key)); }

    Path root() { return root; }

    /** Only well-formed UUID keys map to a path, and the result must stay inside the storage root. */
    Path path(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid attachment key");
        Path resolved = root.resolve(key).normalize();
        if (!resolved.getParent().equals(root)) throw new IllegalArgumentException("Attachment key escapes storage root");
        return resolved;
    }

    private boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Attachment file could not be deleted: {} ({})", file.getFileName(), e.toString());
            return false;
        }
    }

    /** A server stopped mid-upload leaves *.part files behind; they never belong to any attachment row. */
    private void removePartialFiles() {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.newDirectoryStream(root, "*" + PARTIAL)) {
            for (Path file : files) deleteQuietly(file);
        } catch (IOException e) {
            log.warn("Could not scan attachment storage for partial files: {}", e.toString());
        }
    }
}
