package cn.ilink.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Data
public class UserSkillDTO {
    private Long id;

    @NotBlank(message = "技能名称不能为空")
    private String skillName;

    @Min(value = 1, message = "技能等级最小为1")
    @Max(value = 5, message = "技能等级最大为5")
    private Integer skillLevel;

    private String skillCategory;

    private String certification;

    @Min(value = 0, message = "从业年限最小为0")
    private Integer yearsExperience;

    private String portfolioUrl;
}
