-- 队伍与竞赛语义拆分：category = 队伍做什么（项目方向），competition = 参加哪场比赛。
-- 此前发布表单把「分类」硬编码映射成 competition_id 1-4 存库，语义错乱；
-- 现将旧值回填为 category 后清空 competition_id，参赛竞赛改为「选目录或自填」且必填（应用层校验）。
ALTER TABLE team_demand
    ADD COLUMN category VARCHAR(20) NULL COMMENT '项目方向：技术开发/创意设计/市场营销/学术研究',
    ADD COLUMN custom_competition VARCHAR(160) NULL COMMENT '自填竞赛名称（目录中没有时使用，不回写竞赛目录）';

-- 旧数据清洗：competition_id 1-4 是被分类污染的值，按旧映射回填 category 后置空
UPDATE team_demand
SET category = ELT(competition_id, '技术开发', '创意设计', '市场营销', '学术研究')
WHERE competition_id BETWEEN 1 AND 4;
UPDATE team_demand SET competition_id = NULL WHERE competition_id BETWEEN 1 AND 4;
