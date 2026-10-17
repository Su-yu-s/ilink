-- 历史 username 清洗：手机号 / 学号曾直接作为 username 与 real_name 写入，
-- 二者都会经公开接口（/api/user/public/{id}、userPreview）泄露给任意访客。
--
-- 语句顺序不可调换：
--   1. 先把「注册时被自动填成注册标识」的 real_name 置空——此时 username 还是旧值，
--      仍能与 real_name 比较；若先改 username 就再也无法区分「用户自己填的姓名」与
--      「注册时塞进去的手机号」。
--   2. 再把旧格式 username 换成 u + 8 位补零主键。
--
-- 补零形式必然含 0，而随机生成器（UserServiceImpl.USERNAME_ALPHABET）不含 0，
-- 因此迁移值与新注册值不可能碰撞。

UPDATE `user`
SET `real_name` = NULL
WHERE `real_name` = `username`
  AND `username` REGEXP '^([0-9]{11}|[st][0-9]+)$';

UPDATE `user`
SET `username` = CONCAT('u', LPAD(`id`, 8, '0'))
WHERE `username` REGEXP '^([0-9]{11}|[st][0-9]+)$';
