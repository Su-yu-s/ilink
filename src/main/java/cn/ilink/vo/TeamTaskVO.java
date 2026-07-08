package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class TeamTaskVO {
    private Long id;
    private Long teamId;
    private String taskTitle;
    private String taskDescription;
    private String taskType;
    private String taskTypeDesc;
    private Integer priority;
    private String priorityDesc;
    private String status;
    private String statusDesc;
    private BigDecimal estimatedHours;
    private BigDecimal actualHours;
    private Date deadline;
    private Long assignedTo;
    private String assigneeName;
    private String assigneeAvatar;
    private Long createdBy;
    private String creatorName;
    private String creatorAvatar;
    private Date createdAt;
    private Date updatedAt;
    private Date completedAt;
}
