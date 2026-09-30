-- 三方领取与风险结果归属图片，AI 任务重试不再替换这些数据；可重复执行。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'risk_level'),
    'SELECT 1',
    'ALTER TABLE img_asset
       ADD COLUMN risk_level VARCHAR(20) DEFAULT NULL COMMENT ''三方风险等级'' AFTER infringement_scan_item_id,
       ADD COLUMN risk_reason VARCHAR(255) DEFAULT NULL COMMENT ''三方风险原因或疑点'' AFTER risk_level,
       ADD COLUMN case_numbers_json JSON DEFAULT NULL COMMENT ''三方TRO案号数组'' AFTER risk_reason,
       ADD COLUMN verification_urls_json JSON DEFAULT NULL COMMENT ''三方侵权验证URL数组'' AFTER case_numbers_json,
       ADD COLUMN external_claim_token CHAR(36) DEFAULT NULL COMMENT ''三方领取凭证'' AFTER verification_urls_json,
       ADD COLUMN external_claimed_at DATETIME DEFAULT NULL COMMENT ''三方领取时间'' AFTER external_claim_token,
       ADD COLUMN external_completed_at DATETIME DEFAULT NULL COMMENT ''三方检测完成时间'' AFTER external_claimed_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND index_name = 'idx_img_asset_external_claim'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD INDEX idx_img_asset_external_claim (status, del_flag, external_completed_at, external_claimed_at, id)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 优先保留已完成的三方结果；未完成的领取凭证也随图片迁移。
UPDATE img_asset a
JOIN infringement_scan_item i ON i.id = (
    SELECT old.id FROM infringement_scan_item old
    WHERE old.image_id = a.id AND old.external_claim_token IS NOT NULL
    ORDER BY (old.external_completed_at IS NOT NULL) DESC, old.external_claimed_at DESC, old.id DESC
    LIMIT 1
)
SET a.risk_level = i.risk_level,
    a.risk_reason = i.risk_reason,
    a.case_numbers_json = i.case_numbers_json,
    a.verification_urls_json = i.verification_urls_json,
    a.external_claim_token = i.external_claim_token,
    a.external_claimed_at = i.external_claimed_at,
    a.external_completed_at = i.external_completed_at
WHERE a.external_claim_token IS NULL;
