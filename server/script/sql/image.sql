SET NAMES utf8mb4;

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

-- 账号由管理员创建，不开放自助注册。
UPDATE sys_config
SET config_value = 'false',
    update_time = SYSDATE()
WHERE config_key = 'sys.account.registerUser'
  AND config_value <> 'false';

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
    infringement_mode     VARCHAR(20)     NOT NULL DEFAULT 'RELAY' COMMENT '侵权检测模式：RELAY、OFFICIAL_HYBRID或OFFICIAL_FLEX',
    similarity_threshold  DECIMAL(5,4)    NOT NULL DEFAULT 0.6000 COMMENT '重复阈值',
    total_count           INT             NOT NULL DEFAULT 0 COMMENT '总图片数',
    processed_count       INT             NOT NULL DEFAULT 0 COMMENT '已处理数',
    duplicate_count       INT             NOT NULL DEFAULT 0 COMMENT '重复数',
    failed_count          INT             NOT NULL DEFAULT 0 COMMENT '失败数',
    detection_completed_at DATETIME       DEFAULT NULL COMMENT '去重检测完成时间',
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
    similar_matches_json  JSON            DEFAULT NULL COMMENT '人工审核Top30相似图片快照',
    match_method          VARCHAR(32)     DEFAULT NULL COMMENT 'EXACT_HASH或SSCD',
    global_vector         LONGBLOB        DEFAULT NULL COMMENT 'SSCD全图Float32向量',
    center_vector         LONGBLOB        DEFAULT NULL COMMENT 'SSCD中心裁剪Float32向量',
    feature_version       VARCHAR(64)     DEFAULT NULL COMMENT '特征版本',
    infringement_scan_item_id BIGINT      DEFAULT NULL COMMENT '当前侵权检测明细ID',
    error_message         VARCHAR(2000)   DEFAULT NULL COMMENT '处理错误',
    approved_at           DATETIME        DEFAULT NULL COMMENT '正式入库时间',
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
    KEY idx_img_asset_infringement (infringement_scan_item_id),
    KEY idx_img_asset_library (status, del_flag, approved_at, id),
    KEY idx_img_asset_cleanup (deleted_at)
) ENGINE=InnoDB COMMENT='图片资源及去重结果';

CREATE TABLE IF NOT EXISTS infringement_scan_task (
    id                    BIGINT          NOT NULL COMMENT '任务ID',
    task_no               VARCHAR(40)     NOT NULL COMMENT '任务编号',
    task_type             VARCHAR(20)     NOT NULL COMMENT 'INITIAL或RETRY_FAILED',
    source_batch_id       BIGINT          DEFAULT NULL COMMENT '来源图片批次ID',
    create_dept           BIGINT          DEFAULT NULL,
    create_by             BIGINT          DEFAULT NULL,
    create_time           DATETIME        DEFAULT NULL,
    update_by             BIGINT          DEFAULT NULL,
    update_time           DATETIME        DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_infringement_task_no (task_no),
    KEY idx_infringement_task_owner_time (create_by, create_time)
) ENGINE=InnoDB COMMENT='图片侵权检测任务';

CREATE TABLE IF NOT EXISTS infringement_scan_item (
    id                    BIGINT          NOT NULL COMMENT '明细ID',
    task_id               BIGINT          NOT NULL COMMENT '任务ID',
    image_id              BIGINT          NOT NULL COMMENT '图片ID',
    status                VARCHAR(20)     NOT NULL COMMENT 'PENDING PROCESSING SUCCESS FAILED',
    risk_level            VARCHAR(20)     DEFAULT NULL COMMENT 'HIGH LOW NEEDS_REVIEW',
    risk_reason           VARCHAR(255)    DEFAULT NULL COMMENT '风险原因或疑点',
    case_numbers_json     JSON            DEFAULT NULL COMMENT 'TRO案号数组',
    call_type             VARCHAR(20)     NOT NULL DEFAULT 'RISK_SCAN' COMMENT 'RISK_SCAN或CASE_LOOKUP',
    image_description     VARCHAR(255)    DEFAULT NULL COMMENT '英文图片解析',
    raw_ai_response       MEDIUMTEXT      DEFAULT NULL COMMENT 'Gemini原始文本响应',
    model                 VARCHAR(100)    DEFAULT NULL COMMENT '模型',
    prompt_version        VARCHAR(50)     DEFAULT NULL COMMENT 'Prompt版本',
    execution_mode        VARCHAR(20)     DEFAULT NULL COMMENT '实际执行方式：RELAY、OFFICIAL_STANDARD或OFFICIAL_BATCH',
    search_used           TINYINT(1)      DEFAULT NULL COMMENT '本次是否实际使用Google Search',
    search_query_count    INT             DEFAULT NULL COMMENT '本次实际Google Search查询数',
    attempt_count         INT             NOT NULL DEFAULT 0 COMMENT '调用次数',
    last_request_id       VARCHAR(64)     DEFAULT NULL COMMENT '最近请求ID',
    provider_job_id       VARCHAR(255)    DEFAULT NULL COMMENT 'Gemini Batch作业ID',
    provider_request_index INT            DEFAULT NULL COMMENT '图片在Batch作业中的序号',
    error_code            VARCHAR(64)     DEFAULT NULL COMMENT '错误码',
    error_message         VARCHAR(1000)   DEFAULT NULL COMMENT '错误信息',
    started_at            DATETIME        DEFAULT NULL COMMENT '本次开始时间',
    finished_at           DATETIME        DEFAULT NULL COMMENT '最终完成时间',
    create_dept           BIGINT          DEFAULT NULL,
    create_by             BIGINT          DEFAULT NULL,
    create_time           DATETIME        DEFAULT NULL,
    update_by             BIGINT          DEFAULT NULL,
    update_time           DATETIME        DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_infringement_item_task (task_id, id),
    KEY idx_infringement_item_pending (status, id),
    KEY idx_infringement_item_job (provider_job_id, status),
    KEY idx_infringement_item_image (image_id, id)
) ENGINE=InnoDB COMMENT='图片侵权检测明细';

CREATE TABLE IF NOT EXISTS infringement_call_log (
    id                          BIGINT          NOT NULL COMMENT '调用日志ID',
    task_id                     BIGINT          NOT NULL COMMENT '任务ID',
    scan_item_id                BIGINT          NOT NULL COMMENT '检测明细ID',
    batch_id                    BIGINT          DEFAULT NULL COMMENT '图片批次ID',
    image_id                    BIGINT          NOT NULL COMMENT '图片ID',
    request_id                  VARCHAR(64)     DEFAULT NULL COMMENT '请求ID',
    provider_job_id             VARCHAR(255)    DEFAULT NULL COMMENT '上游Batch作业ID',
    provider_request_index      INT             DEFAULT NULL COMMENT '图片在Batch中的序号',
    execution_mode              VARCHAR(20)     DEFAULT NULL COMMENT 'RELAY、OFFICIAL_STANDARD或OFFICIAL_BATCH',
    call_type                   VARCHAR(20)     NOT NULL DEFAULT 'RISK_SCAN' COMMENT 'RISK_SCAN或CASE_LOOKUP',
    model                       VARCHAR(100)    DEFAULT NULL COMMENT '模型',
    status                      VARCHAR(20)     NOT NULL COMMENT 'SUCCESS或FAILED',
    risk_level                  VARCHAR(20)     DEFAULT NULL COMMENT 'HIGH LOW NEEDS_REVIEW',
    raw_ai_response             MEDIUMTEXT      DEFAULT NULL COMMENT 'AI原始返回',
    raw_provider_response       MEDIUMTEXT      DEFAULT NULL COMMENT '上游完整响应JSON，Batch保存当前图片完整条目',
    usage_metadata              MEDIUMTEXT      DEFAULT NULL COMMENT '上游原始usageMetadata',
    prompt_token_count          INT             DEFAULT NULL COMMENT '输入Token',
    cached_content_token_count  INT             DEFAULT NULL COMMENT '缓存Token',
    candidates_token_count      INT             DEFAULT NULL COMMENT '输出Token',
    thoughts_token_count        INT             DEFAULT NULL COMMENT '思考Token',
    tool_use_prompt_token_count INT             DEFAULT NULL COMMENT '工具使用Token，当前为Google搜索',
    total_token_count           INT             DEFAULT NULL COMMENT '总Token',
    search_used                 TINYINT(1)      DEFAULT NULL COMMENT '是否实际使用Google Search',
    search_query_count          INT             DEFAULT NULL COMMENT 'Google Search查询数',
    duration_ms                 BIGINT          DEFAULT NULL COMMENT '调用耗时毫秒',
    error_code                  VARCHAR(64)     DEFAULT NULL COMMENT '错误码',
    error_message               VARCHAR(1000)   DEFAULT NULL COMMENT '错误信息',
    started_at                  DATETIME        DEFAULT NULL COMMENT '开始时间',
    finished_at                 DATETIME        DEFAULT NULL COMMENT '结束时间',
    PRIMARY KEY (id),
    KEY idx_infringement_call_task (task_id, id),
    KEY idx_infringement_call_batch (batch_id, id),
    KEY idx_infringement_call_image (image_id, id)
) ENGINE=InnoDB COMMENT='Gemini逐次调用日志';

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_call_log' AND column_name = 'raw_provider_response'), 'SELECT 1', 'ALTER TABLE infringement_call_log ADD COLUMN raw_provider_response MEDIUMTEXT NULL COMMENT ''上游完整响应JSON，Batch保存当前图片完整条目'' AFTER raw_ai_response');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兼容已经创建过的本地数据卷：增加类目维度，不对历史图片主观补分类。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'case_numbers_json'), 'SELECT 1', 'ALTER TABLE infringement_scan_item ADD COLUMN case_numbers_json JSON DEFAULT NULL COMMENT ''TRO案号数组'' AFTER risk_reason');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'call_type'), 'SELECT 1', 'ALTER TABLE infringement_scan_item ADD COLUMN call_type VARCHAR(20) NOT NULL DEFAULT ''RISK_SCAN'' COMMENT ''RISK_SCAN或CASE_LOOKUP'' AFTER case_numbers_json');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_call_log' AND column_name = 'call_type'), 'SELECT 1', 'ALTER TABLE infringement_call_log ADD COLUMN call_type VARCHAR(20) NOT NULL DEFAULT ''RISK_SCAN'' COMMENT ''RISK_SCAN或CASE_LOOKUP'' AFTER execution_mode');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_batch_category = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_batch' AND column_name = 'category_id'),
    'SELECT 1',
    'ALTER TABLE img_batch ADD COLUMN category_id BIGINT DEFAULT NULL COMMENT ''图片类目ID'' AFTER name'
);
PREPARE add_batch_category_stmt FROM @add_batch_category;
EXECUTE add_batch_category_stmt;
DEALLOCATE PREPARE add_batch_category_stmt;

SET @add_batch_detection_completed_at = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_batch' AND column_name = 'detection_completed_at'),
    'SELECT 1',
    'ALTER TABLE img_batch ADD COLUMN detection_completed_at DATETIME DEFAULT NULL COMMENT ''去重检测完成时间'' AFTER failed_count'
);
PREPARE add_batch_detection_completed_at_stmt FROM @add_batch_detection_completed_at;
EXECUTE add_batch_detection_completed_at_stmt;
DEALLOCATE PREPARE add_batch_detection_completed_at_stmt;

SET @add_batch_infringement_mode = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_batch' AND column_name = 'infringement_mode'),
    'SELECT 1',
    'ALTER TABLE img_batch ADD COLUMN infringement_mode VARCHAR(20) NOT NULL DEFAULT ''RELAY'' COMMENT ''侵权检测模式：RELAY、OFFICIAL_HYBRID或OFFICIAL_FLEX'' AFTER status'
);
PREPARE add_batch_infringement_mode_stmt FROM @add_batch_infringement_mode;
EXECUTE add_batch_infringement_mode_stmt;
DEALLOCATE PREPARE add_batch_infringement_mode_stmt;

SET @extend_infringement_risk_level = IF(
    (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'risk_level') >= 20,
    'SELECT 1',
    'ALTER TABLE infringement_scan_item MODIFY COLUMN risk_level VARCHAR(20) DEFAULT NULL COMMENT ''HIGH或LOW'''
);
PREPARE extend_infringement_risk_level_stmt FROM @extend_infringement_risk_level;
EXECUTE extend_infringement_risk_level_stmt;
DEALLOCATE PREPARE extend_infringement_risk_level_stmt;

SET @add_infringement_search_used = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'search_used'),
    'SELECT 1',
    'ALTER TABLE infringement_scan_item ADD COLUMN search_used TINYINT(1) DEFAULT NULL COMMENT ''本次是否实际使用Google Search'' AFTER prompt_version'
);
PREPARE add_infringement_search_used_stmt FROM @add_infringement_search_used;
EXECUTE add_infringement_search_used_stmt;
DEALLOCATE PREPARE add_infringement_search_used_stmt;

SET @add_infringement_execution_mode = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'execution_mode'),
    'SELECT 1',
    'ALTER TABLE infringement_scan_item ADD COLUMN execution_mode VARCHAR(20) DEFAULT NULL COMMENT ''实际执行方式：RELAY、OFFICIAL_STANDARD或OFFICIAL_BATCH'' AFTER prompt_version'
);
PREPARE add_infringement_execution_mode_stmt FROM @add_infringement_execution_mode;
EXECUTE add_infringement_execution_mode_stmt;
DEALLOCATE PREPARE add_infringement_execution_mode_stmt;

UPDATE infringement_scan_item i
JOIN infringement_scan_task t ON t.id = i.task_id
JOIN img_batch b ON b.id = t.source_batch_id
SET i.execution_mode = CASE
    WHEN b.infringement_mode = 'OFFICIAL_HYBRID'
         AND GREATEST(b.processed_count - b.duplicate_count - b.failed_count, 0) > 100 THEN 'OFFICIAL_BATCH'
    WHEN b.infringement_mode = 'RELAY' THEN 'RELAY'
    ELSE 'OFFICIAL_STANDARD'
END
WHERE i.execution_mode IS NULL;

SET @add_infringement_search_query_count = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'search_query_count'),
    'SELECT 1',
    'ALTER TABLE infringement_scan_item ADD COLUMN search_query_count INT DEFAULT NULL COMMENT ''本次实际Google Search查询数'' AFTER search_used'
);
PREPARE add_infringement_search_query_count_stmt FROM @add_infringement_search_query_count;
EXECUTE add_infringement_search_query_count_stmt;
DEALLOCATE PREPARE add_infringement_search_query_count_stmt;

SET @add_infringement_provider_job = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'provider_job_id'),
    'SELECT 1',
    'ALTER TABLE infringement_scan_item ADD COLUMN provider_job_id VARCHAR(255) DEFAULT NULL COMMENT ''Gemini Batch作业ID'' AFTER last_request_id'
);
PREPARE add_infringement_provider_job_stmt FROM @add_infringement_provider_job;
EXECUTE add_infringement_provider_job_stmt;
DEALLOCATE PREPARE add_infringement_provider_job_stmt;

SET @add_infringement_provider_index = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND column_name = 'provider_request_index'),
    'SELECT 1',
    'ALTER TABLE infringement_scan_item ADD COLUMN provider_request_index INT DEFAULT NULL COMMENT ''图片在Batch作业中的序号'' AFTER provider_job_id'
);
PREPARE add_infringement_provider_index_stmt FROM @add_infringement_provider_index;
EXECUTE add_infringement_provider_index_stmt;
DEALLOCATE PREPARE add_infringement_provider_index_stmt;

SET @add_infringement_job_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'infringement_scan_item' AND index_name = 'idx_infringement_item_job'),
    'SELECT 1',
    'ALTER TABLE infringement_scan_item ADD INDEX idx_infringement_item_job (provider_job_id, status)'
);
PREPARE add_infringement_job_index_stmt FROM @add_infringement_job_index;
EXECUTE add_infringement_job_index_stmt;
DEALLOCATE PREPARE add_infringement_job_index_stmt;

SET @add_asset_category = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'category_id'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD COLUMN category_id BIGINT DEFAULT NULL COMMENT ''图片类目ID'' AFTER batch_id'
);
PREPARE add_asset_category_stmt FROM @add_asset_category;
EXECUTE add_asset_category_stmt;
DEALLOCATE PREPARE add_asset_category_stmt;

SET @add_infringement_pointer = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'infringement_scan_item_id'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD COLUMN infringement_scan_item_id BIGINT DEFAULT NULL COMMENT ''当前侵权检测明细ID'' AFTER feature_version'
);
PREPARE add_infringement_pointer_stmt FROM @add_infringement_pointer;
EXECUTE add_infringement_pointer_stmt;
DEALLOCATE PREPARE add_infringement_pointer_stmt;

SET @add_infringement_pointer_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND index_name = 'idx_img_asset_infringement'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD INDEX idx_img_asset_infringement (infringement_scan_item_id)'
);
PREPARE add_infringement_pointer_index_stmt FROM @add_infringement_pointer_index;
EXECUTE add_infringement_pointer_index_stmt;
DEALLOCATE PREPARE add_infringement_pointer_index_stmt;

SET @add_approved_at = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'approved_at'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD COLUMN approved_at DATETIME DEFAULT NULL COMMENT ''正式入库时间'' AFTER error_message'
);
PREPARE add_approved_at_stmt FROM @add_approved_at;
EXECUTE add_approved_at_stmt;
DEALLOCATE PREPARE add_approved_at_stmt;

SET @add_similar_matches_json = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND column_name = 'similar_matches_json'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD COLUMN similar_matches_json JSON DEFAULT NULL COMMENT ''人工审核Top30相似图片快照'' AFTER similarity'
);
PREPARE add_similar_matches_json_stmt FROM @add_similar_matches_json;
EXECUTE add_similar_matches_json_stmt;
DEALLOCATE PREPARE add_similar_matches_json_stmt;

UPDATE img_asset
SET approved_at = COALESCE(update_time, create_time)
WHERE status = 'APPROVED' AND approved_at IS NULL;

SET @add_library_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'img_asset' AND index_name = 'idx_img_asset_library'),
    'SELECT 1',
    'ALTER TABLE img_asset ADD INDEX idx_img_asset_library (status, del_flag, approved_at, id)'
);
PREPARE add_library_index_stmt FROM @add_library_index;
EXECUTE add_library_index_stmt;
DEALLOCATE PREPARE add_library_index_stmt;

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
(1980000000000000016, '图片侵权检测', 1980000000000000001, 3, 'infringement', 'image/infringement/index', '', 'N', 'Y', 'C', '0', '0', 'image:infringement:list', 'safety-certificate', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '查看Gemini侵权风险检测结果'),
(1980000000000000015, '类目维护', 1761400000000000001, 3, 'categories', 'image/category/index', '', 'N', 'Y', 'C', '0', '0', 'image:category:list', 'appstore', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '维护图片类目'),
(1980000000000000101, '图片查询', 1980000000000000001, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:query', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000102, '图片上传', 1980000000000000001, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:upload', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000103, '开始检测', 1980000000000000001, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:start', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000104, '人工确认', 1980000000000000001, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:confirm', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000105, '失败重试', 1980000000000000001, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:retry', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000106, '图片删除', 1980000000000000001, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:dedup:remove', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000107, '类目新增', 1980000000000000015, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:category:add', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000108, '类目修改', 1980000000000000015, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:category:edit', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000109, '类目删除', 1980000000000000015, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:category:remove', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000110, '侵权检测查询', 1980000000000000016, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:infringement:list', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1980000000000000111, '侵权失败重检', 1980000000000000016, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'image:infringement:retry', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '')
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
(1980000000000000201, 1980000000000000016),
(1980000000000000201, 1980000000000000101),
(1980000000000000201, 1980000000000000102),
(1980000000000000201, 1980000000000000103),
(1980000000000000201, 1980000000000000104),
(1980000000000000201, 1980000000000000105),
(1980000000000000201, 1980000000000000110),
(1980000000000000201, 1980000000000000111);

-- 已能进入图片管理的角色默认可查看并重检自己的侵权检测结果。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 1980000000000000016 FROM sys_role_menu WHERE menu_id = 1980000000000000001;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 1980000000000000110 FROM sys_role_menu WHERE menu_id = 1980000000000000001;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 1980000000000000111 FROM sys_role_menu WHERE menu_id = 1980000000000000001;

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

-- 2026-09-19 账号权限、最终入库及工作台复查。
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
