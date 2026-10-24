package cn.ilink.teamagent;

import cn.ilink.entity.KgAgentTrace;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.kg.AgenticGraphRagService;
import cn.ilink.service.kg.GraphRagService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** Binds a server-built team scope around the existing Agentic GraphRAG tools. */
@Service
public class TeamScopedGraphRagAdapter {
    private final AgenticGraphRagService graphRagService;
    private final GraphRagService fastGraphRagService;
    private final KgAgentTraceMapper traceMapper;

    public TeamScopedGraphRagAdapter(AgenticGraphRagService graphRagService,
                                     GraphRagService fastGraphRagService,
                                     KgAgentTraceMapper traceMapper) {
        this.graphRagService = graphRagService;
        this.fastGraphRagService = fastGraphRagService;
        this.traceMapper = traceMapper;
    }

    public Result query(String question, TeamAgentScope scope, Long runId, Long conversationId,
                        AgenticGraphRagService.AgenticStreamListener listener) {
        Date startedAt = new Date();
        AgenticGraphRagService.AgenticAnswer answer;
        try (TeamAgentScopeContext.BoundScope ignored = TeamAgentScopeContext.bind(scope)) {
            answer = graphRagService.query(question, scope.userId(), listener);
        }
        linkLatestTrace(scope, runId, conversationId, startedAt);
        return new Result(answer.answer, answer.citations, answer.rounds, answer.toolCalls,
            answer.fallback);
    }

    public Result queryQuick(String question, TeamAgentScope scope) {
        GraphRagService.GraphRagAnswer answer;
        try (TeamAgentScopeContext.BoundScope ignored = TeamAgentScopeContext.bind(scope)) {
            answer = fastGraphRagService.query(question, scope.userId());
        }
        return new Result(answer.answer, answer.citations, 0,
            Math.max(0, answer.seedCount + answer.edgeCount), false);
    }

    private void linkLatestTrace(TeamAgentScope scope, Long runId, Long conversationId,
                                 Date startedAt) {
        KgAgentTrace trace = traceMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KgAgentTrace>()
                .eq(KgAgentTrace::getUserId, scope.userId())
                .ge(KgAgentTrace::getCreatedAt, startedAt)
                .orderByDesc(KgAgentTrace::getId)
                .last("LIMIT 1"));
        if (trace == null) return;
        traceMapper.update(null, new LambdaUpdateWrapper<KgAgentTrace>()
            .eq(KgAgentTrace::getId, trace.getId())
            .set(KgAgentTrace::getTeamId, scope.teamId())
            .set(KgAgentTrace::getConversationId, conversationId)
            .set(KgAgentTrace::getRunId, runId));
    }

    public record Result(String answer, List<Map<String, Object>> citations,
                         int rounds, int toolCalls, boolean fallback) {}
}
