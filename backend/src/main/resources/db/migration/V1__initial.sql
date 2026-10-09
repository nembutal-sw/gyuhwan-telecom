CREATE TABLE quote_config(id VARCHAR(40) PRIMARY KEY,payload TEXT NOT NULL,revision BIGINT NOT NULL,updated_at VARCHAR(40) NOT NULL);
CREATE TABLE config_history(revision BIGINT PRIMARY KEY,payload TEXT NOT NULL,updated_at VARCHAR(40) NOT NULL);
CREATE TABLE inquiries(id VARCHAR(80) PRIMARY KEY,owner_id VARCHAR(200),name VARCHAR(40) NOT NULL,title VARCHAR(100) NOT NULL,body TEXT NOT NULL,reply TEXT,revision BIGINT NOT NULL DEFAULT 0,created_at VARCHAR(40) NOT NULL,replied_at VARCHAR(40),privacy_version VARCHAR(80),consented_at VARCHAR(40));
CREATE TABLE consultations(id VARCHAR(80) PRIMARY KEY,name VARCHAR(40) NOT NULL,phone VARCHAR(30) NOT NULL,summary TEXT NOT NULL,created_at VARCHAR(40) NOT NULL,privacy_version VARCHAR(80),consented_at VARCHAR(40));
CREATE INDEX inquiry_created ON inquiries(created_at,id);
CREATE INDEX consultation_created ON consultations(created_at,id);
