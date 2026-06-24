-- 个人中心「成果展示」：奖学金、荣誉称号、竞赛奖项等（JSON 数组存于 user.honors）
-- 若已存在该列，执行会报错 Duplicate column，可忽略。
-- 用法：mysql -u... -p... ilink < sql/user_honors.sql
ALTER TABLE `user`
    ADD COLUMN `honors` TEXT NULL COMMENT 'JSON: 个人荣誉与成果条目' AFTER `gender`;
