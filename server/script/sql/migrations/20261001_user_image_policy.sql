-- 检测与中转站默认开启，所有风险状态放行默认关闭；旧批次保留原有检测行为。可重复执行。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'risk_scan_enabled'), 'SELECT 1', 'ALTER TABLE sys_user ADD COLUMN risk_scan_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否进行侵权风险检测''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'relay_analysis_enabled'), 'SELECT 1', 'ALTER TABLE sys_user ADD COLUMN relay_analysis_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否调用中转站分析图片''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'render_all_risks_enabled'), 'ALTER TABLE sys_user ALTER COLUMN render_all_risks_enabled SET DEFAULT 0', 'ALTER TABLE sys_user ADD COLUMN render_all_risks_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''所有风险状态的正式图库图片可渲染''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'listing_all_risks_enabled'), 'ALTER TABLE sys_user ALTER COLUMN listing_all_risks_enabled SET DEFAULT 0', 'ALTER TABLE sys_user ADD COLUMN listing_all_risks_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''所有风险状态的正式图库图片可生成上架表格''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_batch' AND column_name = 'risk_scan_enabled'), 'SELECT 1', 'ALTER TABLE img_batch ADD COLUMN risk_scan_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''创建批次时固定侵权风险检测配置''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_batch' AND column_name = 'relay_analysis_enabled'), 'SELECT 1', 'ALTER TABLE img_batch ADD COLUMN relay_analysis_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''创建批次时固定中转站图片分析配置''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 首次新增导出按钮时，继承当前图库列表的角色与部门授权；之后可独立撤权。
SET @new_image_export_permission = NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id = 1980000000000000112);
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, remark)
VALUES (1980000000000000112, '导出筛选结果', 1980000000000000014, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:export', '#', '导出正式图库当前筛选结果');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 1980000000000000112 FROM sys_role_menu
WHERE menu_id IN (1980000000000000011, 1980000000000000012) AND @new_image_export_permission = 1;
INSERT IGNORE INTO sys_dept_menu (dept_id, menu_id)
SELECT dept_id, 1980000000000000112 FROM sys_dept_menu
WHERE menu_id IN (1980000000000000011, 1980000000000000012) AND @new_image_export_permission = 1;
