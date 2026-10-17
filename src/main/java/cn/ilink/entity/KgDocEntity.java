package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 文档-实体映射表 */
@Data
@TableName("kg_doc_entity")
public class KgDocEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** POST/COMPETITION/TASK/ASSET 等 */
    private String docType;
    private Long docId;
    private Long entityId;
    private Long teamId;
    private Date createdAt;
}
