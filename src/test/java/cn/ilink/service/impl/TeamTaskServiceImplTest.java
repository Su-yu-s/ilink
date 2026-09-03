package cn.ilink.service.impl;

import cn.ilink.entity.TeamTask;
import cn.ilink.mapper.TaskCommentMapper;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamTaskServiceImplTest {

    private TeamTaskMapper mapper;
    private TeamTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(TeamTaskMapper.class);
        service = new TeamTaskServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "taskParticipantMapper", mock(TaskParticipantMapper.class));
        ReflectionTestUtils.setField(service, "taskCommentMapper", mock(TaskCommentMapper.class));
        ReflectionTestUtils.setField(service, "taskSubmissionMapper", mock(TaskSubmissionMapper.class));
        ReflectionTestUtils.setField(service, "userService", mock(UserService.class));
    }

    @Test
    void genericStatusEndpointRejectsUnknownAndTerminalStatuses() {
        assertFalse(service.updateTaskStatus(1L, "COMPLETED"));
        assertFalse(service.updateTaskStatus(1L, "made_up"));

        verify(mapper, never()).update(any(), any());
    }

    @Test
    void pendingTaskCanOnlyStartAsInProgress() {
        when(mapper.update(any(), any())).thenReturn(1);

        assertTrue(service.updateTaskStatus(1L, "in_progress"));

        verify(mapper).update(any(), any());
    }

    @Test
    void inProgressTaskCannotBeMovedByGenericStatusEndpoint() {
        when(mapper.update(any(), any())).thenReturn(0);

        assertFalse(service.updateTaskStatus(1L, "IN_PROGRESS"));

        verify(mapper).update(any(), any());
    }
}
