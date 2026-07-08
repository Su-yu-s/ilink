package cn.ilink.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String username;
    private Long studentId;
    private String phoneNumber;
    private String password;
    private String email;
    private String role;
    private String gender;
    // 新增一个通用标识符字段
    private String identifier;
}