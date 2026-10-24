package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_material_requirement")
public class TeamMaterialRequirement {
    public static final String REVIEW_UNCHECKED = "UNCHECKED";
    public static final String REVIEW_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String REVIEW_MET = "MET";
    public static final String REVIEW_PARTIAL = "PARTIAL";
    public static final String REVIEW_MISSING = "MISSING";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Integer competitionId;
    private String customCompetition;
    private String title;
    private String description;
    private String category;
    private Boolean required;
    private String sourceType;
    private String sourceDocType;
    private Long sourceDocId;
    private Long sourceChunkId;
    private String sourceExcerpt;
    private String status;
    private String reviewStatus;
    private Long currentReviewId;
    private Integer sortOrder;
    private Integer reviewVersion;
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private Date createdAt;
    private Date updatedAt;
}
