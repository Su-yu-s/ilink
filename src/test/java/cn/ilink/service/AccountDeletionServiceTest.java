package cn.ilink.service;

import cn.ilink.entity.TeamDemand;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock
    private TeamDemandServiceImpl teamDemandService;

    @Mock
    private TeamApplicationServiceImpl teamApplicationService;

    @Mock
    private AdminDataService adminDataService;

    @InjectMocks
    private AccountDeletionService accountDeletionService;

    @Test
    void creatorWithApprovedMembersIsBlockedFromDeletion() {
        TeamDemand team = team(7L, "星火队", "RECRUITING");
        when(teamDemandService.list(any(Wrapper.class))).thenReturn(List.of(team));
        when(teamApplicationService.count(any(Wrapper.class))).thenReturn(2L);

        assertEquals(List.of("星火队"), accountDeletionService.findBlockingTeams(3L));
        assertThrows(AccountDeletionBlockedException.class, () -> accountDeletionService.deleteAccount(3L));
        verify(adminDataService, never()).deleteUser(3L);
    }

    @Test
    void creatorWithoutApprovedMembersCanDeleteAccount() {
        when(teamDemandService.list(any(Wrapper.class))).thenReturn(List.of());

        accountDeletionService.deleteAccount(3L);

        verify(adminDataService).deleteUser(3L);
    }

    @Test
    void dissolvedTeamDoesNotBlockAccountDeletion() {
        when(teamDemandService.list(any(Wrapper.class)))
            .thenReturn(List.of(team(7L, "已解散的队伍", "DISSOLVED")));

        assertEquals(List.of(), accountDeletionService.findBlockingTeams(3L));
        verify(teamApplicationService, never()).count(any(Wrapper.class));
    }

    private TeamDemand team(Long id, String title, String status) {
        TeamDemand team = new TeamDemand();
        team.setId(id);
        team.setTitle(title);
        team.setStatus(status);
        return team;
    }
}
