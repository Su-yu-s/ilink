package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("project_application")
public class ProjectApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teacherId;
    private Long userId;
    private String status; // PENDING, APPROVED, REJECTED
    private String message;
    private Date createdAt;
}