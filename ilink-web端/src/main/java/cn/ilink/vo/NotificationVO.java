package cn.ilink.vo;

import lombok.Data;
import java.util.Date;

@Data
public class NotificationVO {
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private Boolean isRead;
    private Long relatedId;
    private String relatedType;
    private Date createdAt;
    private String timeAgo;
}
