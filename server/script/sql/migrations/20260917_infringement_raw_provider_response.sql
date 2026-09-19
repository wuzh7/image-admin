-- 在当前主站业务数据库执行；可重复执行，仅增加调用日志字段，不修改现有数据。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'infringement_call_log' AND column_name = 'raw_provider_response'), 'SELECT 1', 'ALTER TABLE infringement_call_log ADD COLUMN raw_provider_response MEDIUMTEXT NULL COMMENT ''上游完整响应JSON，Batch保存当前图片完整条目'' AFTER raw_ai_response');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
