SET NAMES utf8mb4;

-- 线上第三方店铺探测与订单店铺目录，数据由系统管理页面维护。
CREATE TABLE IF NOT EXISTS store_probe (
 id BIGINT NOT NULL, store_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, store_name VARCHAR(255) DEFAULT NULL, remarks VARCHAR(500) DEFAULT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,
 update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_store_probe_store_id(store_id)
) ENGINE=InnoDB COMMENT='第三方接口店铺目录';

-- 店铺管理套图流程，可重复执行。
CREATE TABLE IF NOT EXISTS store_shop (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, shop_code VARCHAR(80) DEFAULT NULL, name VARCHAR(128) NOT NULL, platform VARCHAR(64) DEFAULT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', config_json JSON DEFAULT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,
 update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL, PRIMARY KEY(id), KEY idx_store_shop_owner(owner_id,status), UNIQUE KEY uk_store_shop_code(owner_id,shop_code)
) ENGINE=InnoDB COMMENT='套图店铺';
SET @store_shop_add_code = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_shop' AND column_name='shop_code'),
 'SELECT 1', 'ALTER TABLE store_shop ADD COLUMN shop_code VARCHAR(80) DEFAULT NULL AFTER owner_id');
PREPARE store_shop_code_stmt FROM @store_shop_add_code;
EXECUTE store_shop_code_stmt;
DEALLOCATE PREPARE store_shop_code_stmt;
SET @store_shop_unique_code = IF(
 EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='store_shop' AND index_name='uk_store_shop_code'),
 'SELECT 1', 'ALTER TABLE store_shop ADD UNIQUE KEY uk_store_shop_code(owner_id,shop_code)');
PREPARE store_shop_unique_stmt FROM @store_shop_unique_code;
EXECUTE store_shop_unique_stmt;
DEALLOCATE PREPARE store_shop_unique_stmt;
CREATE TABLE IF NOT EXISTS store_reference (
 id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, name VARCHAR(50) NOT NULL,
 status CHAR(1) NOT NULL DEFAULT '0', PRIMARY KEY(id), UNIQUE KEY uk_store_reference_kind_name(kind,name)
) ENGINE=InnoDB COMMENT='店铺平台与站点维护';
INSERT INTO store_reference(id,kind,name,status) VALUES
(1990000000000001001,'PLATFORM','Temu','0'),
(1990000000000001002,'PLATFORM','Amazon','0'),
(1990000000000001003,'PLATFORM','TikTok Shop','0'),
(1990000000000001004,'PLATFORM','SHEIN','0'),
(1990000000000001011,'SITE','US / USD','0'),
(1990000000000001012,'SITE','EU / EUR','0')
ON DUPLICATE KEY UPDATE id=id;
CREATE TABLE IF NOT EXISTS store_template_group (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, category_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL,
 calibration_config_json JSON DEFAULT NULL, mask_oss_key VARCHAR(500) DEFAULT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,
 update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL, PRIMARY KEY(id), KEY idx_store_group_owner(owner_id,status,category_id)
) ENGINE=InnoDB COMMENT='套图模板组';
CREATE TABLE IF NOT EXISTS store_calibration (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, category_id BIGINT NOT NULL, name VARCHAR(80) NOT NULL,
 calibration_config_json JSON NOT NULL, background_oss_key VARCHAR(500) NOT NULL, mask_oss_key VARCHAR(500) DEFAULT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED', version INT NOT NULL DEFAULT 1,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,
 update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,
 PRIMARY KEY(id), KEY idx_store_calibration_owner(owner_id,status,category_id)
) ENGINE=InnoDB COMMENT='独立背景校准记录';
ALTER TABLE store_calibration ALTER COLUMN status SET DEFAULT 'PUBLISHED';
CREATE TABLE IF NOT EXISTS store_template (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, group_id BIGINT NOT NULL, category_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL,
 oss_key VARCHAR(500) NOT NULL, image_url VARCHAR(1000) DEFAULT NULL, template_config_json JSON DEFAULT NULL,
 width INT DEFAULT NULL, height INT DEFAULT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,
 update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL, PRIMARY KEY(id), KEY idx_store_template_group(group_id,status), KEY idx_store_template_owner(owner_id,category_id,status)
) ENGINE=InnoDB COMMENT='套图模板';
CREATE TABLE IF NOT EXISTS store_render_batch (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, category_id BIGINT NOT NULL, template_group_id BIGINT NOT NULL,
 shop_id BIGINT DEFAULT NULL, listing_template_id BIGINT DEFAULT NULL, status VARCHAR(24) NOT NULL,
 selected_count INT NOT NULL DEFAULT 0, completed_count INT NOT NULL DEFAULT 0, skipped_count INT NOT NULL DEFAULT 0, failed_count INT NOT NULL DEFAULT 0,
 listing_profile_json JSON DEFAULT NULL, idempotency_key VARCHAR(128) NOT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,
 update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL, PRIMARY KEY(id), UNIQUE KEY uk_store_batch_idempotency(owner_id,idempotency_key), KEY idx_store_batch_owner(owner_id,status,update_time)
) ENGINE=InnoDB COMMENT='套图渲染批次';
CREATE TABLE IF NOT EXISTS store_render_item (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, batch_id BIGINT NOT NULL, template_group_id BIGINT NOT NULL, template_id BIGINT NOT NULL, image_id BIGINT NOT NULL,
 render_config_hash CHAR(64) NOT NULL, calibration_config_json JSON DEFAULT NULL, mask_oss_key VARCHAR(500) DEFAULT NULL, template_config_json JSON DEFAULT NULL,
 status VARCHAR(24) NOT NULL, lease_until DATETIME DEFAULT NULL, lease_token VARCHAR(64) DEFAULT NULL, worker_id VARCHAR(128) DEFAULT NULL,
 output_format VARCHAR(16) DEFAULT NULL, quality INT DEFAULT NULL, result_oss_key VARCHAR(500) DEFAULT NULL, result_url VARCHAR(1000) DEFAULT NULL,
 result_sha256 CHAR(64) DEFAULT NULL, error_message VARCHAR(2000) DEFAULT NULL, started_at DATETIME DEFAULT NULL, completed_at DATETIME DEFAULT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL, update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,
 PRIMARY KEY(id), KEY idx_store_item_claim(status,lease_until,id), KEY idx_store_item_batch(batch_id,status), KEY idx_store_item_dedupe(owner_id,template_group_id,template_id,image_id,render_config_hash)
) ENGINE=InnoDB COMMENT='套图渲染明细';
-- 使用次数按“原图 + 模板组”计数，修正旧版按每张背景重复累计的记录。
UPDATE img_asset a JOIN (
 SELECT image_id, COUNT(DISTINCT template_group_id) AS use_count
 FROM store_render_item WHERE status='SUCCEEDED' GROUP BY image_id
) rendered ON rendered.image_id=a.id SET a.render_use_count=rendered.use_count;
SET @store_item_add_calibration = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_render_item' AND column_name='calibration_config_json'),
 'SELECT 1', 'ALTER TABLE store_render_item ADD COLUMN calibration_config_json JSON DEFAULT NULL AFTER render_config_hash');
PREPARE store_item_calibration_stmt FROM @store_item_add_calibration;
EXECUTE store_item_calibration_stmt;
DEALLOCATE PREPARE store_item_calibration_stmt;
SET @store_group_add_mask = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_template_group' AND column_name='mask_oss_key'),
 'SELECT 1', 'ALTER TABLE store_template_group ADD COLUMN mask_oss_key VARCHAR(500) DEFAULT NULL AFTER calibration_config_json');
PREPARE store_group_mask_stmt FROM @store_group_add_mask;
EXECUTE store_group_mask_stmt;
DEALLOCATE PREPARE store_group_mask_stmt;
SET @store_item_add_mask = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_render_item' AND column_name='mask_oss_key'),
 'SELECT 1', 'ALTER TABLE store_render_item ADD COLUMN mask_oss_key VARCHAR(500) DEFAULT NULL AFTER calibration_config_json');
PREPARE store_item_mask_stmt FROM @store_item_add_mask;
EXECUTE store_item_mask_stmt;
DEALLOCATE PREPARE store_item_mask_stmt;
SET @store_item_add_template = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_render_item' AND column_name='template_config_json'),
 'SELECT 1', 'ALTER TABLE store_render_item ADD COLUMN template_config_json JSON DEFAULT NULL AFTER calibration_config_json');
PREPARE store_item_template_stmt FROM @store_item_add_template;
EXECUTE store_item_template_stmt;
DEALLOCATE PREPARE store_item_template_stmt;
CREATE TABLE IF NOT EXISTS store_listing_template (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, category_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL,
 oss_key VARCHAR(500) NOT NULL, original_filename VARCHAR(255) DEFAULT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', config_json JSON DEFAULT NULL,
 version_no INT NOT NULL, template_code VARCHAR(320) NOT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL, update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,
 PRIMARY KEY(id), KEY idx_store_listing_owner(owner_id,shop_id,category_id,status),
 UNIQUE KEY uk_store_listing_version(owner_id,shop_id,category_id,version_no), UNIQUE KEY uk_store_listing_code(owner_id,template_code)
) ENGINE=InnoDB COMMENT='上架表格模板';
CREATE TABLE IF NOT EXISTS store_listing_profile (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, category_id BIGINT NOT NULL, config_json JSON NOT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL, update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_store_profile(owner_id,shop_id,category_id)
) ENGINE=InnoDB COMMENT='上架表格生成配置';
SET @store_template_had_config = EXISTS(
 SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_listing_template' AND column_name='config_json');
SET @store_template_add_config = IF(@store_template_had_config,
 'SELECT 1', 'ALTER TABLE store_listing_template ADD COLUMN config_json JSON DEFAULT NULL AFTER original_filename');
PREPARE store_template_config_stmt FROM @store_template_add_config;
EXECUTE store_template_config_stmt;
DEALLOCATE PREPARE store_template_config_stmt;
UPDATE store_listing_template template
JOIN store_listing_profile profile ON profile.owner_id=template.owner_id AND profile.shop_id=template.shop_id AND profile.category_id=template.category_id
SET template.config_json=profile.config_json WHERE @store_template_had_config=0 AND template.config_json IS NULL;
SET @store_listing_add_version = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_listing_template' AND column_name='version_no'),
 'SELECT 1', 'ALTER TABLE store_listing_template ADD COLUMN version_no INT DEFAULT NULL');
PREPARE store_listing_version_stmt FROM @store_listing_add_version;
EXECUTE store_listing_version_stmt;
DEALLOCATE PREPARE store_listing_version_stmt;
SET @store_listing_add_code = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_listing_template' AND column_name='template_code'),
 'SELECT 1', 'ALTER TABLE store_listing_template ADD COLUMN template_code VARCHAR(320) DEFAULT NULL');
PREPARE store_listing_code_stmt FROM @store_listing_add_code;
EXECUTE store_listing_code_stmt;
DEALLOCATE PREPARE store_listing_code_stmt;
DROP TEMPORARY TABLE IF EXISTS store_listing_numbered;
CREATE TEMPORARY TABLE store_listing_numbered AS
SELECT t.id, ROW_NUMBER() OVER (PARTITION BY t.owner_id,t.shop_id,t.category_id ORDER BY t.create_time,t.id) AS version_no,
 CONCAT(COALESCE(s.name,CAST(t.shop_id AS CHAR)),'(',COALESCE(NULLIF(s.shop_code,''),CAST(t.shop_id AS CHAR)),')_',
        COALESCE(c.name,CAST(t.category_id AS CHAR)),'_') AS code_prefix
FROM store_listing_template t
LEFT JOIN store_shop s ON s.id=t.shop_id
LEFT JOIN img_category c ON c.id=t.category_id;
UPDATE store_listing_template t JOIN store_listing_numbered numbered ON numbered.id=t.id
SET t.version_no=COALESCE(t.version_no,numbered.version_no),
    t.template_code=COALESCE(t.template_code,CONCAT(numbered.code_prefix,LPAD(COALESCE(t.version_no,numbered.version_no),3,'0')))
WHERE t.version_no IS NULL OR t.template_code IS NULL;
DROP TEMPORARY TABLE store_listing_numbered;
ALTER TABLE store_listing_template MODIFY COLUMN version_no INT NOT NULL, MODIFY COLUMN template_code VARCHAR(320) NOT NULL;
SET @store_listing_add_version_key = IF(
 EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='store_listing_template' AND index_name='uk_store_listing_version'),
 'SELECT 1', 'ALTER TABLE store_listing_template ADD UNIQUE KEY uk_store_listing_version(owner_id,shop_id,category_id,version_no)');
PREPARE store_listing_version_key_stmt FROM @store_listing_add_version_key;
EXECUTE store_listing_version_key_stmt;
DEALLOCATE PREPARE store_listing_version_key_stmt;
SET @store_listing_add_code_key = IF(
 EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='store_listing_template' AND index_name='uk_store_listing_code'),
 'SELECT 1', 'ALTER TABLE store_listing_template ADD UNIQUE KEY uk_store_listing_code(owner_id,template_code)');
PREPARE store_listing_code_key_stmt FROM @store_listing_add_code_key;
EXECUTE store_listing_code_key_stmt;
DEALLOCATE PREPARE store_listing_code_key_stmt;
CREATE TABLE IF NOT EXISTS store_listing_export (
 id BIGINT NOT NULL, owner_id BIGINT NOT NULL, batch_id BIGINT NOT NULL, shop_id BIGINT DEFAULT NULL, listing_template_id BIGINT DEFAULT NULL,
 listing_profile_json JSON DEFAULT NULL, listing_keywords_json JSON DEFAULT NULL, status VARCHAR(24) NOT NULL, oss_key VARCHAR(500) DEFAULT NULL, error_message VARCHAR(2000) DEFAULT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL, update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_store_export_batch_shop(batch_id,shop_id), KEY idx_store_export_owner(owner_id,create_time)
) ENGINE=InnoDB COMMENT='上架表格导出记录';
SET @store_export_add_profile = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_listing_export' AND column_name='listing_profile_json'),
 'SELECT 1', 'ALTER TABLE store_listing_export ADD COLUMN listing_profile_json JSON DEFAULT NULL AFTER listing_template_id');
PREPARE store_export_profile_stmt FROM @store_export_add_profile;
EXECUTE store_export_profile_stmt;
DEALLOCATE PREPARE store_export_profile_stmt;
SET @store_export_add_keywords = IF(
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='store_listing_export' AND column_name='listing_keywords_json'),
 'SELECT 1', 'ALTER TABLE store_listing_export ADD COLUMN listing_keywords_json JSON DEFAULT NULL AFTER listing_profile_json');
PREPARE store_export_keywords_stmt FROM @store_export_add_keywords;
EXECUTE store_export_keywords_stmt;
DEALLOCATE PREPARE store_export_keywords_stmt;
SET @store_export_add_unique = IF(
 EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='store_listing_export' AND index_name='uk_store_export_batch_shop'),
 'SELECT 1', 'ALTER TABLE store_listing_export ADD UNIQUE KEY uk_store_export_batch_shop(batch_id,shop_id)');
PREPARE store_export_add_unique_stmt FROM @store_export_add_unique;
EXECUTE store_export_add_unique_stmt;
DEALLOCATE PREPARE store_export_add_unique_stmt;
SET @store_export_drop_unique = IF(
 EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='store_listing_export' AND index_name='uk_store_export_batch'),
 'ALTER TABLE store_listing_export DROP INDEX uk_store_export_batch', 'SELECT 1');
PREPARE store_export_drop_unique_stmt FROM @store_export_drop_unique;
EXECUTE store_export_drop_unique_stmt;
DEALLOCATE PREPARE store_export_drop_unique_stmt;

-- 仅首次建店铺菜单时，沿用已有图片图库角色的默认店铺权限；以后保留人工授权结果。
SET @seed_store_menu = NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=1990000000000000020);
INSERT INTO sys_menu VALUES
(1990000000000000020,'店铺管理',0,4,'store-management','#','','N','Y','M','0','0','','shop', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,'店铺套图管理'),
(1990000000000000027,'店铺运营',1990000000000000020,1,'operations','store/operations/index','','N','Y','C','0','0','store:shop:list','shop', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000021,'背景模板校准',1990000000000000020,2,'calibration','store/calibration/index','','N','Y','C','0','0','store:calibration:list','edit', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000022,'模板组',1990000000000000020,3,'template-group','store/template-group/index','','N','Y','C','0','0','store:template-group:list','list', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000026,'上架表格配置',1990000000000000020,4,'listing-config','store/listing-config/index','','N','Y','C','0','0','store:listing-template:list','edit', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000024,'渲染结果',1990000000000000020,5,'render-result','store/render-result/index','','N','Y','C','0','0','store:render:list','image', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000025,'上架表格',1990000000000000020,6,'listing','store/listing/index','','N','Y','C','0','0','store:listing-export:list','table', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000028,'平台维护',1761400000000000001,4,'platforms','store/reference/index','','N','Y','C','0','0','store:reference:list','appstore', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000029,'站点维护',1761400000000000001,5,'sites','store/reference/index','','N','Y','C','0','0','store:reference:list','global', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000000031,'店铺探测配置',1761400000000000001,6,'store-probe','system/store-probe/index','','N','Y','C','0','0','system:store-probe:list','shop', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,'维护线上第三方接口使用的店铺目录'),
(1990000000000001311,'店铺探测查询',1990000000000000031,1,'','','','N','Y','F','0','0','system:store-probe:query','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001312,'店铺探测新增',1990000000000000031,2,'','','','N','Y','F','0','0','system:store-probe:add','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001313,'店铺探测修改',1990000000000000031,3,'','','','N','Y','F','0','0','system:store-probe:edit','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001314,'店铺探测删除',1990000000000000031,4,'','','','N','Y','F','0','0','system:store-probe:remove','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,'')
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),parent_id=VALUES(parent_id),order_num=VALUES(order_num),path=VALUES(path),component=VALUES(component),menu_type=VALUES(menu_type),visible=VALUES(visible),perms=VALUES(perms),icon=VALUES(icon),remark=VALUES(remark);
-- 店铺各页面的写操作独立授权；目录不使用 store:*，避免通配符绕过按钮权限。
INSERT INTO sys_menu VALUES
(1990000000000001201,'店铺新增',1990000000000000027,1,'','','','N','Y','F','0','0','store:shop:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001202,'店铺修改',1990000000000000027,2,'','','','N','Y','F','0','0','store:shop:edit','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001211,'校准新增',1990000000000000021,1,'','','','N','Y','F','0','0','store:calibration:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001212,'校准修改',1990000000000000021,2,'','','','N','Y','F','0','0','store:calibration:edit','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001213,'校准删除',1990000000000000021,3,'','','','N','Y','F','0','0','store:calibration:remove','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001221,'模板组查询',1990000000000000022,1,'','','','N','Y','F','0','0','store:template-group:query','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001222,'模板组新增',1990000000000000022,2,'','','','N','Y','F','0','0','store:template-group:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001223,'模板组修改',1990000000000000022,3,'','','','N','Y','F','0','0','store:template-group:edit','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001224,'模板组删除',1990000000000000022,4,'','','','N','Y','F','0','0','store:template-group:remove','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001225,'模板组上传',1990000000000000022,5,'','','','N','Y','F','0','0','store:template-group:upload','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001231,'上架模板新增',1990000000000000026,1,'','','','N','Y','F','0','0','store:listing-template:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001232,'上架模板修改',1990000000000000026,2,'','','','N','Y','F','0','0','store:listing-template:edit','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001233,'上架模板删除',1990000000000000026,3,'','','','N','Y','F','0','0','store:listing-template:remove','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001234,'上架模板上传',1990000000000000026,4,'','','','N','Y','F','0','0','store:listing-template:upload','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001241,'渲染批次查询',1990000000000000024,1,'','','','N','Y','F','0','0','store:render:query','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001242,'创建渲染批次',1990000000000000024,2,'','','','N','Y','F','0','0','store:render:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001243,'重试渲染',1990000000000000024,3,'','','','N','Y','F','0','0','store:render:retry','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001244,'下载渲染结果',1990000000000000024,4,'','','','N','Y','F','0','0','store:render:download','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001245,'查看渲染日志',1990000000000000024,5,'','','','N','Y','F','0','0','store:render:log','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001251,'生成上架表格',1990000000000000025,1,'','','','N','Y','F','0','0','store:listing-export:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001252,'重试上架表格',1990000000000000025,2,'','','','N','Y','F','0','0','store:listing-export:retry','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001253,'下载上架表格',1990000000000000025,3,'','','','N','Y','F','0','0','store:listing-export:download','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001261,'平台站点新增',1990000000000000028,1,'','','','N','Y','F','0','0','store:reference:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001262,'平台站点修改',1990000000000000028,2,'','','','N','Y','F','0','0','store:reference:edit','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001263,'平台站点删除',1990000000000000028,3,'','','','N','Y','F','0','0','store:reference:remove','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,'')
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),parent_id=VALUES(parent_id),perms=VALUES(perms);
DELETE FROM sys_role_menu WHERE menu_id=1990000000000000023;
DELETE FROM sys_menu WHERE menu_id=1990000000000000023;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role.role_id,menu.menu_id FROM sys_role role CROSS JOIN (
 SELECT 1990000000000000020 menu_id UNION ALL SELECT 1990000000000000027 UNION ALL SELECT 1990000000000000021 UNION ALL SELECT 1990000000000000022 UNION ALL SELECT 1990000000000000026 UNION ALL SELECT 1990000000000000024 UNION ALL SELECT 1990000000000000025
) menu WHERE role.role_key='superadmin';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role_id,1990000000000000028 FROM sys_role WHERE role_key='superadmin';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role_id,1990000000000000029 FROM sys_role WHERE role_key='superadmin';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role_id,menu.menu_id FROM sys_role CROSS JOIN (
 SELECT 1990000000000000031 menu_id UNION ALL SELECT 1990000000000001311 UNION ALL SELECT 1990000000000001312
 UNION ALL SELECT 1990000000000001313 UNION ALL SELECT 1990000000000001314
) menu WHERE role_key='superadmin';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT access.role_id, menu.menu_id FROM sys_role_menu access CROSS JOIN (
 SELECT 1990000000000000020 menu_id UNION ALL SELECT 1990000000000000027 UNION ALL SELECT 1990000000000000021
 UNION ALL SELECT 1990000000000000022 UNION ALL SELECT 1990000000000000026 UNION ALL SELECT 1990000000000000024
 UNION ALL SELECT 1990000000000000025
) menu WHERE access.menu_id=1980000000000000014 AND @seed_store_menu=1;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT access.role_id, button.menu_id FROM sys_role_menu access
JOIN sys_menu button ON button.parent_id=access.menu_id AND button.menu_type='F'
WHERE button.menu_id BETWEEN 1990000000000001201 AND 1990000000000001253 AND @seed_store_menu=1;
INSERT IGNORE INTO sys_dept_menu(dept_id,menu_id)
SELECT dr.dept_id,rm.menu_id FROM sys_dept_role dr JOIN sys_role_menu rm ON rm.role_id=dr.role_id
JOIN sys_menu menu ON menu.menu_id=rm.menu_id
WHERE (menu.menu_id BETWEEN 1990000000000000020 AND 1990000000000000027
 OR menu.menu_id BETWEEN 1990000000000001201 AND 1990000000000001253)
 AND @seed_store_menu=1;
-- 独立演示工作台已由店铺管理流程取代。
DELETE FROM sys_role_menu WHERE menu_id=1980000000000000017;
DELETE FROM sys_menu WHERE menu_id=1980000000000000017;
