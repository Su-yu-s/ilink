package cn.ilink.vo;

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
    private Date createdAt;
    private String timeAgo;
}
