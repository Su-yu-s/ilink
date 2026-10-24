package cn.ilink.teamagent;

import cn.ilink.entity.TaskParticipant;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.service.TeamMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamTaskPolicyTest {
    private static final long TEAM_ID = 20L;
    private static final long OWNER_ID = 1L;
    private static final long MENTOR_ID = 2L;
    private static final long STUDENT_ID = 3L;
    private static final long OTHER_ID = 4L;

    private TeamMembershipService membershipService;
    private TaskParticipantMapper participantMapper;
    private TeamTaskPolicy policy;
    private TeamDemand team;

    @BeforeEach
    void setUp() {
        membershipService = mock(TeamMembershipService.class);
        participantMapper = mock(TaskParticipantMapper.class);
        policy = new TeamTaskPolicy(membershipService, participantMapper);
        team = new TeamDemand();
        team.setId(TEAM_ID);
        team.setCreatorId(OWNER_ID);
        team.setStatus("TEAMING");
        when(membershipService.isApprovedMember(TEAM_ID, STUDENT_ID)).thenReturn(true);
        when(membershipService.isApprovedMember(TEAM_ID, MENTOR_ID)).thenReturn(true);
        when(membershipService.isApprovedMember(TEAM_ID, OTHER_ID)).thenReturn(true);
        when(membershipService.isMentorMember(TEAM_ID, MENTOR_ID)).thenReturn(true);
    }

    @Test
    void ownerAndMentorReadAllTasksButOnlyOwnerAdjustsThem() {
        TeamTask task = task(71L, TEAM_ID, OTHER_ID, "PENDING");

        assertTrue(policy.canReadTask(team, task, user(OWNER_ID)));
        assertTrue(policy.canReadTask(team, task, user(MENTOR_ID)));
        assertTrue(policy.canReadAllTasks(team, user(OWNER_ID)));
        assertTrue(policy.canReadAllTasks(team, user(MENTOR_ID)));
        assertTrue(policy.canAdjustTask(team, task, user(OWNER_ID)));
        assertFalse(policy.canAdjustTask(team, task, user(MENTOR_ID)));
        assertFalse(policy.canDeleteTask(team, task, user(MENTOR_ID)));
    }

    @Test
    void ordinaryMemberCannotReadOrOperateAnotherMembersTask() {
        TeamTask task = task(72L, TEAM_ID, OTHER_ID, "IN_PROGRESS");
        when(participantMapper.selectOne(any())).thenReturn(null);

        assertFalse(policy.canReadTask(team, task, user(STUDENT_ID)));
        assertFalse(policy.canOperateTask(team, task, user(STUDENT_ID)));
        assertFalse(policy.canSubmitTask(team, task, user(STUDENT_ID)));
        assertFalse(policy.canReviewTask(team, task, user(STUDENT_ID)));
    }

    @Test
    void assignedMemberCanWorkOnlyOnTheirOwnTeamTask() {
        TeamTask own = task(73L, TEAM_ID, STUDENT_ID, "IN_PROGRESS");
        TeamTask foreign = task(74L, 99L, STUDENT_ID, "IN_PROGRESS");

        assertTrue(policy.canReadTask(team, own, user(STUDENT_ID)));
        assertTrue(policy.canOperateTask(team, own, user(STUDENT_ID)));
        assertTrue(policy.canSubmitTask(team, own, user(STUDENT_ID)));
        assertFalse(policy.canSubmitTask(team, foreign, user(STUDENT_ID)));
    }

    @Test
    void removedMemberCannotSubmitAnAssignmentLeftOnTheTask() {
        TeamTask task = task(75L, TEAM_ID, OTHER_ID, "IN_PROGRESS");
        when(membershipService.isApprovedMember(TEAM_ID, OTHER_ID)).thenReturn(false);

        assertFalse(policy.canReadTask(team, task, user(OTHER_ID)));
        assertFalse(policy.canSubmitTask(team, task, user(OTHER_ID)));
    }

    @Test
    void leadParticipantCannotOutliveTheCurrentAssigneeRelation() {
        TeamTask task = task(76L, TEAM_ID, STUDENT_ID, "IN_PROGRESS");
        TaskParticipant staleLead = new TaskParticipant();
        staleLead.setTaskId(task.getId());
        staleLead.setUserId(OTHER_ID);
        staleLead.setRole("lead");
        staleLead.setStatus("active");
        when(participantMapper.selectOne(any())).thenReturn(staleLead);

        assertFalse(policy.hasActiveParticipant(task, OTHER_ID));
        assertFalse(policy.hasActiveParticipant(task, STUDENT_ID));

        TaskParticipant activeMember = new TaskParticipant();
        activeMember.setTaskId(task.getId());
        activeMember.setUserId(STUDENT_ID);
        activeMember.setRole("member");
        activeMember.setStatus("active");
        when(participantMapper.selectOne(any())).thenReturn(activeMember);
        assertTrue(policy.hasActiveParticipant(task, STUDENT_ID));
    }

    @Test
    void ownerParticipantRowMustMatchTheHistoricalCreator() {
        TeamTask task = task(77L, TEAM_ID, OTHER_ID, "PENDING");
        task.setCreatedBy(OWNER_ID);
        TaskParticipant ownerParticipant = new TaskParticipant();
        ownerParticipant.setTaskId(task.getId());
        ownerParticipant.setUserId(STUDENT_ID);
        ownerParticipant.setRole("owner");
        ownerParticipant.setStatus("active");
        when(participantMapper.selectOne(any())).thenReturn(ownerParticipant);

        assertFalse(policy.hasActiveParticipant(task, STUDENT_ID));
    }

    @Test
    void studentCreationIsLimitedToOwnSubtaskAndSelfAssignment() {
        TeamTask parent = task(78L, TEAM_ID, STUDENT_ID, "IN_PROGRESS");

        assertTrue(policy.canCreateTask(team, user(STUDENT_ID), STUDENT_ID, parent));
        assertFalse(policy.canCreateTask(team, user(STUDENT_ID), OTHER_ID, parent));
        assertFalse(policy.canCreateTask(team, user(STUDENT_ID), STUDENT_ID, null));
        assertTrue(policy.canCreateTask(team, user(OWNER_ID), OTHER_ID, parent));
    }

    @Test
    void reviewAndStatusRulesRespectHumanOnlyBoundary() {
        TeamTask review = task(79L, TEAM_ID, STUDENT_ID, "REVIEW");
        review.setRequiresReview(true);
        when(membershipService.isMentorMember(TEAM_ID, STUDENT_ID)).thenReturn(false);

        assertTrue(policy.canReviewTask(team, review, user(OWNER_ID)));
        assertTrue(policy.canReviewTask(team, review, user(MENTOR_ID)));
        assertFalse(policy.canReviewTask(team, review, user(STUDENT_ID)));
        assertFalse(policy.canReviewTask(team,
            task(80L, 99L, STUDENT_ID, "REVIEW"), user(MENTOR_ID)));
        assertTrue(policy.canChangeTaskStatus(team, review, user(STUDENT_ID), "COMPLETED"));
        assertFalse(policy.canChangeTaskStatus(team, review, user(MENTOR_ID), "COMPLETED"));
    }

    @Test
    void closedTeamRemainsReadableButCannotBeMutatedAndDissolvedTeamIsInvisible() {
        TeamTask task = task(81L, TEAM_ID, STUDENT_ID, "IN_PROGRESS");
        team.setStatus("CLOSED");
        assertTrue(policy.canReadTask(team, task, user(STUDENT_ID)));
        assertFalse(policy.canOperateTask(team, task, user(STUDENT_ID)));

        team.setStatus("DISSOLVED");
        assertFalse(policy.canReadTask(team, task, user(STUDENT_ID)));
    }

    private TeamTask task(long id, long teamId, long assignedTo, String status) {
        TeamTask task = new TeamTask();
        task.setId(id);
        task.setTeamId(teamId);
        task.setAssignedTo(assignedTo);
        task.setCreatedBy(OWNER_ID);
        task.setStatus(status);
        task.setRequiresReview(true);
        return task;
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
