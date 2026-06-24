package cn.ilink.dto;

import lombok.Data;
import java.util.List;

@Data
public class MatchResult {
    private Double totalScore;
    private Double skillScore;
    private Double urgencyScore;
    private Double historyScore;
    private Double activityScore;
    private List<String> matchReasons;
}
