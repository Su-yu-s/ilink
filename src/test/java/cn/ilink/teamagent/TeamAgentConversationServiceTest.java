package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentMessageMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.kg.KgScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentConversationServiceTest {
    private TeamAgentAuthorization authorization;
    private TeamDemandMapper teamMapper;
    private TeamAgentConversationMapper conversationMapper;
    private TeamAgentRunMapper runMapper;
    private TeamAgentActionGroupMapper groupMapper;
    private TeamAgentEventService eventService;
    private TeamAgentAdmissionController admission;
    private TeamAgentConversationService service;

    @BeforeEach
    void setUp() {
        authorization = mock(TeamAgentAuthorization.class);
        teamMapper = mock(TeamDemandMapper.class);
        conversationMapper = mock(TeamAgentConversationMapper.class);
        runMapper = mock(TeamAgentRunMapper.class);
        groupMapper = mock(TeamAgentActionGroupMapper.class);
        eventService = mock(TeamAgentEventService.class);
        admission = mock(TeamAgentAdmissionController.class);
        when(authorization.requireScope(20L, 7L)).thenReturn(scope());
        service = new TeamAgentConversationService(
            authorization,
            teamMapper,
            conversationMapper,
            mock(TeamAgentMessageMapper.class),
            runMapper,
            groupMapper,
            mock(TeamAgentActionMapper.class),
            mock(KgScopeService.class),
            eventService,
            admission,
            new ObjectMapper());
    }

    @Test
    void deletingConversationEmitsEventAndReleasesEveryQueuedRun() {
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        TeamAgentConversation conversation = new TeamAgentConversation();
        conversation.setId(30L);
        conversation.setTeamId(20L);
        conversation.setOwnerUserId(7L);
        conversation.setStatus(TeamAgentConversation.STATUS_ACTIVE);
        conversation.setVersion(2);
        TeamAgentRun queued = new TeamAgentRun();
        queued.setId(40L);
        queued.setTeamId(20L);
        queued.setUserId(7L);
        queued.setConversationId(30L);
        queued.setStatus(TeamAgentRun.STATUS_QUEUED);
        queued.setVersion(1);

        when(teamMapper.selectByIdForUpdate(20L)).thenReturn(team);
        when(conversationMapper.selectByIdForUpdate(30L)).thenReturn(conversation);
        when(groupMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.selectList(any())).thenReturn(List.of(queued));
        when(runMapper.selectByIdForUpdate(40L)).thenReturn(queued);
        when(eventService.appendLocked(eq(queued), eq("run.cancelled"), any())).thenReturn(4L);
        when(runMapper.updateById(queued)).thenReturn(1);
        when(conversationMapper.updateById(conversation)).thenReturn(1);

        service.delete(20L, 7L, 30L);

        verify(eventService).appendLocked(eq(queued), eq("run.cancelled"), any());
        verify(admission).release(40L, 20L, 7L);
        assertEquals(TeamAgentRun.STATUS_CANCELLED, queued.getStatus());
    }

    @Test
    void createReplaysSameRequestWithoutInsertingAnotherConversation() {
        TeamAgentConversation existing = conversation(30L, "项目讨论",
            "11111111-1111-4111-8111-111111111111");
        existing.setCreateRequestHash(createHash("项目讨论"));
        when(conversationMapper.selectOne(any())).thenReturn(existing);

        Map<String, Object> result = service.create(
            20L, 7L, "11111111-1111-4111-8111-111111111111", " 项目讨论 ");

        assertEquals(30L, result.get("conversationId"));
        verify(conversationMapper, never()).insert(any(TeamAgentConversation.class));
    }

    @Test
    void createRejectsReusedRequestIdWithDifferentTitle() {
        TeamAgentConversation existing = conversation(30L, "项目讨论",
            "22222222-2222-4222-8222-222222222222");
        existing.setCreateRequestHash(createHash("另一标题"));
        when(conversationMapper.selectOne(any())).thenReturn(existing);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.create(20L, 7L,
                "22222222-2222-4222-8222-222222222222", "项目讨论"));

        assertEquals(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCode());
        verify(conversationMapper, never()).insert(any(TeamAgentConversation.class));
    }

    @Test
    void concurrentDuplicateInsertReturnsTheWinningConversation() {
        TeamAgentConversation raced = conversation(31L, "项目讨论",
            "33333333-3333-4333-8333-333333333333");
        raced.setCreateRequestHash(createHash("项目讨论"));
        when(conversationMapper.selectOne(any())).thenReturn(null, raced);
        when(conversationMapper.insert(any(TeamAgentConversation.class)))
            .thenThrow(new DuplicateKeyException("duplicate"));

        Map<String, Object> result = service.create(
            20L, 7L, "33333333-3333-4333-8333-333333333333", "项目讨论");

        assertEquals(31L, result.get("conversationId"));
        verify(conversationMapper).insert(any(TeamAgentConversation.class));
    }

    @Test
    void createPersistsCanonicalTitleAndServerHash() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        when(conversationMapper.insert(any(TeamAgentConversation.class))).thenReturn(1);

        service.create(20L, 7L,
            "44444444-4444-4444-8444-444444444444", "\n 竞赛计划 \t");

        ArgumentCaptor<TeamAgentConversation> saved =
            ArgumentCaptor.forClass(TeamAgentConversation.class);
        verify(conversationMapper).insert(saved.capture());
        assertEquals("竞赛计划", saved.getValue().getTitle());
        assertEquals(createHash("竞赛计划"), saved.getValue().getCreateRequestHash());
        assertEquals(TeamAgentConversation.STATUS_ACTIVE, saved.getValue().getStatus());
        assertEquals(0L, saved.getValue().getMessageSeq());
    }

    private String createHash(String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        return TeamAgentHashes.structureHash("conversation-create", payload);
    }

    private TeamAgentConversation conversation(Long id, String title, String requestId) {
        TeamAgentConversation conversation = new TeamAgentConversation();
        conversation.setId(id);
        conversation.setTeamId(20L);
        conversation.setOwnerUserId(7L);
        conversation.setClientRequestId(requestId);
        conversation.setTitle(title);
        conversation.setStatus(TeamAgentConversation.STATUS_ACTIVE);
        conversation.setLastMessageAt(new Date());
        conversation.setCreatedAt(new Date());
        conversation.setVersion(0);
        return conversation;
    }

    private TeamAgentScope scope() {
        return new TeamAgentScope(
            7L, 20L, "系统队", "TEAMING", TeamAgentScope.TeamRole.LEADER,
            TeamAgentScope.TaskReadMode.ALL, null, null, "自定义竞赛",
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());
    }
}
