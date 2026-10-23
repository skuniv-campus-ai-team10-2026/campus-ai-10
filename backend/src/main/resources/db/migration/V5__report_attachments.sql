CREATE TABLE report_attachments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id BIGINT NOT NULL,
  uploader_id BIGINT NOT NULL,
  storage_key CHAR(36) NOT NULL,
  content_type VARCHAR(20) NOT NULL,
  size_bytes INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_attachment_report FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE,
  CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES users(id),
  CONSTRAINT uq_attachment_storage UNIQUE (storage_key),
  INDEX idx_attachment_report (report_id, created_at)
);
