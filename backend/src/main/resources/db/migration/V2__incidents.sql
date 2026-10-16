CREATE TABLE incidents (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  category VARCHAR(32) NOT NULL,
  subcategory VARCHAR(50) NOT NULL,
  building VARCHAR(100),
  floor VARCHAR(20),
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  previous_incident_id BIGINT NULL,
  recurrence_decision VARCHAR(20) NOT NULL DEFAULT 'NOT_EVALUATED',
  recurrence_reviewed_by BIGINT NULL,
  recurrence_reviewed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  closed_at DATETIME(6) NULL,
  CONSTRAINT fk_incident_previous FOREIGN KEY (previous_incident_id) REFERENCES incidents(id),
  CONSTRAINT fk_incident_reviewer FOREIGN KEY (recurrence_reviewed_by) REFERENCES users(id),
  INDEX idx_incident_lookup (category, subcategory, building, floor, closed_at)
);

ALTER TABLE reports ADD COLUMN occurred_on DATE NULL;
ALTER TABLE reports ADD COLUMN incident_id BIGINT NULL;
ALTER TABLE reports ADD CONSTRAINT fk_report_incident FOREIGN KEY (incident_id) REFERENCES incidents(id);
CREATE INDEX idx_report_incident ON reports (incident_id);

