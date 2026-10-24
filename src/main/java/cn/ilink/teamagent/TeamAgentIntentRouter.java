package cn.ilink.teamagent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class TeamAgentIntentRouter {
    public enum Route {
        KNOWLEDGE_QUICK,
        KNOWLEDGE_DEEP,
        TEAM_METRICS,
        TEAM_RISK,
        WEEKLY_DRAFT,
        ACTION_PLAN,
        COLLAB_DOCUMENT,
        COLLAB_TABLE,
        MATERIAL_REVIEW
    }

    private static final Pattern WEEKLY = Pattern.compile(
        "周报|简报|本周总结|进展汇总|weekly\\s+report|week\\s+summary", Pattern.CASE_INSENSITIVE);
    private static final Pattern MATERIAL = Pattern.compile(
        "材料|报名材料|参赛材料|证明材料|资质文件|佐证|材料矩阵|材料清单|还缺|缺失|补齐"
            + "|material|document\\s+checklist|what.\\s+we.\\s+missing",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE = Pattern.compile(
        "表格|清单表|分工表|排期表|进度表|台账|甘特|电子表格|矩阵表"
            + "|spreadsheet|schedule\\s+sheet|gantt|tracker\\s+sheet",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT = Pattern.compile(
        "文档|方案书|计划书|设计说明书|说明书|草稿|章节|撰写|起草|拟一|写一?[份篇]|协作稿"
            + "|document|draft|write\\s+(a|the)\\s+(section|chapter)"
            + "|collaboration\\s+document",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile(
        "拆解|拆分|创建任务|新建任务|后续任务|行动规划|行动计划|规划|计划|安排|指派"
            + "|改派|重新分配|优先级|截止日期|提醒"
            + "|create\\s+task|break\\s*down|reassign|priority|deadline|remind",
        Pattern.CASE_INSENSITIVE);
    /**
     * Unambiguous task-management wording. It outranks collaboration keywords so
     * "安排任务" never turns into a document/table proposal.
     */
    private static final Pattern TASK_ACTION = Pattern.compile(
        "创建任务|新建任务|后续任务|拆解|拆分|安排任务|任务安排|指派|改派|重新分配"
            + "|优先级|截止日期|提醒|行动规划|行动计划"
            + "|create\\s+task|break\\s*down|reassign|priority|deadline|remind",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RISK = Pattern.compile(
        "风险|逾期|延期|阻塞|未指派|等待审核|进度异常|risk|overdue|blocked|stale",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern METRICS = Pattern.compile(
        "多少|几个|数量|完成率|进度|统计|看板|任务情况|成员|负责人"
            + "|count|how\\s+many|completion|status|progress",
        Pattern.CASE_INSENSITIVE);

    public Route route(String text, String requestedMode, TeamAgentScope scope) {
        String value = safe(text);
        String mode = safe(requestedMode).toUpperCase(Locale.ROOT);
        if (WEEKLY.matcher(value).find()) return Route.WEEKLY_DRAFT;
        if (TASK_ACTION.matcher(value).find()) return Route.ACTION_PLAN;
        Route collaboration = collaborationRoute(value);
        if (collaboration != null) return collaboration;
        if (ACTION.matcher(value).find()) return Route.ACTION_PLAN;
        if (RISK.matcher(value).find()) return Route.TEAM_RISK;
        if (METRICS.matcher(value).find()) return Route.TEAM_METRICS;
        if ("KNOWLEDGE_QUICK".equals(mode)) return Route.KNOWLEDGE_QUICK;
        if ("KNOWLEDGE_DEEP".equals(mode)) return Route.KNOWLEDGE_DEEP;
        if ("TEAM_INSIGHT".equals(mode)) return Route.TEAM_METRICS;
        if ("ACTION_PLAN".equals(mode)) return Route.ACTION_PLAN;
        if ("COLLAB_DOCUMENT".equals(mode)) return Route.COLLAB_DOCUMENT;
        if ("COLLAB_TABLE".equals(mode)) return Route.COLLAB_TABLE;
        if ("MATERIAL_REVIEW".equals(mode)) return Route.MATERIAL_REVIEW;
        return scope != null && scope.privateGraphAllowed()
            ? Route.KNOWLEDGE_DEEP : Route.KNOWLEDGE_QUICK;
    }

    /**
     * Collaboration intent is recognised from the wording only, never from the
     * caller's scope: the run row is claimed with the route computed before any
     * scope exists, so routing must stay deterministic for the same text.
     * Material questions outrank tables, tables outrank documents.
     */
    private Route collaborationRoute(String value) {
        if (MATERIAL.matcher(value).find()) return Route.MATERIAL_REVIEW;
        if (TABLE.matcher(value).find()) return Route.COLLAB_TABLE;
        if (DOCUMENT.matcher(value).find()) return Route.COLLAB_DOCUMENT;
        return null;
    }

    public boolean requiresKnowledge(Route route) {
        return route == Route.KNOWLEDGE_QUICK || route == Route.KNOWLEDGE_DEEP;
    }

    /** Routes that propose whitelisted collaboration actions after confirmation. */
    public boolean requiresActionProposal(Route route) {
        return route == Route.ACTION_PLAN || route == Route.COLLAB_DOCUMENT
            || route == Route.COLLAB_TABLE;
    }

    /** Routes that read collaboration assets and need the material matrix tools. */
    public boolean usesCollaborationAssets(Route route) {
        return route == Route.COLLAB_DOCUMENT || route == Route.COLLAB_TABLE
            || route == Route.MATERIAL_REVIEW;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
