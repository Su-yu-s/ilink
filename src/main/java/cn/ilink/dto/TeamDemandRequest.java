package cn.ilink.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.Date;

@Data
public class TeamDemandRequest {
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最多 200 字")
    private String title;

    @NotBlank(message = "内容描述不能为空")
    private String description;

    private Integer competitionId;

    private String requiredSkills;

    @Min(value = 1, message = "需求人数需在 1-50 之间")
    @Max(value = 50, message = "需求人数需在 1-50 之间")
    private Integer requiredMemberCount;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
    private Date deadline;
}
