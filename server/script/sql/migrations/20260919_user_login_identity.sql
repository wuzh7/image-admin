-- 用户账号用于登录，用户名称用于展示；两者在未删除用户中均保持唯一。
SET @user_identity_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'active_nick_name'),
    'SELECT 1',
    'ALTER TABLE sys_user ADD COLUMN active_nick_name VARCHAR(30) GENERATED ALWAYS AS (CASE WHEN del_flag = ''0'' THEN nick_name ELSE NULL END) STORED'
);
PREPARE user_identity_stmt FROM @user_identity_ddl;
EXECUTE user_identity_stmt;
DEALLOCATE PREPARE user_identity_stmt;

SET @user_identity_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND index_name = 'uk_sys_user_active_nick_name'),
    'SELECT 1',
    'ALTER TABLE sys_user ADD UNIQUE INDEX uk_sys_user_active_nick_name (active_nick_name)'
);
PREPARE user_identity_stmt FROM @user_identity_ddl;
EXECUTE user_identity_stmt;
DEALLOCATE PREPARE user_identity_stmt;
