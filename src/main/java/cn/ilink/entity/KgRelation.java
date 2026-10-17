package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 知识图谱关系表（带时序：valid_from/valid_to，NULL 表示仍有效） */
@Data
@TableName("kg_relation")
public class KgRelation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private Long targetId;
    /** REQUIRES/TEACHES/HOSTED_BY/RELATED_TO/MENTIONS */
    private String relationType;
    private Double weight;
    private Date validFrom;
    /** NULL = 仍然有效 */
    private Date validTo;
    /** GLOBAL/TEAM/PRIVATE */
    private String scope;
    private Long teamId;
    private Long userId;
    /** 抽取来源文档标识（docType:docId） */
    private String sourceDocId;
    private Date createdAt;

    public static final String REQUIRES = "REQUIRES";
    public static final String TEACHES = "TEACHES";
    public static final String HOSTED_BY = "HOSTED_BY";
    public static final String RELATED_TO = "RELATED_TO";
    public static final String MENTIONS = "MENTIONS";
}
