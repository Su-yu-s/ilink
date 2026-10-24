package cn.ilink.teamagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamAgentAdmissionControllerTest {

    @Test
    void releaseIsIdempotentAndFreesCapacityExactlyOnce() {
        TeamAgentAdmissionController controller = new TeamAgentAdmissionController();

        controller.acquire(1L, 10L, 100L);
        assertEquals(1, controller.activeGlobal());

        controller.release(1L, 10L, 100L);
        controller.release(1L, 10L, 100L);

        assertEquals(0, controller.activeGlobal());
        controller.acquire(2L, 10L, 100L);
        assertEquals(1, controller.activeGlobal());
    }

    @Test
    void sameUserCannotQueueTwiceInOneTeam() {
        TeamAgentAdmissionController controller = new TeamAgentAdmissionController();
        controller.acquire(1L, 10L, 100L);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> controller.acquire(2L, 10L, 100L));

        assertEquals(TeamAgentErrorCode.RUN_ALREADY_ACTIVE, error.getErrorCode());
        assertEquals(1, controller.activeGlobal(), "冲突请求不能占用额外名额");
    }

    @Test
    void teamLimitRejectsFourthActiveRun() {
        TeamAgentAdmissionController controller = new TeamAgentAdmissionController();
        controller.acquire(1L, 10L, 100L);
        controller.acquire(2L, 10L, 101L);
        controller.acquire(3L, 10L, 102L);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> controller.acquire(4L, 10L, 103L));

        assertEquals(TeamAgentErrorCode.AGENT_CAPACITY_EXCEEDED, error.getErrorCode());
        assertEquals(3, controller.activeGlobal());
    }

    @Test
    void userLimitAppliesAcrossTeams() {
        TeamAgentAdmissionController controller = new TeamAgentAdmissionController();
        controller.acquire(1L, 10L, 100L);
        controller.acquire(2L, 11L, 100L);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> controller.acquire(3L, 12L, 100L));

        assertEquals(TeamAgentErrorCode.AGENT_CAPACITY_EXCEEDED, error.getErrorCode());
        assertEquals(2, controller.activeGlobal());
    }
}
