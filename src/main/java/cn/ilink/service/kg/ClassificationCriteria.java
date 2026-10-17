package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One query-derived criterion shared by generation and production verification, not a fact list. */
final class ClassificationCriteria {
    static final String POLICY = "query-ecosystem-criteria-v1";
    private static final Pattern ECOSYSTEM = Pattern.compile(
        "(不属于|不属|不是|属于|属|非)\\s*((?:[A-Za-z]|\\.[A-Za-z])[A-Za-z0-9_+#.\\-]{0,23}|[\\p{IsHan}]{1,8})\\s*生态");

    record Criteria(String focus, String ecosystem, String operator, boolean negative,
                    boolean explicit, String instruction) {
        Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("policy", POLICY); result.put("focus", focus);
            result.put("ecosystem", ecosystem); result.put("operator", operator);
            result.put("negative", negative); result.put("explicit", explicit);
            result.put("instruction", instruction);
            return result;
        }

        /** Reject only an explicit category label opposing the question, never infer member facts. */
        List<String> polarityFailures(GraphClassificationContract.Parsed parsed) {
            if (!explicit) return List.of();
            List<String> failures = new ArrayList<>();
            for (int index = 0; index < parsed.groups().size(); index++) {
                var group = parsed.groups().get(index);
                if (group.state() == GraphClassificationContract.State.U) continue;
                int direction = roleDirection(group.role());
                if (direction == 0) continue;
                boolean matchesCondition = negative ? direction < 0 : direction > 0;
                if ((group.state() == GraphClassificationContract.State.I) != matchesCondition)
                    failures.add("CLASSIFICATION_POLARITY_MISMATCH:GROUP:" + (index + 1));
            }
            return List.copyOf(failures);
        }

        /** A local string check is neither exhaustive classification nor a semantic verdict. */
        Map<String, Object> polarityMetadata(GraphClassificationContract.Parsed parsed) {
            List<Integer> checked = new ArrayList<>(), unchecked = new ArrayList<>();
            for (int index = 0; index < parsed.groups().size(); index++) {
                var group = parsed.groups().get(index);
                if (group.state() == GraphClassificationContract.State.U) continue;
                (explicit && roleDirection(group.role()) != 0 ? checked : unchecked).add(index + 1);
            }
            List<String> failures = polarityFailures(parsed);
            boolean complete = explicit && parsed.complete() && !checked.isEmpty() && unchecked.isEmpty();
            String status = !failures.isEmpty() ? "EXPLICIT_LABEL_CONFLICT"
                : !parsed.complete() ? "INVALID_PARTITION"
                : !explicit ? "CRITERION_UNRESOLVED"
                : checked.isEmpty() && unchecked.isEmpty() ? "NO_ASSERTIVE_GROUPS"
                : complete ? "NO_EXPLICIT_LABEL_CONFLICT" : "PARTIAL_LABEL_CHECK";
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("criterionCheckScope", "EXPLICIT_ROLE_LABELS_ONLY");
            metadata.put("criterionCheckStatus", status); metadata.put("criterionCheckComplete", complete);
            metadata.put("criterionCheckedGroups", List.copyOf(checked));
            metadata.put("criterionUncheckedGroups", List.copyOf(unchecked));
            metadata.put("criterionConsistent", !failures.isEmpty() ? Boolean.FALSE : complete ? Boolean.TRUE : null);
            metadata.put("criterionFailures", failures);
            return java.util.Collections.unmodifiableMap(metadata);
        }

        /** Closed codes remove free labels; actual attribute truth still depends on source verification. */
        Map<String, Object> decisionMetadata(GraphClassificationContract.DecisionParsed parsed,
                                             boolean semanticAccepted) {
            boolean hasAssertions = parsed.groups().stream()
                .anyMatch(group -> group.state() != GraphClassificationContract.State.U);
            boolean accepted = semanticAccepted && parsed.complete();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("criterionCheckScope", "STATE_REASON_COMPATIBILITY_ONLY");
            metadata.put("criterionCheckStatus", parsed.complete() ? "DECISION_CODES_VALID" : "INVALID_DECISION_PROTOCOL");
            metadata.put("criterionCheckComplete", parsed.complete());
            metadata.put("criterionConsistent", accepted && hasAssertions ? Boolean.TRUE : null);
            metadata.put("criterionSemanticsStatus", !hasAssertions ? "NO_I_E_DECISIONS"
                : accepted ? "VERIFIER_ACCEPTED_I_E_DECISIONS" : "NOT_VERIFIED");
            metadata.put("criterionFailures", parsed.failures());
            return java.util.Collections.unmodifiableMap(metadata);
        }

        private int roleDirection(String role) {
            String target = Pattern.quote(ecosystem);
            Pattern positive = Pattern.compile("^" + target + "生态(?:组件|技术|语言|框架|工具)?$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Pattern opposite = Pattern.compile("^(?:非|不属于|不属)" + target
                + "生态(?:组件|技术|语言|框架|工具)?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            String compact = role.replaceAll("\\s+", "");
            return positive.matcher(compact).matches() ? 1 : opposite.matcher(compact).matches() ? -1 : 0;
        }
    }

    private ClassificationCriteria() { }

    static Criteria fromQuestion(String question) {
        var intent = QueryIntent.fromQuestion(question);
        String focus = intent.focusText();
        // QueryIntent's retrieval text strips punctuation; retain C++/.NET-style attribute tokens.
        String criterionText = question == null ? "" : question;
        for (String subject : intent.subjects().stream()
            .sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList())
            criterionText = criterionText.replaceFirst(Pattern.quote(subject), " ");
        Matcher matcher = ECOSYSTEM.matcher(criterionText);
        String ecosystem = "", operator = "";
        int count = 0;
        while (matcher.find()) {
            operator = matcher.group(1); ecosystem = matcher.group(2); count++;
        }
        // Compound or multiple criteria remain in the question; do not choose one silently.
        boolean compound = Pattern.compile("(?:不属于|不属|不是|属于|属)\\s*(?:非|不属|不是)")
            .matcher(criterionText).find();
        boolean explicit = count == 1 && !compound && !ecosystem.startsWith("非") && !ecosystem.startsWith("不");
        if (!explicit) { ecosystem = ""; operator = ""; }
        boolean negative = explicit && List.of("不属于", "不属", "不是", "非").contains(operator);
        String direction = explicit ? "目标生态=" + ecosystem + "；用户条件="
            + (negative ? "不属于" : "属于") + ecosystem + "生态。I=满足这个条件；E=不满足或不是所问属性。"
            + (negative ? "属于目标生态的技术应为E，非目标生态技术应为I。"
                : "属于目标生态的技术应为I，非目标生态技术应为E。")
            : "按问题原文判断I/E，否定词作用于筛选条件，不能把I固定当作属于某生态。";
        boolean technical = focus.contains("技术") || focus.contains("技能") || focus.contains("语言")
            || focus.contains("运行时") || focus.contains("框架");
        String rules = technical ? "先判断是否为用户所问的独立技术，再判断生态归属；原文明示的业务模块、类、文件、"
            + "表、环境变量、版本标记不能仅凭SKILL标签算独立技术，可有据归E；身份不明则U。"
            + "语言生态归属以原文明示的语言、运行时或专用框架/库依赖为据；部署在同一后端、被该语言调用"
            + "不证明归属。原文明示的其他语言服务、前端技术栈、独立数据库/缓存/存储/消息队列/协议/部署服务"
            + "须按所问生态判断，不能全部并入后端语言生态；不可臆测产品实现语言或未载明的专用依赖。"
            + "每个I/E成员必须有自己的原文支持，不借相邻项目或同组其他成员证明；仅有名字或关联边则U。"
            + "U是未确认，不是排除；缺少原文不能推导为非目标生态。"
            : "按用户所问属性及原文逐成员分类；不将技术栈判据套用到其他对象。"
                + "缺少证明的成员为U，不能因资料未展示而归E。";
        return new Criteria(focus, ecosystem, operator, negative, explicit, direction + rules);
    }
}
