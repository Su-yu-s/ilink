package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("recommendation_log")
public class RecommendationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long recommendedUserId;
    private Long recommendedTeamId;
    private Double matchScore;
    private String matchReasons;
    private String action;
    private Date createdAt;
}
