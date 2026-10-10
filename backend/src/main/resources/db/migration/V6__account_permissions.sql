-- 기존 직원은 모든 업무 권한을 유지한다. 이후 변경은 계정 revision을 증가시켜 기존 JWT를 무효화한다.
ALTER TABLE admin_accounts ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE admin_accounts ADD COLUMN permissions VARCHAR(500) NOT NULL DEFAULT 'PRICE_EDIT,CONTENT_EDIT,CATALOG_EDIT,MEDIA_EDIT,STATS_VIEW,AUDIT_VIEW';
