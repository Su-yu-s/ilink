-- ============================================================
-- iLink 演示种子数据 · 第三部分：导师合作申请 + 公开成果
-- 目的：让导师详情页的「已通过合作」「公开成果」两个统计有真实数字。
-- 依赖：demo_showcase_seed.sql、demo_showcase_teams.sql
-- ============================================================

SET NAMES utf8mb4;

START TRANSACTION;

-- ============================================================
-- 7. 导师合作申请（project_application）
--    注意：teacher_id 存的是 teacher_application.id（导师档案 id），不是 user.id
-- ============================================================
INSERT INTO `project_application` (`teacher_id`, `user_id`, `status`, `message`, `created_at`)
SELECT p.teacher_profile_id, u.id, p.status, p.message, p.created_at
FROM (
    -- 每个导师档案 1-3 条 APPROVED 合作
    SELECT 5  AS teacher_profile_id, 'ilink_demo_s_chenyu'  AS uname, 'APPROVED' AS status, '希望加入毕设团队，做分布式调度方向。' AS message, DATE_SUB(NOW(), INTERVAL 25 DAY) AS created_at
    UNION ALL SELECT 5,  'ilink_demo_s_luobeiya', 'APPROVED', '对前端架构很感兴趣，想参与一个完整项目周期。', DATE_SUB(NOW(), INTERVAL 18 DAY)
    UNION ALL SELECT 5,  'ilink_demo_s_shenxing', 'PENDING',  '运维方向，想补云原生经验。', DATE_SUB(NOW(), INTERVAL 4 DAY)
    UNION ALL SELECT 6,  'ilink_demo_s_jiangzhi',  'APPROVED', 'CV 方向，想进实验室做目标检测。', DATE_SUB(NOW(), INTERVAL 30 DAY)
    UNION ALL SELECT 6,  'ilink_demo_s_zhoumu',    'APPROVED', 'NLP 背景，愿意转做多模态。', DATE_SUB(NOW(), INTERVAL 22 DAY)
    UNION ALL SELECT 6,  'ilink_demo_s_liushu',    'PENDING',  '数据方向，想接触模型训练全流程。', DATE_SUB(NOW(), INTERVAL 6 DAY)
    UNION ALL SELECT 7,  'ilink_demo_s_heyue',     'APPROVED', '数据可视化课题，想负责看板部分。', DATE_SUB(NOW(), INTERVAL 28 DAY)
    UNION ALL SELECT 7,  'ilink_demo_s_ainuo',     'PENDING',  '产品视角想参与用研设计。', DATE_SUB(NOW(), INTERVAL 5 DAY)
    UNION ALL SELECT 8,  'ilink_demo_s_xueqin',    'APPROVED', '想系统学品牌设计，手头有插画基础。', DATE_SUB(NOW(), INTERVAL 35 DAY)
    UNION ALL SELECT 8,  'ilink_demo_s_luyang',    'APPROVED', '三维方向，希望补平面与包装设计。', DATE_SUB(NOW(), INTERVAL 26 DAY)
    UNION ALL SELECT 9,  'ilink_demo_s_tangwei',   'APPROVED', '嵌入式方向，想跟老师做物联网课题。', DATE_SUB(NOW(), INTERVAL 33 DAY)
    UNION ALL SELECT 9,  'ilink_demo_s_muyan',     'APPROVED', '硬件测试方向，愿意长期跟进。', DATE_SUB(NOW(), INTERVAL 24 DAY)
    UNION ALL SELECT 9,  'ilink_demo_s_shuhe',     'PENDING',  '传感与信号处理，想进实验室。', DATE_SUB(NOW(), INTERVAL 3 DAY)
    UNION ALL SELECT 10, 'ilink_demo_s_qiaowei',   'APPROVED', '运营方向，想跟老师做真实企业案例。', DATE_SUB(NOW(), INTERVAL 31 DAY)
    UNION ALL SELECT 10, 'ilink_demo_s_hanzhi',    'APPROVED', '外联与谈判，想学数字营销方法论。', DATE_SUB(NOW(), INTERVAL 20 DAY)
    UNION ALL SELECT 11, 'ilink_demo_s_yanjiu',    'APPROVED', '算法底子有，想学建模论文写作。', DATE_SUB(NOW(), INTERVAL 27 DAY)
    UNION ALL SELECT 11, 'ilink_demo_s_peiying',   'APPROVED', '建模与写作方向，愿意赛前集训。', DATE_SUB(NOW(), INTERVAL 23 DAY)
    UNION ALL SELECT 12, 'ilink_demo_s_wanshu',    'APPROVED', '内容创作方向，想做出校园媒体品牌。', DATE_SUB(NOW(), INTERVAL 29 DAY)
    UNION ALL SELECT 12, 'ilink_demo_s_ruoxi',     'APPROVED', '访谈与脚本，想学新媒体写作。', DATE_SUB(NOW(), INTERVAL 21 DAY)
) p
JOIN `user` u ON u.username = p.uname
WHERE EXISTS (SELECT 1 FROM `teacher_application` t WHERE t.id = p.teacher_profile_id)
  AND NOT EXISTS (
    SELECT 1 FROM `project_application` x
    WHERE x.teacher_id = p.teacher_profile_id AND x.user_id = u.id
  );

-- ============================================================
-- 8. 公开成果（asset）—— 归属种子用户，让导师/学生主页有成果
-- ============================================================
INSERT INTO `asset` (`title`, `description`, `category`, `file_url`, `cover_url`, `user_id`, `view_count`, `download_count`, `is_pinned`, `created_at`)
SELECT a.title, a.description, a.category, a.file_url, a.cover_url, u.id, a.view_count, a.download_count, a.is_pinned, a.created_at
FROM (
    SELECT 'ilink_demo_s_chenyu' AS uname,
           'iLink 团队协作平台' AS title,
           '面向高校场景的团队协作平台，包含组队大厅、任务看板、团队空间与即时通讯四个核心模块。后端 Spring Boot + MyBatis-Plus，前端 Vue 3 + Vite，数据库 MySQL 8。' AS description,
           '作品项目' AS category,
           NULL AS file_url,
           NULL AS cover_url,
           328 AS view_count,
           46 AS download_count,
           1 AS is_pinned,
           DATE_SUB(NOW(), INTERVAL 40 DAY) AS created_at
    UNION ALL SELECT 'ilink_demo_s_chenyu',
           '分布式任务调度器 mini-scheduler',
           '基于 Quartz 改造的轻量分布式调度器，支持分片广播、故障转移与执行日志追踪。star 数 120+。',
           '作品项目', NULL, NULL, 156, 32, 0, DATE_SUB(NOW(), INTERVAL 65 DAY)
    UNION ALL SELECT 'ilink_demo_s_luobeiya',
           '无障碍组件库 a11y-vue',
           '面向 Vue 3 的无障碍组件库，覆盖 28 个常用组件，全部通过 WCAG 2.1 AA 级校验，附键盘操作与读屏适配指南。',
           '作品项目', NULL, NULL, 214, 28, 1, DATE_SUB(NOW(), INTERVAL 33 DAY)
    UNION ALL SELECT 'ilink_demo_s_jiangzhi',
           '工业缺陷检测数据集与基线模型',
           '采集自本地两家合作企业的 6 类缺陷图像共 8600 张，附标注规范、YOLOv8 基线模型与评测脚本，mAP@0.5 = 0.82。',
           '研究成果', NULL, NULL, 189, 54, 0, DATE_SUB(NOW(), INTERVAL 52 DAY)
    UNION ALL SELECT 'ilink_demo_s_zhoumu',
           '课程知识图谱构建流水线',
           '从课程讲义与论文中自动抽取实体与关系，构建含 370 实体、249 关系的知识图谱，支持向量 + 图遍历混合检索。',
           '研究成果', NULL, NULL, 143, 21, 0, DATE_SUB(NOW(), INTERVAL 47 DAY)
    UNION ALL SELECT 'ilink_demo_s_heyue',
           '校园食堂消费数据可视化报告',
           '基于一年脱敏消费数据的交互式分析报告，覆盖高峰时段、品类偏好与价格敏感度三个维度，ECharts 实现。',
           '作品项目', NULL, NULL, 267, 39, 0, DATE_SUB(NOW(), INTERVAL 29 DAY)
    UNION ALL SELECT 'ilink_demo_s_tangwei',
           '温室环境监控节点设计与实现',
           'ESP32 + 多路传感器的低功耗监控节点，含 PCB、固件与本地看板，连续运行 90 天无掉线。',
           '作品项目', NULL, NULL, 176, 24, 0, DATE_SUB(NOW(), INTERVAL 58 DAY)
    UNION ALL SELECT 'ilink_demo_s_yanjiu',
           '竞赛算法模板库',
           '整理自三年竞赛训练的算法模板，覆盖图论、动态规划、计算几何与字符串，共 210 个模板与 60 篇题解。',
           '学习资料', NULL, NULL, 402, 88, 1, DATE_SUB(NOW(), INTERVAL 70 DAY)
    UNION ALL SELECT 'ilink_demo_s_xueqin',
           '校园文创品牌视觉系统',
           '为校园文创社团设计的完整视觉系统，含 Logo、标准色、包装规范与 40 余件延展物料，已投入实际生产。',
           '设计作品', NULL, NULL, 233, 17, 0, DATE_SUB(NOW(), INTERVAL 44 DAY)
    UNION ALL SELECT 'ilink_demo_s_qiaowei',
           '校园音乐节招商方案复盘',
           '500 人规模校园音乐节的完整招商与运营复盘，含预算表、排期表与 12 家赞助商谈判要点。',
           '文档报告', NULL, NULL, 118, 15, 0, DATE_SUB(NOW(), INTERVAL 36 DAY)
    UNION ALL SELECT 'ilink_demo_s_wanshu',
           '《晓庄侧影》人物访谈合集',
           '24 期校园人物访谈的文字与影像合集，累计阅读 12 万+，含采访提纲、录音整理与成稿流程说明。',
           '设计作品', NULL, NULL, 351, 42, 0, DATE_SUB(NOW(), INTERVAL 62 DAY)
    UNION ALL SELECT 'ilink_demo_s_baichuan',
           'CTF 校赛题解合集',
           'CTF 校赛全部赛题的题解与复现环境，覆盖 Web、Pwn、Crypto、Misc 四方向，附 Docker 一键部署。',
           '学习资料', NULL, NULL, 298, 63, 0, DATE_SUB(NOW(), INTERVAL 49 DAY)
    UNION ALL SELECT 'ilink_demo_s_guqi',
           '模块化六轴教学机械臂',
           '可拼装的六轴教学机械臂，含结构图纸、运动学标定流程与 ROS 驱动，单轴重复定位精度 ±0.05mm。',
           '作品项目', NULL, NULL, 164, 19, 0, DATE_SUB(NOW(), INTERVAL 41 DAY)
) a
JOIN `user` u ON u.username = a.uname
WHERE NOT EXISTS (
    SELECT 1 FROM `asset` x WHERE x.title = a.title AND x.user_id = u.id
);

-- ============================================================
-- 9. 导师公开成果（asset.user_id = 导师的 user.id）
--    让导师详情页「公开成果」统计不为 0
-- ============================================================
INSERT INTO `asset` (`title`, `description`, `category`, `file_url`, `cover_url`, `user_id`, `view_count`, `download_count`, `is_pinned`, `created_at`)
SELECT a.title, a.description, a.category, a.file_url, a.cover_url, u.id, a.view_count, a.download_count, a.is_pinned, a.created_at
FROM (
    SELECT 'ilink_demo_t_prof_lin' AS uname,
           '软件架构设计一流课程建设包' AS title,
           '省级一流本科课程《软件架构设计》的完整建设资料，含教学大纲、案例库、实验指导书与评测标准，已被 6 所高校采用。' AS description,
           '教学成果' AS category, NULL AS file_url, NULL AS cover_url, 412 AS view_count, 96 AS download_count, 1 AS is_pinned, DATE_SUB(NOW(), INTERVAL 120 DAY) AS created_at
    UNION ALL SELECT 'ilink_demo_t_prof_lin',
           '微服务演进：从单体到服务网格',
           '以一个真实电商系统为例，完整演示单体拆分、服务治理到服务网格的演进路径，附可运行的实验代码与压测报告。',
           '研究成果', NULL, NULL, 287, 61, 0, DATE_SUB(NOW(), INTERVAL 95 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_gu',
           '小样本工业缺陷检测方法研究',
           '针对工业场景标注成本高的问题，提出基于对比学习与伪标签的小样本检测方法，在 3 个数据集上验证，论文已被核心期刊录用。',
           '研究成果', NULL, NULL, 356, 74, 1, DATE_SUB(NOW(), INTERVAL 150 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_gu',
           '计算机视觉实验教学案例集',
           '面向本科教学的 24 个 CV 实验案例，从图像滤波到 Transformer 逐层递进，均提供数据集、代码与评分细则。',
           '教学成果', NULL, NULL, 198, 52, 0, DATE_SUB(NOW(), INTERVAL 108 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_shen',
           '校园数据治理与开放共享白皮书',
           '面向高校数据治理的实践白皮书，涵盖数据标准、共享流程与安全边界，已被校内 8 个部门采用。',
           '文档报告', NULL, NULL, 241, 68, 1, DATE_SUB(NOW(), INTERVAL 135 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_shen',
           '推荐系统实验平台',
           '支持多模型对比的推荐系统实验平台，内置 4 个公开数据集与召回-排序-重排全链路可视化，用于本科教学与毕设。',
           '作品项目', NULL, NULL, 176, 44, 0, DATE_SUB(NOW(), INTERVAL 88 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_xu',
           '视觉传达设计实务（教材）',
           '校企合作编写的应用型教材，含 32 个真实商业案例与配套素材，已被 15 所高校选作专业课教材。',
           '教学成果', NULL, NULL, 389, 121, 1, DATE_SUB(NOW(), INTERVAL 210 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_xu',
           '校园 VI 系统升级项目成果集',
           '主持完成的校园视觉识别系统升级，含基础系统、应用系统与延展规范共 180 页规范文档。',
           '设计作品', NULL, NULL, 264, 37, 0, DATE_SUB(NOW(), INTERVAL 76 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_he',
           '低功耗物联网节点设计（发明专利）',
           '一种面向农业监测的低功耗物联网节点设计，休眠电流降至 12μA，已获发明专利授权（专利号 ZL2023xxxxxxx）。',
           '研究成果', NULL, NULL, 213, 29, 0, DATE_SUB(NOW(), INTERVAL 165 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_he',
           '电子设计竞赛集训实验手册',
           '七年竞赛指导经验沉淀的实验手册，含 18 个必做实验、常见故障排查表与器件选型建议。',
           '教学成果', NULL, NULL, 302, 87, 1, DATE_SUB(NOW(), INTERVAL 112 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_cen',
           '新媒体营销实务（校本教材）',
           '依托 6 个真实企业咨询案例编写的校本教材，覆盖内容、投放、转化与复购全链路。',
           '教学成果', NULL, NULL, 189, 41, 0, DATE_SUB(NOW(), INTERVAL 92 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_qi',
           '数学建模案例精讲',
           '从国赛与美赛真题中精选 20 个案例，按模型假设、求解、检验到论文写作逐步拆解，附完整论文模板。',
           '教学成果', NULL, NULL, 476, 152, 1, DATE_SUB(NOW(), INTERVAL 190 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_qi',
           '优化理论数值实验库',
           '覆盖线性/非线性规划、整数规划与启发式算法的 MATLAB 实验库，共 42 个实验与 60 道练习题。',
           '学习资料', NULL, NULL, 167, 48, 0, DATE_SUB(NOW(), INTERVAL 118 DAY)
    UNION ALL SELECT 'ilink_demo_t_prof_luo',
           '校园媒体内容生产流程规范',
           '从选题、采访、成稿到发布的完整 SOP，含选题会模板、采访提纲库与三审流程表，已在校内 9 个媒体账号落地。',
           '文档报告', NULL, NULL, 156, 33, 0, DATE_SUB(NOW(), INTERVAL 84 DAY)
) a
JOIN `user` u ON u.username = a.uname
WHERE NOT EXISTS (
    SELECT 1 FROM `asset` x WHERE x.title = a.title AND x.user_id = u.id
);

COMMIT;

SELECT 'project_application seed' AS step, COUNT(*) AS cnt FROM `project_application` p JOIN `user` u ON u.id = p.user_id WHERE u.username LIKE 'ilink_demo_%'
UNION ALL SELECT 'approved collaborations', COUNT(*) FROM `project_application` p JOIN `user` u ON u.id = p.user_id WHERE u.username LIKE 'ilink_demo_%' AND p.status = 'APPROVED'
UNION ALL SELECT 'student assets', COUNT(*) FROM `asset` a JOIN `user` u ON u.id = a.user_id WHERE u.username LIKE 'ilink_demo_s_%'
UNION ALL SELECT 'teacher assets', COUNT(*) FROM `asset` a JOIN `user` u ON u.id = a.user_id WHERE u.username LIKE 'ilink_demo_t_%';
