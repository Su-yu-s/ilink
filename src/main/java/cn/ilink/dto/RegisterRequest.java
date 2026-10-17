package cn.ilink.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class RegisterRequest {
    @Size(min = 2, max = 50, message = "{validation.username.size}")
    private String username;

    private Long studentId;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "{validation.phone.format}")
    private String phoneNumber;

    @NotBlank(message = "{validation.password.required}")
    @Size(min = 6, max = 100, message = "{validation.password.length}")
    private String password;

    @NotBlank(message = "{validation.email.required}")
    @Pattern(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "{validation.email.format}")
    private String email;

    @NotBlank(message = "{validation.emailCode.required}")
    @Pattern(regexp = "^\\d{6}$", message = "{validation.emailCode.format}")
    private String emailCode;

    @NotBlank(message = "{validation.role.required}")
    @Pattern(regexp = "STUDENT|TEACHER", message = "{validation.role.invalid}")
    private String role;

    private String gender;

    // 新增一个通用标识符字段
    private String identifier;
}
