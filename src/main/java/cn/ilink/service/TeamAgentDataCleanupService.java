package cn.ilink.service;

import cn.ilink.entity.KgAgentTrace;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgIndexTask;
import cn.ilink.entity.TeamAgentAction;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentAuditLog;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentMessage;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamAgentRunEvent;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgIndexTaskMapper;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamAgentAuditLogMapper;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentMessageMapper;
import cn.ilink.mapper.TeamAgentRunEventMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.teamagent.TeamAgentAdmissionController;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Hard-cleanup for private Team Agent state. Team deletion removes the whole
 * private collaboration trail; account deletion removes private conversations
 * while retaining only anonymized team action facts.
 */
@Service
public class TeamAgentDataCleanupService {
    private final TeamAgentConversationMapper conversationMapper;
    private final TeamAgentMessageMapper messageMapper;
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentRunEventMapper eventMapper;
    private final TeamAgentActionGroupMapper groupMapper;
    private final TeamAgentActionMapper actionMapper;
    private final TeamAgentAuditLogMapper auditMapper;
    private final KgAgentTraceMapper traceMapper;
    private final KgIndexTaskMapper indexTaskMapper;
    private final KgDocumentChunkMapper chunkMapper;
    private final TeamAgentAdmissionController admission;

    public TeamAgentDataCleanupService(
        TeamAgentConversationMapper conversationMapper,
        TeamAgentMessageMapper messageMapper,
        TeamAgentRunMapper runMapper,
        TeamAgentRunEventMapper eventMapper,
        TeamAgentActionGroupMapper groupMapper,
        TeamAgentActionMapper actionMapper,
        TeamAgentAuditLogMapper auditMapper,
        KgAgentTraceMapper traceMapper,
        KgIndexTaskMapper indexTaskMapper,
        KgDocumentChunkMapper chunkMapper,
        TeamAgentAdmissionController admission) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.groupMapper = groupMapper;
        this.actionMapper = actionMapper;
        this.auditMapper = auditMapper;
        this.traceMapper = traceMapper;
        this.indexTaskMapper = indexTaskMapper;
        this.chunkMapper = chunkMapper;
        this.admission = admission;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTeamData(Long teamId) {
        if (teamId == null) return;
        List<TeamAgentRun> runs = runMapper.selectList(new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getTeamId, teamId)
            .select(TeamAgentRun::getId, TeamAgentRun::getTeamId, TeamAgentRun::getUserId));
        List<Long> runIds = runs.stream().map(TeamAgentRun::getId).toList();
        if (!runIds.isEmpty()) {
            eventMapper.delete(new LambdaQueryWrapper<TeamAgentRunEvent>()
                .eq(TeamAgentRunEvent::getTeamId, teamId)
                .or().in(TeamAgentRunEvent::getRunId, runIds));
        }
        List<Long> conversationIds = conversationMapper.selectList(
                new LambdaQueryWrapper<TeamAgentConversation>()
                    .eq(TeamAgentConversation::getTeamId, teamId)
                    .select(TeamAgentConversation::getId))
            .stream().map(TeamAgentConversation::getId).toList();
        List<Long> groupIds = groupMapper.selectList(new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getTeamId, teamId).select(TeamAgentActionGroup::getId))
            .stream().map(TeamAgentActionGroup::getId).toList();
        if (!groupIds.isEmpty()) {
            actionMapper.delete(new LambdaQueryWrapper<TeamAgentAction>()
                .eq(TeamAgentAction::getTeamId, teamId)
                .or().in(TeamAgentAction::getGroupId, groupIds));
        } else {
            actionMapper.delete(new LambdaQueryWrapper<TeamAgentAction>()
                .eq(TeamAgentAction::getTeamId, teamId));
        }
        groupMapper.delete(new LambdaQueryWrapper<TeamAgentActionGroup>()
            .eq(TeamAgentActionGroup::getTeamId, teamId));
        messageMapper.delete(new LambdaQueryWrapper<TeamAgentMessage>()
            .eq(TeamAgentMessage::getTeamId, teamId));
        runMapper.delete(new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getTeamId, teamId));
        conversationMapper.delete(new LambdaQueryWrapper<TeamAgentConversation>()
            .eq(TeamAgentConversation::getTeamId, teamId));
        auditMapper.delete(new LambdaQueryWrapper<TeamAgentAuditLog>()
            .eq(TeamAgentAuditLog::getTeamId, teamId));
        traceMapper.delete(new LambdaQueryWrapper<KgAgentTrace>()
            .eq(KgAgentTrace::getTeamId, teamId));
        indexTaskMapper.delete(new LambdaQueryWrapper<KgIndexTask>()
            .eq(KgIndexTask::getTeamId, teamId));
        chunkMapper.delete(new LambdaQueryWrapper<KgDocumentChunk>()
            .eq(KgDocumentChunk::getTeamId, teamId));
        releaseRunAdmissionsAfterCommit(runs);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteUserData(Long userId) {
        if (userId == null) return;
        List<TeamAgentConversation> conversations = conversationMapper.selectList(
            new LambdaQueryWrapper<TeamAgentConversation>()
                .eq(TeamAgentConversation::getOwnerUserId, userId));
        List<Long> conversationIds = conversations.stream()
            .map(TeamAgentConversation::getId).toList();
        List<TeamAgentRun> runs = runMapper.selectList(new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getUserId, userId)
            .or(!conversationIds.isEmpty(), w -> w.in(TeamAgentRun::getConversationId, conversationIds)));
        List<Long> runIds = runs.stream().map(TeamAgentRun::getId).toList();
        if (!runIds.isEmpty()) {
            eventMapper.delete(new LambdaQueryWrapper<TeamAgentRunEvent>()
                .in(TeamAgentRunEvent::getRunId, runIds));
        }
        if (!conversationIds.isEmpty()) {
            messageMapper.delete(new LambdaQueryWrapper<TeamAgentMessage>()
                .in(TeamAgentMessage::getConversationId, conversationIds));
        }
        if (!runIds.isEmpty()) {
            messageMapper.delete(new LambdaQueryWrapper<TeamAgentMessage>()
                .in(TeamAgentMessage::getRunId, runIds));
            runMapper.deleteBatchIds(runIds);
        }
        if (!conversationIds.isEmpty()) {
            conversationMapper.deleteBatchIds(conversationIds);
        }

        List<Long> retainedGroupIds = groupMapper.selectList(
                new LambdaQueryWrapper<TeamAgentActionGroup>()
                    .eq(TeamAgentActionGroup::getInitiatorUserId, userId)
                    .select(TeamAgentActionGroup::getId))
            .stream().map(TeamAgentActionGroup::getId).toList();
        groupMapper.update(null, new LambdaUpdateWrapper<TeamAgentActionGroup>()
            .eq(TeamAgentActionGroup::getInitiatorUserId, userId)
            .set(TeamAgentActionGroup::getInitiatorUserId, null)
            .set(TeamAgentActionGroup::getConversationId, null)
            .set(TeamAgentActionGroup::getRunId, null)
            .set(TeamAgentActionGroup::getClientRequestId, null)
            .set(TeamAgentActionGroup::getRequestHash, null)
            .set(TeamAgentActionGroup::getErrorMessage, null));
        if (!retainedGroupIds.isEmpty()) {
            actionMapper.update(null, new LambdaUpdateWrapper<TeamAgentAction>()
                .in(TeamAgentAction::getGroupId, retainedGroupIds)
                .set(TeamAgentAction::getBeforeJson, null)
                .set(TeamAgentAction::getAfterJson, "{}")
                .set(TeamAgentAction::getReason, null)
                .set(TeamAgentAction::getEvidenceJson, null)
                .set(TeamAgentAction::getResultJson, null)
                .set(TeamAgentAction::getErrorMessage, null)
                .set(TeamAgentAction::getUndoRequestId, null)
                .set(TeamAgentAction::getUndoRequestHash, null));
            actionMapper.update(null, new LambdaUpdateWrapper<TeamAgentAction>()
                .in(TeamAgentAction::getGroupId, retainedGroupIds)
                .eq(TeamAgentAction::getUndoStatus, TeamAgentAction.UNDO_AVAILABLE)
                .set(TeamAgentAction::getUndoStatus, TeamAgentAction.UNDO_EXPIRED)
                .set(TeamAgentAction::getUndoResultJson, null)
                .set(TeamAgentAction::getUndoErrorMessage, null));
        }
        auditMapper.update(null, new LambdaUpdateWrapper<TeamAgentAuditLog>()
            .eq(TeamAgentAuditLog::getActorUserId, userId)
            .set(TeamAgentAuditLog::getActorUserId, null)
            .set(TeamAgentAuditLog::getConversationId, null)
            .set(TeamAgentAuditLog::getRunId, null)
            .set(TeamAgentAuditLog::getMetadataJson, null));
        traceMapper.delete(new LambdaQueryWrapper<KgAgentTrace>()
            .eq(KgAgentTrace::getUserId, userId));
        indexTaskMapper.delete(new LambdaQueryWrapper<KgIndexTask>()
            .eq(KgIndexTask::getUserId, userId)
            .eq(KgIndexTask::getScope, "PRIVATE"));
        chunkMapper.delete(new LambdaQueryWrapper<KgDocumentChunk>()
            .eq(KgDocumentChunk::getUserId, userId)
            .eq(KgDocumentChunk::getScope, "PRIVATE"));
        releaseRunAdmissionsAfterCommit(runs);
    }

    /**
     * Hard cleanup removes the rows that a worker would normally use to reach
     * its terminal transition. Release each admitted run after the cleanup
     * transaction commits; release itself is idempotent.
     */
    private void releaseRunAdmissionsAfterCommit(List<TeamAgentRun> runs) {
        if (runs == null || runs.isEmpty()) return;
        Runnable release = () -> runs.forEach(run ->
            admission.release(run.getId(), run.getTeamId(), run.getUserId()));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            release.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    release.run();
                }
            });
    }
}
