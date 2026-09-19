package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("feedback")
public class Feedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 详情页令牌的 SHA-256，明文令牌只在提交响应里返回一次 */
    private String accessTokenHash;
    private String type;
    private String description;
    private String contact;
    /** 截图地址的 JSON 数组 */
    private String imageUrls;
    private Long submitterId;
    private String clientIp;
    private String userAgent;
    private Boolean handled;
    private Date createdAt;
}
