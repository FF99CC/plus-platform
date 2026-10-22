-- Additive, repeatable project foundation schema. No legacy tables are read or altered.
CREATE TABLE IF NOT EXISTS pm_project (
 id BIGINT NOT NULL PRIMARY KEY,
 name VARCHAR(200) NOT NULL,
 customer_name VARCHAR(200) NOT NULL,
 contact_name VARCHAR(100), contact_phone VARCHAR(50), location VARCHAR(500) NOT NULL,
 project_type VARCHAR(100), acquisition_mode VARCHAR(20) NOT NULL,
 estimated_amount DECIMAL(18,2), owner_id BIGINT NOT NULL, owner_name VARCHAR(100) NOT NULL,
 stage VARCHAR(32) NOT NULL,
 bid_name VARCHAR(200), bid_type VARCHAR(100), bid_deadline DATE, opening_date DATE,
 bid_amount DECIMAL(18,2), bid_result VARCHAR(10), bid_remark VARCHAR(2000),
 manager_name VARCHAR(100), project_amount DECIMAL(18,2), planned_start DATE, planned_end DATE,
 termination_reason VARCHAR(2000), created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 KEY idx_pm_project_owner_stage(owner_id, stage), KEY idx_pm_project_stage(stage, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS pm_application (
 id BIGINT NOT NULL PRIMARY KEY, project_id BIGINT NOT NULL, project_name VARCHAR(200) NOT NULL,
 type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL,
 submitted_by BIGINT NOT NULL, submitted_at DATETIME(3) NOT NULL,
 finished_at DATETIME(3), comment VARCHAR(2000), task_id BIGINT, instance_id BIGINT,
 snapshot JSON NOT NULL,
 KEY idx_pm_application_project(project_id, submitted_at),
 KEY idx_pm_application_status(status, submitted_at),
 CONSTRAINT fk_pm_application_project FOREIGN KEY (project_id) REFERENCES pm_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS pm_event (
 id BIGINT NOT NULL PRIMARY KEY, project_id BIGINT NOT NULL,
 type VARCHAR(40) NOT NULL, message VARCHAR(2000) NOT NULL,
 actor_id BIGINT NOT NULL, actor_name VARCHAR(100) NOT NULL, created_at DATETIME(3) NOT NULL,
 KEY idx_pm_event_project(project_id, created_at),
 CONSTRAINT fk_pm_event_project FOREIGN KEY (project_id) REFERENCES pm_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS pm_task (
 id BIGINT NOT NULL PRIMARY KEY, project_id BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL, kind VARCHAR(30) NOT NULL, assignee_name VARCHAR(100),
 due_date DATE, status VARCHAR(10) NOT NULL, note VARCHAR(2000),
 KEY idx_pm_task_project(project_id),
 CONSTRAINT fk_pm_task_project FOREIGN KEY (project_id) REFERENCES pm_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
