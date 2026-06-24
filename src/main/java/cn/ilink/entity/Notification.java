package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private Boolean isRead;
    private Long relatedId;
    private String relatedType;
    private Date createdAt;
    
    public enum NotificationType {
        TEAM_INVITE,
        TASK_ASSIGNED,
        TASK_COMPLETED,
        MILESTONE_UPDATE,
        RECOMMENDATION,
        SYSTEM
    }
}
