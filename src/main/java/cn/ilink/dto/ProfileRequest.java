package cn.ilink.dto;

import lombok.Data;

@Data
public class ProfileRequest {
    private String username;
    private String email;
    private String realName;
    private String avatar;
    private String gender;
    private String grade;
    private String major;
    private String school;
    private String college;
    private String bio;
    /** JSON 数组字符串，与 User.honors 一致 */
    private String honors;
}