SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tro_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_no_format VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    case_name VARCHAR(512) DEFAULT NULL,
    court VARCHAR(128) DEFAULT NULL,
    judge VARCHAR(128) DEFAULT NULL,
    description VARCHAR(255) DEFAULT NULL,
    nature_of_suit VARCHAR(128) DEFAULT NULL,
    cause VARCHAR(255) DEFAULT NULL,
    date_filed DATE DEFAULT NULL,
    plaintiff VARCHAR(255) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    plaintiff_cn VARCHAR(255) DEFAULT NULL,
    plaintiff_names JSON DEFAULT NULL,
    lawyer VARCHAR(128) DEFAULT NULL,
    law_firms VARCHAR(128) DEFAULT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tro_case_identity (case_no_format, plaintiff),
    KEY idx_tro_case_date (date_filed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TRO案件信息';

ALTER TABLE tro_case MODIFY COLUMN date_filed DATE DEFAULT NULL;

INSERT INTO sys_menu VALUES
(1990000000000000030, 'TRO信息', 0, 4, 'tro', 'tro/index', '', 'N', 'Y', 'C', '0', '0', 'tro:case:list', 'file-search', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, 'TRO案件查询与新增'),
(1990000000000000301, 'TRO查询', 1990000000000000030, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'tro:case:query', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1990000000000000302, 'TRO新增', 1990000000000000030, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'tro:case:add', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    path = VALUES(path), component = VALUES(component), menu_type = VALUES(menu_type), visible = VALUES(visible),
    perms = VALUES(perms), icon = VALUES(icon), remark = VALUES(remark);

DELETE role_menu FROM sys_role_menu role_menu
INNER JOIN sys_role role ON role.role_id = role_menu.role_id
WHERE role_menu.menu_id IN (1990000000000000030, 1990000000000000301, 1990000000000000302)
  AND role.role_key <> 'superadmin';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
CROSS JOIN (
    SELECT 1990000000000000030 AS menu_id
    UNION ALL SELECT 1990000000000000301
    UNION ALL SELECT 1990000000000000302
) menu WHERE role.role_key = 'superadmin';
