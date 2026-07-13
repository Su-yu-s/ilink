package cn.ilink.service;

import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TeamAccessServiceTest {

    @Mock
    private TeamApplicationServiceImpl teamApplicationService;

    @Mock
    private TeamDemandServiceImpl teamDemandService;

    private TeamAccessService teamAccessService;

    @BeforeEach
    void setUp() {
        teamAccessService = new TeamAccessService(teamApplicationService, teamDemandService);
    }

    @Test
    void creatorIsAValidTeamParticipant() {
        given(teamDemandService.count(any())).willReturn(1L);

        assertTrue(teamAccessService.isTeamParticipant(3L, 9L));
        verifyNoInteractions(teamApplicationService);
    }

    @Test
    void approvedApplicantIsAValidTeamParticipant() {
        given(teamDemandService.count(any())).willReturn(0L);
        given(teamApplicationService.count(any())).willReturn(1L);

        assertTrue(teamAccessService.isTeamParticipant(3L, 9L));
    }

    @Test
    void invalidIdentifiersCannotAccessTeamData() {
        assertFalse(teamAccessService.isTeamParticipant(0L, 9L));
        assertFalse(teamAccessService.isTeamParticipant(3L, 0L));
        verifyNoInteractions(teamApplicationService, teamDemandService);
    }
}
