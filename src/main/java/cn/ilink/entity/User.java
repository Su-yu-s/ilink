package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.util.Date;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private Long studentId;
    private String phoneNumber;
    @JsonIgnore
    private String password;
    private String email;
    private String role;
    private String avatar;
    private String realName;
    private String gender;
    private String grade;
    private String major;
    private String school;
    private String college;
    /** 个人简介（技能、擅长方向、可提供帮助等） */
    private String bio;
    /** JSON 数组：个人荣誉（奖学金、荣誉称号、竞赛奖项等） */
    private String honors;
    private Date createdAt;
}