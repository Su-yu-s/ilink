package cn.ilink.vo;

import lombok.Data;

/**
 * 用户技能视图 VO — 替代 TeamController.skillToView()
 */
@Data
public class SkillViewVO {
    private String name;
    private Integer level;
    private String category;
}
