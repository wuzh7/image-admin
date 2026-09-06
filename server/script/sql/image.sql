-- 图片去重模块，可重复执行。
CREATE TABLE IF NOT EXISTS img_category (
    id                    BIGINT          NOT NULL COMMENT '类目ID',
    name                  VARCHAR(50)     NOT NULL COMMENT '类目名称',
    sort_order            INT             NOT NULL DEFAULT 0 COMMENT '显示顺序',
    status                CHAR(1)         NOT NULL DEFAULT '0' COMMENT '状态（0启用 1停用）',
    create_dept           BIGINT          DEFAULT NULL,
    create_by             BIGINT          DEFAULT NULL,
    create_time           DATETIME        DEFAULT NULL,
    update_by             BIGINT          DEFAULT NULL,
    update_time           DATETIME        DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_img_category_name (name),
    KEY idx_img_category_status_sort (status, sort_order)
) ENGINE=InnoDB COMMENT='图片类目';

-- 开启账号自助注册；用于升级已有数据库，避免仅修改初始化脚本后现有环境仍无法注册。
UPDATE sys_config
SET config_value = 'true',
    update_time = SYSDATE()
WHERE config_key = 'sys.account.registerUser'
  AND config_value <> 'true';

INSERT INTO img_category (id, name, sort_order, status, create_time)
SELECT seed.id, seed.name, seed.sort_order, '0', SYSDATE()
FROM (
    SELECT 1980000000000001001 AS id, '家居收纳' AS name, 1 AS sort_order
    UNION ALL SELECT 1980000000000001002, '户外用品', 2
    UNION ALL SELECT 1980000000000001003, '美妆工具', 3
) seed
WHERE NOT EXISTS (SELECT 1 FROM img_category);

CREATE TABLE IF NOT EXISTS img_batch (
    id                    BIGINT          NOT NULL COMMENT '批次ID',
    name                  VARCHAR(100)    NOT NULL COMMENT '批次名称',
    category_id           BIGINT          DEFAULT NULL COMMENT '图片类目ID',
    status                VARCHAR(20)     NOT NULL COMMENT '批次状态',
    similarity_threshold  DECIMAL(5,4)    NOT NULL DEFAULT 0.6000 COMMENT '重复阈值',
    total_count           INT             NOT NULL DEFAULT 0 COMMENT '总图片数',
    processed_count       INT             NOT NULL DEFAULT 0 COMMENT '已处理数',
    duplicate_count       INT             NOT NULL DEFAULT 0 COMMENT '重复数',
    failed_count          INT             NOT NULL DEFAULT 0 COMMENT '失败数',
    error_message         VARCHAR(2000)   DEFAULT NULL COMMENT '批次错误',
    create_dept           BIGINT          DEFAULT NULL,
    create_by             BIGINT          DEFAULT NULL,
    create_time           DATETIME        DEFAULT NULL,
    update_by             BIGINT          DEFAULT NULL,
    update_time           DATETIME        DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_img_batch_status (status),
    KEY idx_img_batch_owner_create (create_by, create_time),
    KEY idx_img_batch_create_time (create_time)
) ENGINE=InnoDB COMMENT='图片检测批次';

CREATE TABLE IF NOT EXISTS img_asset (
    id                    BIGINT          NOT NULL COMMENT '图片ID',
    batch_id              BIGINT          NOT NULL COMMENT '批次ID',
    category_id           BIGINT          DEFAULT NULL COMMENT '图片类目ID',
    storage_key           VARCHAR(500)    DEFAULT NULL COMMENT '原图内部路径',
    thumbnail_key         VARCHAR(500)    DEFAULT NULL COMMENT '缩略图内部路径',
    original_name         VARCHAR(255)    NOT NULL COMMENT '上传文件名',
    display_name          VARCHAR(255)    NOT NULL COMMENT '展示名称',
    content_type          VARCHAR(50)     NOT NULL COMMENT '图片MIME',
    file_size             BIGINT          NOT NULL COMMENT '字节数',
    width                 INT             NOT NULL COMMENT '宽度',
    height                INT             NOT NULL COMMENT '高度',
    aspect_ratio          VARCHAR(20)     DEFAULT NULL COMMENT '宽高化简比例，例如4:5',
    sha256                CHAR(64)        NOT NULL COMMENT '文件SHA-256',
    status                VARCHAR(20)     NOT NULL COMMENT '图片状态',
    duplicate_flag        TINYINT(1)      DEFAULT NULL COMMENT '是否达到重复阈值',
    duplicate_of          BIGINT          DEFAULT NULL COMMENT '最佳匹配图片ID',
    similarity            DECIMAL(6,5)    DEFAULT NULL COMMENT '最终精排分数',
    match_method          VARCHAR(32)     DEFAULT NULL COMMENT 'EXACT_HASH或SSCD',
    global_vector         LONGBLOB        DEFAULT NULL COMMENT 'SSCD全图Float32向量',
    center_vector         LONGBLOB        DEFAULT NULL COMMENT 'SSCD中心裁剪Float32向量',
    feature_version       VARCHAR(64)     DEFAULT NULL COMMENT '特征版本',
    error_message         VARCHAR(2000)   DEFAULT NULL COMMENT '处理错误',
    deleted_at            DATETIME        DEFAULT NULL COMMENT '文件待清理时间起点',
    del_flag              CHAR(1)         NOT NULL DEFAULT '0' COMMENT '逻辑删除（0存在 1删除）',
    create_dept           BIGINT          DEFAULT NULL,
    create_by             BIGINT          DEFAULT NULL,
    create_time           DATETIME        DEFAULT NULL,
    update_by             BIGINT          DEFAULT NULL,
    update_time           DATETIME        DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_img_asset_batch (batch_id, id),
    KEY idx_img_asset_category (category_id, status, del_flag),
    KEY idx_img_asset_owner_status (create_by, status, del_flag),
    KEY idx_img_asset_status (status, del_flag),
    KEY idx_img_asset_sha (sha256, status, del_flag),
    KEY idx_img_asset_duplicate (duplicate_of),
    KEY idx_img_asset_cleanup (deleted_at)
) ENGINE=InnoDB COMMENT='图片资源及去重结果';

-- 兼容已经创建过的本地数据卷：增加类目维度，不对历史图片主观补分类。
SET @add_batch_category = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_batch' AND column_name = 'category_id'),
    'SELECT 1',
    'ALTER TABLE img_batch ADD COLUMN category_id BIGINT DEFAULT NULL COMMENT ''图片类目ID'' AFTER name'
);
PREPARE add_batch_category_stmt FROM @add_batch_category;
EXECUTE add_batch_category_stmt;
DEALLOCATE PREPARE add_batch_category_stmt;

SET @add_asset_category = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'category_id'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD COLUMN category_id BIGINT DEFAULT NULL COMMENT ''图片类目ID'' AFTER batch_id'
);
PREPARE add_asset_category_stmt FROM @add_asset_category;
EXECUTE add_asset_category_stmt;
DEALLOCATE PREPARE add_asset_category_stmt;

SET @add_asset_aspect_ratio = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'aspect_ratio'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD COLUMN aspect_ratio VARCHAR(20) DEFAULT NULL COMMENT ''宽高化简比例，例如4:5'' AFTER height'
);
PREPARE add_asset_aspect_ratio_stmt FROM @add_asset_aspect_ratio;
EXECUTE add_asset_aspect_ratio_stmt;
DEALLOCATE PREPARE add_asset_aspect_ratio_stmt;

SET @add_asset_aspect_ratio_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'img_asset'
             AND index_name = 'idx_img_asset_aspect_ratio'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD INDEX idx_img_asset_aspect_ratio (aspect_ratio, status, del_flag, create_by)'
);
PREPARE add_asset_aspect_ratio_index_stmt FROM @add_asset_aspect_ratio_index;
EXECUTE add_asset_aspect_ratio_index_stmt;
DEALLOCATE PREPARE add_asset_aspect_ratio_index_stmt;

SET @add_asset_category_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'img_asset'
             AND index_name = 'idx_img_asset_category'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD INDEX idx_img_asset_category (category_id, status, del_flag)'
);
PREPARE add_asset_category_index_stmt FROM @add_asset_category_index;
EXECUTE add_asset_category_index_stmt;
DEALLOCATE PREPARE add_asset_category_index_stmt;

-- 兼容已经创建过的本地数据卷：补齐历史图片归属和多人查询索引。
UPDATE img_asset asset
JOIN img_batch batch ON batch.id = asset.batch_id
SET asset.create_by = batch.create_by,
    asset.create_dept = batch.create_dept
WHERE asset.create_by IS NULL;

SET @add_batch_owner_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'img_batch'
             AND index_name = 'idx_img_batch_owner_create'),
    'SELECT 1',
    'ALTER TABLE img_batch ADD INDEX idx_img_batch_owner_create (create_by, create_time)'
);
PREPARE add_batch_owner_index_stmt FROM @add_batch_owner_index;
EXECUTE add_batch_owner_index_stmt;
DEALLOCATE PREPARE add_batch_owner_index_stmt;

SET @add_asset_owner_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'img_asset'
             AND index_name = 'idx_img_asset_owner_status'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD INDEX idx_img_asset_owner_status (create_by, status, del_flag)'
);
PREPARE add_asset_owner_index_stmt FROM @add_asset_owner_index;
EXECUTE add_asset_owner_index_stmt;
DEALLOCATE PREPARE add_asset_owner_index_stmt;

-- 图片管理采用与系统管理一致的目录 + 子页面结构；旧图片去重菜单原地升级，避免遗留重复入口。
INSERT INTO sys_menu VALUES
(1980000000000000001, '图片管理', 0, 2, 'image', '', '', 'N', 'Y', 'M', '0', '0', '', 'picture', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '图片上传、进度与人工复核')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    path = VALUES(path), component = VALUES(component), menu_type = VALUES(menu_type), perms = VALUES(perms), icon = VALUES(icon), remark = VALUES(remark);

INSERT INTO sys_menu VALUES
(1980000000000000011, '上传与人工复核', 1980000000000000001, 1, 'upload', 'image/dedup/index', '', 'N', 'Y', 'C', '0', '0', 'image:dedup:list', 'upload', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '上传图片、查看进度并人工复核'),
(1980000000000000012, '上传进度', 1980000000000000001, 2, 'tasks', 'image/dedup/index', '', 'N', 'Y', 'C', '1', '0', 'image:dedup:list', 'unordered-list', '/image/upload', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '上传与人工复核内部页签'),
(1980000000000000013, '人工审核', 1980000000000000001, 3, 'review', 'image/dedup/index', '', 'N', 'Y', 'C', '1', '0', 'image:dedup:confirm', 'audit', '/image/upload', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '上传与人工复核内部页签'),
(1980000000000000014, '正式图库', 1980000000000000001, 2, 'library', 'image/dedup/index', '', 'N', 'Y', 'C', '0', '0', 'image:dedup:query', 'picture', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '查询正式图库'),
(1980000000000000015, '类目维护', 1761400000000000001, 3, 'categories', 'image/category/index', '', 'N', 'Y', 'C', '0', '0', 'image:category:list', 'appstore', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '维护图片类目'),
(1980000000000000101, '图片查询', 1980000000000000001, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:query', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000102, '图片上传', 1980000000000000001, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:upload', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000103, '开始检测', 1980000000000000001, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:start', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000104, '人工确认', 1980000000000000001, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:confirm', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000105, '失败重试', 1980000000000000001, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:retry', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000106, '图片删除', 1980000000000000001, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:remove', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000107, '类目新增', 1980000000000000015, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:category:add', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000108, '类目修改', 1980000000000000015, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:category:edit', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000109, '类目删除', 1980000000000000015, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:category:remove', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    path = VALUES(path), component = VALUES(component), menu_type = VALUES(menu_type), visible = VALUES(visible), active_menu = VALUES(active_menu),
    perms = VALUES(perms), icon = VALUES(icon), remark = VALUES(remark);

-- 自助注册用户默认使用图片用户角色；可操作工作台、上传与人工复核、正式图库，不授予删除和类目维护。
INSERT INTO sys_role VALUES
(1980000000000000201, '图片用户', 'image_user', 10, '5', 1, 1, '0', '0', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '自助注册用户默认角色')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), role_key = VALUES(role_key), role_sort = VALUES(role_sort),
    data_scope = VALUES(data_scope), status = VALUES(status), del_flag = VALUES(del_flag), remark = VALUES(remark);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(1980000000000000201, 1980000000000000001),
(1980000000000000201, 1980000000000000011),
(1980000000000000201, 1980000000000000012),
(1980000000000000201, 1980000000000000013),
(1980000000000000201, 1980000000000000014),
(1980000000000000201, 1980000000000000101),
(1980000000000000201, 1980000000000000102),
(1980000000000000201, 1980000000000000103),
(1980000000000000201, 1980000000000000104),
(1980000000000000201, 1980000000000000105);

-- 类目维护迁入系统管理后，为已授权角色补充新父目录。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 1761400000000000001
FROM sys_role_menu
WHERE menu_id = 1980000000000000015;

-- 正式图库删除只允许超级管理员，移除其他角色历史授权。
DELETE role_menu
FROM sys_role_menu role_menu
INNER JOIN sys_role role ON role.role_id = role_menu.role_id
WHERE role_menu.menu_id = 1980000000000000106
  AND role.role_key <> 'superadmin';

-- 原图片去重页面升级为目录后，为原有角色补充合并后的可见入口，不扩大其按钮权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 1980000000000000011
FROM sys_role_menu
WHERE menu_id = 1980000000000000001;

-- 仅替换模板自带的管理员昵称，保留用户后续自行修改的值。
UPDATE sys_user
SET nick_name = '管理员'
WHERE user_name = 'admin'
  AND nick_name = '疯狂的狮子Li';

-- 精简系统功能，仅保留用户、角色、菜单及其必要按钮权限；图片去重使用上方独立ID。
DELETE FROM sys_role_menu
WHERE menu_id BETWEEN 1761400000000000001 AND 1761400000000019999
  AND menu_id NOT IN (
    1761400000000000001,
    1761400000000000100, 1761400000000000101,
    1761400000000000130, 1761400000000000131,
    1761400000000001001, 1761400000000001002, 1761400000000001003,
    1761400000000001004, 1761400000000001007, 1761400000000001008,
    1761400000000001009, 1761400000000001010, 1761400000000001011
  );

DELETE FROM sys_menu
WHERE menu_id BETWEEN 1761400000000000001 AND 1761400000000019999
  AND menu_id NOT IN (
    1761400000000000001,
    1761400000000000100, 1761400000000000101,
    1761400000000000130, 1761400000000000131,
    1761400000000001001, 1761400000000001002, 1761400000000001003,
    1761400000000001004, 1761400000000001007, 1761400000000001008,
    1761400000000001009, 1761400000000001010, 1761400000000001011
  );

-- OSS 异步入库：记录提交成功后才可删除的源文件，兼容现有数据卷。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'cleanup_key'), 'SELECT 1', 'ALTER TABLE img_asset ADD COLUMN cleanup_key VARCHAR(500) DEFAULT NULL');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'cleanup_thumbnail_key'), 'SELECT 1', 'ALTER TABLE img_asset ADD COLUMN cleanup_thumbnail_key VARCHAR(500) DEFAULT NULL');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
