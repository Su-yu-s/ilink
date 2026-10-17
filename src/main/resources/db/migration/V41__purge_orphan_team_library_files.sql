-- 清理"队伍已删除但资料库记录还在"的历史孤儿（核实报告 P2-4）。
--
-- 根因已在 AdminDataService.deleteTeamInternal 修复（补上 team_library_files 的级联清理），
-- 这里只处理修复前已经积压的存量数据：team_id 指向不存在的队伍。
-- 这类记录无法再通过 API 删除（文件接口要求队伍存在 + 成员权限），只能一次性清库。
--
-- 幂等：删除条件基于"队伍不存在"，重复执行不会命中任何新行。

DELETE FROM `team_library_files`
WHERE `team_id` NOT IN (SELECT `id` FROM (SELECT `id` FROM `team_demand`) AS live_teams);
