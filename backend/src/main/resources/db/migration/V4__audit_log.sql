CREATE TABLE audit_log(id VARCHAR(36) PRIMARY KEY,actor_id VARCHAR(36),actor VARCHAR(100) NOT NULL,action VARCHAR(50) NOT NULL,target VARCHAR(300) NOT NULL,created_at VARCHAR(40) NOT NULL,payload LONGTEXT NOT NULL);
CREATE INDEX audit_created ON audit_log(created_at,id);
CREATE INDEX audit_actor ON audit_log(actor,created_at);
