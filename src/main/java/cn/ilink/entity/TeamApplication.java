package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("team_application")
public class TeamApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long userId;
    private String status; // PENDING, APPROVED, REJECTED
    private String message;
    /** 审批备注（通过时选填）/ 拒绝理由（必填） */
    private String reviewerNote;
    /** 审批时间 */
    private Date reviewedAt;
    private Date createdAt;
}