package cn.ilink.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 对外展示的用户概览（不含邮箱、手机等敏感字段）
 */
@Data
public class PublicUserProfileVO {
    private Long id;
    private String username;
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
}
