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
    /** 账号状态：ACTIVE / DISABLED。null 视为 ACTIVE，兼容迁移前的会话快照。 */
    private String status;
    /** 禁用原因，仅 status = DISABLED 时有值 */
    private String disabledReason;
    /** 禁用时间 */
    private Date disabledAt;
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

    /**
     * 账号是否可用。
     *
     * <p>status 为 null 时按 ACTIVE 处理：迁移前的会话快照里没有该字段，
     * 若判为禁用会把所有在线用户踢下线。
     */
    public boolean isActive() {
        return status == null || "ACTIVE".equalsIgnoreCase(status);
    }

    @JsonIgnore
    public boolean isDisabled() {
        return !isActive();
    }

    /**
     * 展示名。序列化后会出现在接口响应里，模板中也可用 {@code currentUser.displayName} 读取。
     *
     * <p>规则见 {@link cn.ilink.util.DisplayNameResolver}：优先姓名，未填写时按角色给占位，
     * <b>绝不回退 username</b>。加这个 getter 是为了让模板与前端不必各自实现一遍回退逻辑。
     */
    public String getDisplayName() {
        return cn.ilink.util.DisplayNameResolver.resolve(this);
    }

    /** 展示名首字，供头像占位使用。 */
    public String getAvatarInitial() {
        return cn.ilink.util.DisplayNameResolver.initial(this);
    }
}
