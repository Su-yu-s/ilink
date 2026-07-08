package cn.ilink.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class RegisterRequest {
    @Size(min = 2, max = 50, message = "用户名需在 2-50 个字符之间")
    private String username;

    private Long studentId;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
    private String phoneNumber;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度需在 6-100 之间")
    private String password;

    private String email;

    @NotBlank(message = "请选择身份")
    @Pattern(regexp = "STUDENT|TEACHER", message = "身份只能为学生或教师")
    private String role;

    private String gender;

    // 新增一个通用标识符字段
    private String identifier;
}
