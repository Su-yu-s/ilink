package cn.ilink.teamagent;

import cn.ilink.collab.TeamCollaborationAgentCommands;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Collaboration proposals are open to every active member while the team is
 * TEAMING, but task proposals stay leader-only. Both rules must hold at the
 * same time, so this covers the split introduced for M3.
 */
class TeamAgentActionProposalServiceCollabTest {
    private TeamDemandMapper teamMapper;
    private UserService userService;
    private TeamCollaborationAgentCommands collaborationCommands;
    private TeamAgentActionProposalService service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(TeamDemandMapper.class);
        userService = mock(UserService.class);
        collaborationCommands = mock(TeamCollaborationAgentCommands.class);
        service = new TeamAgentActionProposalService(
            teamMapper,
            mock(TeamTaskMapper.class),
            userService,
            mock(TeamTaskPolicy.class),
            mock(TeamAgentActionGroupMapper.class),
            mock(TeamAgentActionMapper.class),
            new TeamAgentProperties(),
            new ObjectMapper(),
            collaborationCommands);
        when(teamMapper.selectById(20L)).thenReturn(team());
        User user = new User();
        user.setId(7L);
        when(userService.getById(7L)).thenReturn(user);
    }

    @Test
    void aStudentMayProposeADocumentButNotATask() {
        when(collaborationCommands.supports("CREATE_TEAM_DOCUMENT")).thenReturn(true);
        when(collaborationCommands.prepare(any(), eq("CREATE_TEAM_DOCUMENT"),
            any(), any())).thenReturn(new TeamCollaborationAgentCommands.Prepared(
            "COLLAB_DOCUMENT", null, null, Map.of(),
            Map.of("title", "方案", "contentMarkdown", "", "visibility", "TEAM"), true));

        var rows = service.prepare(student(), List.of(
            draft("CREATE_TEAM_DOCUMENT"),
            draft("CREATE_TASK")));

        assertEquals(1, rows.size());
        assertEquals("CREATE_TEAM_DOCUMENT", rows.get(0).actionType());
    }

    @Test
    void aStudentWithNoCollaborationRightGetsNoProposals() {
        TeamAgentScope closedTeam = new TeamAgentScope(
            7L, 20L, "系统队", "CLOSED", TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.RELATION_ONLY, 3, "嵌入式竞赛",
            null, Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());

        var rows = service.prepare(closedTeam, List.of(draft("CREATE_TEAM_DOCUMENT")));

        assertTrue(rows.isEmpty());
        verify(collaborationCommands, never()).prepare(any(), any(), any(), any());
    }

    @Test
    void theLeaderStillGetsTaskProposals() {
        // The draft reaches the task pipeline (and is rejected there because the
        // mocked policy has no assignee) instead of being filtered out silently
        // the way it is for students.
        TeamAgentException error = org.junit.jupiter.api.Assertions.assertThrows(
            TeamAgentException.class,
            () -> service.prepare(leader(), List.of(draft("CREATE_TASK"))));
        assertEquals(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        verify(collaborationCommands, never()).prepare(any(), any(), anyLong(), any());
    }

    private TeamAgentActionDraft draft(String actionType) {
        return new TeamAgentActionDraft("v1", actionType, null, null, null,
            Map.of(), Map.of("title", "x"), "测试", List.of());
    }

    private TeamDemand team() {
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setTitle("系统队");
        team.setStatus("TEAMING");
        team.setCreatorId(7L);
        return team;
    }

    private TeamAgentScope student() {
        return new TeamAgentScope(7L, 20L, "系统队", "TEAMING",
            TeamAgentScope.TeamRole.STUDENT, TeamAgentScope.TaskReadMode.RELATION_ONLY,
            3, "嵌入式竞赛", null,
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());
    }

    private TeamAgentScope leader() {
        return new TeamAgentScope(7L, 20L, "系统队", "TEAMING",
            TeamAgentScope.TeamRole.LEADER, TeamAgentScope.TaskReadMode.ALL,
            3, "嵌入式竞赛", null,
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());
    }
}
