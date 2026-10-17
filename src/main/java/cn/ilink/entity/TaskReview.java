package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 任务批阅记录。verdict：PASS=通过（任务完成）、REJECT=驳回（回到进行中）、
 * COMMENT=补充点评（不改变任务状态）。file_comments 为 JSON 数组快照。
 */
@Data
@TableName("task_reviews")
public class TaskReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long submissionId;
    private Long reviewerId;
    private String verdict;
    private String overallComment;
    private String fileComments;
    private Date createdAt;
}
