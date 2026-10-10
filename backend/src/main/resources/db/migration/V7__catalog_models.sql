-- 원본 상품 ID는 유지하고 신규 ID 발급·모델 수정을 하나의 카탈로그 잠금으로 보호한다.
CREATE TABLE catalog_state(id VARCHAR(20) PRIMARY KEY,revision BIGINT NOT NULL,next_id INT NOT NULL);
INSERT INTO catalog_state(id,revision,next_id) VALUES('main',1,48);
CREATE TABLE catalog_model(id INT PRIMARY KEY,payload LONGTEXT NOT NULL,created_model BOOLEAN NOT NULL,updated_at VARCHAR(40) NOT NULL);
