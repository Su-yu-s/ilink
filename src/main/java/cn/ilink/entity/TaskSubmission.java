package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_task_submissions")
public class TaskSubmission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long submitterId;
    private String content;
    private String attachments;
    private Date createdAt;
}
