CREATE TABLE media_asset(id VARCHAR(36) PRIMARY KEY,file_name VARCHAR(80) NOT NULL UNIQUE,original_name VARCHAR(200) NOT NULL,title VARCHAR(200) NOT NULL,bytes BIGINT NOT NULL,created_at VARCHAR(40) NOT NULL,deleted_at VARCHAR(40));
CREATE INDEX media_asset_created ON media_asset(created_at,id);
-- 방문자별 행을 만들지 않는다. 서울 날짜와 모델별 클릭 수만 합산한다.
CREATE TABLE interest_daily(stat_date VARCHAR(10) NOT NULL,model_id INT NOT NULL,model_name VARCHAR(200) NOT NULL,view_count BIGINT NOT NULL DEFAULT 0,phone_count BIGINT NOT NULL DEFAULT 0,kakao_count BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(stat_date,model_id));
