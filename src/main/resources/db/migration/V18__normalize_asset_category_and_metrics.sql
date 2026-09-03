ALTER TABLE `asset`
    ADD COLUMN `category` VARCHAR(50) NOT NULL DEFAULT '其他' COMMENT '成果分类' AFTER `description`,
    ADD COLUMN `download_count` INT NOT NULL DEFAULT 0 COMMENT '下载次数' AFTER `view_count`,
    ADD INDEX `idx_asset_category` (`category`);

UPDATE `asset`
SET `category` = TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(`description`, '（分类：', -1), '）', 1))
WHERE `description` LIKE '%（分类：%）%';

UPDATE `asset`
SET `description` = TRIM(REPLACE(`description`, CONCAT('（分类：', `category`, '）'), ''))
WHERE `description` LIKE '%（分类：%）%';
