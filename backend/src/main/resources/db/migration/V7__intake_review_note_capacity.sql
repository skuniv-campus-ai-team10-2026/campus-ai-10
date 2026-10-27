-- Preserve both bounded student replies and server review context. Public manual input remains limited to 1000.
ALTER TABLE reports MODIFY COLUMN review_note VARCHAR(2000) NULL;
