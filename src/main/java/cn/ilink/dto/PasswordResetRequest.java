package cn.ilink.dto;

import lombok.Data;

@Data
public class PasswordResetRequest {
    /** 手机号、学号/工号或邮箱 */
    private String account;
}
