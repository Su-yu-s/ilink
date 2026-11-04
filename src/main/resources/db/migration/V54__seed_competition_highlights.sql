-- 给种子赛事补上核心考察点。内容取自确认过的设计稿，不是编造。
-- 单独成迁移：V53 只负责加列，已经执行过，不能再改它的内容。

UPDATE `competition` SET `highlights` = JSON_ARRAY(
    JSON_OBJECT('t', '商业落地', 'd', '项目是否具备真实的商业闭环、盈利模式与市场验证。'),
    JSON_OBJECT('t', '技术创新', 'd', '核心技术壁垒、专利情况以及与竞品的差异化优势。'),
    JSON_OBJECT('t', '路演能力', 'd', '团队表达、材料制作及应对评委答辩的逻辑能力。')
) WHERE `name` = '中国国际大学生创新大赛（原“互联网+”）';

UPDATE `competition` SET `highlights` = JSON_ARRAY(
    JSON_OBJECT('t', '学术深度', 'd', '自然科学类学术论文、哲学社会科学类社会调查报告的严谨性。'),
    JSON_OBJECT('t', '科技发明', 'd', '科技发明制作（A/B 类）的创新性与实物展示效果。'),
    JSON_OBJECT('t', '答辩质询', 'd', '国赛现场采用封闭式答辩与公开展示结合，极其考验学术功底。')
) WHERE `name` = '“挑战杯”全国大学生课外学术科技作品竞赛';

UPDATE `competition` SET `highlights` = JSON_ARRAY(
    JSON_OBJECT('t', '问题抽象', 'd', '将实际工程或生活问题转化为数学语言。'),
    JSON_OBJECT('t', '模型构建与求解', 'd', '熟练运用微积分、统计学、运筹学或机器学习算法。'),
    JSON_OBJECT('t', '科技论文写作', 'd', '论文排版、图表制作、摘要提炼——摘要往往是获奖关键。')
) WHERE `name` = '全国大学生数学建模竞赛';

UPDATE `competition` SET `highlights` = JSON_ARRAY(
    JSON_OBJECT('t', '算法与数据结构', 'd', '动态规划、图论、高级数据结构、计算几何等。'),
    JSON_OBJECT('t', '抗压与策略', 'd', '极短时间内完成题目分配、跟榜策略与代码调试。'),
    JSON_OBJECT('t', '团队配合', 'd', '三人分工明确（代码手、思维手、辅助），默契度决定上限。')
) WHERE `name` = 'ACM-ICPC / CCPC 系列';
