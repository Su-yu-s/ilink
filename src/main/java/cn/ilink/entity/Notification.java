package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

/**
 * 用户通知实体，对应 notification 表。
 */
@Data
@TableName("notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收通知的用户 ID */
    private Long userId;

    /** 发送通知的用户 ID（操作发起人） */
    private Long senderId;

    /** 通知类型，如 TEAM_APPLY、TASK_ASSIGNED 等 */
    private String type;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 是否已读 */
    private Boolean isRead;

    /** 关联业务 ID（如团队 ID、帖子 ID） */
    private Long relatedId;

    /** 关联业务类型（如 TEAM、POST） */
    private String relatedType;

    /** 创建时间 */
    private Date createdAt;

    /** 通知类型枚举 */
    public enum NotificationType {
        TEAM_INVITE,
        TEAM_APPLY,
        TEAM_APPROVED,
        TEAM_REJECTED,
        TASK_ASSIGNED,
        TASK_COMPLETED,
        TASK_SUBMITTED,
        MILESTONE_UPDATE,
        RECOMMENDATION,
        SYSTEM,
        LIKE,
        FAVORITE,
        COMMENT,
        TEACHER_APPROVED
    }
}
