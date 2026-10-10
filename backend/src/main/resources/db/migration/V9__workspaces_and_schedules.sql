-- 임시 작업은 계정별로 격리하며 공개 가격/콘텐츠 테이블과 분리한다.
CREATE TABLE admin_workspaces (
 account_id VARCHAR(100) NOT NULL,
 kind VARCHAR(20) NOT NULL,
 base_revision BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL,
 updated_at VARCHAR(30) NOT NULL,
 PRIMARY KEY (account_id, kind)
);

-- 모든 앱 인스턴스는 quote_config 행 잠금을 공유해 예약을 한 번만 적용한다.
CREATE TABLE policy_schedules (
 id VARCHAR(36) PRIMARY KEY,
 title VARCHAR(120) NOT NULL,
 account_id VARCHAR(100) NOT NULL,
 created_by VARCHAR(100) NOT NULL,
 starts_at VARCHAR(30) NOT NULL,
 ends_at VARCHAR(30),
 status VARCHAR(30) NOT NULL,
 base_revision BIGINT NOT NULL,
 applied_revision BIGINT,
 payload LONGTEXT NOT NULL,
 return_payload LONGTEXT NOT NULL,
 note VARCHAR(1000) NOT NULL,
 created_at VARCHAR(30) NOT NULL,
 updated_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_policy_schedule_due ON policy_schedules(status, starts_at);
