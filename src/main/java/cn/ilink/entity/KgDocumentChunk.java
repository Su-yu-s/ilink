package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Original document text unit shared by ordinary RAG and graph provenance. */
@Data
@TableName("kg_document_chunk")
public class KgDocumentChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docType;
    private Long docId;
    private Integer ordinalNo;
    private String title;
    private String content;
    private Integer startOffset;
    private Integer endOffset;
    private String scope;
    private Long teamId;
    private Long userId;
    private String embeddingModel;
    private Integer embeddingDim;
    private byte[] embedding;
    private String contentHash;
}
