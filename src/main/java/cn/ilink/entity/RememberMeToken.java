package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("remember_me_token")
public class RememberMeToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String selector;
    private String validatorHash;
    private Date expiresAt;
    private Date lastUsedAt;
    private String userAgent;
    private Date createdAt;
}
