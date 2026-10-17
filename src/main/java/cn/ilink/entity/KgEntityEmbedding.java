package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 实体向量表（BLOB 存 float32 小端序列） */
@Data
@TableName("kg_entity_embedding")
public class KgEntityEmbedding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entityId;
    /** Embedding 模型名（如 qwen3.7-text-embedding-flash） */
    private String model;
    private Integer dim;
    private byte[] vector;
    /** 被向量化文本的 SHA-256，避免重复计算 */
    private String textHash;
    private Date createdAt;
    private Date updatedAt;
}
