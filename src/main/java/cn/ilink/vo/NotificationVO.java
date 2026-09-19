package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.util.Date;

/**
 * 通知视图对象，向前端暴露的字段。
 */
@Data
public class NotificationVO {
    private Long id;
    private Long userId;
    private Long senderId;
    private String type;
    private String title;
    private String content;
    private Boolean isRead;
    private Long relatedId;
    private String relatedType;
    /**
     * 邀请类通知对应的 team_application.id，前端据此调「同意加入 / 拒绝」。
     * 非邀请通知为 null，已处理或已作废的邀请也为 null（此时不该再显示按钮）。
     */
    private Long invitationId;
    private Date createdAt;
    private String timeAgo;
}
