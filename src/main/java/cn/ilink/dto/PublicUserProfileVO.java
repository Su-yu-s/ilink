package cn.ilink.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 对外展示的用户概览（不含邮箱、手机等敏感字段）
 *
 * <p>不下发 {@code username}：它是内部标识，历史账号可能是手机号或学号。
 * 展示名以 {@code displayName} 下发，由 {@link cn.ilink.util.DisplayNameResolver} 解析。
 */
@Data
public class PublicUserProfileVO {
    private Long id;
    /** 展示名：用户填写的姓名，未填写时按角色给出占位 */
    private String displayName;
    /** 展示名首字，供头像占位使用 */
    private String avatarInitial;
    private String avatar;
    private String role;
    private String grade;
    private String major;
    private String school;
    private String college;
    private String bio;
    private Date createdAt;
    /** JSON 数组字符串，与 user.honors 一致 */
    private String honors;
    /** 用户发布的公开文字内容（社区帖子） */
    private List<Map<String, Object>> publishedPosts;
    /** 用户发布的公开成果（成果展示里的资产，只带概要字段） */
    private List<Map<String, Object>> publishedAssets;
}
