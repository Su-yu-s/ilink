-- ============================================================
-- iLink 演示种子数据（本地 ilink 库专用）
-- 目的：填充组队大厅 / 导师招贤 / 公开主页，让页面有真实内容可看。
-- 特性：可重复执行（幂等），按 username / 标题去重；不修改既有任何数据。
-- 密码统一：demo1234（BCrypt，与 Spring Security PasswordEncoder 兼容）
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 ilink < sql/demo_showcase_seed.sql
-- ============================================================

SET NAMES utf8mb4;

-- 统一演示密码 demo1234
SET @demo_pwd := '$2b$12$oZP/.wsgJ2ZwXWTi/uMSA.sa0o9OEZO270tNDopjakWPtKwkYSfga';

-- 学校/学院常量（南京晓庄学院）
SET @school := '南京晓庄学院';

START TRANSACTION;

-- ============================================================
-- 1. 虚拟用户：8 名导师 + 24 名学生
-- ============================================================
INSERT INTO `user` (`username`, `password`, `email`, `role`, `real_name`, `gender`,
                    `grade`, `major`, `school`, `college`, `bio`, `honors`, `created_at`, `last_active_at`)
VALUES
-- ---------- 导师（TEACHER）----------
('ilink_demo_t_prof_lin',  @demo_pwd, 'lin.yan@demo.local', 'TEACHER', '林砚',   'MALE', NULL, '软件工程',           @school, '信息工程学院',   '十年软件工程教学经验，主攻分布式系统与微服务架构，长期指导学生参加各类软件设计大赛。', '[{"id":"h1","type":"teaching","title":"校级教学成果一等奖","level":"school","issuer":"南京晓庄学院"}]', NOW(), NOW()),
('ilink_demo_t_prof_gu',   @demo_pwd, 'gu.hua@demo.local',  'TEACHER', '顾华',   'MALE', NULL, '人工智能',           @school, '人工智能学院',   '研究方向为计算机视觉与深度学习，主持省级科研项目两项，发表核心期刊论文十余篇。', '[{"id":"h2","type":"research","title":"江苏省自然科学基金面上项目","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_t_prof_shen', @demo_pwd, 'shen.zhi@demo.local','TEACHER', '沈知远', 'MALE', NULL, '数据科学与大数据技术', @school, '信息工程学院', '专注大数据分析与可视化，与多家本地企业共建实习基地，欢迎对数据方向感兴趣的同学。', '[{"id":"h3","type":"competition","title":"指导学生获数据挖掘竞赛全国二等奖","level":"national"}]', NOW(), NOW()),
('ilink_demo_t_prof_xu',   @demo_pwd, 'xu.meng@demo.local', 'TEACHER', '徐梦',   'FEMALE', NULL, '视觉传达设计',       @school, '美术学院',       '从事品牌视觉与交互设计教学，作品多次入选省级设计展，擅长产品视觉与用户体验指导。', '[{"id":"h4","type":"award","title":"江苏省高校设计展优秀指导教师","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_t_prof_he',   @demo_pwd, 'he.zhou@demo.local','TEACHER', '何洲',   'MALE', NULL, '电子信息工程',       @school, '电子工程学院',   '嵌入式与物联网方向，带队参加全国大学生电子设计竞赛多次获省级以上奖项。', '[{"id":"h5","type":"competition","title":"全国大学生电子设计竞赛优秀指导教师","level":"national"}]', NOW(), NOW()),
('ilink_demo_t_prof_cen',  @demo_pwd, 'cen.luo@demo.local','TEACHER', '岑落',   'FEMALE', NULL, '市场营销',           @school, '商学院',         '专注数字营销与品牌策划，曾任上市公司市场总监，擅长把课堂知识落到真实商业项目。', '[{"id":"h6","type":"project","title":"主持企业数字化转型咨询项目 6 项","level":"enterprise"}]', NOW(), NOW()),
('ilink_demo_t_prof_qi',   @demo_pwd, 'qi.heng@demo.local','TEACHER', '祁衡',   'MALE', NULL, '数学与应用数学',     @school, '数学学院',       '数学建模竞赛资深指导教师，连续五年带队参加国赛与美国大学生数学建模竞赛。', '[{"id":"h7","type":"competition","title":"全国大学生数学建模竞赛优秀指导教师","level":"national"}]', NOW(), NOW()),
('ilink_demo_t_prof_luo',  @demo_pwd, 'luo.qing@demo.local','TEACHER', '洛青',  'FEMALE', NULL, '汉语言文学',         @school, '文学院',         '研究方向为新媒体写作与文化传播，指导多个校园内容创作团队从 0 到 1。', '[{"id":"h8","type":"project","title":"校级课程思政示范课程负责人","level":"school"}]', NOW(), NOW()),

-- ---------- 学生（STUDENT）----------
('ilink_demo_s_chenyu',    @demo_pwd, 'chenyu@demo.local',    'STUDENT', '陈屿',   'MALE',   '2023', '软件工程',           @school, '信息工程学院',   '全栈方向，熟悉 Vue 与 Spring Boot，做过两个上线项目。', '[{"id":"s1","type":"competition","title":"蓝桥杯省赛一等奖","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_s_luobeiya',  @demo_pwd, 'luobeiya@demo.local',  'STUDENT', '罗北雅', 'FEMALE', '2023', '软件工程',           @school, '信息工程学院',   '前端与交互方向，关注无障碍设计与组件化实践。', NULL, NOW(), NOW()),
('ilink_demo_s_jiangzhi',  @demo_pwd, 'jiangzhi@demo.local',  'STUDENT', '江织',   'FEMALE', '2022', '人工智能',           @school, '人工智能学院',   '做过两个 CV 项目，熟悉 PyTorch 与模型部署。', '[{"id":"s2","type":"competition","title":"中国高校计算机大赛人工智能创意赛二等奖","level":"national"}]', NOW(), NOW()),
('ilink_demo_s_zhoumu',     @demo_pwd, 'zhoumu@demo.local',    'STUDENT', '周牧',   'MALE',   '2022', '人工智能',           @school, '人工智能学院',   'NLP 方向，参与过实验室知识图谱课题。', NULL, NOW(), NOW()),
('ilink_demo_s_heyue',      @demo_pwd, 'heyue@demo.local',     'STUDENT', '何悦',   'FEMALE', '2023', '数据科学与大数据技术', @school, '信息工程学院',   '数据分析与可视化，熟练 Python 与 SQL。', '[{"id":"s3","type":"competition","title":"大学生数据挖掘竞赛省赛三等奖","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_s_liushu',     @demo_pwd, 'liushu@demo.local',    'STUDENT', '柳舒',   'FEMALE', '2023', '数据科学与大数据技术', @school, '信息工程学院',   '偏工程，做过实时看板与 ETL 流水线。', NULL, NOW(), NOW()),
('ilink_demo_s_tangwei',    @demo_pwd, 'tangwei@demo.local',   'STUDENT', '唐维',   'MALE',   '2022', '电子信息工程',       @school, '电子工程学院',   '嵌入式方向，STM32 / ESP32 都能上手，喜欢做实物。', '[{"id":"s4","type":"competition","title":"全国大学生电子设计竞赛省赛二等奖","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_s_muyan',      @demo_pwd, 'muyan@demo.local',     'STUDENT', '沐燕',   'FEMALE', '2023', '电子信息工程',       @school, '电子工程学院',   '硬件测试与 PCB 设计，细心且抗压。', NULL, NOW(), NOW()),
('ilink_demo_s_yanjiu',     @demo_pwd, 'yanjiu@demo.local',    'STUDENT', '严九',   'MALE',   '2022', '数学与应用数学',     @school, '数学学院',       '算法竞赛出身，ACM 校赛银牌。', '[{"id":"s5","type":"competition","title":"ACM-ICPC 亚洲区域赛铜牌","level":"national"}]', NOW(), NOW()),
('ilink_demo_s_peiying',    @demo_pwd, 'peiying@demo.local',   'STUDENT', '裴婴',   'FEMALE', '2023', '数学与应用数学',     @school, '数学学院',       '建模与论文写作，负责队内文档与答辩。', NULL, NOW(), NOW()),
('ilink_demo_s_xueqin',     @demo_pwd, 'xueqin@demo.local',    'STUDENT', '薛琴',   'FEMALE', '2022', '视觉传达设计',       @school, '美术学院',       'UI 与插画，作品集在站酷有 2k 关注。', '[{"id":"s6","type":"award","title":"大学生广告艺术大赛省赛一等奖","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_s_luyang',     @demo_pwd, 'luyang@demo.local',    'STUDENT', '陆仰',   'MALE',   '2023', '视觉传达设计',       @school, '美术学院',       '三维与动效，熟悉 C4D 与 Blender。', NULL, NOW(), NOW()),
('ilink_demo_s_qiaowei',    @demo_pwd, 'qiaowei@demo.local',   'STUDENT', '乔薇',   'FEMALE', '2022', '市场营销',           @school, '商学院',         '运营实习两段，擅长活动策划与内容投放。', '[{"id":"s7","type":"project","title":"校园文创品牌主理人","level":"school"}]', NOW(), NOW()),
('ilink_demo_s_hanzhi',     @demo_pwd, 'hanzhi@demo.local',    'STUDENT', '韩织',   'FEMALE', '2023', '市场营销',           @school, '商学院',         '商务谈判与社团外联，沟通能力强。', NULL, NOW(), NOW()),
('ilink_demo_s_wanshu',     @demo_pwd, 'wanshu@demo.local',    'STUDENT', '万书',   'MALE',   '2022', '汉语言文学',         @school, '文学院',         '内容创作与脚本，公众号主笔。', '[{"id":"s8","type":"project","title":"校级新媒体中心内容总监","level":"school"}]', NOW(), NOW()),
('ilink_demo_s_ruoxi',      @demo_pwd, 'ruoxi@demo.local',     'STUDENT', '若溪',   'FEMALE', '2023', '汉语言文学',         @school, '文学院',         '文案与访谈，做过 10w+ 阅读的校园推文。', NULL, NOW(), NOW()),
('ilink_demo_s_shenxing',   @demo_pwd, 'shenxing@demo.local',   'STUDENT', '沈星',   'MALE',   '2023', '计算机科学与技术',   @school, '信息工程学院',   '后端与运维，熟悉 Linux 与 Docker。', NULL, NOW(), NOW()),
('ilink_demo_s_baichuan',   @demo_pwd, 'baichuan@demo.local',  'STUDENT', '白川',   'MALE',   '2022', '计算机科学与技术',   @school, '信息工程学院',   '网络与安全方向，CTF 校赛冠军。', '[{"id":"s9","type":"competition","title":"全国大学生信息安全竞赛省赛三等奖","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_s_ainuo',      @demo_pwd, 'ainuo@demo.local',     'STUDENT', '艾诺',   'FEMALE', '2023', '网络工程',           @school, '信息工程学院',   '产品与用研，写过 3 份完整 PRD。', NULL, NOW(), NOW()),
('ilink_demo_s_muzhi',      @demo_pwd, 'muzhi@demo.local',     'STUDENT', '慕枝',   'FEMALE', '2022', '网络工程',           @school, '信息工程学院',   '测试开发，会写自动化脚本。', NULL, NOW(), NOW()),
('ilink_demo_s_guqi',       @demo_pwd, 'guqi@demo.local',      'STUDENT', '古岐',   'MALE',   '2023', '机械设计制造及其自动化', @school, '机械工程学院', '机械结构设计，熟悉 SolidWorks 与 3D 打印。', NULL, NOW(), NOW()),
('ilink_demo_s_lianzhu',    @demo_pwd, 'lianzhu@demo.local',   'STUDENT', '连珠',   'FEMALE', '2023', '机械设计制造及其自动化', @school, '机械工程学院', '工业设计方向，关注人因与结构优化。', NULL, NOW(), NOW()),
('ilink_demo_s_yanchen',    @demo_pwd, 'yanchen@demo.local',   'STUDENT', '晏沉',   'MALE',   '2022', '自动化',             @school, '电子工程学院',   '控制算法与 ROS，做过平衡车项目。', '[{"id":"s10","type":"competition","title":"全国大学生智能汽车竞赛省赛二等奖","level":"provincial"}]', NOW(), NOW()),
('ilink_demo_s_shuhe',      @demo_pwd, 'shuhe@demo.local',     'STUDENT', '束荷',   'FEMALE', '2023', '自动化',             @school, '电子工程学院',   '传感器与信号处理，实验室常驻。', NULL, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    `real_name` = VALUES(`real_name`),
    `role` = VALUES(`role`),
    `grade` = VALUES(`grade`),
    `major` = VALUES(`major`),
    `school` = VALUES(`school`),
    `college` = VALUES(`college`),
    `bio` = VALUES(`bio`),
    `honors` = VALUES(`honors`),
    `last_active_at` = NOW();

-- ============================================================
-- 2. 用户技能（user_skills）
-- ============================================================
INSERT INTO `user_skills` (`user_id`, `skill_name`, `skill_level`, `skill_category`, `certification`, `years_experience`, `is_verified`)
SELECT u.id, s.skill_name, s.skill_level, s.skill_category, s.certification, s.years_experience, 1
FROM `user` u
JOIN (
    SELECT 'ilink_demo_s_chenyu' AS uname, 'Java' AS skill_name, 5 AS skill_level, '后端' AS skill_category, NULL AS certification, 2 AS years_experience
    UNION ALL SELECT 'ilink_demo_s_chenyu', 'Spring Boot', 5, '后端', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_chenyu', 'Vue.js', 4, '前端', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_luobeiya', 'HTML/CSS', 5, '前端', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_luobeiya', 'Figma', 4, '设计', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_luobeiya', 'JavaScript', 4, '前端', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_jiangzhi', 'Python', 5, '算法', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_jiangzhi', 'PyTorch', 4, '算法', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_jiangzhi', 'OpenCV', 4, '算法', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_zhoumu', 'Python', 4, '算法', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_zhoumu', 'Neo4j', 4, '后端', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_zhoumu', '自然语言处理', 4, '算法', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_heyue', 'SQL', 5, '数据', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_heyue', 'Python', 4, '数据', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_heyue', 'Tableau', 4, '数据', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_liushu', 'Spark', 4, '数据', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_liushu', 'Flink', 3, '数据', NULL, 1
    UNION ALL SELECT 'ilink_demo_s_liushu', 'Python', 4, '数据', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_tangwei', 'C 语言', 5, '嵌入式', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_tangwei', 'STM32', 5, '嵌入式', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_tangwei', 'ESP32', 4, '嵌入式', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_muyan', 'Altium Designer', 4, '硬件', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_muyan', '焊接与调试', 4, '硬件', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_yanjiu', 'C++', 5, '算法', NULL, 4
    UNION ALL SELECT 'ilink_demo_s_yanjiu', '算法竞赛', 5, '算法', 'ICPC 区域赛铜牌', 4
    UNION ALL SELECT 'ilink_demo_s_yanjiu', '动态规划', 5, '算法', NULL, 4
    UNION ALL SELECT 'ilink_demo_s_peiying', 'MATLAB', 4, '建模', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_peiying', 'LaTeX', 4, '建模', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_peiying', '学术写作', 4, '建模', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_xueqin', 'Illustrator', 5, '设计', NULL, 4
    UNION ALL SELECT 'ilink_demo_s_xueqin', 'Photoshop', 5, '设计', NULL, 4
    UNION ALL SELECT 'ilink_demo_s_xueqin', '插画', 4, '设计', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_luyang', 'Blender', 5, '设计', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_luyang', 'Cinema 4D', 4, '设计', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_luyang', 'After Effects', 4, '设计', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_qiaowei', '活动策划', 5, '运营', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_qiaowei', '内容投放', 4, '运营', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_qiaowei', '用户增长', 4, '运营', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_hanzhi', '商务谈判', 4, '运营', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_hanzhi', '外联赞助', 4, '运营', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_wanshu', '文案写作', 5, '内容', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_wanshu', '公众号运营', 4, '内容', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_ruoxi', '深度访谈', 4, '内容', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_ruoxi', '脚本创作', 4, '内容', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_shenxing', 'Linux', 4, '运维', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_shenxing', 'Docker', 4, '运维', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_shenxing', 'MySQL', 4, '后端', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_baichuan', '网络安全', 5, '安全', 'CTF 校赛冠军', 3
    UNION ALL SELECT 'ilink_demo_s_baichuan', '渗透测试', 4, '安全', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_baichuan', 'Wireshark', 4, '安全', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_ainuo', '产品设计', 4, '产品', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_ainuo', 'Axure', 4, '产品', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_ainuo', '用户研究', 4, '产品', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_muzhi', '自动化测试', 4, '测试', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_muzhi', 'Python', 4, '测试', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_guqi', 'SolidWorks', 5, '机械', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_guqi', '3D 打印', 4, '机械', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_lianzhu', '工业设计', 4, '机械', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_lianzhu', '人因工程', 4, '机械', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_yanchen', 'ROS', 4, '自动化', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_yanchen', '控制算法', 4, '自动化', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_yanchen', 'C++', 4, '自动化', NULL, 3
    UNION ALL SELECT 'ilink_demo_s_shuhe', '信号处理', 4, '自动化', NULL, 2
    UNION ALL SELECT 'ilink_demo_s_shuhe', '传感器融合', 4, '自动化', NULL, 2
) s ON s.uname = u.username
ON DUPLICATE KEY UPDATE
    `skill_level` = VALUES(`skill_level`),
    `certification` = VALUES(`certification`),
    `years_experience` = VALUES(`years_experience`),
    `is_verified` = 1;

-- ============================================================
-- 3. 导师档案（teacher_application，APPROVED + 资料完整 → 公开展示）
-- ============================================================
INSERT INTO `teacher_application` (`user_id`, `introduction`, `research_direction`, `professional_title`, `projects`, `status`, `created_at`)
SELECT u.id, t.introduction, t.research_direction, t.professional_title, t.projects, 'APPROVED', NOW()
FROM `user` u
JOIN (
    SELECT 'ilink_demo_t_prof_lin'  AS uname,
           '十年软件工程教学经验，主攻分布式系统与微服务架构。先后指导 20 余支学生团队完成软件类竞赛与毕业设计，注重工程规范与代码质量，欢迎跨专业同学加入。' AS introduction,
           '软件工程、分布式系统、微服务架构' AS research_direction,
           '教授' AS professional_title,
           '主持《软件架构设计》省级一流本科课程建设；指导学生获中国国际大学生创新大赛省赛一等奖 3 项。' AS projects
    UNION ALL SELECT 'ilink_demo_t_prof_gu',
           '研究方向为计算机视觉与深度学习，主持省级科研项目两项，发表核心期刊论文十余篇。实验室配有 GPU 服务器，对愿意做科研的同学提供算力与数据支持。',
           '计算机视觉、深度学习、目标检测',
           '副教授',
           '国家自然科学基金青年项目（参与）；江苏省自然科学基金面上项目（主持）；CVPR / ICCV  workshop 论文 3 篇。'
    UNION ALL SELECT 'ilink_demo_t_prof_shen',
           '专注大数据分析与可视化，与多家本地企业共建实习基地。课程以真实数据集驱动，期末作品可直接放进简历。',
           '大数据分析、数据可视化、推荐系统',
           '副教授',
           '主持教育部产学合作协同育人项目 2 项；共建校企联合实验室 1 个；横向课题经费累计 80 余万元。'
    UNION ALL SELECT 'ilink_demo_t_prof_xu',
           '从事品牌视觉与交互设计教学十余年，作品多次入选省级设计展。指导风格偏共创，会陪着学生一版一版打磨细节。',
           '品牌视觉设计、交互设计、用户体验',
           '教授',
           '省级高校设计展优秀指导教师；主持校园 VI 系统升级项目；出版教材《视觉传达设计实务》。'
    UNION ALL SELECT 'ilink_demo_t_prof_he',
           '嵌入式与物联网方向，带队参加全国大学生电子设计竞赛多次获省级以上奖项。实验室器件齐全，欢迎喜欢动手做实物的同学。',
           '嵌入式系统、物联网、传感器网络',
           '副教授',
           '全国大学生电子设计竞赛优秀指导教师；主持省级产学研项目 1 项；授权发明专利 4 项。'
    UNION ALL SELECT 'ilink_demo_t_prof_cen',
           '曾任上市公司市场总监，擅长把课堂知识落到真实商业项目。手里有稳定的企业案例资源，可提供真实客户做毕设。',
           '数字营销、品牌策划、消费者行为',
           '讲师',
           '主持企业数字化转型咨询项目 6 项；校级创新创业导师；主编《新媒体营销实务》校本教材。'
    UNION ALL SELECT 'ilink_demo_t_prof_qi',
           '数学建模竞赛资深指导教师，连续五年带队参加国赛与美国大学生数学建模竞赛。赛前有系统集训，愿意零基础带新人。',
           '数学建模、优化理论、科学计算',
           '教授',
           '全国大学生数学建模竞赛优秀指导教师；主持省级教改项目 2 项；出版《数学建模案例精讲》。'
    UNION ALL SELECT 'ilink_demo_t_prof_luo',
           '研究方向为新媒体写作与文化传播，指导多个校园内容创作团队从 0 到 1。适合想做账号、想写出好内容的同学。',
           '新媒体写作、文化传播、内容策划',
           '讲师',
           '校级课程思政示范课程负责人；指导校园公众号获省级十佳校园媒体；主持市级社科基金 1 项。'
) t ON t.uname = u.username
ON DUPLICATE KEY UPDATE
    `introduction` = VALUES(`introduction`),
    `research_direction` = VALUES(`research_direction`),
    `professional_title` = VALUES(`professional_title`),
    `projects` = VALUES(`projects`),
    `status` = 'APPROVED';

COMMIT;

SELECT 'seed users' AS step, COUNT(*) AS cnt FROM `user` WHERE username LIKE 'ilink_demo_%'
UNION ALL SELECT 'seed skills', COUNT(*) FROM user_skills s JOIN `user` u ON u.id = s.user_id WHERE u.username LIKE 'ilink_demo_%'
UNION ALL SELECT 'seed teacher profiles', COUNT(*) FROM teacher_application t JOIN `user` u ON u.id = t.user_id WHERE u.username LIKE 'ilink_demo_%';
