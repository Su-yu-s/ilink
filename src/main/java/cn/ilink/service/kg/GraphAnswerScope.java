package cn.ilink.service.kg;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Visible graph candidates and query coverage, independent of what the draft chose to cite. */
final class GraphAnswerScope {
    private static final int INVENTORY_BUDGET = 1200;
    private record Candidate(Long id, Long anchorId, String direction, String relationType,
                             String name, String entityType) { }
    record Scope(boolean requested, boolean sourceOnly, boolean queryComplete, boolean inventoryComplete,
                 String text, Set<Long> relationIds, int candidateCount, int shownCount) {
        boolean limited() { return requested && (sourceOnly || !queryComplete || !inventoryComplete); }
        String instruction() {
            if (!requested) return "";
            return "图谱查询完整=" + queryComplete + "；候选清单展示完整=" + inventoryComplete
                + "；候选已展示=" + shownCount + "/" + candidateCount + "。"
                + (sourceOnly ? "只有原文支持，不能确认所问图谱集合；回答必须限定为原文内容。"
                    : limited() ? "不能声称全集、唯一或总数；明确只列已确认的部分。"
                    : "按用户属性逐项分类候选；同义名称可合并，但不能漏掉已确定符合条件的项目。")
                + "候选是待分类的关系集合，不表示每个候选都符合筛选条件；不得擅自扩大技术栈等属性范围。"
                + "完整性仅指当前授权可见范围，不支持图谱全局不存在或未收录断言。";
        }
    }

    private GraphAnswerScope() { }

    static Scope describe(String question, EvidenceLedger ledger) {
        QueryIntent intent = QueryIntent.fromQuestion(question);
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(question);
        if (!intent.graphRequested() || !plan.requiresRelation() || plan.requiresTemporal()
            || RecommendationEvidencePolicy.isRecommendationQuery(question))
            return new Scope(false, false, true, true, "", Set.of(), 0, 0);
        SubjectScopeLedger.Analysis subjectScopes = SubjectScopeLedger.assess(question, ledger);
        Set<Long> anchors = new LinkedHashSet<>();
        for (SubjectScopeLedger.SubjectScope subject : subjectScopes.subjects())
            if (subject.exact()) anchors.add(subject.entityId());
        String focus = intent.focusText();
        String targetType = focus.contains("技能") || focus.contains("技术栈") ? "SKILL"
            : focus.contains("竞赛") || focus.contains("赛事") ? "COMPETITION" : null;
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        java.util.Date currentAt = KgTimeRange.now();
        for (EvidenceLedger.RelationFact edge : ledger.relations().stream()
            .sorted(Comparator.comparing(EvidenceLedger.RelationFact::id)).toList()) {
            if (edge.validFrom() != null && edge.validFrom().after(currentAt)
                || edge.validTo() != null && !edge.validTo().after(currentAt)) continue;
            for (boolean out : List.of(true, false)) {
                Long anchorId = out ? edge.sourceId() : edge.targetId();
                if (!anchors.contains(anchorId)) continue;
                Long otherId = out ? edge.targetId() : edge.sourceId();
                EvidenceLedger.EntityFact other = ledger.entity(otherId).orElse(null);
                if (other == null || targetType != null && !targetType.equals(other.type())) continue;
                String key = anchorId + ":" + otherId + ":" + edge.type() + ":" + out;
                if (candidates.containsKey(key)) continue;
                candidates.put(key, new Candidate(edge.id(), anchorId, out ? "OUT" : "IN",
                    edge.type(), other.name(), other.type()));
            }
        }
        boolean complete = subjectScopes.queryComplete();
        StringBuilder text = new StringBuilder("【授权图谱候选清单，OUT=主体指向候选，IN=候选指向主体】\n")
            .append(subjectScopes.instruction()).append('\n');
        for (Long anchorId : anchors) ledger.entity(anchorId).ifPresent(anchor ->
            text.append("主体#").append(anchorId).append(' ').append(anchor.name()).append('\n'));
        Set<Long> shownIds = new LinkedHashSet<>();
        int shown = 0;
        boolean inventoryComplete = true;
        String previousGroup = null;
        for (Candidate candidate : candidates.values()) {
            String group = "主体#" + candidate.anchorId() + " " + candidate.direction()
                + " " + candidate.relationType() + " 候选类型=" + candidate.entityType();
            String line = (group.equals(previousGroup) ? "" : "【" + group + "】\n")
                + "r#" + candidate.id() + " " + candidate.name() + "\n";
            if (EvidenceContext.tokens(text + line) > INVENTORY_BUDGET) {
                inventoryComplete = false;
                break;
            }
            text.append(line);
            previousGroup = group;
            shownIds.add(candidate.id());
            shown++;
        }
        if (candidates.isEmpty()) text.append("未取得符合主体/类型要求的图谱候选；不能凭原文建立关系。\n");
        return new Scope(true, !complete && candidates.isEmpty(), complete, inventoryComplete,
            text.toString(), Set.copyOf(shownIds), candidates.size(), shown);
    }
}
