// 竞赛目录：静态汇总 + 分类筛选 + 关键词搜索

const COMP_TRACKS = [
    { key: '', label: '全部' },
    { key: 'cs', label: '计算机软件' },
    { key: 'ee', label: '电子信息' },
    { key: 'innovation', label: '创新创业' },
    { key: 'stem', label: '数理建模' },
    { key: 'robot', label: '机器人 / 智能车' },
    { key: 'general', label: '综合 / 语言' }
];

/**
 * 说明：赛季、标签为常见经验归纳，以各届官方通知为准。
 * officialUrl 为赛事主站或教育部公示入口，外链在新标签打开。
 */
const COMPETITIONS = [
    {
        name: '中国国际大学生创新大赛（原“互联网+”）',
        track: 'innovation',
        organizer: '教育部等',
        season: '校赛约 4–6 月，国赛约 9–10 月',
        level: '国赛',
        tags: ['双创', '路演', '红旅'],
        desc: '覆盖高教主赛道、职教赛道、产业命题、青年红色筑梦之旅等，适合有项目沉淀的团队。',
        officialUrl: 'https://cy.ncss.cn/'
    },
    {
        name: '“挑战杯”全国大学生课外学术科技作品竞赛',
        track: 'innovation',
        organizer: '共青团中央等',
        season: '奇数年举办，校赛多在上半学年启动',
        level: '国赛',
        tags: ['大挑', '学术', '科技作品'],
        desc: '偏学术与科技创新作品展示，与“小挑”创业计划竞赛交替举办。',
        officialUrl: 'http://www.tiaozhanbei.net/'
    },
    {
        name: '“挑战杯”中国大学生创业计划大赛',
        track: 'innovation',
        organizer: '共青团中央等',
        season: '偶数年举办',
        level: '国赛',
        tags: ['小挑', '创业计划'],
        desc: '侧重创业计划与商业模式，适合早期创业项目打磨与路演。',
        officialUrl: 'http://www.tiaozhanbei.net/'
    },
    {
        name: '全国大学生数学建模竞赛',
        track: 'stem',
        organizer: '中国工业与应用数学学会等',
        season: '每年 9 月（通常 3 天赛）',
        level: '国赛',
        tags: ['建模', '论文', '国赛'],
        desc: '连续三天封闭式建模与论文写作，考察问题抽象、模型与求解、论文表达。',
        officialUrl: 'http://www.mcm.edu.cn/'
    },
    {
        name: '美国大学生数学建模竞赛（MCM/ICM）',
        track: 'stem',
        organizer: 'COMAP',
        season: '每年 1–2 月（寒假）',
        level: '国际赛',
        tags: ['美赛', '英文论文'],
        desc: '英文论文、选题开放，常与国赛备赛体系衔接。',
        officialUrl: 'https://www.contest.comap.com/'
    },
    {
        name: 'ACM-ICPC / CCPC 系列',
        track: 'cs',
        organizer: '各赛区 / 组委会',
        season: '区域赛多在秋季，校队选拔因校而异',
        level: '区域 / 亚洲 / 世界总决赛',
        tags: ['算法', '程序设计', '组队'],
        desc: '5 小时现场编程，强调算法与团队协作，适合长期训练队伍。',
        officialUrl: 'https://icpc.global/'
    },
    {
        name: '蓝桥杯全国软件和信息技术专业人才大赛',
        track: 'cs',
        organizer: '工信部人才交流中心等',
        season: '省赛约 4 月，国赛约 6 月',
        level: '省赛 / 国赛',
        tags: ['软件类', '单片机', '嵌入式'],
        desc: '个人赛为主，覆盖软件开发、嵌入式、物联网等方向，门槛相对友好。',
        officialUrl: 'https://dasai.lanqiao.cn/'
    },
    {
        name: '中国高校计算机大赛（含多个子赛）',
        track: 'cs',
        organizer: '教指委等',
        season: '因子赛而异，多集中在春夏',
        level: '国赛',
        tags: ['移动应用', '网络技术', '人工智能', '大数据'],
        desc: '如移动应用创新赛、网络技术挑战赛、人工智能创意赛等，可按子赛选报。',
        officialUrl: 'https://www.c4best.cn/'
    },
    {
        name: '全国大学生电子设计竞赛',
        track: 'ee',
        organizer: '教育部、工信部',
        season: '奇数年国赛，暑期集中测评',
        level: '国赛',
        tags: ['模电', '数电', '作品'],
        desc: '四天三夜制作实物作品，强调电路设计与系统实现能力。',
        officialUrl: 'https://www.nuedc.com.cn/'
    },
    {
        name: '“西门子杯”中国智能制造挑战赛',
        track: 'ee',
        organizer: '教育部国际合作与交流司等',
        season: '校赛 / 省赛春夏，国赛暑假',
        level: '国赛',
        tags: ['自动化', 'PLC', '工业网络'],
        desc: '面向自动化、机电、计算机等方向的工程实践类赛事。',
        officialUrl: 'http://www.siemenscup-cimc.org.cn/'
    },
    {
        name: '全国大学生智能汽车竞赛',
        track: 'robot',
        organizer: '教指委',
        season: '校赛春季起，分区赛与全国赛暑假',
        level: '国赛',
        tags: ['嵌入式', '视觉', '控制'],
        desc: '以智能车为载体，涵盖摄像头、电磁、创意组等多组别。',
        officialUrl: 'https://smartcar.cdstm.cn/'
    },
    {
        name: 'RoboMaster 机甲大师赛',
        track: 'robot',
        organizer: '大疆等',
        season: '备赛周期长，分区赛与总决赛多在春夏',
        level: '国赛',
        tags: ['机器人', '对抗', '战队'],
        desc: '机器人对抗与工程能力并重，适合有实验室与经费支持的战队。',
        officialUrl: 'https://www.robomaster.com/'
    },
    {
        name: '中国机器人及人工智能大赛',
        track: 'robot',
        organizer: '中国人工智能学会等',
        season: '省赛与国赛多在夏季',
        level: '国赛',
        tags: ['机器人', '人工智能'],
        desc: '赛项多、覆盖面广，含仿真与实物等多种形态。',
        officialUrl: 'https://www.caairobot.com/'
    },
    {
        name: '“外研社·国才杯”理解当代中国系列赛事',
        track: 'general',
        organizer: '外研社等',
        season: '校赛秋季，国赛秋冬',
        level: '国赛',
        tags: ['英语', '演讲', '写作', '阅读'],
        desc: '英语演讲、写作、阅读等分项，适合语言类能力提升与综测加分。',
        officialUrl: 'https://ucc.fltrp.com/'
    },
    {
        name: '中国大学生医学技术技能大赛',
        track: 'general',
        organizer: '教育部',
        season: '以当年组委会通知为准',
        level: '一类B',
        tags: ['医学', '技能', '临床'],
        desc: '医学类专业实践技能竞赛，强调临床操作与综合能力。',
        officialUrl: ''
    },
    {
        name: '全国大学生机械创新设计大赛',
        track: 'general',
        organizer: '教育部高等学校机械学科教学指导委员会',
        season: '校赛至国赛，周期约半年至一年',
        level: '一类B',
        tags: ['机械', '设计', '创新'],
        desc: '面向机械类与工程类学生的实物创新设计竞赛。',
        officialUrl: ''
    },
    {
        name: '全国大学生结构设计竞赛',
        track: 'general',
        organizer: '中国高等教育学会工程教育专业委员会等',
        season: '校赛春季，省赛/国赛夏秋',
        level: '一类B',
        tags: ['土木', '结构', '工程'],
        desc: '以模型结构设计与加载测试为核心，突出工程实践能力。',
        officialUrl: ''
    },
    {
        name: '全国大学生广告艺术大赛',
        track: 'general',
        organizer: '教育部高等学校新闻传播学类专业教学指导委员会等',
        season: '每年赛季安排以官方通知为准',
        level: '一类B',
        tags: ['广告', '创意', '传播'],
        desc: '广告与视觉传播方向的全国性学生竞赛。',
        officialUrl: ''
    },
    {
        name: '全国大学生电子商务“创新、创意及创业”挑战赛',
        track: 'innovation',
        organizer: '教育部高等学校电子商务专业教学指导委员会',
        season: '校赛春季，省赛/国赛夏季',
        level: '一类B',
        tags: ['电商', '三创赛', '创新创业'],
        desc: '聚焦电子商务领域项目方案、商业模式与路演能力。',
        officialUrl: ''
    },
    {
        name: '中国大学生工程实践与创新能力大赛',
        track: 'general',
        organizer: '教育部工程训练教学指导委员会',
        season: '校赛至国赛分阶段进行',
        level: '一类B',
        tags: ['工程训练', '实践', '创新'],
        desc: '强调工程训练、综合设计与团队协作。',
        officialUrl: ''
    },
    {
        name: '全国大学生物流设计大赛',
        track: 'general',
        organizer: '教育部高等学校物流类专业教学指导委员会',
        season: '每届时间以赛事公告为准',
        level: '一类B',
        tags: ['物流', '供应链', '方案设计'],
        desc: '面向物流与供应链管理的实战型方案竞赛。',
        officialUrl: ''
    },
    {
        name: '两岸新锐设计竞赛·华灿奖',
        track: 'general',
        organizer: '中国高等教育学会',
        season: '年度赛',
        level: '一类B',
        tags: ['设计', '文创', '两岸'],
        desc: '聚焦视觉设计、产品设计、数字创意等方向。',
        officialUrl: ''
    },
    {
        name: '全国大学生创新创业训练计划年会展示',
        track: 'innovation',
        organizer: '教育部高等教育司',
        season: '年度年会展示',
        level: '一类B',
        tags: ['大创', '创新创业', '项目展示'],
        desc: '国家级大学生创新创业训练计划项目集中展示交流活动。',
        officialUrl: ''
    },
    {
        name: '全国大学生化工设计竞赛',
        track: 'general',
        organizer: '中国化工学会化学工程专业委员会等',
        season: '春夏组织校赛与区域赛',
        level: '一类B',
        tags: ['化工', '流程设计', '工程'],
        desc: '围绕化工流程与工艺设计的工程类竞赛。',
        officialUrl: ''
    },
    {
        name: '全国大学生市场调查与分析大赛',
        track: 'general',
        organizer: '教育部高等学校统计学类专业教学指导委员会等',
        season: '每年春季启动',
        level: '一类B',
        tags: ['市调', '统计', '分析'],
        desc: '强调调研设计、数据分析与报告表达。',
        officialUrl: ''
    },
    {
        name: '全国大学生先进成图技术与产品信息建模创新大赛',
        track: 'general',
        organizer: '教育部高等学校工程图学课程教学指导委员会等',
        season: '年度赛',
        level: '一类B',
        tags: ['成图', '建模', 'BIM'],
        desc: '涵盖工程制图、三维建模与信息化表达能力。',
        officialUrl: ''
    },
    {
        name: '全国三维数字化创新设计大赛',
        track: 'general',
        organizer: '全国三维数字化创新设计大赛组委会等',
        season: '年度赛',
        level: '一类B',
        tags: ['3D', '数字化', '设计'],
        desc: '面向三维数字化设计、创意与应用实践。',
        officialUrl: ''
    },
    {
        name: '中国大学生服务外包创新创业大赛',
        track: 'innovation',
        organizer: '教育部、商务部等',
        season: '每年春夏组织',
        level: '一类B',
        tags: ['服务外包', '创新创业', '项目实践'],
        desc: '以企业真实需求为导向的创新实践赛事。',
        officialUrl: ''
    },
    {
        name: '中国大学生计算机设计大赛',
        track: 'cs',
        organizer: '中国高等教育学会、教育部高等学校计算机类专业教学指导委员会等',
        season: '年度赛',
        level: '一类B',
        tags: ['计算机', '设计', '应用开发'],
        desc: '覆盖软件应用、数媒设计、人工智能等多个赛道。',
        officialUrl: ''
    },
    {
        name: '全国大学生光电设计竞赛',
        track: 'ee',
        organizer: '中国光学学会等',
        season: '年度赛',
        level: '一类B',
        tags: ['光电', '电子', '工程'],
        desc: '聚焦光电系统设计与实现能力。',
        officialUrl: ''
    },
    {
        name: '全国大学生集成电路创新创业大赛',
        track: 'ee',
        organizer: '工业和信息化部人才交流中心',
        season: '每年赛季以公告为准',
        level: '一类B',
        tags: ['集成电路', '芯片', '创新创业'],
        desc: '半导体与集成电路方向的重要学生赛事。',
        officialUrl: ''
    },
    {
        name: '全国大学生信息安全竞赛',
        track: 'cs',
        organizer: '教育部高等学校信息安全类专业教学指导委员会',
        season: '每年多阶段进行',
        level: '一类B',
        tags: ['网络安全', '攻防', '信息安全'],
        desc: '覆盖安全理论、实战攻防、应用创新等方向。',
        officialUrl: ''
    },
    {
        name: '中国大学生机械工程创新创意大赛',
        track: 'general',
        organizer: '中国机械工程学会',
        season: '年度赛',
        level: '一类B',
        tags: ['机械工程', '创新创意', '制造'],
        desc: '机械类综合创新创意竞赛，含多个专项赛道。',
        officialUrl: ''
    },
    {
        name: '“中国软件杯”大学生软件设计大赛',
        track: 'cs',
        organizer: '工业和信息化部、教育部等',
        season: '年度赛',
        level: '一类B',
        tags: ['软件开发', '工程实践', '应用设计'],
        desc: '聚焦软件工程与应用创新开发能力。',
        officialUrl: ''
    },
    {
        name: '“大唐杯”全国大学生新一代信息通信技术大赛',
        track: 'ee',
        organizer: '工业和信息化部人才交流中心、中国通信企业协会',
        season: '年度赛',
        level: '一类B',
        tags: ['通信', '5G', 'ICT'],
        desc: '信息通信技术方向的全国大学生竞赛。',
        officialUrl: ''
    },
    {
        name: '华为 ICT 大赛',
        track: 'ee',
        organizer: '华为技术有限公司',
        season: '年度赛',
        level: '一类B',
        tags: ['华为', 'ICT', '云与网络'],
        desc: '面向网络、云、计算、AI 等方向的技术竞赛。',
        officialUrl: 'https://e.huawei.com/cn/talent/ict-academy/ict-competition'
    },
    {
        name: '全国大学生嵌入式芯片与系统设计竞赛',
        track: 'ee',
        organizer: '中国电子学会',
        season: '年度赛',
        level: '一类B',
        tags: ['嵌入式', '芯片', '系统设计'],
        desc: '强调芯片应用、嵌入式系统与软硬件协同设计。',
        officialUrl: ''
    },
    {
        name: '全国大学生生命科学竞赛（CULSC）',
        track: 'general',
        organizer: '教育部',
        season: '年度赛',
        level: '一类B',
        tags: ['生命科学', '实验', '研究'],
        desc: '生命科学方向的研究与创新实践竞赛。',
        officialUrl: ''
    },
    {
        name: '全国大学生物理实验竞赛',
        track: 'stem',
        organizer: '教育部高等教育司',
        season: '年度赛',
        level: '一类B',
        tags: ['物理实验', '实验技能', '创新'],
        desc: '面向物理实验教学与创新实践能力提升。',
        officialUrl: ''
    },
    {
        name: '全国高校 BIM 毕业设计创新大赛',
        track: 'general',
        organizer: '中国土木工程学会建筑市场与招标投标研究分会等',
        season: '年度赛',
        level: '一类B',
        tags: ['BIM', '土木', '毕业设计'],
        desc: '建筑与土木工程方向的 BIM 应用竞赛。',
        officialUrl: ''
    },
    {
        name: '中国机器人及人工智能大赛',
        track: 'robot',
        organizer: '中国人工智能学会',
        season: '年度赛',
        level: '一类B',
        tags: ['机器人', '人工智能', '算法'],
        desc: '覆盖机器人、智能控制、视觉与 AI 应用等赛道。',
        officialUrl: ''
    },
    {
        name: '全国大学生节能减排社会实践与科技竞赛',
        track: 'general',
        organizer: '教育部高等教育司、教育部高等学校能源动力学科教学指导委员会',
        season: '年度赛',
        level: '一类B',
        tags: ['节能减排', '社会实践', '科技'],
        desc: '鼓励能源与环保方向的创新实践项目。',
        officialUrl: ''
    },
    {
        name: '“21 世纪杯”全国英语演讲比赛',
        track: 'general',
        organizer: '21 世纪报社',
        season: '年度赛',
        level: '一类B',
        tags: ['英语', '演讲', '表达'],
        desc: '全国性英语演讲赛事，注重语言表达与思辨能力。',
        officialUrl: ''
    },
    {
        name: 'iCAN 大学生创新创业大赛',
        track: 'innovation',
        organizer: '国际 iCAN 联盟、教育部创新创业教育指导委员会等',
        season: '年度赛',
        level: '一类B',
        tags: ['iCAN', '创新创业', '项目孵化'],
        desc: '面向技术创新与创业实践的综合竞赛平台。',
        officialUrl: ''
    },
    {
        name: '“工行杯”全国大学生金融科技创新大赛',
        track: 'innovation',
        organizer: '中国工商银行股份有限公司',
        season: '年度赛',
        level: '一类B',
        tags: ['金融科技', 'FinTech', '创新'],
        desc: '聚焦金融科技应用创新与行业场景实践。',
        officialUrl: ''
    },
    {
        name: '江苏大学生创新大赛',
        track: 'innovation',
        organizer: '江苏省教育厅等',
        season: '省赛年度举办',
        level: '二类A',
        tags: ['江苏省赛', '创新创业', '项目路演'],
        desc: '江苏省重点大学生创新创业赛事，与国创类赛事衔接。',
        officialUrl: ''
    },
    {
        name: '江苏省师范生教学基本功大赛',
        track: 'general',
        organizer: '江苏省教育厅',
        season: '省赛年度举办',
        level: '二类A',
        tags: ['师范生', '教学技能', '江苏省赛'],
        desc: '师范生教学设计与课堂展示能力竞赛。',
        officialUrl: ''
    },
    {
        name: '江苏省大学生职业规划大赛',
        track: 'general',
        organizer: '江苏省教育厅',
        season: '省赛年度举办',
        level: '二类B',
        tags: ['职业规划', '生涯发展', '江苏省赛'],
        desc: '面向全省高校大学生的职业规划与发展能力赛事。',
        officialUrl: ''
    },
    {
        name: '江苏省大学生程序设计大赛',
        track: 'cs',
        organizer: '江苏省计算机学会',
        season: '省赛年度举办',
        level: '二类B',
        tags: ['程序设计', '算法', '江苏省赛'],
        desc: '江苏省高校程序设计能力竞技赛事。',
        officialUrl: ''
    },
    {
        name: '江苏省大学生网络空间安全知识技能大赛',
        track: 'cs',
        organizer: '江苏省计算机学会',
        season: '省赛年度举办',
        level: '二类B',
        tags: ['网络安全', '攻防', '江苏省赛'],
        desc: '网络安全方向省级技能竞赛。',
        officialUrl: ''
    },
    {
        name: '江苏省大学生电子设计竞赛',
        track: 'ee',
        organizer: '全国大学生电子设计竞赛江苏赛区组织委员会',
        season: '省赛年度举办',
        level: '二类B',
        tags: ['电子设计', '硬件', '江苏省赛'],
        desc: '电子系统设计与实现能力省级竞赛。',
        officialUrl: ''
    },
    {
        name: '江苏省高校智能机器人创意大赛',
        track: 'robot',
        organizer: '江苏省高校智能机器人创意大赛组委会',
        season: '省赛年度举办',
        level: '二类B',
        tags: ['机器人', '创意', '江苏省赛'],
        desc: '机器人创意设计与实现的省级赛事。',
        officialUrl: ''
    },
    {
        name: '全国高校计算机能力挑战赛',
        track: 'cs',
        organizer: '全国高等学校计算机教育研究会',
        season: '年度赛',
        level: '二类B',
        tags: ['计算机', '能力挑战', '应用'],
        desc: '覆盖编程、算法与综合应用能力的竞赛。',
        officialUrl: ''
    },
    {
        name: '中国高校计算机大赛 AIGC 创新赛',
        track: 'cs',
        organizer: '全国高等学校计算机教育研究会',
        season: '年度赛',
        level: '二类B',
        tags: ['AIGC', '人工智能', '创新'],
        desc: '聚焦生成式 AI 与相关应用创新的专项赛事。',
        officialUrl: ''
    },
    {
        name: '全国大学生 GIS 应用技能大赛',
        track: 'general',
        organizer: '中国地理信息产业协会、中国地理学会',
        season: '年度赛',
        level: '二类B',
        tags: ['GIS', '地理信息', '应用技能'],
        desc: '地理信息系统应用开发与分析能力竞赛。',
        officialUrl: ''
    },
    {
        name: '美国大学生数学建模竞赛（MCM）',
        track: 'stem',
        organizer: '美国工业与应用数学学会等',
        season: '每年寒假赛季',
        level: '三类',
        tags: ['MCM', '数学建模', '英文论文'],
        desc: '国际知名数学建模赛事，强调建模与英文论文表达。',
        officialUrl: 'https://www.contest.comap.com/'
    }
];

let currentTrack = '';
let searchKeyword = '';
let currentPage = 1;
const PAGE_SIZE = 6;

// 以《南京晓庄学院学科竞赛分类目录（2025）》为准的显式映射，优先级最高。
const PDF_CLASS_SCOPE_RULES = [
    { pattern: /中国国际大学生创新大赛|互联网\+/, levelClass: '一类A', scope: '国赛' },
    { pattern: /挑战杯.*课外学术科技作品竞赛/, levelClass: '一类A', scope: '国赛' },
    { pattern: /挑战杯.*创业计划大赛/, levelClass: '一类A', scope: '国赛' },
    { pattern: /ACM-ICPC|ICPC|CCPC/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生数学建模竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生电子设计竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国大学生医学技术技能大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生机械创新设计大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生结构设计竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生广告艺术大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /电子商务.*创新.*创意.*创业.*挑战赛|三创/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国大学生工程实践与创新能力大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生物流设计大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /外研社.*国才杯.*理解当代中国/, levelClass: '一类B', scope: '国赛' },
    { pattern: /两岸新锐设计竞赛.*华灿奖/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生创新创业训练计划年会展示/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生化工设计竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生市场调查与分析大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生先进成图技术与产品信息建模创新大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国三维数字化创新设计大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /西门子杯.*智能制造挑战赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国大学生服务外包创新创业大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国大学生计算机设计大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国高校计算机大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /蓝桥杯全国软件和信息技术专业人才大赛/, levelClass: '一类B', scope: '国赛/省赛' },
    { pattern: /全国大学生光电设计竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生集成电路创新创业大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生信息安全竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国大学生机械工程创新创意大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国软件杯.*软件设计大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /大唐杯.*信息通信技术大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /华为\s*ICT\s*大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生嵌入式芯片与系统设计竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生生命科学竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生物理实验竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国高校\s*BIM\s*毕业设计创新大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生智能汽车竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /中国机器人及人工智能大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /全国大学生节能减排社会实践与科技竞赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /21\s*世纪杯.*英语演讲比赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /iCAN.*创新创业大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /工行杯.*金融科技创新大赛/, levelClass: '一类B', scope: '国赛' },
    { pattern: /江苏大学生创新大赛/, levelClass: '二类A', scope: '省赛' },
    { pattern: /江苏省师范生教学基本功大赛/, levelClass: '二类A', scope: '省赛' },
    { pattern: /江苏省大学生职业规划大赛/, levelClass: '二类B', scope: '省赛' },
    { pattern: /江苏省大学生程序设计大赛/, levelClass: '二类B', scope: '省赛' },
    { pattern: /江苏省大学生网络空间安全知识技能大赛/, levelClass: '二类B', scope: '省赛' },
    { pattern: /江苏省大学生电子设计竞赛/, levelClass: '二类B', scope: '省赛' },
    { pattern: /江苏省高校智能机器人创意大赛/, levelClass: '二类B', scope: '省赛' },
    { pattern: /全国高校计算机能力挑战赛/, levelClass: '二类B', scope: '国赛' },
    { pattern: /中国高校计算机大赛.*AIGC/, levelClass: '二类B', scope: '国赛' },
    { pattern: /全国大学生\s*GIS\s*应用技能大赛/, levelClass: '二类B', scope: '国赛' },
    { pattern: /美国大学生数学建模竞赛|MCM/, levelClass: '三类', scope: '国际赛' }
];

function resolveByPdfRules(item) {
    const name = String(item.name || '');
    for (let i = 0; i < PDF_CLASS_SCOPE_RULES.length; i++) {
        const rule = PDF_CLASS_SCOPE_RULES[i];
        if (rule.pattern.test(name)) {
            return { levelClass: rule.levelClass, scope: rule.scope };
        }
    }
    return null;
}

function resolveLevelClass(item) {
    const byPdf = resolveByPdfRules(item);
    if (byPdf && byPdf.levelClass) return byPdf.levelClass;

    const raw = String(item.levelClass || item.level || '').trim();
    if (/^一类A$/.test(raw)) return '一类A';
    if (/^一类B$/.test(raw)) return '一类B';
    if (/^二类A$/.test(raw)) return '二类A';
    if (/^二类B$/.test(raw)) return '二类B';
    if (/^三类$/.test(raw)) return '三类';

    if (/国际赛|国赛|全国/.test(raw)) return '一类B';
    if (/省赛|江苏/.test(raw)) return '二类B';
    return '三类';
}

function resolveScope(item) {
    const byPdf = resolveByPdfRules(item);
    if (byPdf && byPdf.scope) return byPdf.scope;

    const raw = String(item.scope || item.level || '').trim();
    if (/国际/.test(raw)) return '国际赛';
    if (/国赛|全国/.test(raw)) return '国赛';
    if (/省赛|江苏/.test(raw)) return '省赛';

    const klass = resolveLevelClass(item);
    if (/^一类/.test(klass)) return '国赛';
    if (/^二类/.test(klass)) return '省赛';
    return '省赛';
}

function buildTrackTabs() {
    const nav = document.getElementById('competitionTrackTabs');
    if (!nav) return;
    nav.innerHTML = COMP_TRACKS.map(function (t) {
        const active = t.key === currentTrack ? ' active' : '';
        return (
            '<button type="button" class="btn btn-sm competition-track-tab' +
            active +
            '" data-track="' +
            escapeHtml(t.key) +
            '">' +
            escapeHtml(t.label) +
            '</button>'
        );
    }).join('');

    nav.querySelectorAll('[data-track]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            currentTrack = btn.getAttribute('data-track') || '';
            currentPage = 1;
            nav.querySelectorAll('.competition-track-tab').forEach(function (b) {
                b.classList.toggle('active', (b.getAttribute('data-track') || '') === currentTrack);
            });
            renderGrid();
        });
    });
}

function renderPager(totalItems) {
    const pager = document.getElementById('competitionPager');
    const inner = document.getElementById('competitionPagerInner');
    if (!pager || !inner) return;

    const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE));
    if (totalItems <= PAGE_SIZE) {
        pager.classList.add('d-none');
        inner.innerHTML = '';
        return;
    }

    if (currentPage > totalPages) {
        currentPage = totalPages;
    }

    const makeItem = function (label, page, disabled, active) {
        return (
            '<li class="page-item' +
            (disabled ? ' disabled' : '') +
            (active ? ' active' : '') +
            '">' +
            '<button class="page-link" type="button" data-page="' +
            page +
            '"' +
            (disabled ? ' disabled' : '') +
            (active ? ' aria-current="page"' : '') +
            '>' +
            label +
            '</button></li>'
        );
    };

    const pieces = [];
    pieces.push(makeItem('上一页', currentPage - 1, currentPage <= 1, false));

    let start = Math.max(1, currentPage - 2);
    let end = Math.min(totalPages, currentPage + 2);
    if (end - start < 4) {
        if (start === 1) end = Math.min(totalPages, start + 4);
        if (end === totalPages) start = Math.max(1, end - 4);
    }
    for (let p = start; p <= end; p++) {
        pieces.push(makeItem(String(p), p, false, p === currentPage));
    }
    pieces.push(makeItem('下一页', currentPage + 1, currentPage >= totalPages, false));

    inner.innerHTML = pieces.join('');
    pager.classList.remove('d-none');

    inner.querySelectorAll('button[data-page]').forEach(function (a) {
        a.addEventListener('click', function (e) {
            e.preventDefault();
            const page = parseInt(a.getAttribute('data-page') || '1', 10);
            if (!Number.isFinite(page)) return;
            if (page < 1 || page > totalPages || page === currentPage) return;
            currentPage = page;
            renderGrid();
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });
    });
}

function matchItem(item) {
    if (currentTrack && item.track !== currentTrack) {
        return false;
    }
    if (!searchKeyword) {
        return true;
    }
    const blob =
        (item.name || '') +
        ' ' +
        (item.organizer || '') +
        ' ' +
        (item.tags || []).join(' ') +
        ' ' +
        (item.desc || '');
    return blob.toLowerCase().indexOf(searchKeyword) !== -1;
}

function renderGrid() {
    const grid = document.getElementById('competitionGrid');
    const empty = document.getElementById('competitionEmpty');
    const hint = document.getElementById('competitionCountHint');
    if (!grid) return;

    const list = COMPETITIONS.filter(matchItem);
    const totalFiltered = list.length;
    const totalPages = Math.max(1, Math.ceil(totalFiltered / PAGE_SIZE));
    if (currentPage > totalPages) {
        currentPage = totalPages;
    }
    const start = (currentPage - 1) * PAGE_SIZE;
    const pageList = list.slice(start, start + PAGE_SIZE);

    if (hint) {
        hint.textContent =
            '当前第 ' +
            currentPage +
            ' / ' +
            totalPages +
            ' 页 · 筛选结果 ' +
            totalFiltered +
            ' 项 · 共收录 ' +
            COMPETITIONS.length +
            ' 项（持续可扩展）';
    }

    if (totalFiltered === 0) {
        grid.innerHTML = '';
        if (empty) empty.classList.remove('d-none');
        renderPager(0);
        return;
    }
    if (empty) empty.classList.add('d-none');

    grid.innerHTML = pageList
        .map(function (item) {
            const levelClass = resolveLevelClass(item);
            const scope = resolveScope(item);
            const tags = (item.tags || [])
                .map(function (t) {
                    return '<span class="meta-chip meta-chip--muted">' + escapeHtml(t) + '</span>';
                })
                .join('');
            const url = (item.officialUrl || '').trim();
            const link =
                url ?
                    '<a href="' +
                    escapeHtml(url) +
                    '" class="btn btn-sm btn-outline-primary" target="_blank" rel="noopener noreferrer">访问官网</a>'
                :   '<span class="text-muted small">官网请自行检索</span>';
                var scopeClass = 'scope--default';
                if (scope === '国赛' || scope === '国际赛' || scope === '国际级') scopeClass = 'scope--national';
                else if (scope === '省赛' || scope === '省级') scopeClass = 'scope--province';
                else if (scope === '校赛' || scope === '校级') scopeClass = 'scope--school';
                return (
                '<article class="competition-card h-100">' +
                '<div class="competition-card__header">' +
                '<h2 class="competition-card__title mb-0">' +
                escapeHtml(item.name) +
                '</h2>' +
                '<div class="competition-card__levels">' +
                '<span class="competition-card__level competition-card__level--scope ' + scopeClass + '">' +
                escapeHtml(scope) +
                '</span>' +
                '<span class="competition-card__level competition-card__level--class level--' +
                (levelClass.includes('一类A') ? '1a' :
                 levelClass.includes('一类B') ? '1b' :
                 levelClass.includes('二类A') ? '2a' :
                 levelClass.includes('二类B') ? '2b' :
                 levelClass.includes('三类') ? '3' : 'default') + '">' +
                escapeHtml(levelClass) +
                '</span>' +
                '</div>' +
                '</div>' +
                '<div class="competition-card__meta small text-muted mb-2">' +
                '<span class="meta-item"><strong>主办：</strong>' +
                escapeHtml(item.organizer || '') +
                '</span>' +
                '<span class="meta-item mt-1"><strong>赛季参考：</strong>' +
                escapeHtml(item.season || '') +
                '</span>' +
                '</div>' +
                '<div class="competition-card__tags d-flex flex-wrap gap-1 mb-2">' +
                tags +
                '</div>' +
                '<p class="competition-card__desc mb-3">' +
                escapeHtml(item.desc || '') +
                '</p>' +
                '<div class="competition-card__foot mt-auto">' +
                link +
                '</div>' +
                '</div></article>'
            );
        })
        .join('');

    renderPager(totalFiltered);
}

document.addEventListener('DOMContentLoaded', function () {
    buildTrackTabs();
    const inp = document.getElementById('compSearchInput');
    const btn = document.getElementById('compSearchBtn');
    function applySearch() {
        searchKeyword = inp ? inp.value.trim().toLowerCase() : '';
        currentPage = 1;
        renderGrid();
    }
    if (btn) {
        btn.addEventListener('click', applySearch);
    }
    if (inp) {
        inp.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                applySearch();
            }
        });
    }
    renderGrid();
});
