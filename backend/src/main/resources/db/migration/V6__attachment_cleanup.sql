-- Files that may need removing. A row is committed BEFORE the file work it guards and removed only once the
-- file is known to be gone (deleted or already absent), so a failed delete or a crash never loses track of a file.
CREATE TABLE attachment_cleanup (
  storage_key CHAR(36) NOT NULL PRIMARY KEY,
  reason VARCHAR(16) NOT NULL,
  owner_instance CHAR(36) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(255),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_attempt_at DATETIME(6),
  INDEX idx_cleanup_created (created_at)
);
