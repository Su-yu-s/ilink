package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-supplied subjects and filters; never contains inferred entity IDs or expected answers. */
public record QueryIntent(List<String> subjects, String focusText, boolean graphRequested) {
    private static final Pattern QUOTED = Pattern.compile("[「《“\"]([^」》”\"]{2,80})[」》”\"]");
    private static final Pattern PARENTHETICAL = Pattern.compile("[（(]([^）)]{2,60})[）)]");
    /**
     * 名称尾部的说明性后缀，不属于名称本身。通用中心词（平台/系统/项目/模块）、自我指代词
     * （本身/自身/自己，说明前后指的是同一个对象）与类型词（实体）可以叠加，剥离时反复进行。
     */
    private static final String[] CORE_SUFFIXES =
        { "平台", "系统", "项目", "模块", "本身", "自身", "自己", "实体" };
    private static final Pattern DATES = Pattern.compile(
        "20\\d{2}\\s*年(?:\\s*\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*日)?)?"
            + "|20\\d{2}-\\d{1,2}(?:-\\d{1,2})?|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日"
            // 旬属于时间范围，不是主体的一部分；KgTimeRange 已能把它换算成半开窗口。
            + "|(?:中下|上|中|下)旬|[（(]\\s*\\d{1,2}\\s*日?\\s*[至到-]\\s*\\d{1,2}\\s*日\\s*[）)]");
    private static final Pattern FILLER = Pattern.compile(
        "在(?:知识)?图谱(?:里|中)?|关联的|关联|相关的|相关|分别|有哪些|有哪|哪些|什么"
            + "|与|的|里|中|请|列举|包括|包含|有哪些");
    private static final Pattern SUBJECT_END = Pattern.compile(
        "(?:与|和|及)(?:哪些|哪个|什么|谁)|在(?:知识)?图谱(?:里|中)?|在(?:桌面端|移动端|网页端|手机端|PC端|服务端|客户端)"
            // 文档类型词是请求尾部，不是主体名称：相关的记录 / 的开发记录 / 开发日志 / 的帖子还提到。
            // 必须带“的”或“开发”，否则“平台记录”这类本身就是主体的一部分；
            // “那份/这份开发日志”本身是所指对象，剥掉会只剩指示代词。
            + "|(?<!那份)(?<!这份)(?<!该份)(?<!这条)(?<!那条)(?<!上述)(?<!该)(?<!此)(?<!本)"
            + "(?:(?:相关)?的(?:开发)?(?:记录|帖子|文档|日志|周报)|开发(?:记录|日志))"
            + "|的?(?:记录|帖子|文档|日志)(?:中|里)?提到"
            + "|提到过|提及|分别|各自|由什么|由谁|是否|有哪些|有哪|哪些|有什么|按(?:什么|哪些|哪)"
            + "|哪个|哪种|什么"
            + "|的(?:主办|承办|组织|关系|技能|技术栈|报名|颜色|操作|步骤|界面|功能|高度|宽度|尺寸|大小|规则|样式|布局|结构|流程|数量)"
            // 属性请求尾部同样不是主体名称：“X 的技术细节是什么”里的主体是 X。
            // 只列属性词，避免把“X 的技术方案”这类本身就是主体名称的短语切断。
            + "|的(?:技术|实现|设计|核心|主要|关键|具体|详细)?"
            + "(?:细节|要点|内容|特点|原理|机制|思路|做法|方案|用途|作用|优势|缺点|定义|含义|参数|配置|指标|说明)"
            + "|关联|关系");
    /**
     * 主体尾部的情态副词不属于名称：例如 “iLink 平台本身（ilink 实体）直接关联了哪些竞赛”
     * 在 “关联” 处切断后还剩一个 “直接”。这些词不能作为专有名称结尾，剥离后仍保留原始请求名。
     */
    private static final Pattern SUBJECT_TRAILING_ADVERB = Pattern.compile(
        "(?:直接|间接|到底|究竟|明确|确切|仍然|同时|并且|还|又|也)\\s*$");

    /**
     * Alias strength is deliberately ordered. A short core name may locate a candidate, but it
     * cannot turn a more specific composite name into an ambiguous subject.
     */
    public enum AliasTier {
        EXACT_ORIGINAL(100),
        COMPOSITE(80),
        PARENTHETICAL(60),
        SHORT_CORE(40);

        private final int strength;

        AliasTier(int strength) { this.strength = strength; }

        public int strength() { return strength; }
    }

    public record AliasForm(String normalized, AliasTier tier) { }

    /** One permission-checked entity and the alias that caused it to survive the highest tier. */
    public record ExactSubjectMatch(EvidenceLedger.EntityFact entity, String matchedAlias, AliasTier tier) { }

    /** Extraction completeness is separate from whether a retrieved entity looks similar. */
    public record SubjectRequests(List<String> subjects, boolean complete, String reason) {
        public SubjectRequests { subjects = List.copyOf(subjects); }
    }

    public QueryIntent {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        focusText = focusText == null ? "" : focusText;
    }

    public static QueryIntent fromQuestion(String question) {
        String q = question == null ? "" : question.trim();
        SubjectRequests request = subjectRequests(q);
        String focus = DATES.matcher(q).replaceAll("");
        for (String subject : request.subjects().stream()
            .sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList())
            focus = focus.replace(subject, " ");
        focus = FILLER.matcher(focus).replaceAll(" ").replaceAll("[\\p{P}\\s]+", " ").trim();
        return new QueryIntent(request.subjects(), focus, q.contains("图谱"));
    }

    public static SubjectRequests subjectRequests(String question) {
        String q = question == null ? "" : question.trim();
        List<String> quoted = new ArrayList<>();
        List<String> documentTitles = new ArrayList<>();
        Matcher matcher = QUOTED.matcher(q);
        while (matcher.find()) {
            quoted.add(matcher.group(1).trim());
            if (q.charAt(matcher.start()) == '《') documentTitles.add(matcher.group(1).trim());
        }
        LinkedHashSet<String> subjects = new LinkedHashSet<>();
        boolean comparison = q.matches("(?s).*(?:分别|对比|比较|两者|二者|各自|区别|差异|同时提到|共同提到).*");
        if (!quoted.isEmpty()) {
            List<String> subjectCandidates = documentTitles.isEmpty() ? quoted : documentTitles;
            subjects.add(subjectCandidates.get(0));
            boolean joinedQuotes = q.matches("(?s).*[」》”\"]\\s*(?:与|和|及|、)\\s*[「《“\"].*");
            if (comparison || joinedQuotes) {
                for (String text : subjectCandidates) {
                    subjects.add(text);
                }
            }
            // Mask quoted names before splitting: a title may itself contain 与/和.
            StringBuffer masked = new StringBuffer();
            matcher.reset();
            int index = 0;
            while (matcher.find()) matcher.appendReplacement(masked, "__Q" + index++ + "__");
            matcher.appendTail(masked);
            String[] parts = subjectHeader(masked.toString()).split("\\s*(?:与|和|及|、|\\s+and\\s+)\\s*", -1);
            if (parts.length > 1 && java.util.Arrays.stream(parts).anyMatch(part -> part.contains("__Q"))) {
                subjects.clear();
                boolean malformed = false;
                for (String part : parts) {
                    String candidate = part.trim().replaceFirst("的$", "");
                    if (candidate.matches("__Q\\d+__")) {
                        int quotedIndex = Integer.parseInt(candidate.substring(3, candidate.length() - 2));
                        subjects.add(quoted.get(quotedIndex));
                    } else if (!candidate.contains("__Q") && candidate.length() >= 2 && candidate.length() <= 80) {
                        subjects.add(candidate);
                    } else malformed = true;
                }
                if (malformed) return boundedSubjects(subjects, false, "SUBJECT_PARSE_UNCERTAIN");
            }
        } else {
            String header = subjectHeader(q);
            String[] parts = header.split("\\s*(?:与|和|及|、|\\s+and\\s+)\\s*", -1);
            boolean malformed = false;
            for (String part : parts) {
                String candidate = part.trim().replaceFirst("的$", "");
                if (candidate.length() >= 2 && candidate.length() <= 80) subjects.add(candidate);
                else malformed = true;
            }
            if (malformed) return boundedSubjects(subjects, false, "SUBJECT_PARSE_UNCERTAIN");
        }
        return boundedSubjects(subjects, !subjects.isEmpty(), subjects.isEmpty() ? "NO_SUBJECT" : "PARSED");
    }

    private static String subjectHeader(String question) {
        String withoutDates = DATES.matcher(question).replaceAll("")
            .replaceFirst("^[\\s，,：:至到及和—–~-]*(?:期间|之间|以后|之后|以前|之前|以来)?[\\s，,：:]*", "")
            .replaceFirst("^(?:截至目前|截至|当前|目前|现在|今年|至今)[，,：:\\s]*", "")
            .replaceFirst("^(?:知识图谱里|知识图谱中|图谱里|图谱中)[，,：:\\s]*", "")
            .replaceFirst("^(?:请)?(?:比较|对比|查询|列举|说明)[，,：:\\s]*", "");
        Matcher end = SUBJECT_END.matcher(withoutDates);
        String header = (end.find() ? withoutDates.substring(0, end.start()) : withoutDates)
            .trim().replaceAll("^[，,：:至到及和\\s]+|[，,：:。？?\\s]+$", "");
        return SUBJECT_TRAILING_ADVERB.matcher(header).replaceAll("").trim();
    }

    private static SubjectRequests boundedSubjects(LinkedHashSet<String> subjects, boolean complete, String reason) {
        if (subjects.size() > 4)
            return new SubjectRequests(subjects.stream().limit(4).toList(), false, "SUBJECT_LIMIT");
        return new SubjectRequests(new ArrayList<>(subjects), complete, reason);
    }

    public String primarySubject() { return subjects.isEmpty() ? null : subjects.get(0); }

    /**
     * A relation-list request is still public even though primarySubject only exposes the first name.
     */
    public String requestedEntityType() {
        String text = normalize(focusText);
        if (text.contains("技能") || text.contains("技术栈") || text.contains("技术技能")) return "SKILL";
        if (text.contains("竞赛") || text.contains("赛事") || text.contains("子赛")
            || text.contains("大赛") || text.contains("比赛")) return "COMPETITION";
        if (text.contains("组织") || text.contains("机构")) return "ORGANIZATION";
        if (text.contains("帖子") || text.contains("记录") || text.contains("文档")) return "POST";
        for (String subject : subjects) {
            String name = normalize(subject);
            if (name.contains("竞赛") || name.contains("赛事") || name.contains("子赛")
                || name.contains("大赛") || name.contains("比赛")) return "COMPETITION";
        }
        return null;
    }

    public boolean matchesSubject(String name) {
        String candidate = normalize(name);
        return candidate.length() >= 3 && subjects.stream().anyMatch(subject -> {
            for (String expected : aliasForms(subject)) {
                if (expected.equals(candidate)) return true;
                String shorter = expected.length() <= candidate.length() ? expected : candidate;
                String longer = expected.length() <= candidate.length() ? candidate : expected;
                if (shorter.length() * 5 < longer.length() * 3) continue;
                int position = 0;
                for (int i = 0; i < longer.length() && position < shorter.length(); i++)
                    if (longer.charAt(i) == shorter.charAt(position)) position++;
                if (position == shorter.length()) return true;
            }
            return false;
        });
    }

    /** 括号同位语只影响名称对齐，原始请求名保持不变。 */
    public static boolean sameSubjectName(String requested, String candidate) {
        String target = normalize(candidate);
        return !target.isEmpty() && aliasForms(requested).stream().anyMatch(target::equals);
    }

    /**
     * Returns only entities matched at the strongest alias tier shared by the candidate set.
     * A lower-tier short name never competes with a unique composite match.
     */
    public static List<ExactSubjectMatch> bestExactMatches(
        String requested, Collection<EvidenceLedger.EntityFact> entities
    ) {
        if (entities == null || entities.isEmpty()) return List.of();
        List<AliasForm> aliases = aliasFormsByStrength(requested);
        if (aliases.isEmpty()) return List.of();
        List<ExactSubjectMatch> matches = new ArrayList<>();
        for (EvidenceLedger.EntityFact entity : entities) {
            if (entity == null) continue;
            String candidate = normalize(entity.name());
            if (candidate.isEmpty()) continue;
            AliasForm best = null;
            for (AliasForm alias : aliases) {
                if (!alias.normalized().equals(candidate)) continue;
                if (best == null || alias.tier().strength() > best.tier().strength()) best = alias;
            }
            if (best != null) matches.add(new ExactSubjectMatch(entity, best.normalized(), best.tier()));
        }
        if (matches.isEmpty()) return List.of();
        int strongest = matches.stream().mapToInt(match -> match.tier().strength()).max().orElse(0);
        return matches.stream()
            .filter(match -> match.tier().strength() == strongest)
            .sorted(Comparator.comparing(match -> match.entity().id(),
                Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    public String searchQuery(String fallback) {
        if (subjects.isEmpty()) return fallback;
        return String.join(" ", subjects) + (focusText.isBlank() ? "" : " " + focusText);
    }

    public String contract() {
        return "用户指定主体：" + (subjects.isEmpty() ? "按问题原文确认" : String.join("、", subjects))
            + "；筛选或属性要求：" + (focusText.isBlank() ? "按问题原文" : focusText)
            + "。只覆盖用户实际要求，不自行添加必答项。"
            + (graphRequested ? "原文提及与图谱关系分别说明；原文不能证明已经存在图谱边。" : "");
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * 括号同位语与通用中心词不属于名称主体：
     * iLink 平台（Agentic GraphRAG 项目）→ iLink Agentic GraphRAG。
     * 只用于把请求名对齐到已授权实体，不改变原始请求名，便于审计。
     *
     * <p>括号外的名称与括号内的名称都单独作为候选：请求 “iLink 平台本身（ilink 实体）”
     * 指向的就是实体 ilink，而不是 “ilinkilink”。括号只是同名补充说明，
     * 因此不能只保留拼接形式。
     */
    static List<String> aliasForms(String text) {
        return aliasFormsByStrength(text).stream().map(AliasForm::normalized).toList();
    }

    static List<AliasForm> aliasFormsByStrength(String text) {
        String source = text == null ? "" : text;
        Map<String, AliasTier> forms = new LinkedHashMap<>();
        addAlias(forms, normalize(source), AliasTier.EXACT_ORIGINAL);
        addAlias(forms, coreName(normalize(source)), AliasTier.SHORT_CORE);
        Matcher inner = PARENTHETICAL.matcher(source);
        while (inner.find()) {
            String outer = coreName(normalize(source.substring(0, inner.start())));
            String nested = coreName(normalize(inner.group(1)));
            if (!outer.isEmpty() && !nested.isEmpty()) addAlias(forms, outer + nested, AliasTier.COMPOSITE);
            addAlias(forms, outer, AliasTier.SHORT_CORE);
            addAlias(forms, nested, AliasTier.PARENTHETICAL);
        }
        return forms.entrySet().stream()
            .map(entry -> new AliasForm(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * A short core needs at least four normalized characters. Even then it is a low-strength
     * candidate and cannot outrank a composite or parenthetical alias.
     */
    private static void addAlias(Map<String, AliasTier> forms, String alias, AliasTier tier) {
        if (alias == null || alias.isEmpty()) return;
        if (tier == AliasTier.SHORT_CORE && alias.length() < 4) return;
        AliasTier existing = forms.get(alias);
        if (existing == null || tier.strength() > existing.strength()) forms.put(alias, tier);
    }

    /** 去掉通用中心词、自我指代与类型后缀，只留下名称本身；后缀可叠加，所以反复剥离。 */
    private static String coreName(String value) {
        String core = value;
        for (boolean changed = true; changed; ) {
            changed = false;
            for (String suffix : CORE_SUFFIXES) {
                if (core.length() > suffix.length() && core.endsWith(suffix)) {
                    core = core.substring(0, core.length() - suffix.length());
                    changed = true;
                }
            }
        }
        return core;
    }
}
