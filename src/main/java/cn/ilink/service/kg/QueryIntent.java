package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-supplied subjects and filters; never contains inferred entity IDs or expected answers. */
public record QueryIntent(List<String> subjects, String focusText, boolean graphRequested) {
    private static final Pattern QUOTED = Pattern.compile("[「《“\"]([^」》”\"]{2,80})[」》”\"]");
    private static final Pattern PARENTHETICAL = Pattern.compile("[（(]([^）)]{2,60})[）)]");
    private static final String[] GENERIC_HEADS = { "平台", "系统", "项目", "模块" };
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
            + "|关联|关系");

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
        return (end.find() ? withoutDates.substring(0, end.start()) : withoutDates)
            .trim().replaceAll("^[，,：:至到及和\\s]+|[，,：:。？?\\s]+$", "");
    }

    private static SubjectRequests boundedSubjects(LinkedHashSet<String> subjects, boolean complete, String reason) {
        if (subjects.size() > 4)
            return new SubjectRequests(subjects.stream().limit(4).toList(), false, "SUBJECT_LIMIT");
        return new SubjectRequests(new ArrayList<>(subjects), complete, reason);
    }

    public String primarySubject() { return subjects.isEmpty() ? null : subjects.get(0); }

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
     */
    static List<String> aliasForms(String text) {
        String source = text == null ? "" : text;
        List<String> forms = new ArrayList<>();
        forms.add(normalize(source));
        Matcher inner = PARENTHETICAL.matcher(source);
        while (inner.find()) {
            String outer = normalize(source.substring(0, inner.start()));
            String nested = normalize(inner.group(1));
            for (String head : GENERIC_HEADS) {
                if (outer.endsWith(head)) outer = outer.substring(0, outer.length() - head.length());
                if (nested.endsWith(head)) nested = nested.substring(0, nested.length() - head.length());
            }
            if (!outer.isEmpty() && !nested.isEmpty()) forms.add(outer + nested);
        }
        return forms;
    }
}
