package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("user_skills")
public class UserSkill {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("skill_name")
    private String skillName;

    @TableField("skill_level")
    private Integer skillLevel;

    @TableField("skill_category")
    private String skillCategory;

    @TableField("certification")
    private String certification;

    @TableField("years_experience")
    private Integer yearsExperience;

    @TableField("portfolio_url")
    private String portfolioUrl;

    @TableField("is_verified")
    private Boolean verified;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
