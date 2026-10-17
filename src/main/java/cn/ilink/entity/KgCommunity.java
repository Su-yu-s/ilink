package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** KG 社区摘要表 */
@Data
@TableName("kg_community")
public class KgCommunity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String summary;
    /** 成员实体ID列表的 JSON 数组 */
    private String entityIdsJson;
    private Integer entityCount;
    private Date createdAt;
    private Date updatedAt;
}
