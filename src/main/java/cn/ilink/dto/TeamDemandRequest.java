package cn.ilink.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class TeamDemandRequest {
    private String title;
    private String description;
    private Integer competitionId;
    private String requiredSkills;
    private Integer requiredMemberCount;
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
    private Date deadline;
}
