SET NAMES utf8mb4;

-- 订单明细外部接入与后台管理，可重复执行。
CREATE TABLE IF NOT EXISTS ord_order_detail (
    id                       BIGINT          NOT NULL COMMENT '订单明细ID',
    parent_order_sn          VARCHAR(64)     NOT NULL COMMENT '主订单号',
    fulfillment_mall_id      VARCHAR(64)     DEFAULT NULL COMMENT '店铺ID',
    estimated_amount         DECIMAL(12,2)   DEFAULT NULL COMMENT '运费',
    shipping_settle_amount   DECIMAL(12,2)   DEFAULT NULL COMMENT '订单运费预计冲回',
    sales_settle_amount      DECIMAL(12,2)   DEFAULT NULL COMMENT '子订单预计回款',
    site_name                VARCHAR(128)    DEFAULT NULL COMMENT '销售站点',
    customized_products      VARCHAR(255)    DEFAULT NULL COMMENT '是否定制',
    send_warehouse_name      VARCHAR(128)    DEFAULT NULL COMMENT '下单工厂',
    shipping_company_name    VARCHAR(128)    DEFAULT NULL COMMENT '物流商',
    order_sn                 VARCHAR(64)     NOT NULL COMMENT '子订单号',
    goods_name               VARCHAR(1000)   DEFAULT NULL COMMENT '商品名称',
    sku_quantity             INT             NOT NULL COMMENT '数量',
    spec                     VARCHAR(512)    DEFAULT NULL COMMENT 'SKU',
    thumb_url                VARCHAR(1000)   DEFAULT NULL COMMENT '图片地址',
    create_dept              BIGINT          DEFAULT NULL,
    create_by                BIGINT          DEFAULT NULL,
    create_time              DATETIME        DEFAULT NULL,
    update_by                BIGINT          DEFAULT NULL,
    update_time              DATETIME        DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ord_order_detail_order_sn (order_sn),
    KEY idx_ord_order_detail_parent (parent_order_sn),
    KEY idx_ord_order_detail_mall (fulfillment_mall_id),
    KEY idx_ord_order_detail_warehouse (send_warehouse_name),
    KEY idx_ord_order_detail_shipping (shipping_company_name),
    CONSTRAINT chk_ord_order_detail_quantity CHECK (sku_quantity > 0),
    CONSTRAINT chk_ord_order_detail_amount CHECK (estimated_amount IS NULL OR estimated_amount >= 0)
) ENGINE=InnoDB COMMENT='订单明细';

SET @add_shipping_settle_amount = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ord_order_detail'
             AND column_name = 'shipping_settle_amount'),
    'DO 0',
    'ALTER TABLE ord_order_detail ADD COLUMN shipping_settle_amount DECIMAL(12,2) DEFAULT NULL COMMENT ''订单运费预计冲回'' AFTER estimated_amount'
);
PREPARE stmt FROM @add_shipping_settle_amount;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_sales_settle_amount = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ord_order_detail'
             AND column_name = 'sales_settle_amount'),
    'DO 0',
    'ALTER TABLE ord_order_detail ADD COLUMN sales_settle_amount DECIMAL(12,2) DEFAULT NULL COMMENT ''子订单预计回款'' AFTER shipping_settle_amount'
);
PREPARE stmt FROM @add_sales_settle_amount;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_site_name = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ord_order_detail'
             AND column_name = 'site_name'),
    'DO 0',
    'ALTER TABLE ord_order_detail ADD COLUMN site_name VARCHAR(128) DEFAULT NULL COMMENT ''销售站点'' AFTER sales_settle_amount'
);
PREPARE stmt FROM @add_site_name;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_customized_products = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ord_order_detail'
             AND column_name = 'customized_products'),
    'DO 0',
    'ALTER TABLE ord_order_detail ADD COLUMN customized_products VARCHAR(255) DEFAULT NULL COMMENT ''是否定制'' AFTER site_name'
);
PREPARE stmt FROM @add_customized_products;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO sys_menu VALUES
(1990000000000000010, '订单明细', 0, 3, 'order-details', 'order/detail/index', '', 'N', 'Y', 'C', '0', '0', 'order:detail:list', 'unordered-list', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '维护外部接入的订单明细'),
(1990000000000000101, '订单明细查询', 1990000000000000010, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'order:detail:query', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1990000000000000102, '订单明细新增', 1990000000000000010, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'order:detail:add', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1990000000000000103, '订单明细修改', 1990000000000000010, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'order:detail:edit', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1990000000000000104, '订单明细删除', 1990000000000000010, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'order:detail:remove', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, ''),
(1990000000000000105, '订单明细导出', 1990000000000000010, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'order:detail:export', '#', '', '', 1761000000000000103, 1761100000000000001, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    path = VALUES(path), component = VALUES(component), menu_type = VALUES(menu_type), visible = VALUES(visible),
    perms = VALUES(perms), icon = VALUES(icon), remark = VALUES(remark);

-- 菜单只分配给超级管理员；后端仍以角色校验作为最终授权边界。
DELETE role_menu
FROM sys_role_menu role_menu
INNER JOIN sys_role role ON role.role_id = role_menu.role_id
WHERE role_menu.menu_id IN (
    1990000000000000010,
    1990000000000000101,
    1990000000000000102,
    1990000000000000103,
    1990000000000000104,
    1990000000000000105
)
  AND role.role_key <> 'superadmin';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role
CROSS JOIN (
    SELECT 1990000000000000010 AS menu_id
    UNION ALL SELECT 1990000000000000101
    UNION ALL SELECT 1990000000000000102
    UNION ALL SELECT 1990000000000000103
    UNION ALL SELECT 1990000000000000104
    UNION ALL SELECT 1990000000000000105
) menu
WHERE role.role_key = 'superadmin';
