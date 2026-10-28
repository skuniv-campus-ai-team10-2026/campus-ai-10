-- Handling area (small fixed set), free-text target object and campus relevance. All nullable:
-- existing rows are not reclassified; their work group is derived from subcategory when read.
ALTER TABLE reports ADD COLUMN work_group VARCHAR(32) NULL;
ALTER TABLE reports ADD COLUMN target_text VARCHAR(100) NULL;
ALTER TABLE reports ADD COLUMN campus_relevance VARCHAR(24) NULL;
