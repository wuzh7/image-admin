-- 三方侵权检测领取凭证、领取时间和验证链接；可重复执行。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'verification_urls_json'),
    'SELECT 1', 'ALTER TABLE infringement_scan_item ADD COLUMN verification_urls_json JSON DEFAULT NULL COMMENT ''三方侵权验证URL数组'' AFTER case_numbers_json');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'external_claim_token'),
    'SELECT 1', 'ALTER TABLE infringement_scan_item ADD COLUMN external_claim_token CHAR(36) DEFAULT NULL COMMENT ''三方领取凭证'' AFTER verification_urls_json');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'external_claimed_at'),
    'SELECT 1', 'ALTER TABLE infringement_scan_item ADD COLUMN external_claimed_at DATETIME DEFAULT NULL COMMENT ''三方领取时间'' AFTER external_claim_token');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'external_completed_at'),
    'SELECT 1', 'ALTER TABLE infringement_scan_item ADD COLUMN external_completed_at DATETIME DEFAULT NULL COMMENT ''三方检测完成时间'' AFTER external_claimed_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND index_name = 'idx_infringement_external_claim' AND column_name = 'risk_level'),
    'ALTER TABLE infringement_scan_item DROP INDEX idx_infringement_external_claim', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND index_name = 'idx_infringement_external_claim'),
    'SELECT 1', 'ALTER TABLE infringement_scan_item ADD INDEX idx_infringement_external_claim (external_completed_at, status, external_claimed_at, id)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
