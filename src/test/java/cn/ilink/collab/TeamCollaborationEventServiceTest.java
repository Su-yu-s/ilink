package cn.ilink.collab;

import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.mapper.TeamCollabEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamCollaborationEventServiceTest {

    @Test
    void broadcastPayloadDoesNotRevealRestrictedAssetIdentityOrVersion() {
        TeamCollabEventMapper mapper = mock(TeamCollabEventMapper.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            TeamCollabEvent event = invocation.getArgument(0);
            event.setId(41L);
            return 1;
        });
        TeamCollaborationEventService service = new TeamCollaborationEventService(
            mapper, new ObjectMapper(), messaging);

        service.append(9L, "DOCUMENT", 77L, 5L, "DOCUMENT_SAVED",
            "SUCCESS", 12, "request-1", Map.of("title", "机密方案"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(messaging).convertAndSend(
            eq("/topic/team/9/collaboration"), payload.capture());
        assertEquals("COLLABORATION_CHANGED", payload.getValue().get("kind"));
        assertTrue(payload.getValue().containsKey("occurredAt"));
        assertFalse(payload.getValue().containsKey("assetType"));
        assertFalse(payload.getValue().containsKey("assetId"));
        assertFalse(payload.getValue().containsKey("version"));
        assertFalse(payload.getValue().containsKey("eventType"));
    }

    @Test
    void publishRunsAfterCommitNotBefore() {
        TeamCollabEventMapper mapper = mock(TeamCollabEventMapper.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        TeamCollaborationEventService service = new TeamCollaborationEventService(
            mapper, new ObjectMapper(), messaging);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.append(9L, "SPREADSHEET", 88L, 5L, "SPREADSHEET_SAVED",
                "SUCCESS", 3, null, Map.of());
            verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
            for (TransactionSynchronization sync
                : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(messaging).convertAndSend(
                eq("/topic/team/9/collaboration"), any(Object.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void transportFailureDoesNotEscapeTheCommittedCommand() {
        TeamCollabEventMapper mapper = mock(TeamCollabEventMapper.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        doThrow(new IllegalStateException("socket down"))
            .when(messaging).convertAndSend(anyString(), any(Object.class));
        TeamCollaborationEventService service = new TeamCollaborationEventService(
            mapper, new ObjectMapper(), messaging);

        TeamCollabEvent event = service.append(9L, "DOCUMENT", 77L, 5L,
            "DOCUMENT_CREATED", "SUCCESS", 1, null, Map.of());

        assertEquals(9L, event.getTeamId());
        verify(mapper).insert(any());
    }
}
