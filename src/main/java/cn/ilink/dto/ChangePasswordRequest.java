package cn.ilink.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class ChangePasswordRequest {
    @NotBlank(message = "{validation.oldPassword.required}")
    private String oldPassword;

    @NotBlank(message = "{validation.newPassword.required}")
    @Size(min = 8, max = 32, message = "{validation.newPassword.length}")
    private String newPassword;
}
