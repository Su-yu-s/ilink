package cn.ilink.dto;

import lombok.Data;
import java.util.List;

@Data
public class RecommendedTeamVO {
    private Long teamId;
    private String teamName;
    private String description;
    private Double matchScore;
    private List<String> matchReasons;
}
