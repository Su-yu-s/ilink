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
    /** 1/true=需提交审阅（走 REVIEW）；0/false=可被分配者直接完成 */
    private Boolean requiresReview;
    /** AI 拆解时指向来源任务；普通任务为空 */
    private Long parentTaskId;
}
