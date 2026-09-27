SET NAMES utf8mb4;

-- 先建表和菜单，再用 import_store_probe_xlsx.py 导入线上 Excel；核对行数后才能切换新版服务。
CREATE TABLE IF NOT EXISTS store_probe (
 id BIGINT NOT NULL, store_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, store_name VARCHAR(255) DEFAULT NULL, remarks VARCHAR(500) DEFAULT NULL,
 create_dept BIGINT DEFAULT NULL, create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,
 update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_store_probe_store_id(store_id)
) ENGINE=InnoDB COMMENT='第三方接口店铺目录';

INSERT INTO sys_menu VALUES
(1990000000000000030,'店铺探测配置',1761400000000000001,6,'store-probe','system/store-probe/index','','N','Y','C','0','0','system:store-probe:list','shop', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,'维护线上第三方接口使用的店铺目录'),
(1990000000000001101,'店铺探测查询',1990000000000000030,1,'','','','N','Y','F','0','0','system:store-probe:query','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001102,'店铺探测新增',1990000000000000030,2,'','','','N','Y','F','0','0','system:store-probe:add','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001103,'店铺探测修改',1990000000000000030,3,'','','','N','Y','F','0','0','system:store-probe:edit','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,''),
(1990000000000001104,'店铺探测删除',1990000000000000030,4,'','','','N','Y','F','0','0','system:store-probe:remove','#', '', '',1761000000000000103,1761100000000000001,SYSDATE(),NULL,NULL,'')
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),parent_id=VALUES(parent_id),order_num=VALUES(order_num),path=VALUES(path),component=VALUES(component),menu_type=VALUES(menu_type),visible=VALUES(visible),perms=VALUES(perms),icon=VALUES(icon),remark=VALUES(remark);

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role_id,menu.menu_id FROM sys_role CROSS JOIN (
 SELECT 1990000000000000030 menu_id UNION ALL SELECT 1990000000000001101 UNION ALL SELECT 1990000000000001102
 UNION ALL SELECT 1990000000000001103 UNION ALL SELECT 1990000000000001104
) menu WHERE role_key='superadmin';
