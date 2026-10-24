package cn.ilink.collab;

import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.service.kg.tool.AbstractGraphTool;
import cn.ilink.teamagent.TeamAgentScopeContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The five read-only collaboration tools exposed to the ReAct loop. They
 * accept no identity parameters at all: the team and user are taken from the
 * server-bound scope, so a forged teamId/userId in Action Input cannot reach
 * another team. Outside a team scope every tool reports NOT_IN_TEAM_SCOPE.
 */
public final class TeamCollaborationGraphTools {
    private TeamCollaborationGraphTools() {
    }

    private static final String DENIED = "{\"error\":\"NOT_IN_TEAM_SCOPE\","
        + "\"message\":\"协作资产工具只能在团队 Agent 会话作用域内使用。\"}";

    private static boolean inTeamScope() {
        return TeamAgentScopeContext.current() != null;
    }

    /** list_team_collaboration_assets */
    @Component
    public static class ListAssets extends AbstractGraphTool {
        private final TeamCollaborationAgentTools tools;

        public ListAssets(TeamCollaborationAgentTools tools, KgScopeService scopeService) {
            super(scopeService);
            this.tools = tools;
        }

        @Override
        public String getName() {
            return "list_team_collaboration_assets";
        }

        @Override
        public String getDescription() {
            return "列出当前团队中我有权读取的协作文档、表格与材料矩阵摘要。"
                + "用于回答“我们团队有哪些文档/表格”“材料准备到什么程度”。无需参数。";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }

        @Override
        public Object execute(Map<String, Object> params, Long userId) {
            if (!inTeamScope()) return DENIED;
            try {
                return tools.listAssets();
            } catch (TeamCollabException e) {
                return errorJson(e.code().name(), e.getMessage());
            } catch (RuntimeException e) {
                return errorJson("NO_RESULTS", "没有可见的协作资产");
            }
        }
    }

    /** get_team_document */
    @Component
    public static class GetDocument extends AbstractGraphTool {
        private final TeamCollaborationAgentTools tools;

        public GetDocument(TeamCollaborationAgentTools tools, KgScopeService scopeService) {
            super(scopeService);
            this.tools = tools;
        }

        @Override
        public String getName() {
            return "get_team_document";
        }

        @Override
        public String getDescription() {
            return "读取团队协作文档正文与最近版本。document_id 必须来自"
                + " list_team_collaboration_assets 的返回结果。";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                + "\"document_id\":{\"type\":\"integer\",\"description\":\"团队文档ID\"},"
                + "\"version_limit\":{\"type\":\"integer\",\"default\":5}"
                + "},\"required\":[\"document_id\"]}";
        }

        @Override
        public Object execute(Map<String, Object> params, Long userId) {
            if (!inTeamScope()) return DENIED;
            Long id = paramLong(params, "document_id");
            if (id == null || id <= 0) return errorJson("INVALID_PARAMS", "document_id 必须是正整数");
            try {
                return tools.getDocument(id, paramInt(params, "version_limit", 5));
            } catch (TeamCollabException e) {
                return errorJson(e.code().name(), e.getMessage());
            }
        }
    }

    /** get_team_spreadsheet */
    @Component
    public static class GetSpreadsheet extends AbstractGraphTool {
        private final TeamCollaborationAgentTools tools;

        public GetSpreadsheet(TeamCollaborationAgentTools tools, KgScopeService scopeService) {
            super(scopeService);
            this.tools = tools;
        }

        @Override
        public String getName() {
            return "get_team_spreadsheet";
        }

        @Override
        public String getDescription() {
            return "读取团队协作表格的列定义与有行数上限的数据。spreadsheet_id 必须来自"
                + " list_team_collaboration_assets。";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                + "\"spreadsheet_id\":{\"type\":\"integer\",\"description\":\"团队表格ID\"},"
                + "\"row_limit\":{\"type\":\"integer\",\"default\":60}"
                + "},\"required\":[\"spreadsheet_id\"]}";
        }

        @Override
        public Object execute(Map<String, Object> params, Long userId) {
            if (!inTeamScope()) return DENIED;
            Long id = paramLong(params, "spreadsheet_id");
            if (id == null || id <= 0) {
                return errorJson("INVALID_PARAMS", "spreadsheet_id 必须是正整数");
            }
            try {
                return tools.getSpreadsheet(id, paramInt(params, "row_limit", 60));
            } catch (TeamCollabException e) {
                return errorJson(e.code().name(), e.getMessage());
            }
        }
    }

    /** search_team_collaboration_assets */
    @Component
    public static class SearchAssets extends AbstractGraphTool {
        private final TeamCollaborationAgentTools tools;

        public SearchAssets(TeamCollaborationAgentTools tools, KgScopeService scopeService) {
            super(scopeService);
            this.tools = tools;
        }

        @Override
        public String getName() {
            return "search_team_collaboration_assets";
        }

        @Override
        public String getDescription() {
            return "只检索团队协作文档、表格与资料库正文片段。适合“我们的方案文档里怎么写”"
                + "“资料里有没有提到某个要求”这类问题。";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\"},"
                + "\"top_k\":{\"type\":\"integer\",\"default\":8}"
                + "},\"required\":[\"query\"]}";
        }

        @Override
        public Object execute(Map<String, Object> params, Long userId) {
            if (!inTeamScope()) return DENIED;
            try {
                Map<String, Object> result = tools.search(paramString(params, "query"),
                    paramInt(params, "top_k", 8));
                Object count = result.get("count");
                if (count instanceof Number number && number.intValue() == 0) {
                    return errorJson("NO_RESULTS", "未找到有权读取的协作资产片段");
                }
                return result;
            } catch (TeamCollabException e) {
                return errorJson(e.code().name(), e.getMessage());
            } catch (IllegalArgumentException e) {
                return errorJson("INVALID_PARAMS", e.getMessage());
            }
        }
    }

    /** get_material_matrix */
    @Component
    public static class MaterialMatrix extends AbstractGraphTool {
        private final TeamCollaborationAgentTools tools;

        public MaterialMatrix(TeamCollaborationAgentTools tools, KgScopeService scopeService) {
            super(scopeService);
            this.tools = tools;
        }

        @Override
        public String getName() {
            return "get_material_matrix";
        }

        @Override
        public String getDescription() {
            return "读取竞赛材料对照矩阵：要求、证据与真人复核状态。用于“报名材料还缺什么”"
                + "“哪些材料已确认”。status 可选 UNCHECKED/NEEDS_REVIEW/MET/PARTIAL/MISSING。";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                + "\"status\":{\"type\":\"string\"},"
                + "\"required\":{\"type\":\"boolean\"}"
                + "},\"required\":[]}";
        }

        @Override
        public Object execute(Map<String, Object> params, Long userId) {
            if (!inTeamScope()) return DENIED;
            Boolean required = params == null || !(params.get("required") instanceof Boolean)
                ? null : (Boolean) params.get("required");
            try {
                return tools.materialMatrix(paramString(params, "status"), required);
            } catch (TeamCollabException e) {
                return errorJson(e.code().name(), e.getMessage());
            }
        }
    }
}
