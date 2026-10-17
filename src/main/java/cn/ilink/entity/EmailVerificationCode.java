package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 邮箱验证码（当前用于注册），落库以便跨请求与跨重启存活 */
@Data
@TableName("email_verification_code")
public class EmailVerificationCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String codeHash;
    private String purpose;
    private Date expiresAt;
    private Date usedAt;
    private Integer attemptCount;
    private String requestIp;
    private Date createdAt;
}
