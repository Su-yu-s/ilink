package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_agent_action")
public class TeamAgentAction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private Long teamId;
    private Integer seq;
    private String schemaVersion;
    private String actionType;
    private String targetType;
    private Long targetId;
    private Integer expectedTargetVersion;
    private String beforeJson;
    private String afterJson;
    private String reason;
    private String evidenceJson;
    private String status;
    private String resultJson;
    private String undoStatus;
    private String undoRequestId;
    private String undoRequestHash;
    private Date undoDeadline;
    private String undoResultJson;
    private String undoErrorCode;
    private String undoErrorMessage;
    private String errorCode;
    private String errorMessage;
    private Date createdAt;
    private Date updatedAt;
    private Date executedAt;
    private Date undoneAt;
    private Integer version;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMING = "CONFIRMING";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String UNDO_NOT_SUPPORTED = "NOT_SUPPORTED";
    public static final String UNDO_AVAILABLE = "AVAILABLE";
    public static final String UNDO_EXPIRED = "EXPIRED";
    public static final String UNDOING = "UNDOING";
    public static final String UNDONE = "UNDONE";
    public static final String UNDO_FAILED = "FAILED";
}
