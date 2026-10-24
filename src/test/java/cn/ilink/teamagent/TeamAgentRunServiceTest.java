package cn.ilink.teamagent;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentMessage;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentMessageMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentRunServiceTest {
    private static final Long TEAM_ID = 20L;
    private static final Long USER_ID = 7L;
    private static final Long CONVERSATION_ID = 30L;
    private static final String REQUEST_ID = "55555555-5555-4555-8555-555555555555";

    private TeamAgentAuthorization authorization;
    private TeamAgentConversationService conversationService;
    private TeamAgentConversationMapper conversationMapper;
    private TeamAgentMessageMapper messageMapper;
    private TeamAgentRunMapper runMapper;
    private TeamAgentActionGroupMapper groupMapper;
    private TeamTaskPolicy taskPolicy;
    private TeamAgentEventService eventService;
    private TeamAgentAdmissionController admission;
    private TeamAgentRunExecutor executor;
    private TeamAgentIntentRouter intentRouter;
    private AiProperties aiProperties;
    private TeamAgentRunService service;

    @BeforeEach
    void setUp() {
        authorization = mock(TeamAgentAuthorization.class);
        conversationService = mock(TeamAgentConversationService.class);
        conversationMapper = mock(TeamAgentConversationMapper.class);
        messageMapper = mock(TeamAgentMessageMapper.class);
        runMapper = mock(TeamAgentRunMapper.class);
        groupMapper = mock(TeamAgentActionGroupMapper.class);
        taskPolicy = mock(TeamTaskPolicy.class);
        eventService = mock(TeamAgentEventService.class);
        admission = mock(TeamAgentAdmissionController.class);
        executor = mock(TeamAgentRunExecutor.class);
        intentRouter = mock(TeamAgentIntentRouter.class);
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        aiProperties.setApiKey("test-key");
        when(authorization.requireScope(TEAM_ID, USER_ID)).thenReturn(scope());
        when(intentRouter.route(any(), any(), any()))
            .thenReturn(TeamAgentIntentRouter.Route.TEAM_METRICS);
        when(groupMapper.selectList(any())).thenReturn(List.of());
        service = new TeamAgentRunService(
            authorization,
            conversationService,
            conversationMapper,
            messageMapper,
            runMapper,
            groupMapper,
            mock(TeamDemandMapper.class),
            mock(TeamTaskMapper.class),
            taskPolicy,
            eventService,
            admission,
            executor,
            intentRouter,
            aiProperties,
            new ObjectMapper());
    }

    @Test
    void createBindsMessageRunAdmissionAndCommitDispatch() {
        TeamAgentConversation conversation = conversation();
        TeamAgentMessage message = new TeamAgentMessage();
        message.setId(50L);
        when(conversationService.lockOwnedConversation(TEAM_ID, USER_ID, CONVERSATION_ID))
            .thenReturn(conversation);
        when(runMapper.selectOne(any())).thenReturn(null, null);
        when(conversationService.appendMessageLocked(
            eq(conversation), eq(TeamAgentMessage.ROLE_USER), eq(USER_ID),
            eq("统计本周任务"), eq(TeamAgentMessage.STATUS_COMPLETE), eq(null), eq(List.of())))
            .thenReturn(message);
        when(runMapper.insert(any(TeamAgentRun.class))).thenAnswer(invocation -> {
            TeamAgentRun run = invocation.getArgument(0);
            run.setId(60L);
            return 1;
        });
        when(messageMapper.updateById(message)).thenReturn(1);

        Map<String, Object> view = service.create(TEAM_ID, USER_ID, request(
            "统计本周任务", "TEAM_INSIGHT"));

        assertEquals(60L, view.get("runId"));
        assertEquals(TeamAgentRun.STATUS_QUEUED, view.get("status"));
        assertEquals(60L, message.getRunId());
        verify(admission).acquire(60L, TEAM_ID, USER_ID);
        verify(executor).submit(60L);
        ArgumentCaptor<TeamAgentRun> saved = ArgumentCaptor.forClass(TeamAgentRun.class);
        verify(runMapper).insert(saved.capture());
        assertEquals(inputHash("统计本周任务", "TEAM_INSIGHT"), saved.getValue().getInputHash());
        assertEquals(TeamAgentRunService.PROMPT_VERSION, saved.getValue().getPromptVersion());
    }

    @Test
    void sameRequestIdAndPayloadReplaysWithoutNewMessageOrRun() {
        String text = "统计本周任务";
        TeamAgentRun existing = run(60L, inputHash(text, "TEAM_INSIGHT"));
        when(conversationService.lockOwnedConversation(TEAM_ID, USER_ID, CONVERSATION_ID))
            .thenReturn(conversation());
        when(runMapper.selectOne(any())).thenReturn(existing);

        Map<String, Object> view = service.create(TEAM_ID, USER_ID, request(
            text, "TEAM_INSIGHT"));

        assertEquals(60L, view.get("runId"));
        verify(conversationService, never()).appendMessageLocked(
            any(), any(), any(), any(), any(), any(), any());
        verify(runMapper, never()).insert(any(TeamAgentRun.class));
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsRejected() {
        TeamAgentRun existing = run(60L, inputHash("另一个问题", "TEAM_INSIGHT"));
        when(conversationService.lockOwnedConversation(TEAM_ID, USER_ID, CONVERSATION_ID))
            .thenReturn(conversation());
        when(runMapper.selectOne(any())).thenReturn(existing);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.create(TEAM_ID, USER_ID, request("统计本周任务", "TEAM_INSIGHT")));

        assertEquals(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCode());
        verify(conversationService, never()).appendMessageLocked(
            any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateRaceReturnsWinnerOnlyWhenPayloadHashMatches() {
        String text = "统计本周任务";
        TeamAgentRun raced = run(61L, inputHash(text, "TEAM_INSIGHT"));
        when(conversationService.lockOwnedConversation(TEAM_ID, USER_ID, CONVERSATION_ID))
            .thenReturn(conversation());
        when(runMapper.selectOne(any())).thenReturn(null, null, raced);
        when(conversationService.appendMessageLocked(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(message());
        when(runMapper.insert(any(TeamAgentRun.class)))
            .thenThrow(new DuplicateKeyException("duplicate"));

        Map<String, Object> view = service.create(TEAM_ID, USER_ID, request(
            text, "TEAM_INSIGHT"));

        assertEquals(61L, view.get("runId"));
        verify(admission, never()).acquire(anyLong(), anyLong(), anyLong());
        verify(executor, never()).submit(anyLong());
    }

    private TeamAgentRequests.CreateRun request(String text, String mode) {
        return new TeamAgentRequests.CreateRun(
            CONVERSATION_ID, REQUEST_ID,
            new TeamAgentRequests.RunMessage(text, mode),
            new TeamAgentRequests.RunFocus(List.of()));
    }

    private String inputHash(String text, String mode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", CONVERSATION_ID);
        payload.put("text", text);
        payload.put("mode", mode);
        payload.put("taskIds", List.of());
        return TeamAgentHashes.structureHash("run-input", payload);
    }

    private TeamAgentConversation conversation() {
        TeamAgentConversation conversation = new TeamAgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setTeamId(TEAM_ID);
        conversation.setOwnerUserId(USER_ID);
        conversation.setStatus(TeamAgentConversation.STATUS_ACTIVE);
        conversation.setVersion(0);
        conversation.setMessageSeq(0L);
        return conversation;
    }

    private TeamAgentMessage message() {
        TeamAgentMessage message = new TeamAgentMessage();
        message.setId(50L);
        return message;
    }

    private TeamAgentRun run(Long id, String hash) {
        TeamAgentRun run = new TeamAgentRun();
        run.setId(id);
        run.setTeamId(TEAM_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setUserId(USER_ID);
        run.setRequestMessageId(50L);
        run.setClientRequestId(REQUEST_ID);
        run.setInputHash(hash);
        run.setStatus(TeamAgentRun.STATUS_COMPLETED);
        run.setEventSeq(0L);
        run.setCreatedAt(new Date());
        return run;
    }

    private TeamAgentScope scope() {
        return new TeamAgentScope(
            USER_ID, TEAM_ID, "系统队", "TEAMING", TeamAgentScope.TeamRole.LEADER,
            TeamAgentScope.TaskReadMode.ALL, null, null, "自定义竞赛",
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());
    }
}
