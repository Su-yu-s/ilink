package cn.ilink.vo;

import lombok.Data;

import java.util.Date;

@Data
public class UserSkillVO {
    private Long id;
    private Long userId;
    private String skillName;
    private Integer skillLevel;
    private String skillCategory;
    private String certification;
    private Integer yearsExperience;
    private String portfolioUrl;
    private Boolean verified;
    private Date createdAt;
    private Date updatedAt;
}
