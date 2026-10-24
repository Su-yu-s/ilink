package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentAction;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentMessage;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentMessageMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TeamAgentConversationService {
    private static final int DEFAULT_TITLE_MAX = 100;
    private final TeamAgentAuthorization authorization;
    private final TeamDemandMapper teamMapper;
    private final TeamAgentConversationMapper conversationMapper;
    private final TeamAgentMessageMapper messageMapper;
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentActionGroupMapper groupMapper;
    private final TeamAgentActionMapper actionMapper;
    private final KgScopeService kgScopeService;
    private final TeamAgentEventService eventService;
    private final TeamAgentAdmissionController admission;
    private final ObjectMapper objectMapper;

    public TeamAgentConversationService(TeamAgentAuthorization authorization,
                                        TeamDemandMapper teamMapper,
                                        TeamAgentConversationMapper conversationMapper,
                                        TeamAgentMessageMapper messageMapper,
                                        TeamAgentRunMapper runMapper,
                                        TeamAgentActionGroupMapper groupMapper,
                                        TeamAgentActionMapper actionMapper,
                                        KgScopeService kgScopeService,
                                        TeamAgentEventService eventService,
                                        TeamAgentAdmissionController admission,
                                        ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.teamMapper = teamMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.groupMapper = groupMapper;
        this.actionMapper = actionMapper;
        this.kgScopeService = kgScopeService;
        this.eventService = eventService;
        this.admission = admission;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long teamId, Long userId, String clientRequestId, String rawTitle) {
        authorization.requireScope(teamId, userId);
        String requestId = requireUuid(clientRequestId, "clientRequestId");
        String title = normalizeTitle(rawTitle);
        Map<String, Object> hashPayload = new LinkedHashMap<>();
        hashPayload.put("title", title);
        String hash = TeamAgentHashes.structureHash("conversation-create", hashPayload);

        TeamAgentConversation existing = conversationMapper.selectOne(
            new LambdaQueryWrapper<TeamAgentConversation>()
                .eq(TeamAgentConversation::getTeamId, teamId)
                .eq(TeamAgentConversation::getOwnerUserId, userId)
                .eq(TeamAgentConversation::getClientRequestId, requestId)
                .last("LIMIT 1"));
        if (existing != null) {
            if (TeamAgentConversation.STATUS_DELETED.equals(existing.getStatus())) {
                throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_NOT_FOUND, "会话已删除");
            }
            if (!Objects.equals(hash, existing.getCreateRequestHash())) {
                throw new TeamAgentException(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "同一请求标识不能用于不同会话内容");
            }
            return conversationView(existing);
        }

        Date now = new Date();
        TeamAgentConversation conversation = new TeamAgentConversation();
        conversation.setTeamId(teamId);
        conversation.setOwnerUserId(userId);
        conversation.setClientRequestId(requestId);
        conversation.setCreateRequestHash(hash);
        conversation.setTitle(title == null ? "新会话" : title);
        conversation.setTitleSource(title == null
            ? TeamAgentConversation.TITLE_AUTO : TeamAgentConversation.TITLE_USER);
        conversation.setStatus(TeamAgentConversation.STATUS_ACTIVE);
        conversation.setLastMessageAt(now);
        conversation.setMessageSeq(0L);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversation.setVersion(0);
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException race) {
            TeamAgentConversation raced = conversationMapper.selectOne(
                new LambdaQueryWrapper<TeamAgentConversation>()
                    .eq(TeamAgentConversation::getTeamId, teamId)
                    .eq(TeamAgentConversation::getOwnerUserId, userId)
                    .eq(TeamAgentConversation::getClientRequestId, requestId)
                    .last("LIMIT 1"));
            if (raced != null && Objects.equals(hash, raced.getCreateRequestHash())
                && !TeamAgentConversation.STATUS_DELETED.equals(raced.getStatus())) {
                return conversationView(raced);
            }
            throw new TeamAgentException(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED,
                "会话创建请求发生冲突，请刷新后重试");
        }
        return conversationView(conversation);
    }

    public Map<String, Object> list(Long teamId, Long userId, int limit, String cursor) {
        authorization.requireScope(teamId, userId);
        int safeLimit = safeLimit(limit);
        LambdaQueryWrapper<TeamAgentConversation> query = new LambdaQueryWrapper<TeamAgentConversation>()
            .eq(TeamAgentConversation::getTeamId, teamId)
            .eq(TeamAgentConversation::getOwnerUserId, userId)
            .eq(TeamAgentConversation::getStatus, TeamAgentConversation.STATUS_ACTIVE);
        Cursor decoded = decodeCursor(cursor);
        if (decoded != null) {
            query.and(w -> w.lt(TeamAgentConversation::getLastMessageAt, new Date(decoded.time()))
                .or(inner -> inner.eq(TeamAgentConversation::getLastMessageAt, new Date(decoded.time()))
                    .lt(TeamAgentConversation::getId, decoded.id())));
        }
        query.orderByDesc(TeamAgentConversation::getLastMessageAt)
            .orderByDesc(TeamAgentConversation::getId)
            .last("LIMIT " + (safeLimit + 1));
        List<TeamAgentConversation> rows = conversationMapper.selectList(query);
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = rows.subList(0, safeLimit);
        List<Map<String, Object>> items = rows.stream().map(this::conversationView).toList();
        String next = hasMore && !rows.isEmpty()
            ? encodeCursor(rows.get(rows.size() - 1)) : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", next);
        return result;
    }

    public Map<String, Object> detail(Long teamId, Long userId, Long conversationId,
                                      int limit, Long beforeSeq, Long afterSeq) {
        authorization.requireScope(teamId, userId);
        if (beforeSeq != null && afterSeq != null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "beforeSeq 与 afterSeq 不能同时使用");
        }
        if ((beforeSeq != null && beforeSeq < 0) || (afterSeq != null && afterSeq < 0)) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, "消息游标必须是非负整数");
        }
        TeamAgentConversation conversation = requireConversation(teamId, userId, conversationId);
        int safeLimit = safeLimit(limit);
        LambdaQueryWrapper<TeamAgentMessage> query = new LambdaQueryWrapper<TeamAgentMessage>()
            .eq(TeamAgentMessage::getConversationId, conversationId)
            .eq(TeamAgentMessage::getTeamId, teamId);
        boolean recentPage = beforeSeq == null && afterSeq == null;
        if (beforeSeq != null) query.lt(TeamAgentMessage::getSeq, beforeSeq);
        if (afterSeq != null) query.gt(TeamAgentMessage::getSeq, afterSeq);
        if (recentPage || beforeSeq != null) {
            query.orderByDesc(TeamAgentMessage::getSeq).last("LIMIT " + (safeLimit + 1));
        } else {
            query.orderByAsc(TeamAgentMessage::getSeq).last("LIMIT " + safeLimit);
        }
        List<TeamAgentMessage> rows = new ArrayList<>(messageMapper.selectList(query));
        boolean hasOlder = false;
        if (recentPage || beforeSeq != null) {
            hasOlder = rows.size() > safeLimit;
            if (hasOlder) rows = rows.subList(0, safeLimit);
            Collections.reverse(rows);
        }
        TeamAgentScope scope = authorization.requireScope(teamId, userId);
        List<Map<String, Object>> messages = rows.stream()
            .map(message -> messageView(message, scope)).toList();
        Long oldest = rows.isEmpty() ? null : rows.get(0).getSeq();
        Long newest = rows.isEmpty() ? null : rows.get(rows.size() - 1).getSeq();
        TeamAgentRun activeRun = runMapper.selectOne(new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getConversationId, conversationId)
            .eq(TeamAgentRun::getUserId, userId)
            .in(TeamAgentRun::getStatus, List.of(TeamAgentRun.STATUS_QUEUED, TeamAgentRun.STATUS_RUNNING))
            .orderByDesc(TeamAgentRun::getId)
            .last("LIMIT 1"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation", conversationView(conversation));
        result.put("messages", messages);
        result.put("hasOlder", hasOlder);
        result.put("oldestSeq", oldest);
        result.put("newestSeq", newest);
        result.put("activeRunId", activeRun == null ? null : activeRun.getId());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long teamId, Long userId, Long conversationId) {
        authorization.requireScope(teamId, userId);
        if (teamMapper.selectByIdForUpdate(teamId) == null) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_NOT_FOUND,
                "会话不存在");
        }
        authorization.requireScope(teamId, userId);
        TeamAgentConversation conversation = conversationMapper.selectByIdForUpdate(conversationId);
        if (conversation == null || !Objects.equals(teamId, conversation.getTeamId())
            || !Objects.equals(userId, conversation.getOwnerUserId())) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        if (TeamAgentConversation.STATUS_DELETED.equals(conversation.getStatus())) return;

        List<TeamAgentActionGroup> groups = groupMapper.selectList(
            new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getConversationId, conversationId));
        if (groups.stream().anyMatch(group ->
            TeamAgentActionGroup.CONFIRMING.equals(group.getStatus()))) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                "动作正在执行，请等待处理完成");
        }
        List<Long> groupIds = groups.stream().map(TeamAgentActionGroup::getId).toList();
        if (!groupIds.isEmpty()) {
            long undoing = actionMapper.selectCount(new LambdaQueryWrapper<TeamAgentAction>()
                .in(TeamAgentAction::getGroupId, groupIds)
                .eq(TeamAgentAction::getUndoStatus, TeamAgentAction.UNDOING));
            if (undoing > 0) {
                throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                    "动作正在撤销，请等待处理完成");
            }
        }
        Date now = new Date();
        List<TeamAgentRun> activeRuns = runMapper.selectList(
            new LambdaQueryWrapper<TeamAgentRun>()
                .eq(TeamAgentRun::getConversationId, conversationId)
                .in(TeamAgentRun::getStatus,
                    List.of(TeamAgentRun.STATUS_QUEUED, TeamAgentRun.STATUS_RUNNING))
                .orderByAsc(TeamAgentRun::getId));
        for (TeamAgentRun candidate : activeRuns) {
            TeamAgentRun run = runMapper.selectByIdForUpdate(candidate.getId());
            if (run == null) continue;
            if (TeamAgentRun.STATUS_QUEUED.equals(run.getStatus())) {
                run.setStatus(TeamAgentRun.STATUS_CANCELLED);
                run.setFinishedAt(now);
                run.setElapsedMs(0L);
                long seq = eventService.appendLocked(run, "run.cancelled",
                    TeamAgentEventService.payload("message", "会话已删除，运行已取消"));
                run.setEventSeq(seq);
                run.setVersion(run.getVersion() == null ? 1 : run.getVersion() + 1);
                if (runMapper.updateById(run) != 1) {
                    throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                        "运行状态已变化，请刷新后重试");
                }
                releaseAdmissionAfterCommit(run);
            } else if (TeamAgentRun.STATUS_RUNNING.equals(run.getStatus())
                && run.getCancelRequestedAt() == null) {
                run.setCancelRequestedAt(now);
                run.setVersion(run.getVersion() == null ? 1 : run.getVersion() + 1);
                if (runMapper.updateById(run) != 1) {
                    throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                        "运行状态已变化，请刷新后重试");
                }
            }
        }
        for (TeamAgentActionGroup candidate : groups) {
            TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(candidate.getId());
            if (group == null
                || !TeamAgentActionGroup.PENDING.equals(group.getStatus())) {
                continue;
            }
            group.setStatus(TeamAgentActionGroup.CANCELLED);
            group.setDecisionType("SYSTEM_CANCELLED");
            group.setClientRequestId(null);
            group.setRequestHash(null);
            group.setDecidedAt(now);
            group.setUpdatedAt(now);
            group.setVersion(group.getVersion() == null ? 1 : group.getVersion() + 1);
            groupMapper.updateById(group);
        }
        if (!groupIds.isEmpty()) {
            actionMapper.update(null, new LambdaUpdateWrapper<TeamAgentAction>()
                .in(TeamAgentAction::getGroupId, groupIds)
                .eq(TeamAgentAction::getStatus, TeamAgentAction.STATUS_PENDING)
                .set(TeamAgentAction::getStatus, TeamAgentAction.STATUS_CANCELLED)
                .set(TeamAgentAction::getUpdatedAt, now)
                .setSql("version = version + 1"));
            actionMapper.update(null, new LambdaUpdateWrapper<TeamAgentAction>()
                .in(TeamAgentAction::getGroupId, groupIds)
                .eq(TeamAgentAction::getUndoStatus, TeamAgentAction.UNDO_AVAILABLE)
                .set(TeamAgentAction::getUndoStatus, TeamAgentAction.UNDO_EXPIRED)
                .set(TeamAgentAction::getUpdatedAt, now)
                .setSql("version = version + 1"));
        }
        conversation.setStatus(TeamAgentConversation.STATUS_DELETED);
        conversation.setUpdatedAt(now);
        conversation.setVersion(conversation.getVersion() == null ? 1 : conversation.getVersion() + 1);
        if (conversationMapper.updateById(conversation) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                "会话状态已变化，请刷新后重试");
        }
    }

    public TeamAgentConversation requireConversation(Long teamId, Long userId, Long conversationId) {
        TeamAgentConversation conversation = conversationId == null ? null
            : conversationMapper.selectById(conversationId);
        if (conversation == null || !Objects.equals(conversation.getTeamId(), teamId)
            || !Objects.equals(conversation.getOwnerUserId(), userId)
            || !TeamAgentConversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    /**
     * Locks an active, owner-bound conversation for a message/run transaction.
     */
    public TeamAgentConversation lockOwnedConversation(Long teamId, Long userId,
                                                       Long conversationId) {
        TeamAgentConversation conversation = conversationMapper.selectByIdForUpdate(conversationId);
        if (conversation == null || !Objects.equals(conversation.getTeamId(), teamId)
            || !Objects.equals(conversation.getOwnerUserId(), userId)
            || !TeamAgentConversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    /**
     * Appends one message while the caller holds the conversation row lock.
     */
    public TeamAgentMessage appendMessageLocked(TeamAgentConversation conversation, String role,
                                                Long authorUserId, String content, String status,
                                                Long runId, List<Map<String, Object>> citations) {
        if (conversation == null) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        Date now = new Date();
        long seq = (conversation.getMessageSeq() == null ? 0L : conversation.getMessageSeq()) + 1L;
        TeamAgentMessage message = new TeamAgentMessage();
        message.setTeamId(conversation.getTeamId());
        message.setConversationId(conversation.getId());
        message.setSeq(seq);
        message.setRole(role);
        message.setAuthorUserId(TeamAgentMessage.ROLE_USER.equals(role) ? authorUserId : null);
        message.setContent(content == null ? "" : content);
        message.setContentFormat(TeamAgentMessage.FORMAT_MARKDOWN);
        message.setStatus(status);
        message.setCitationsJson(writeCitations(citations));
        message.setRunId(runId);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        message.setVersion(0);
        if (messageMapper.insert(message) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "消息保存失败");
        }

        conversation.setMessageSeq(seq);
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        conversation.setVersion(conversation.getVersion() == null
            ? 1 : conversation.getVersion() + 1);
        if (TeamAgentMessage.ROLE_USER.equals(role)
            && TeamAgentConversation.TITLE_AUTO.equals(conversation.getTitleSource())) {
            String generated = safeAutoTitle(content);
            if (generated != null) conversation.setTitle(generated);
        }
        if (conversationMapper.updateById(conversation) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                "会话状态已变化，请刷新后重试");
        }
        return message;
    }

    public Map<String, Object> conversationView(TeamAgentConversation conversation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", conversation.getId());
        row.put("title", conversation.getTitle());
        row.put("titleSource", conversation.getTitleSource());
        row.put("lastMessageAt", TeamAgentTime.iso(conversation.getLastMessageAt()));
        row.put("createdAt", TeamAgentTime.iso(conversation.getCreatedAt()));
        row.put("version", conversation.getVersion());
        return row;
    }

    public Map<String, Object> messageView(TeamAgentMessage message) {
        return messageView(message, null);
    }

    public Map<String, Object> messageView(TeamAgentMessage message, TeamAgentScope scope) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("messageId", message.getId());
        row.put("seq", message.getSeq());
        row.put("role", message.getRole());
        row.put("content", message.getContent());
        row.put("contentFormat", message.getContentFormat());
        row.put("status", message.getStatus());
        row.put("runId", message.getRunId());
        row.put("createdAt", TeamAgentTime.iso(message.getCreatedAt()));
        row.put("citations", safeCitations(message.getCitationsJson(), scope));
        return row;
    }

    private List<Map<String, Object>> safeCitations(String json, TeamAgentScope scope) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(json,
                new TypeReference<List<Map<String, Object>>>() {});
            return sanitizeCitationsForScope(rows, scope);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> citationsForRun(TeamAgentRun run,
                                                     List<Map<String, Object>> citations) {
        if (run == null) return List.of();
        TeamAgentScope scope = authorization.requireScope(run.getTeamId(), run.getUserId());
        return sanitizeCitationsForScope(citations, scope);
    }

    private List<Map<String, Object>> sanitizeCitationsForScope(
        List<Map<String, Object>> rows, TeamAgentScope scope) {
        List<Map<String, Object>> safe = TeamAgentMarkdownSanitizer.sanitizeCitations(rows);
        if (scope == null) return List.of();
        return safe.stream().filter(row -> citationVisible(row, scope)).toList();
    }

    private boolean citationVisible(Map<String, Object> row, TeamAgentScope scope) {
        String kind = Objects.toString(row.get("kind"), null);
        Long id = row.get("id") instanceof Number number ? number.longValue() : null;
        if (kind != null) {
            return switch (kind) {
                case "TASK", "TEAM_TASK" ->
                    id != null && kgScopeService.canSeeDocument("TASK", id, scope);
                case "TEAM", "TEAM_STATE" ->
                    id != null && Objects.equals(id, scope.teamId());
                case "TEAM_MEMBER" ->
                    id != null && kgScopeService.canSeeTeamMember(id, scope);
                case "TEAM_METRIC", "WEEKLY_METRIC", "KNOWLEDGE_FRESHNESS" -> true;
                default -> false;
            };
        }
        String docType = Objects.toString(row.get("docType"), null);
        Long docId = row.get("docId") instanceof Number number
            ? number.longValue() : null;
        return docId != null && kgScopeService.canSeeDocument(docType, docId, scope);
    }

    public static String requireUuid(String value, String label) {
        if (value == null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, label + " 必填");
        }
        String normalized = value.trim().toLowerCase();
        try {
            java.util.UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, label + " 必须是 UUID");
        }
        return normalized;
    }

    private String normalizeTitle(String raw) {
        String title = TeamAgentHashes.normalizeText(raw);
        if (title == null) return null;
        title = title.trim();
        if (title.isEmpty()) return null;
        if (title.length() > DEFAULT_TITLE_MAX) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, "会话标题不能超过 100 字");
        }
        return title;
    }

    private String safeAutoTitle(String raw) {
        String text = TeamAgentMarkdownSanitizer.sanitize(raw, 100)
            .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[邮箱已隐藏]")
            .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号已隐藏]")
            .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[已隐藏]")
            .replaceAll("\\s+", " ").trim();
        return text.isBlank() ? null : text;
    }

    private String writeCitations(List<Map<String, Object>> citations) {
        if (citations == null || citations.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "引用内容无法保存");
        }
    }

    private int safeLimit(int value) {
        if (value <= 0) return 20;
        return Math.min(value, 50);
    }

    private String encodeCursor(TeamAgentConversation conversation) {
        String raw = conversation.getLastMessageAt().getTime() + ":" + conversation.getId();
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            long time = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (time < 0 || id <= 0) throw new IllegalArgumentException();
            return new Cursor(time, id);
        } catch (RuntimeException e) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, "会话游标不合法");
        }
    }

    private record Cursor(long time, long id) {}

    private void releaseAdmissionAfterCommit(TeamAgentRun run) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            admission.release(run.getId(), run.getTeamId(), run.getUserId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    admission.release(run.getId(), run.getTeamId(), run.getUserId());
                }
            });
    }
}
