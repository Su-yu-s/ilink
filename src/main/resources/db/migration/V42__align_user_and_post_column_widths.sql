-- 用户表/帖子表若干 VARCHAR 列宽与迁移声明不一致（全库比对发现，见 scripts/schema_drift_audit.js）。
--
-- 现象：迁移与实体/校验都按更宽的上限声明，线上库却更窄，属于「代码允许 -> 库里拒绝」的方向，
-- 会在边界输入时表现为 500 或"提交的数据不符合要求"这类与字段无关的通用错误
-- （与 10-03 报告 P2-5 的 team_demand.title 同一根因，那次已由 V40 修掉）。
--
--   user.username   VARCHAR(50)  -> VARCHAR(100)   （ControllerUtils.USERNAME_MAX=100）
--   user.password   VARCHAR(200) -> VARCHAR(255)   （BCrypt 哈希 60 字符，留足未来换算法的空间）
--   user.avatar     VARCHAR(255) -> VARCHAR(500)   （站内上传 URL）
--   user.real_name  VARCHAR(50)  -> VARCHAR(100)   （REAL_NAME_MAX=100）
--   user.major      VARCHAR(64)  -> VARCHAR(100)   （MAJOR_MAX=100）
--   user.school     VARCHAR(100) -> VARCHAR(100) 列已是 128，声明 100 更窄，这里以「声明」为准收敛
--   user.college    VARCHAR(128) -> VARCHAR(100)  同上
--   user.grade      VARCHAR(32)  -> VARCHAR(20)
--   community_post.category VARCHAR(32) -> VARCHAR(50)（V12 声明 50）
--
-- 加宽/收敛都只改列定义，不动数据：已核对现有数据最大长度（username 13、password 60、avatar 78、
-- real_name 12、grade 4、major 8、school 6、college 13、category 8），全部远小于目标上限，
-- 收敛方向（school/college/grade）也已逐列核对无超限值。语句幂等，重复执行同样安全。

ALTER TABLE `user`
    MODIFY COLUMN `username`  VARCHAR(100) NOT NULL COMMENT '用户名',
    MODIFY COLUMN `password`  VARCHAR(255) NOT NULL COMMENT '密码哈希',
    MODIFY COLUMN `avatar`    VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    MODIFY COLUMN `real_name` VARCHAR(100) DEFAULT NULL COMMENT '真实姓名',
    MODIFY COLUMN `grade`     VARCHAR(20)  DEFAULT NULL COMMENT '年级',
    MODIFY COLUMN `major`     VARCHAR(100) DEFAULT NULL COMMENT '专业',
    MODIFY COLUMN `school`    VARCHAR(100) DEFAULT NULL COMMENT '学校',
    MODIFY COLUMN `college`   VARCHAR(100) DEFAULT NULL COMMENT '学院';

ALTER TABLE `community_post`
    MODIFY COLUMN `category` VARCHAR(50) NOT NULL DEFAULT 'general' COMMENT '分区: general/tech/competition/resource';
