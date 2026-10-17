package cn.ilink.dto;

import lombok.Data;
import java.util.List;

@Data
public class RecommendedUserVO {
    private Long logId;
    private Long userId;
    /** 展示名：由 {@link cn.ilink.util.DisplayNameResolver} 解析，不下发内部 username */
    private String displayName;
    private String realName;
    private String school;
    private String avatar;
    private Double matchScore;
    private List<String> matchReasons;
    private List<String> skills;
    private Double rating;
}
