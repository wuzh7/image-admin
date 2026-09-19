SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS img_library_mutex (id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT IGNORE INTO img_library_mutex (id) VALUES (1), (2);
CREATE TABLE IF NOT EXISTS img_replacement_log (
 id BIGINT NOT NULL PRIMARY KEY, old_image_id BIGINT NOT NULL, new_image_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, user_name VARCHAR(30) NOT NULL,
 old_storage_key VARCHAR(500) NOT NULL, new_storage_key VARCHAR(500) NOT NULL,
 create_time DATETIME NOT NULL, UNIQUE KEY uk_image_replacement_new (new_image_id), KEY idx_replacement_user_time (user_id, create_time)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS img_image_export (
 user_id BIGINT NOT NULL, image_id BIGINT NOT NULL, request_id VARCHAR(64) NOT NULL,
 completed TINYINT(1) NOT NULL DEFAULT 0, create_time DATETIME NOT NULL,
 PRIMARY KEY (user_id, image_id), KEY idx_export_request (request_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS img_duplicate_todo (
 id BIGINT NOT NULL PRIMARY KEY, image_id BIGINT NOT NULL, matched_image_id BIGINT NOT NULL,
 similarity DECIMAL(6,5) NOT NULL, scan_date DATE NOT NULL, completed TINYINT(1) NOT NULL DEFAULT 0,
 completed_by BIGINT DEFAULT NULL, completed_at DATETIME DEFAULT NULL, create_time DATETIME NOT NULL,
 UNIQUE KEY uk_duplicate_pair (image_id, matched_image_id), KEY idx_duplicate_pending (completed, create_time)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS img_duplicate_scan_run (scan_date DATE NOT NULL PRIMARY KEY, completed_at DATETIME NOT NULL) ENGINE=InnoDB;


SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'query_vectors'), 'SELECT 1', 'ALTER TABLE img_asset ADD COLUMN query_vectors LONGBLOB DEFAULT NULL');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'manually_approved'), 'SELECT 1', 'ALTER TABLE img_asset ADD COLUMN manually_approved TINYINT(1) NOT NULL DEFAULT 0');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'replaces_image_id'), 'SELECT 1', 'ALTER TABLE img_asset ADD COLUMN replaces_image_id BIGINT DEFAULT NULL');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'approved_sha256'), 'SELECT 1', 'ALTER TABLE img_asset ADD COLUMN approved_sha256 CHAR(64) GENERATED ALWAYS AS (CASE WHEN status = ''APPROVED'' AND del_flag = ''0'' THEN sha256 ELSE NULL END) STORED');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'active_user_name'), 'SELECT 1', 'ALTER TABLE sys_user ADD COLUMN active_user_name VARCHAR(30) GENERATED ALWAYS AS (CASE WHEN del_flag = ''0'' THEN user_name ELSE NULL END) STORED');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_role' AND column_name = 'active_role_name'), 'SELECT 1', 'ALTER TABLE sys_role ADD COLUMN active_role_name VARCHAR(30) GENERATED ALWAYS AS (CASE WHEN del_flag = ''0'' THEN role_name ELSE NULL END) STORED');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND index_name = 'uk_img_asset_approved_sha'), 'SELECT 1', 'ALTER TABLE img_asset ADD UNIQUE INDEX uk_img_asset_approved_sha (approved_sha256)');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND index_name = 'uk_sys_user_active_name'), 'SELECT 1', 'ALTER TABLE sys_user ADD UNIQUE INDEX uk_sys_user_active_name (active_user_name)');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'sys_role' AND index_name = 'uk_sys_role_active_name'), 'SELECT 1', 'ALTER TABLE sys_role ADD UNIQUE INDEX uk_sys_role_active_name (active_role_name)');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND index_name = 'idx_img_asset_replaces'), 'SELECT 1', 'ALTER TABLE img_asset ADD INDEX idx_img_asset_replaces (replaces_image_id)');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

SET @image_access_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND index_name = 'idx_img_asset_owner_upload'), 'SELECT 1', 'ALTER TABLE img_asset ADD INDEX idx_img_asset_owner_upload (create_by, create_time, status, del_flag)');
PREPARE image_access_stmt FROM @image_access_ddl; EXECUTE image_access_stmt; DEALLOCATE PREPARE image_access_stmt;

UPDATE sys_config SET config_value = 'false', update_time = NOW() WHERE config_key = 'sys.account.registerUser';
