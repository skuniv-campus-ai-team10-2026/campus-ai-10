-- Work-group routing rules share routing_rules (same department, verification and demo labels):
--   type rule        category/subcategory = report type, target_key = ''
--   group default    category = 'WORK_GROUP', subcategory = work group, target_key = ''
--   group exception  category = 'WORK_GROUP', subcategory = work group, target_key = normalized target name
-- Existing rows keep target_key '' and their (category, subcategory) uniqueness.
ALTER TABLE routing_rules ADD COLUMN target_key VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE routing_rules DROP CONSTRAINT uq_route_type;
ALTER TABLE routing_rules ADD CONSTRAINT uq_route_key UNIQUE (category, subcategory, target_key);
