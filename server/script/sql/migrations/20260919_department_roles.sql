CREATE TABLE IF NOT EXISTS sys_dept_role (
    dept_id BIGINT NOT NULL COMMENT '部门ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (dept_id, role_id),
    KEY idx_sys_dept_role_rid (role_id)
) ENGINE=InnoDB COMMENT='部门可用角色关联表';

INSERT IGNORE INTO sys_dept_role (dept_id, role_id)
SELECT DISTINCT u.dept_id, ur.role_id
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.user_id
WHERE u.dept_id IS NOT NULL;

INSERT IGNORE INTO sys_menu VALUES
(1761400000000000103, '部门管理', 1761400000000000001, 3, 'dept', 'system/dept/index', '', 'N', 'Y', 'C', '0', '0', 'system:dept:list', 'tree', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '部门管理菜单'),
(1761400000000001020, '部门查询', 1761400000000000103, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:query', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1761400000000001021, '部门新增', 1761400000000000103, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:add', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1761400000000001022, '部门修改', 1761400000000000103, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:edit', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1761400000000001023, '部门删除', 1761400000000000103, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:remove', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '');
