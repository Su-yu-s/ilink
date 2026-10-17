package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 知识图谱实体表 */
@Data
@TableName("kg_entity")
public class KgEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归一化名称（小写、去空格标点），用于对齐去重 */
    private String name;
    /** 展示名称（原始大小写） */
    private String displayName;
    /** COMPETITION/SKILL/TEACHER/ORGANIZATION/POST */
    private String entityType;
    private String description;
    /** GLOBAL/TEAM/PRIVATE */
    private String scope;
    /** 所属团队ID，0=不限定 */
    private Long teamId;
    /** 所属用户ID（PRIVATE 时有效），0=不限定 */
    private Long userId;
    /** 来源文档计数（对齐合并时保留计数最多的一条） */
    private Integer docCount;
    /** ACTIVE/MERGED/DELETED */
    private String status;
    private Date createdAt;
    private Date updatedAt;

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_TEAM = "TEAM";
    public static final String SCOPE_PRIVATE = "PRIVATE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_MERGED = "MERGED";
}
