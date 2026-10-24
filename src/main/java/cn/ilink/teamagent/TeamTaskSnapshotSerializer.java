package cn.ilink.teamagent;

import cn.ilink.entity.TeamTask;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TeamTaskSnapshotSerializer {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZONE);

    public String serialize(TeamTask task) {
        StringBuilder text = new StringBuilder();
        text.append("任务ID：").append(value(task.getId())).append('\n');
        text.append("标题：").append(value(task.getTaskTitle())).append('\n');
        text.append("描述：").append(value(task.getTaskDescription())).append('\n');
        text.append("状态：").append(value(task.getStatus())).append('\n');
        text.append("优先级：").append(value(task.getPriority())).append('\n');
        text.append("截止时间：").append(task.getDeadline() == null ? ""
            : DATE_TIME.format(task.getDeadline().toInstant())).append('\n');
        text.append("负责人ID：").append(value(task.getAssignedTo())).append('\n');
        text.append("需审核：").append(value(task.getRequiresReview())).append('\n');
        text.append("父任务ID：").append(value(task.getParentTaskId())).append('\n');
        text.append("更新时间：").append(task.getUpdatedAt() == null ? ""
            : DATE_TIME.format(task.getUpdatedAt().toInstant())).append('\n');
        text.append("版本：").append(value(task.getVersion()));
        return text.toString();
    }

    public String contentHash(TeamTask task) {
        return TeamAgentHashes.contentHash("kg-source", serialize(task));
    }

    public String beforeJson(TeamTask task) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("taskTitle", task.getTaskTitle());
        before.put("status", task.getStatus());
        before.put("priority", task.getPriority());
        before.put("deadline", task.getDeadline() == null ? null
            : DATE_TIME.format(task.getDeadline().toInstant()));
        before.put("assignedTo", task.getAssignedTo());
        before.put("version", task.getVersion());
        return TeamAgentHashes.structureHash("task-before-snapshot", before);
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
