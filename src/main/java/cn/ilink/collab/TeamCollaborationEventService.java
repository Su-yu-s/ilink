package cn.ilink.collab;

import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.mapper.TeamCollabEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Append-only collaboration events with deliberately small, safe metadata. */
@Service
public class TeamCollaborationEventService {
    private static final Logger log =
        LoggerFactory.getLogger(TeamCollaborationEventService.class);
    private final TeamCollabEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public TeamCollaborationEventService(TeamCollabEventMapper mapper,
                                         ObjectMapper objectMapper,
                                         SimpMessagingTemplate messagingTemplate) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    public TeamCollabEvent append(Long teamId, String assetType, Long assetId, Long actorUserId,
                                  String eventType, String outcome, Integer version,
                                  String requestId, Map<String, ?> metadata) {
        TeamCollabEvent event = new TeamCollabEvent();
        event.setTeamId(teamId);
        event.setAssetType(assetType);
        event.setAssetId(assetId);
        event.setActorUserId(actorUserId);
        event.setEventType(eventType);
        event.setOutcome(outcome == null ? "SUCCESS" : outcome);
        event.setVersion(version);
        event.setRequestId(requestId);
        event.setMetadataJson(write(metadata));
        event.setCreatedAt(new Date());
        mapper.insert(event);
        publishAfterCommit(event);
        return event;
    }

    public TeamCollabEvent findRequest(Long teamId, String assetType, Long assetId,
                                       String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<TeamCollabEvent>()
            .eq(TeamCollabEvent::getTeamId, teamId)
            .eq(TeamCollabEvent::getAssetType, assetType)
            .eq(TeamCollabEvent::getAssetId, assetId)
            .eq(TeamCollabEvent::getRequestId, requestId)
            .orderByDesc(TeamCollabEvent::getId)
            .last("LIMIT 1"));
    }

    private String write(Map<String, ?> metadata) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            if (metadata != null) {
                for (Map.Entry<String, ?> entry : metadata.entrySet()) {
                    String key = entry.getKey();
                    if (key == null || key.matches("(?i).*(content|body|token|password|email|phone).*")) {
                        continue;
                    }
                    safe.put(key, entry.getValue());
                }
            }
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void publishAfterCommit(TeamCollabEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish(event);
                    }
                });
            return;
        }
        publish(event);
    }

    private void publish(TeamCollabEvent event) {
        // Broadcast only an opaque invalidation signal. Asset identifiers,
        // versions and event types may themselves reveal the existence of a
        // RESTRICTED document or spreadsheet to members without an ACL grant.
        // Clients re-read through REST, where authorization is applied per user.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "COLLABORATION_CHANGED");
        payload.put("occurredAt", new Date());
        try {
            messagingTemplate.convertAndSend(
                "/topic/team/" + event.getTeamId() + "/collaboration", payload);
        } catch (RuntimeException e) {
            // Database commit remains authoritative; a stale socket is repaired
            // by the next list/detail request or foreground refresh.
            log.warn("协作变更 WebSocket 推送失败，团队={}", event.getTeamId());
        }
    }
}
