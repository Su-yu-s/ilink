package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ai_usage_log")
public class AiUsageLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long teamId;
    private String action;
    private Integer promptTokens;
    private Integer completionTokens;
    private Boolean success;
    private Date createdAt;
}
