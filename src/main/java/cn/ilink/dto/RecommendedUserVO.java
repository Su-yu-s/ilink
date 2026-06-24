package cn.ilink.dto;

import lombok.Data;
import java.util.List;

@Data
public class RecommendedUserVO {
    private Long userId;
    private String username;
    private String realName;
    private String school;
    private String avatar;
    private Double matchScore;
    private List<String> matchReasons;
    private List<String> skills;
    private Double rating;
}
