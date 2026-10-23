package edu.skope.attachment;

import edu.skope.controller.AuthController;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HTTP layer only: who is calling and which single file arrived. Every access rule lives in AttachmentService.
 * Role checks (/api/reports/** students, /api/admin/** staff) and CSRF come from the existing SecurityConfig.
 */
@RestController
@RequestMapping("/api")
public class AttachmentController {
    private final AttachmentService attachments;
    private final AuthController auth;

    public AttachmentController(AttachmentService attachments, AuthController auth) {
        this.attachments = attachments; this.auth = auth;
    }

    private long uid(Authentication a) { return Db.id(auth.user(a), "id"); }

    // ---- student ----

    @PostMapping("/reports/{reportId}/attachments")
    public ResponseEntity<Map<String,Object>> upload(Authentication a, @PathVariable long reportId,
                                                     @RequestParam(name = "file", required = false) List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.getFirst().isEmpty())
            throw new ApiException(400, "VALIDATION_ERROR", "사진 파일(file)을 선택하세요.");
        if (files.size() > 1)
            throw new ApiException(400, "ATTACHMENT_ONE_PER_REQUEST", "사진은 한 번에 한 장씩 올려 주세요.");
        byte[] bytes;
        try { bytes = files.getFirst().getBytes(); }
        catch (IOException e) { throw new ApiException(400, "ATTACHMENT_INVALID_IMAGE", "사진 파일을 읽을 수 없습니다. 다른 사진을 올려 주세요."); }
        return ResponseEntity.status(201).body(attachments.upload(uid(a), reportId, bytes));
    }

    @GetMapping("/reports/{reportId}/attachments")
    public Map<String,Object> list(Authentication a, @PathVariable long reportId) {
        return attachments.listForStudent(uid(a), reportId);
    }

    @GetMapping("/reports/{reportId}/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> content(Authentication a, @PathVariable long reportId, @PathVariable long attachmentId) {
        return image(attachments.contentForStudent(uid(a), reportId, attachmentId), attachmentId);
    }

    @DeleteMapping("/reports/{reportId}/attachments/{attachmentId}")
    public ResponseEntity<Void> delete(Authentication a, @PathVariable long reportId, @PathVariable long attachmentId) {
        attachments.deleteForStudent(uid(a), reportId, attachmentId);
        return ResponseEntity.noContent().build();
    }

    // ---- supervisor / department staff ----

    @GetMapping("/admin/reports/{reportId}/attachments")
    public Map<String,Object> staffList(Authentication a, @PathVariable long reportId) {
        return attachments.listForStaff(uid(a), reportId);
    }

    @GetMapping("/admin/reports/{reportId}/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> staffContent(Authentication a, @PathVariable long reportId, @PathVariable long attachmentId) {
        return image(attachments.contentForStaff(uid(a), reportId, attachmentId), attachmentId);
    }

    /** Server-chosen name, no caching outside this browser tab, and nothing in the response may run as a page. */
    private static ResponseEntity<byte[]> image(AttachmentService.Content content, long attachmentId) {
        String extension = "image/png".equals(content.contentType()) ? "png" : "jpg";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType()))
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header("Content-Disposition", ContentDisposition.inline().filename("attachment-" + attachmentId + "." + extension).build().toString())
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .body(content.bytes());
    }
}
