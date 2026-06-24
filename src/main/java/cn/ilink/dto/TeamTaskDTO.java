package cn.ilink.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class TeamTaskDTO {
    private Long teamId;
    private String taskTitle;
    private String taskDescription;
    private String taskType;
    private Integer priority;
    private BigDecimal estimatedHours;
    private Date deadline;
    private Long assignedTo;
}
