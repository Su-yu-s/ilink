package cn.ilink.dto;

import lombok.Data;

@Data
public class LoginRequest {
    // 为了兼容之前的实现，保留这些字段
    private String phoneNumber;
    private String studentId;
    private String password;
    
    // 新增一个通用标识符字段
    private String identifier;
}