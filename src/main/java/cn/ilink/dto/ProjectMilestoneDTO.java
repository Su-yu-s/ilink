package cn.ilink.dto;

import cn.ilink.util.StrictDateDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import java.util.Date;

@Data
public class ProjectMilestoneDTO {
    private Long teamId;
    private String milestoneName;
    private String milestoneDescription;

    /**
     * 计划完成日期。用严格解析器替代 Jackson 默认的宽松解析：
     * {@code "2026-13-45"} 这类不存在的日期必须 400，而不是被滚动成 2027-02-14 静默落库（核实报告 P1-2）。
     */
    @JsonDeserialize(using = StrictDateDeserializer.class)
    private Date dueDate;

    private Integer completionRate;
    private String deliverables;
    private Long createdBy;
}
