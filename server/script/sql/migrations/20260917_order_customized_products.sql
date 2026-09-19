SET NAMES utf8mb4;

SET @add_customized_products = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ord_order_detail'
             AND column_name = 'customized_products'),
    'DO 0',
    'ALTER TABLE ord_order_detail ADD COLUMN customized_products VARCHAR(255) DEFAULT NULL COMMENT ''是否定制'''
);
PREPARE stmt FROM @add_customized_products;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
