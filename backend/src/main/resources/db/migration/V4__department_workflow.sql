ALTER TABLE users ADD COLUMN department_id BIGINT NULL;
ALTER TABLE users ADD CONSTRAINT fk_user_department FOREIGN KEY (department_id) REFERENCES departments(id);
CREATE INDEX idx_user_department ON users (department_id, role, active);

ALTER TABLE routing_rules ADD COLUMN demo_only BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_report_department ON reports (assigned_department_id, status, created_at);
