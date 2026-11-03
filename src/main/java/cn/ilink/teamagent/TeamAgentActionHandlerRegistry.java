package cn.ilink.teamagent;

import cn.ilink.collab.TeamCollabException;
import cn.ilink.collab.TeamCollaborationAgentCommands;
import cn.ilink.entity.TeamAgentAction;
import cn.ilink.vo.ChatMessageVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned, closed registry for executable Agent actions. Unknown action or
 * schema versions fail before any business write is attempted.
 */
@Component
public class TeamAgentActionHandlerRegistry {
    private final Map<Key, Handler> handlers;
    private final ObjectMapper objectMapper;

    public TeamAgentActionHandlerRegistry(TeamTaskCommandService taskCommands,
                                          TeamChatCommandService chatCommands,
                                          ObjectMapper objectMapper,
                                          TeamCollaborationAgentCommands collaborationCommands,
                                          cn.ilink.service.TeamLibraryService libraryService,
                                          cn.ilink.mapper.TeamLibraryFileMapper libraryFileMapper) {
        this.objectMapper = objectMapper;
        Map<Key, Handler> registered = new LinkedHashMap<>();
        register(registered, "CREATE_TASK", new CreateTaskHandler(taskCommands));
        register(registered, "CREATE_SUBTASK", new CreateTaskHandler(taskCommands));
        register(registered, "ASSIGN_TASK", new AssignHandler(taskCommands));
        register(registered, "SET_TASK_PRIORITY", new PriorityHandler(taskCommands));
        register(registered, "SET_TASK_DEADLINE", new DeadlineHandler(taskCommands));
        register(registered, "SEND_TASK_REMINDER", new ReminderHandler(taskCommands));
        register(registered, "SEND_WEEKLY_REPORT", new WeeklyHandler(chatCommands));
        register(registered, "CREATE_LIBRARY_FILE", new LibraryFileHandler(libraryService));
        register(registered, "UPDATE_LIBRARY_FILE",
            new UpdateLibraryFileHandler(libraryService, libraryFileMapper));
        CollaborationHandler collaboration = new CollaborationHandler(collaborationCommands);
        for (String type : TeamCollaborationAgentCommands.ACTION_TYPES) {
            register(registered, type, collaboration);
        }
        this.handlers = Map.copyOf(registered);
    }

    public Map<String, Object> execute(TeamAgentAction action, Long actorUserId,
                                       String requestId, String existingLeaseToken) {
        Handler handler = require(action);
        Map<String, Object> after = read(action.getAfterJson());
        return handler.execute(new Context(action, actorUserId, requestId, after,
            read(action.getBeforeJson()), Map.of(), existingLeaseToken));
    }

    public Map<String, Object> undo(TeamAgentAction action, Long actorUserId,
                                    String requestId, String existingLeaseToken) {
        Handler handler = require(action);
        Map<String, Object> after = read(action.getAfterJson());
        Map<String, Object> before = read(action.getBeforeJson());
        Map<String, Object> result = read(action.getResultJson());
        return handler.undo(new Context(action, actorUserId, requestId, after, before,
            result, existingLeaseToken));
    }

    public boolean supportsUndo(TeamAgentAction action) {
        try {
            return require(action).undoSupported();
        } catch (TeamAgentException e) {
            return false;
        }
    }

    private Handler require(TeamAgentAction action) {
        Handler handler = action == null ? null
            : handlers.get(new Key(action.getActionType(), action.getSchemaVersion()));
        if (handler == null) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SCHEMA_UNSUPPORTED,
                "该动作版本已不受支持，请重新生成提案");
        }
        return handler;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SCHEMA_UNSUPPORTED,
                "动作载荷无法读取");
        }
    }

    private void register(Map<Key, Handler> target, String type, Handler handler) {
        target.put(new Key(type, TeamAgentActionSchema.VERSION), handler);
    }

    private record Key(String actionType, String schemaVersion) {}

    public record Context(TeamAgentAction action, Long actorUserId, String requestId,
                          Map<String, Object> after, Map<String, Object> before,
                          Map<String, Object> result, String existingLeaseToken) {}

    private interface Handler {
        Map<String, Object> execute(Context context);
        default Map<String, Object> undo(Context context) {
            throw new TeamAgentException(TeamAgentErrorCode.UNDO_NOT_AVAILABLE,
                "该动作不支持撤销");
        }
        default boolean undoSupported() { return false; }
    }

    private static final class CreateTaskHandler implements Handler {
        private final TeamTaskCommandService commands;
        private CreateTaskHandler(TeamTaskCommandService commands) { this.commands = commands; }

        @Override
        public Map<String, Object> execute(Context c) {
            TeamTaskCommands.MutationResult result = commands.createTask(
                new TeamTaskCommands.CreateTask(
                    c.action().getTeamId(), c.actorUserId(), c.requestId(),
                    c.action().getId(), text(c.after(), "taskTitle"),
                    text(c.after(), "taskDescription"), text(c.after(), "taskType"),
                    integer(c.after(), "priority"), decimal(c.after(), "estimatedHours"),
                    date(c.after().get("deadline")), longValue(c.after(), "assignedTo"),
                    true, longValue(c.after(), "parentTaskId")));
            return mutation(result);
        }

        @Override
        public Map<String, Object> undo(Context c) {
            TeamTaskCommands.MutationResult result = commands.undoCreatedTask(
                new TeamTaskCommands.UndoCreatedTask(
                    c.action().getTeamId(), c.actorUserId(),
                    longValue(c.result(), "taskId"),
                    integer(c.result(), "version"), c.requestId(), c.action().getId()));
            return mutation(result);
        }

        @Override
        public boolean undoSupported() { return true; }
    }

    private static final class AssignHandler implements Handler {
        private final TeamTaskCommandService commands;
        private AssignHandler(TeamTaskCommandService commands) { this.commands = commands; }

        @Override
        public Map<String, Object> execute(Context c) {
            TeamTaskCommands.MutationResult result = commands.assignTask(
                new TeamTaskCommands.AssignTask(c.action().getTeamId(), c.actorUserId(),
                    c.action().getTargetId(), c.action().getExpectedTargetVersion(),
                    longValue(c.after(), "assignedTo"), c.requestId(), c.action().getId()));
            return mutation(result);
        }

        @Override
        public Map<String, Object> undo(Context c) {
            TeamTaskCommands.MutationResult result = commands.assignTask(
                new TeamTaskCommands.AssignTask(c.action().getTeamId(), c.actorUserId(),
                    c.action().getTargetId(), integer(c.result(), "version"),
                    longValue(c.before(), "assignedTo"), c.requestId(), c.action().getId()));
            return mutation(result);
        }

        @Override
        public boolean undoSupported() { return true; }
    }

    private static final class PriorityHandler implements Handler {
        private final TeamTaskCommandService commands;
        private PriorityHandler(TeamTaskCommandService commands) { this.commands = commands; }

        @Override
        public Map<String, Object> execute(Context c) {
            TeamTaskCommands.MutationResult result = commands.setPriority(
                new TeamTaskCommands.SetPriority(c.action().getTeamId(), c.actorUserId(),
                    c.action().getTargetId(), c.action().getExpectedTargetVersion(),
                    integer(c.after(), "priority"), c.requestId(), c.action().getId()));
            return mutation(result);
        }

        @Override
        public Map<String, Object> undo(Context c) {
            TeamTaskCommands.MutationResult result = commands.setPriority(
                new TeamTaskCommands.SetPriority(c.action().getTeamId(), c.actorUserId(),
                    c.action().getTargetId(), integer(c.result(), "version"),
                    integer(c.before(), "priority"), c.requestId(), c.action().getId()));
            return mutation(result);
        }

        @Override
        public boolean undoSupported() { return true; }
    }

    private static final class DeadlineHandler implements Handler {
        private final TeamTaskCommandService commands;
        private DeadlineHandler(TeamTaskCommandService commands) { this.commands = commands; }

        @Override
        public Map<String, Object> execute(Context c) {
            TeamTaskCommands.MutationResult result = commands.setDeadline(
                new TeamTaskCommands.SetDeadline(c.action().getTeamId(), c.actorUserId(),
                    c.action().getTargetId(), c.action().getExpectedTargetVersion(),
                    date(c.after().get("deadline")), c.requestId(), c.action().getId()));
            return mutation(result);
        }

        @Override
        public Map<String, Object> undo(Context c) {
            TeamTaskCommands.MutationResult result = commands.setDeadline(
                new TeamTaskCommands.SetDeadline(c.action().getTeamId(), c.actorUserId(),
                    c.action().getTargetId(), integer(c.result(), "version"),
                    date(c.before().get("deadline")), c.requestId(), c.action().getId()));
            return mutation(result);
        }

        @Override
        public boolean undoSupported() { return true; }
    }

    private static final class ReminderHandler implements Handler {
        private final TeamTaskCommandService commands;
        private ReminderHandler(TeamTaskCommandService commands) { this.commands = commands; }

        @Override
        public Map<String, Object> execute(Context c) {
            TeamTaskCommands.MutationResult result = commands.sendReminder(
                new TeamTaskCommands.SendReminder(c.action().getTeamId(), c.actorUserId(),
                    c.action().getTargetId(), null, text(c.after(), "reminderText"),
                    c.requestId()));
            return mutation(result);
        }
    }

    private static final class WeeklyHandler implements Handler {
        private final TeamChatCommandService commands;
        private WeeklyHandler(TeamChatCommandService commands) { this.commands = commands; }

        @Override
        public Map<String, Object> execute(Context c) {
            ChatMessageVO message = commands.sendConfirmed(c.action().getTeamId(),
                c.actorUserId(), text(c.after(), "reportMarkdown"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messageId", message.getId());
            result.put("sentAt", TeamAgentTime.iso(message.getCreatedAt()));
            result.put("actionMessage", "周报已发送到团队群聊");
            return result;
        }
    }

    private static Map<String, Object> mutation(TeamTaskCommands.MutationResult mutation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", mutation.taskId());
        row.put("version", mutation.version());
        row.put("actionMessage", mutation.actionMessage());
        return row;
    }

    /**
     * All seven whitelisted collaboration actions run through the same services
     * the REST API uses, inside the action group transaction. Business failures
     * map onto the stable Agent error contract so the group rolls back rather
     * than half-applying.
     */
    /**
     * 把 agent 暂存的文件登记进团队资料库。文件本身在上传阶段已经落盘，
     * 这里只做资料库记录与索引入队；撤销走软删除，文件留在回收站。
     */
    private static final class LibraryFileHandler implements Handler {
        private final cn.ilink.service.TeamLibraryService libraryService;

        private LibraryFileHandler(cn.ilink.service.TeamLibraryService libraryService) {
            this.libraryService = libraryService;
        }

        @Override
        public Map<String, Object> execute(Context c) {
            String fileName = text(c.after(), "fileName");
            String fileUrl = text(c.after(), "fileUrl");
            Object sizeValue = c.after().get("fileSize");
            Long size = sizeValue instanceof Number number ? number.longValue() : null;
            Object folderValue = c.after().get("folderId");
            Long folderId = folderValue instanceof Number number ? number.longValue() : null;
            try {
                Map<String, Object> result = libraryService.registerFile(
                    c.action().getTeamId(), c.actorUserId(), folderId, fileName, fileUrl, size, "KEEP_BOTH");
                Object fileNode = result.get("file");
                Object fileId = fileNode instanceof Map<?, ?> map ? map.get("id") : null;
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("fileId", fileId);
                output.put("fileName", fileName);
                output.put("fileUrl", fileUrl);
                output.put("conflict", result.get("conflict"));
                return output;
            } catch (IllegalArgumentException rejected) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                    rejected.getMessage() == null ? "文件登记失败" : rejected.getMessage());
            }
        }

        @Override
        public Map<String, Object> undo(Context c) {
            Object fileId = c.result().get("fileId");
            if (!(fileId instanceof Number number)) {
                throw new TeamAgentException(TeamAgentErrorCode.UNDO_NOT_AVAILABLE, "文件记录不存在");
            }
            libraryService.softDelete(number.longValue(), c.actorUserId(), false);
            return Map.of("deleted", true, "fileId", number.longValue());
        }

        @Override
        public boolean undoSupported() { return true; }
    }

    /**
     * 用新文件替换资料库里的同名文件。旧文件走 REPLACE 策略进回收站，
     * 因此可人工恢复；这个动作本身不提供撤销。
     */
    private static final class UpdateLibraryFileHandler implements Handler {
        private final cn.ilink.service.TeamLibraryService libraryService;
        private final cn.ilink.mapper.TeamLibraryFileMapper libraryFileMapper;

        private UpdateLibraryFileHandler(cn.ilink.service.TeamLibraryService libraryService,
                                         cn.ilink.mapper.TeamLibraryFileMapper libraryFileMapper) {
            this.libraryService = libraryService;
            this.libraryFileMapper = libraryFileMapper;
        }

        @Override
        public Map<String, Object> execute(Context c) {
            Long fileId = c.action().getTargetId();
            cn.ilink.entity.TeamLibraryFile target =
                fileId == null ? null : libraryFileMapper.selectById(fileId);
            if (target == null || !java.util.Objects.equals(target.getTeamId(), c.action().getTeamId())
                || Boolean.TRUE.equals(target.getIsPurged()) || Boolean.TRUE.equals(target.getIsDeleted())) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED,
                    "目标文件不存在或已被删除");
            }
            Integer expected = c.action().getExpectedTargetVersion();
            Integer actual = target.getContentRevision() == null
                ? null : target.getContentRevision().intValue();
            if (expected != null && !java.util.Objects.equals(actual, expected)) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                    "文件已被其他人更新，请重新生成提案");
            }
            String fileUrl = text(c.after(), "fileUrl");
            Object sizeValue = c.after().get("fileSize");
            Long size = sizeValue instanceof Number number ? number.longValue() : null;
            try {
                // 强制沿用原文件名，保证 registerFile 走 REPLACE 分支而不是并列新增。
                Map<String, Object> result = libraryService.registerFile(
                    c.action().getTeamId(), c.actorUserId(), target.getParentId(),
                    target.getName(), fileUrl, size, "REPLACE");
                Object fileNode = result.get("file");
                Object newFileId = fileNode instanceof Map<?, ?> map ? map.get("id") : null;
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("fileId", newFileId);
                output.put("previousFileId", fileId);
                output.put("fileName", target.getName());
                output.put("fileUrl", fileUrl);
                return output;
            } catch (IllegalArgumentException rejected) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                    rejected.getMessage() == null ? "文件更新失败" : rejected.getMessage());
            }
        }

        @Override
        public boolean undoSupported() { return false; }
    }

    private static final class CollaborationHandler implements Handler {
        private final TeamCollaborationAgentCommands commands;
        private CollaborationHandler(TeamCollaborationAgentCommands commands) {
            this.commands = commands;
        }

        @Override
        public Map<String, Object> execute(Context c) {
            try {
                return commands.execute(c.action().getActionType(), c.action().getTeamId(),
                    c.actorUserId(), c.action().getTargetId(),
                    c.action().getExpectedTargetVersion(), c.after(), c.action().getId(),
                    c.existingLeaseToken());
            } catch (TeamCollabException e) {
                throw new TeamAgentException(map(e), e.getMessage() == null
                    ? "协作资产动作执行失败" : e.getMessage());
            }
        }

        @Override
        public Map<String, Object> undo(Context c) {
            try {
                return commands.undo(c.action().getActionType(), c.action().getTeamId(),
                    c.actorUserId(), c.action().getTargetId(), c.before(), c.result(),
                    c.action().getId(), c.existingLeaseToken());
            } catch (TeamCollabException e) {
                throw new TeamAgentException(TeamAgentErrorCode.UNDO_CONFLICT,
                    e.getMessage() == null ? "该协作动作已无法撤销" : e.getMessage());
            }
        }

        @Override
        public boolean undoSupported() {
            return true;
        }

        private static TeamAgentErrorCode map(TeamCollabException e) {
            return switch (e.code()) {
                case EDIT_VERSION_CONFLICT, AGENT_ACTION_CONFLICT ->
                    TeamAgentErrorCode.ACTION_VERSION_CONFLICT;
                case ASSET_NOT_FOUND, TEAM_NOT_FOUND, ASSET_FORBIDDEN, EDIT_LEASE_HELD,
                    EDIT_LEASE_INVALID, EDIT_LEASE_EXPIRED, TEAM_READ_ONLY ->
                    TeamAgentErrorCode.ACTION_PERMISSION_CHANGED;
                default -> TeamAgentErrorCode.ACTION_TRANSACTION_FAILED;
            };
        }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Date date(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return Date.from(Instant.parse(value.toString()));
        } catch (RuntimeException e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SCHEMA_UNSUPPORTED,
                "动作日期格式不合法");
        }
    }
}
