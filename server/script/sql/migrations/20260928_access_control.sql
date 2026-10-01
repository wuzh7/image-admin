-- 一次性授权迁移：本地启动可重复调用，但历史权限只迁移一次，之后保留人工设置。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS sys_access_migration (
  version VARCHAR(32) NOT NULL PRIMARY KEY
) ENGINE=InnoDB COMMENT='已完成的权限数据迁移';
SET @seed_access_control = NOT EXISTS(SELECT 1 FROM sys_access_migration WHERE version='20260928');

-- 历史订单归超级管理员，外部导入和之后的人工分配由服务端维护 owner_id。
SET @add_order_owner = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ord_order_detail' AND column_name='owner_id'),
  'DO 0',
  'ALTER TABLE ord_order_detail ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 1761100000000000001 COMMENT ''归属用户ID'' AFTER id'
);
PREPARE stmt FROM @add_order_owner;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
UPDATE ord_order_detail SET owner_id=1761100000000000001 WHERE owner_id IS NULL;

-- 移除重复的角色侧“分配用户”入口，只保留用户侧“分配角色”。
DELETE FROM sys_role_menu WHERE menu_id=1761400000000000130;
DELETE FROM sys_menu WHERE menu_id=1761400000000000130;
INSERT IGNORE INTO sys_menu VALUES
(1761400000000001017,'分配角色权限',1761400000000000101,5,'','','','N','Y','F','0','0','system:role:permission','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1761400000000000102,'菜单管理',1761400000000000001,4,'menu','system/menu/index','','N','Y','C','0','0','system:menu:list','tree-table','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1761400000000001012,'菜单查询',1761400000000000102,1,'','','','N','Y','F','0','0','system:menu:query','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1761400000000001013,'菜单新增',1761400000000000102,2,'','','','N','Y','F','0','0','system:menu:add','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1761400000000001014,'菜单修改',1761400000000000102,3,'','','','N','Y','F','0','0','system:menu:edit','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1761400000000001015,'菜单删除',1761400000000000102,4,'','','','N','Y','F','0','0','system:menu:remove','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1761400000000001024,'设置部门权限',1761400000000000103,5,'','','','N','Y','F','0','0','system:dept:permission','#','','',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,'');

-- 系统管理菜单由权限点控制，保留普通角色已配置的授权。

-- 目录不使用通配权限；旧版已获店铺页面的角色，一次性继承该页面现有操作能力。
UPDATE sys_menu SET perms='' WHERE menu_id=1990000000000000020;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT access.role_id, page.menu_id FROM sys_role_menu access
CROSS JOIN (SELECT 1990000000000000020 menu_id UNION ALL SELECT 1990000000000000027
 UNION ALL SELECT 1990000000000000021 UNION ALL SELECT 1990000000000000022
 UNION ALL SELECT 1990000000000000026 UNION ALL SELECT 1990000000000000024
 UNION ALL SELECT 1990000000000000025) page
WHERE access.menu_id=1980000000000000014 AND @seed_access_control=1;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT access.role_id, button.menu_id FROM sys_role_menu access
JOIN sys_menu button ON button.parent_id=access.menu_id AND button.menu_type='F'
WHERE button.menu_id BETWEEN 1990000000000001201 AND 1990000000000001253 AND @seed_access_control=1;

-- 既有普通用户归入默认部门；按现有部门角色/菜单授权初始化部门上限，仅执行一次。
CREATE TABLE IF NOT EXISTS sys_dept_menu (
  dept_id BIGINT NOT NULL COMMENT '部门ID',
  menu_id BIGINT NOT NULL COMMENT '菜单或按钮ID',
  PRIMARY KEY (dept_id,menu_id),
  KEY idx_sys_dept_menu_mid (menu_id)
) ENGINE=InnoDB COMMENT='部门菜单按钮权限上限';
UPDATE sys_user SET dept_id=1761000000000000100
WHERE dept_id IS NULL AND user_id<>1761100000000000001 AND del_flag='0';
INSERT IGNORE INTO sys_dept_role(dept_id,role_id)
SELECT DISTINCT u.dept_id,ur.role_id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id
WHERE u.dept_id IS NOT NULL AND u.del_flag='0' AND ur.role_id<>1761300000000000001 AND @seed_access_control=1;
INSERT IGNORE INTO sys_dept_menu(dept_id,menu_id)
SELECT DISTINCT dr.dept_id,rm.menu_id FROM sys_dept_role dr
JOIN sys_role_menu rm ON rm.role_id=dr.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.perms NOT LIKE 'tro:%'
 AND m.perms NOT IN ('order:detail:assign','image:dedup:remove')
 AND @seed_access_control=1;
INSERT IGNORE INTO sys_access_migration(version) VALUES ('20260928');
