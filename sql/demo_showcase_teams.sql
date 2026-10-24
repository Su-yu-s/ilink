-- ============================================================
-- iLink 演示种子数据 · 第二部分：组队大厅 / 团队 / 成果 / 合作申请
-- 依赖：demo_showcase_seed.sql 第一部分（用户 + 导师档案）
-- 特性：可重复执行（幂等），按标题去重；不修改既有任何数据。
-- ============================================================

SET NAMES utf8mb4;

START TRANSACTION;

-- ============================================================
-- 4. 组队需求（team_demand）—— 覆盖 4 个分类、OPEN / TEAMING / CLOSED 三态
--    竞赛绑定 competition_id（competition 目录真实数据）
-- ============================================================
INSERT INTO `team_demand`
(`title`, `description`, `competition_id`, `custom_competition`, `category`, `required_skills`,
 `required_member_count`, `deadline`, `status`, `creator_id`, `created_at`, `updated_at`)
SELECT
    d.title, d.description, d.competition_id, d.custom_competition, d.category, d.required_skills,
    d.required_member_count, d.deadline, d.status, u.id, d.created_at, d.created_at
FROM `user` u
JOIN (
    SELECT 'ilink_demo_s_chenyu' AS uname,
           '校园协作平台 iLink —— 诚招后端与产品同学' AS title,
           '我们在做一套面向高校的团队协作平台，核心是组队、任务看板与团队空间三个模块。后端基于 Spring Boot + MyBatis-Plus，前端 Vue 3。目前后端骨架已经跑通，缺一个熟悉业务建模的同学一起把权限与通知做扎实，也欢迎产品同学介入需求梳理。每两周一次线下评审，期末目标是在校内部署并拿到真实用户反馈。' AS description,
           1 AS competition_id,
           NULL AS custom_competition,
           '技术开发' AS category,
           'Java、Spring Boot、MySQL、产品设计' AS required_skills,
           4 AS required_member_count,
           DATE_ADD(NOW(), INTERVAL 46 DAY) AS deadline,
           'OPEN' AS status,
           DATE_SUB(NOW(), INTERVAL 3 DAY) AS created_at
    UNION ALL SELECT 'ilink_demo_s_luobeiya',
           '失物招领小程序 —— 招募设计与前端',
           '课程设计作业要升级成完整作品：失物发布、地点标记、消息通知三块。设计稿已完成 70%，还差前端把组件落地。希望你对无障碍和细节有要求，能一起把交互抠到位。工作量约每周 8 小时，周期 6 周。',
           NULL,
           '软件设计大赛（校赛）',
           '技术开发',
           'UI 设计、Vue、uni-app',
           3,
           DATE_ADD(NOW(), INTERVAL 38 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 5 DAY)
    UNION ALL SELECT 'ilink_demo_s_jiangzhi',
           '工业质检缺陷检测 —— 缺一名部署同学',
           '基于 YOLO 的小型数据集缺陷检测，模型已经训到 mAP 0.82，现在要把推理服务部署到边缘盒子并做性能优化。需要熟悉 ONNX / TensorRT 或至少愿意啃文档的同学，算法侧我们三个人都能顶。',
           9,
           NULL,
           '技术开发',
           'Python、ONNX、TensorRT、Linux',
           4,
           DATE_ADD(NOW(), INTERVAL 60 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 8 DAY)
    UNION ALL SELECT 'ilink_demo_s_zhoumu',
           '知识图谱问答助手 —— 图数据库方向',
           '把课程讲义与论文抽取成知识图谱，再做一个能带引用回答问题的助手。图谱存储用 Neo4j，检索走向量 + 图遍历混合。目前缺一个熟悉图查询与后端接口的同学，图谱 schema 已定稿。',
           NULL,
           '中国高校计算机大赛（人工智能创意赛）',
           '学术研究',
           'Neo4j、Cypher、Python、FastAPI',
           3,
           DATE_ADD(NOW(), INTERVAL 52 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 10 DAY)
    UNION ALL SELECT 'ilink_demo_s_heyue',
           '校园食堂消费数据分析与可视化',
           '拿到一年的脱敏消费数据，做一份可交互的可视化报告：高峰时段、品类偏好、价格敏感度。前端用 ECharts，希望找一个对数据敏感、能写清楚结论的同学。适合想积累作品集的低年级。',
           4,
           NULL,
           '学术研究',
           'SQL、Python、ECharts、数据可视化',
           3,
           DATE_ADD(NOW(), INTERVAL 44 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 6 DAY)
    UNION ALL SELECT 'ilink_demo_s_tangwei',
           '智能温室监控系统 —— 硬件组招人',
           '用 ESP32 + 多路传感器搭一套温室环境监控，温湿度、光照、土壤墒情三路上报，带本地看板与告警。硬件板子与传感器都已采购，缺一个能一起画板、写固件和调传感器的同学。',
           9,
           NULL,
           '技术开发',
           'C、STM32、ESP32、传感器',
           3,
           DATE_ADD(NOW(), INTERVAL 58 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 12 DAY)
    UNION ALL SELECT 'ilink_demo_s_yanjiu',
           '数学建模国赛队 —— 还差一位写作手',
           '三个人都能建模和编程，缺一个能把论文写漂亮、答辩讲清楚的队友。赛前 7 月开始集训，每周两次模拟。国赛期间三天封闭，介意时间的同学请谨慎。',
           4,
           NULL,
           '学术研究',
           'LaTeX、学术写作、MATLAB',
           3,
           DATE_ADD(NOW(), INTERVAL 40 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 2 DAY)
    UNION ALL SELECT 'ilink_demo_s_xueqin',
           '校园文创品牌视觉升级',
           '给学校的文创社团做一次完整的视觉升级：Logo、包装、线上线下物料。已有两位设计同学，缺一个能负责插画延展与打样的伙伴。有稿费分成，按项目结算。',
           NULL,
           '大学生广告艺术大赛',
           '创意设计',
           '插画、Illustrator、包装设计',
           3,
           DATE_ADD(NOW(), INTERVAL 66 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 4 DAY)
    UNION ALL SELECT 'ilink_demo_s_qiaowei',
           '校园音乐节运营组 —— 招商与内容',
           '五月份办一场 500 人规模的校园音乐节，现在缺招商与内容两块负责人。招商需要对外谈赞助，内容要排演出流程与宣发节奏。适合想完整跑一次大型活动的同学，履历加分项。',
           NULL,
           '大学生创新创业训练计划（校级）',
           '市场营销',
           '商务谈判、活动策划、内容运营',
           4,
           DATE_ADD(NOW(), INTERVAL 72 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 7 DAY)
    UNION ALL SELECT 'ilink_demo_s_wanshu',
           '校园人物访谈专栏《晓庄侧影》',
           '每期采访一位有故事的师生，文字 + 摄影 + 短视频三形态发布。目前文字侧成熟，缺一个能出镜剪辑的同学。每月两期，已积累 3000+ 关注。',
           NULL,
           '新媒体创意大赛',
           '创意设计',
           '视频剪辑、Premiere、脚本',
           3,
           DATE_ADD(NOW(), INTERVAL 90 DAY),
           'OPEN',
           DATE_SUB(NOW(), INTERVAL 15 DAY)

    -- ---------- TEAMING（已组队）----------
    UNION ALL SELECT 'ilink_demo_s_shenxing',
           '校园云盘系统 —— 后端与运维',
           '一个面向社团与课程组的文件协作服务，后端 Go + MySQL，前端 React。已完成 MVP，现在进入稳定性阶段：迁移到容器化部署、补齐监控。后端还缺一个熟悉部署与排障的同学。',
           1,
           NULL,
           '技术开发',
           'Go、Docker、Linux、MySQL',
           4,
           DATE_ADD(NOW(), INTERVAL 30 DAY),
           'TEAMING',
           DATE_SUB(NOW(), INTERVAL 20 DAY)
    UNION ALL SELECT 'ilink_demo_s_baichuan',
           'CTF 校赛命题组',
           '为下半年 CTF 校赛出一套题，覆盖 Web、Pwn、Crypto、Misc 四个方向。目前 Web 与 Misc 已定稿，缺一个能出 Crypto 题、会写题解的同学。命题经历对保研与求职都是实打实的加分。',
           8,
           NULL,
           '技术开发',
           '密码学、Python、Crypto',
           4,
           DATE_ADD(NOW(), INTERVAL 55 DAY),
           'TEAMING',
           DATE_SUB(NOW(), INTERVAL 18 DAY)
    UNION ALL SELECT 'ilink_demo_s_ainuo',
           '老年友好型 App 可用性研究',
           '针对社区老年人做智能机使用障碍调研，产出一份可用性报告与一套改造建议。目前调研已完成 60%，缺一个擅长数据分析与报告撰写的同学收尾。',
           8,
           NULL,
           '学术研究',
           'SPSS、用户研究、报告撰写',
           3,
           DATE_ADD(NOW(), INTERVAL 34 DAY),
           'TEAMING',
           DATE_SUB(NOW(), INTERVAL 16 DAY)
    UNION ALL SELECT 'ilink_demo_s_guqi',
           '模块化机械臂项目',
           '设计一套可拼装的六轴教学机械臂，结构 + 控制 + 上位机三块并行。结构侧已有两人，缺一个能一起做运动学标定与轨迹规划的同学。',
           11,
           NULL,
           '技术开发',
           'C++、ROS、运动学',
           4,
           DATE_ADD(NOW(), INTERVAL 62 DAY),
           'TEAMING',
           DATE_SUB(NOW(), INTERVAL 22 DAY)

    -- ---------- CLOSED（已结束）----------
    UNION ALL SELECT 'ilink_demo_s_yanchen',
           '双轮自平衡车（已结项）',
           '以 MPU6050 + 直流电机完成的经典平衡车项目，含直立环、速度环、转向环三闭环控制。项目已结项并提交结题报告，留档供后续同学参考。',
           11,
           NULL,
           '技术开发',
           'C、STM32、PID',
           3,
           DATE_SUB(NOW(), INTERVAL 10 DAY),
           'CLOSED',
           DATE_SUB(NOW(), INTERVAL 75 DAY)
    UNION ALL SELECT 'ilink_demo_s_ruoxi',
           '校园美食地图（已完结）',
           '走遍全校 27 个窗口，产出图文测评与一份校园美食地图 H5。项目已完结，累计阅读 1.2 万。',
           NULL,
           '新媒体创意大赛',
           '创意设计',
           '文案、摄影、H5',
           3,
           DATE_SUB(NOW(), INTERVAL 30 DAY),
           'CLOSED',
           DATE_SUB(NOW(), INTERVAL 96 DAY)
    UNION ALL SELECT 'ilink_demo_s_peiying',
           '疫情后大学生消费结构调研（已完结）',
           '回收有效问卷 1100 份，完成聚类分析与报告撰写，报告已提交学院。',
           4,
           NULL,
           '学术研究',
           '问卷设计、SPSS、报告',
           3,
           DATE_SUB(NOW(), INTERVAL 20 DAY),
           'CLOSED',
           DATE_SUB(NOW(), INTERVAL 110 DAY)
    UNION ALL SELECT 'ilink_demo_s_hanzhi',
           '社团招新摆摊营销案（已完结）',
           '为 12 个社团设计统一招新方案，线下三天新增社员 800+。',
           NULL,
           '市场营销策划大赛',
           '市场营销',
           '活动策划、地推、物料',
           4,
           DATE_SUB(NOW(), INTERVAL 15 DAY),
           'CLOSED',
           DATE_SUB(NOW(), INTERVAL 88 DAY)
    UNION ALL SELECT 'ilink_demo_s_muzhi',
           '在线课程平台自动化测试（已完结）',
           '为课程平台补充接口自动化与回归用例，缺陷发现率提升 40%。',
           7,
           NULL,
           '技术开发',
           'Selenium、Python、接口测试',
           3,
           DATE_SUB(NOW(), INTERVAL 5 DAY),
           'CLOSED',
           DATE_SUB(NOW(), INTERVAL 70 DAY)
) d ON d.uname = u.username
WHERE NOT EXISTS (
    SELECT 1 FROM `team_demand` x WHERE x.title = d.title
);

-- ============================================================
-- 5. 团队成员（team_application APPROVED）—— 让卡片显示「当前 N 人」
-- ============================================================
INSERT INTO `team_application` (`team_id`, `user_id`, `initiator_id`, `status`, `member_role`, `message`, `reviewed_at`, `created_at`)
SELECT t.id, u.id AS user_id, u.id AS initiator_id, 'APPROVED' AS status, m.member_role, NULL AS message, m.created_at, m.created_at
FROM `team_demand` t
JOIN (
    -- 校园协作平台（队长 陈屿）：3 名队员
    SELECT '校园协作平台 iLink —— 诚招后端与产品同学' AS team_title, 'ilink_demo_s_luobeiya' AS uname, 'STUDENT' AS member_role, DATE_SUB(NOW(), INTERVAL 2 DAY) AS created_at
    UNION ALL SELECT '校园协作平台 iLink —— 诚招后端与产品同学', 'ilink_demo_s_shenxing', 'STUDENT', DATE_SUB(NOW(), INTERVAL 2 DAY)
    UNION ALL SELECT '校园协作平台 iLink —— 诚招后端与产品同学', 'ilink_demo_s_ainuo', 'STUDENT', DATE_SUB(NOW(), INTERVAL 1 DAY)
    -- 失物招领小程序（队长 罗北雅）：2 名队员
    UNION ALL SELECT '失物招领小程序 —— 招募设计与前端', 'ilink_demo_s_luyang', 'STUDENT', DATE_SUB(NOW(), INTERVAL 4 DAY)
    UNION ALL SELECT '失物招领小程序 —— 招募设计与前端', 'ilink_demo_s_muzhi', 'STUDENT', DATE_SUB(NOW(), INTERVAL 3 DAY)
    -- 工业质检缺陷检测（队长 江织）：2 名队员
    UNION ALL SELECT '工业质检缺陷检测 —— 缺一名部署同学', 'ilink_demo_s_shenxing', 'STUDENT', DATE_SUB(NOW(), INTERVAL 7 DAY)
    UNION ALL SELECT '工业质检缺陷检测 —— 缺一名部署同学', 'ilink_demo_s_liushu', 'STUDENT', DATE_SUB(NOW(), INTERVAL 6 DAY)
    -- 知识图谱问答助手（队长 周牧）：导师 顾华 以 MENTOR 入队
    UNION ALL SELECT '知识图谱问答助手 —— 图数据库方向', 'ilink_demo_t_prof_gu', 'MENTOR', DATE_SUB(NOW(), INTERVAL 9 DAY)
    UNION ALL SELECT '知识图谱问答助手 —— 图数据库方向', 'ilink_demo_s_jiangzhi', 'STUDENT', DATE_SUB(NOW(), INTERVAL 8 DAY)
    -- 校园食堂消费数据分析（队长 何悦）：导师 沈知远 MENTOR
    UNION ALL SELECT '校园食堂消费数据分析与可视化', 'ilink_demo_t_prof_shen', 'MENTOR', DATE_SUB(NOW(), INTERVAL 6 DAY)
    UNION ALL SELECT '校园食堂消费数据分析与可视化', 'ilink_demo_s_liushu', 'STUDENT', DATE_SUB(NOW(), INTERVAL 5 DAY)
    -- 智能温室监控系统（队长 唐维）：导师 何洲 MENTOR
    UNION ALL SELECT '智能温室监控系统 —— 硬件组招人', 'ilink_demo_t_prof_he', 'MENTOR', DATE_SUB(NOW(), INTERVAL 11 DAY)
    UNION ALL SELECT '智能温室监控系统 —— 硬件组招人', 'ilink_demo_s_muyan', 'STUDENT', DATE_SUB(NOW(), INTERVAL 10 DAY)
    UNION ALL SELECT '智能温室监控系统 —— 硬件组招人', 'ilink_demo_s_shuhe', 'STUDENT', DATE_SUB(NOW(), INTERVAL 9 DAY)
    -- 数学建模国赛队（队长 严九）：导师 祁衡 MENTOR
    UNION ALL SELECT '数学建模国赛队 —— 还差一位写作手', 'ilink_demo_t_prof_qi', 'MENTOR', DATE_SUB(NOW(), INTERVAL 2 DAY)
    UNION ALL SELECT '数学建模国赛队 —— 还差一位写作手', 'ilink_demo_s_peiying', 'STUDENT', DATE_SUB(NOW(), INTERVAL 2 DAY)
    -- 校园文创品牌视觉升级（队长 薛琴）：导师 徐梦 MENTOR
    UNION ALL SELECT '校园文创品牌视觉升级', 'ilink_demo_t_prof_xu', 'MENTOR', DATE_SUB(NOW(), INTERVAL 4 DAY)
    UNION ALL SELECT '校园文创品牌视觉升级', 'ilink_demo_s_luyang', 'STUDENT', DATE_SUB(NOW(), INTERVAL 3 DAY)
    -- 校园音乐节运营组（队长 乔薇）：导师 岑落 MENTOR
    UNION ALL SELECT '校园音乐节运营组 —— 招商与内容', 'ilink_demo_t_prof_cen', 'MENTOR', DATE_SUB(NOW(), INTERVAL 6 DAY)
    UNION ALL SELECT '校园音乐节运营组 —— 招商与内容', 'ilink_demo_s_hanzhi', 'STUDENT', DATE_SUB(NOW(), INTERVAL 5 DAY)
    UNION ALL SELECT '校园音乐节运营组 —— 招商与内容', 'ilink_demo_s_ruoxi', 'STUDENT', DATE_SUB(NOW(), INTERVAL 4 DAY)
    -- 校园人物访谈专栏（队长 万书）：导师 洛青 MENTOR
    UNION ALL SELECT '校园人物访谈专栏《晓庄侧影》', 'ilink_demo_t_prof_luo', 'MENTOR', DATE_SUB(NOW(), INTERVAL 14 DAY)
    UNION ALL SELECT '校园人物访谈专栏《晓庄侧影》', 'ilink_demo_s_ruoxi', 'STUDENT', DATE_SUB(NOW(), INTERVAL 13 DAY)
    -- 校园云盘系统（队长 沈星）
    UNION ALL SELECT '校园云盘系统 —— 后端与运维', 'ilink_demo_s_chenyu', 'STUDENT', DATE_SUB(NOW(), INTERVAL 19 DAY)
    UNION ALL SELECT '校园云盘系统 —— 后端与运维', 'ilink_demo_t_prof_lin', 'MENTOR', DATE_SUB(NOW(), INTERVAL 19 DAY)
    -- CTF 校赛命题组（队长 白川）
    UNION ALL SELECT 'CTF 校赛命题组', 'ilink_demo_s_shenxing', 'STUDENT', DATE_SUB(NOW(), INTERVAL 17 DAY)
    -- 老年友好型 App 可用性研究（队长 艾诺）
    UNION ALL SELECT '老年友好型 App 可用性研究', 'ilink_demo_s_ainuo', 'STUDENT', DATE_SUB(NOW(), INTERVAL 15 DAY)
    UNION ALL SELECT '老年友好型 App 可用性研究', 'ilink_demo_s_qiaowei', 'STUDENT', DATE_SUB(NOW(), INTERVAL 14 DAY)
    -- 模块化机械臂项目（队长 古岐）
    UNION ALL SELECT '模块化机械臂项目', 'ilink_demo_s_lianzhu', 'STUDENT', DATE_SUB(NOW(), INTERVAL 21 DAY)
    UNION ALL SELECT '模块化机械臂项目', 'ilink_demo_s_yanchen', 'STUDENT', DATE_SUB(NOW(), INTERVAL 20 DAY)
    UNION ALL SELECT '模块化机械臂项目', 'ilink_demo_t_prof_he', 'MENTOR', DATE_SUB(NOW(), INTERVAL 20 DAY)

    -- ---------- 已完结队伍的历史成员 ----------
    UNION ALL SELECT '双轮自平衡车（已结项）', 'ilink_demo_s_shuhe', 'STUDENT', DATE_SUB(NOW(), INTERVAL 74 DAY)
    UNION ALL SELECT '双轮自平衡车（已结项）', 'ilink_demo_t_prof_he', 'MENTOR', DATE_SUB(NOW(), INTERVAL 74 DAY)
    UNION ALL SELECT '校园美食地图（已完结）', 'ilink_demo_s_luyang', 'STUDENT', DATE_SUB(NOW(), INTERVAL 95 DAY)
    UNION ALL SELECT '校园美食地图（已完结）', 'ilink_demo_s_qiaowei', 'STUDENT', DATE_SUB(NOW(), INTERVAL 95 DAY)
    UNION ALL SELECT '疫情后大学生消费结构调研（已完结）', 'ilink_demo_s_heyue', 'STUDENT', DATE_SUB(NOW(), INTERVAL 109 DAY)
    UNION ALL SELECT '疫情后大学生消费结构调研（已完结）', 'ilink_demo_t_prof_qi', 'MENTOR', DATE_SUB(NOW(), INTERVAL 109 DAY)
    UNION ALL SELECT '社团招新摆摊营销案（已完结）', 'ilink_demo_s_qiaowei', 'STUDENT', DATE_SUB(NOW(), INTERVAL 87 DAY)
    UNION ALL SELECT '社团招新摆摊营销案（已完结）', 'ilink_demo_s_wanshu', 'STUDENT', DATE_SUB(NOW(), INTERVAL 87 DAY)
    UNION ALL SELECT '社团招新摆摊营销案（已完结）', 'ilink_demo_s_ruoxi', 'STUDENT', DATE_SUB(NOW(), INTERVAL 87 DAY)
    UNION ALL SELECT '在线课程平台自动化测试（已完结）', 'ilink_demo_s_luobeiya', 'STUDENT', DATE_SUB(NOW(), INTERVAL 69 DAY)
    UNION ALL SELECT '在线课程平台自动化测试（已完结）', 'ilink_demo_t_prof_lin', 'MENTOR', DATE_SUB(NOW(), INTERVAL 69 DAY)
) m ON m.team_title = t.title
JOIN `user` u ON u.username = m.uname
WHERE NOT EXISTS (
    SELECT 1 FROM `team_application` x WHERE x.team_id = t.id AND x.user_id = u.id
);

-- ============================================================
-- 6. 待处理申请（PENDING）—— 让「我的团队」里有审核动作可看
-- ============================================================
INSERT INTO `team_application` (`team_id`, `user_id`, `initiator_id`, `status`, `member_role`, `message`, `created_at`)
SELECT t.id, u.id AS user_id, u.id AS initiator_id, 'PENDING' AS status, 'STUDENT' AS member_role, p.message, p.created_at
FROM `team_demand` t
JOIN (
    SELECT '校园协作平台 iLink —— 诚招后端与产品同学' AS team_title, 'ilink_demo_s_baichuan' AS uname,
           '后端我也可以顶，安全方向但对 Java 不陌生，愿意从业务建模学起。' AS message, DATE_SUB(NOW(), INTERVAL 1 DAY) AS created_at
    UNION ALL SELECT '校园协作平台 iLink —— 诚招后端与产品同学', 'ilink_demo_s_heyue', '数据侧想转全栈，能写 SQL 也能写简单接口。', DATE_SUB(NOW(), INTERVAL 20 HOUR)
    UNION ALL SELECT '失物招领小程序 —— 招募设计与前端', 'ilink_demo_s_xueqin', '设计稿可以支援，时间充裕。', DATE_SUB(NOW(), INTERVAL 2 DAY)
    UNION ALL SELECT '工业质检缺陷同学', 'ilink_demo_s_tangwei', '嵌入式背景，愿意学部署。', DATE_SUB(NOW(), INTERVAL 1 DAY)
    UNION ALL SELECT '工业质检缺陷检测 —— 缺一名部署同学', 'ilink_demo_s_guqi', '机械臂项目用过 TensorRT，能搭推理服务。', DATE_SUB(NOW(), INTERVAL 3 DAY)
    UNION ALL SELECT '知识图谱问答助手 —— 图数据库方向', 'ilink_demo_s_zhoumu', '已在该队，换一条测试申请。', DATE_SUB(NOW(), INTERVAL 1 DAY)
    UNION ALL SELECT '校园食堂消费数据分析与可视化', 'ilink_demo_s_ainuo', '产品视角想参与，能做用研与报告。', DATE_SUB(NOW(), INTERVAL 12 HOUR)
    UNION ALL SELECT '智能温室监控系统 —— 硬件组招人', 'ilink_demo_s_tangwei', '硬件组我自己也想再进一次。', DATE_SUB(NOW(), INTERVAL 1 DAY)
    UNION ALL SELECT '数学建模国赛队 —— 还差一位写作手', 'ilink_demo_s_wanshu', '写作我可以，建模愿意学。', DATE_SUB(NOW(), INTERVAL 6 HOUR)
    UNION ALL SELECT '校园音乐节运营组 —— 招商与内容', 'ilink_demo_s_wanshu', '内容侧可以支援宣发。', DATE_SUB(NOW(), INTERVAL 1 DAY)
) p ON p.team_title = t.title
JOIN `user` u ON u.username = p.uname
WHERE NOT EXISTS (
    SELECT 1 FROM `team_application` x WHERE x.team_id = t.id AND x.user_id = u.id
);

COMMIT;

SELECT 'team_demand seed' AS step, COUNT(*) AS cnt FROM `team_demand` t JOIN `user` u ON u.id = t.creator_id WHERE u.username LIKE 'ilink_demo_%'
UNION ALL SELECT 'team_application seed', COUNT(*) FROM team_application a JOIN `user` u ON u.id = a.user_id WHERE u.username LIKE 'ilink_demo_%'
UNION ALL SELECT 'approved members', COUNT(*) FROM team_application a JOIN `user` u ON u.id = a.user_id WHERE u.username LIKE 'ilink_demo_%' AND a.status = 'APPROVED'
UNION ALL SELECT 'pending applications', COUNT(*) FROM team_application a JOIN `user` u ON u.id = a.user_id WHERE u.username LIKE 'ilink_demo_%' AND a.status = 'PENDING';
