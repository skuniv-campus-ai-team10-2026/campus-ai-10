CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(60) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  role VARCHAR(16) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE departments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  description VARCHAR(255),
  source_url VARCHAR(500),
  verified_at DATETIME(6),
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE routing_rules (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  category VARCHAR(32) NOT NULL,
  subcategory VARCHAR(50) NOT NULL,
  work_name VARCHAR(200) NOT NULL,
  department_id BIGINT NOT NULL,
  contact_phone VARCHAR(80),
  contact_email VARCHAR(120),
  source_url VARCHAR(500),
  verified_at DATETIME(6),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_route_dept FOREIGN KEY (department_id) REFERENCES departments(id),
  CONSTRAINT uq_route_type UNIQUE (category, subcategory)
);

CREATE TABLE reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  student_id BIGINT NOT NULL,
  client_request_id CHAR(36) NOT NULL,
  original_text TEXT NOT NULL,
  category VARCHAR(32) NOT NULL,
  subcategory VARCHAR(50) NOT NULL,
  location_text VARCHAR(200),
  building VARCHAR(100),
  floor VARCHAR(20),
  symptom VARCHAR(500),
  occurred_at_text VARCHAR(120),
  routing_rule_id BIGINT,
  assigned_department_id BIGINT,
  route_status VARCHAR(20) NOT NULL DEFAULT 'NEEDS_REVIEW',
  status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  emergency_hint BOOLEAN NOT NULL DEFAULT FALSE,
  resolution_text TEXT,
  embedding_json JSON,
  embedding_model VARCHAR(80),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_report_student FOREIGN KEY (student_id) REFERENCES users(id),
  CONSTRAINT fk_report_route FOREIGN KEY (routing_rule_id) REFERENCES routing_rules(id),
  CONSTRAINT fk_report_dept FOREIGN KEY (assigned_department_id) REFERENCES departments(id),
  CONSTRAINT uq_report_request UNIQUE (student_id, client_request_id),
  INDEX idx_report_search (category, subcategory, building, floor, created_at),
  INDEX idx_report_owner (student_id, created_at),
  INDEX idx_report_status (status, created_at)
);

CREATE TABLE report_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  old_value VARCHAR(100),
  new_value VARCHAR(100),
  note TEXT,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_event_report FOREIGN KEY (report_id) REFERENCES reports(id),
  CONSTRAINT fk_event_actor FOREIGN KEY (actor_id) REFERENCES users(id),
  INDEX idx_event_report (report_id, created_at)
);

CREATE TABLE similar_pairs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  left_report_id BIGINT NOT NULL,
  right_report_id BIGINT NOT NULL,
  similarity_score DECIMAL(5,4),
  reason_text VARCHAR(255),
  decision VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
  reviewed_by BIGINT,
  reviewed_at DATETIME(6),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_pair_left FOREIGN KEY (left_report_id) REFERENCES reports(id),
  CONSTRAINT fk_pair_right FOREIGN KEY (right_report_id) REFERENCES reports(id),
  CONSTRAINT fk_pair_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
  CONSTRAINT uq_report_pair UNIQUE (left_report_id, right_report_id),
  INDEX idx_pair_decision (decision, created_at)
);

