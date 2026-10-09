CREATE TABLE site_content(id VARCHAR(40) PRIMARY KEY,payload TEXT NOT NULL,revision BIGINT NOT NULL,updated_at VARCHAR(40) NOT NULL);
CREATE TABLE site_content_history(revision BIGINT PRIMARY KEY,payload TEXT NOT NULL,updated_at VARCHAR(40) NOT NULL);
